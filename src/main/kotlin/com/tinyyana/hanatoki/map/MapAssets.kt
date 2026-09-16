package com.tinyyana.hanatoki.map

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * 資產從哪裡來。官方 bundled 資產、資料夾裡的建築、未來玩家 Snapshot 都實作這個介面,
 * 下游([MapAssetLibrary]、[JigsawPlanner]、placement)只認 bytes + [MapAssetRef],不認來源。
 *
 * 可能做 IO:**不得**在 region/tick thread 上呼叫。
 */
interface MapAssetSource {
    /** 原版 Structure `.nbt` 的 bytes;不存在回 null。 */
    fun structure(ref: MapAssetRef): ByteArray?

    /** 原版 template pool JSON 的 bytes;不存在回 null。 */
    fun templatePool(ref: MapAssetRef): ByteArray?
}

/**
 * 資料夾來源,目錄結構照 datapack 的 `data/`:
 * `<root>/<namespace>/structure/<path>.nbt`、`<root>/<namespace>/worldgen/template_pool/<path>.json`。
 * 建築者可以直接把 datapack 的 `data/` 內容放進來,改檔即生效,不需要重編插件。
 */
class DirectoryMapAssetSource(private val root: Path) : MapAssetSource {
    override fun structure(ref: MapAssetRef): ByteArray? = read(root.resolve(ref.namespace).resolve("structure").resolve("${ref.path}.nbt"))
    override fun templatePool(ref: MapAssetRef): ByteArray? =
        read(root.resolve(ref.namespace).resolve("worldgen").resolve("template_pool").resolve("${ref.path}.json"))

    private fun read(file: Path): ByteArray? = if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
}

/** 從 classloader(插件 jar)讀,目錄結構同 [DirectoryMapAssetSource],前面加 [prefix]。 */
class ClassLoaderMapAssetSource(private val loader: ClassLoader, private val prefix: String) : MapAssetSource {
    override fun structure(ref: MapAssetRef): ByteArray? = read("$prefix/${ref.namespace}/structure/${ref.path}.nbt")
    override fun templatePool(ref: MapAssetRef): ByteArray? = read("$prefix/${ref.namespace}/worldgen/template_pool/${ref.path}.json")

    private fun read(name: String): ByteArray? = loader.getResourceAsStream(name)?.use { it.readBytes() }
}

/** 權重候選;[weight] 照原版 1..150。 */
data class PoolEntry(val structure: MapAssetRef, val weight: Int)

/**
 * 原版 template pool JSON 的子集:只接受 `single_pool_element`(含 legacy)與 `empty_pool_element`。
 * `processors`/`projection` 目前忽略(一律 rigid、不處理)。之後要接原版 Template Pool 的其他元素型別,
 * 從這裡擴充,[JigsawPlanner] 不需要知道。
 */
class TemplatePool(val ref: MapAssetRef, val entries: List<PoolEntry>) {
    companion object {
        fun parse(ref: MapAssetRef, bytes: ByteArray): TemplatePool {
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val entries = ArrayList<PoolEntry>()
            for (element in root.getAsJsonArray("elements") ?: throw IllegalArgumentException("$ref:缺 elements")) {
                val obj = element.asJsonObject
                val weight = obj.get("weight")?.asInt ?: 1
                require(weight in 1..150) { "$ref:weight 要在 1..150:$weight" }
                val inner = obj.getAsJsonObject("element") ?: throw IllegalArgumentException("$ref:缺 element")
                when (val type = inner.get("element_type")?.asString) {
                    "minecraft:single_pool_element", "minecraft:legacy_single_pool_element" -> {
                        val location = inner.get("location")?.asString ?: throw IllegalArgumentException("$ref:缺 location")
                        entries += PoolEntry(MapAssetRef.parse(location, "minecraft"), weight)
                    }
                    "minecraft:empty_pool_element" -> {}
                    else -> throw IllegalArgumentException("$ref:不支援的 element_type「$type」")
                }
            }
            return TemplatePool(ref, entries)
        }
    }
}

/**
 * 讀資產 + 驗證。不快取:資產可以在伺服器開著的時候被換掉,下一次讀就是新 revision。
 * 需要快取的內容插件自己留著回傳的 [StructureTemplate](它是不可變的)。
 */
class MapAssetLibrary(private val source: MapAssetSource) {
    fun template(ref: MapAssetRef): StructureTemplate {
        val bytes = source.structure(ref) ?: throw IllegalArgumentException("找不到 structure 資產:$ref")
        return StructureTemplate.parse(ref.namespace, ref.path, bytes)
    }

    fun pool(ref: MapAssetRef): TemplatePool {
        val bytes = source.templatePool(ref) ?: throw IllegalArgumentException("找不到 template pool:$ref")
        return TemplatePool.parse(ref, bytes)
    }
}
