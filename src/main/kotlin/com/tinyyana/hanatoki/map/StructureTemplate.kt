package com.tinyyana.hanatoki.map

import org.bukkit.block.BlockFace
import java.security.MessageDigest

/**
 * 資產的不可變身分。[revision] 是內容雜湊(sha-256 前 16 hex),不是人工版號:
 * 同一個 `namespace:path` 換了檔案就是另一個 revision,log 與 placement 結果可以對回是哪一版。
 * 官方資產與未來玩家 Snapshot 用的是同一種身分。
 */
data class MapAssetId(val namespace: String, val path: String, val revision: String) {
    val key: String get() = "$namespace:$path"
    override fun toString(): String = "$namespace:$path@$revision"

    companion object {
        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val PATH = Regex("[a-z0-9_.-]+(/[a-z0-9_.-]+)*")

        fun validate(namespace: String, path: String) {
            require(NAMESPACE.matches(namespace)) { "asset namespace 不合法:「$namespace」" }
            require(PATH.matches(path) && path.split('/').none { it == "." || it == ".." }) { "asset path 不合法:「$path」" }
        }

        fun revisionOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
    }
}

/** 還沒讀檔、只有名字的資產參照(`namespace:path`)。 */
data class MapAssetRef(val namespace: String, val path: String) {
    init { MapAssetId.validate(namespace, path) }
    override fun toString(): String = "$namespace:$path"

    companion object {
        /** 沒寫 namespace 時用 [defaultNamespace]。 */
        fun parse(key: String, defaultNamespace: String): MapAssetRef {
            val colon = key.indexOf(':')
            return if (colon < 0) MapAssetRef(defaultNamespace, key) else MapAssetRef(key.substring(0, colon), key.substring(colon + 1))
        }
    }
}

/**
 * Jigsaw 方塊變成的 connector(區域座標)。語意照原版:父模組 connector 的 [target] 要等於子模組
 * connector 的 [name],兩者正面相對;[pool] 指定要從哪個 template pool 挑子模組,
 * `minecraft:empty` = 這個接口不往外長。[finalState] 是放置時 jigsaw 本身要換成的方塊。
 */
data class ConnectorSpec(
    val pos: MapPos,
    val front: BlockFace,
    val name: String,
    val target: String,
    val pool: String,
    val finalState: String,
) {
    val expandable: Boolean get() = pool != EMPTY_POOL

    companion object {
        const val EMPTY_POOL = "minecraft:empty"
    }
}

/**
 * 一個 Structure 模組:原版 Structure Block 存出來的 `.nbt`(DataVersion、size、palette、blocks)。
 *
 * 載入時就把 marker(Data 模式的 structure block)與 connector(jigsaw)拆出來,並把它們
 * 在方塊表裡換成要真的放下去的方塊(marker → 空氣、jigsaw → `final_state`),所以 placement
 * 不需要再知道 marker/connector 的存在。
 *
 * ⚠ 已知不做:方塊實體內容(箱子物品、告示牌文字)與實體(`entities`)不會放置——
 * 只放方塊狀態,數量記在 [ignoredBlockEntities]/[ignoredEntities]。箱子要放什麼請用 `loot` marker。
 */
class StructureTemplate private constructor(
    val id: MapAssetId,
    val dataVersion: Int,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val palette: List<String>,
    private val positions: IntArray,
    private val states: IntArray,
    val markers: List<MarkerSpec>,
    val connectors: List<ConnectorSpec>,
    val ignoredBlockEntities: Int,
    val ignoredEntities: Int,
) {
    val blockCount: Int get() = states.size

    inline fun forEachBlock(body: (x: Int, y: Int, z: Int, state: String) -> Unit) {
        for (i in 0 until blockCount) body(blockX(i), blockY(i), blockZ(i), palette[blockState(i)])
    }

    fun blockX(i: Int): Int = positions[i * 3]
    fun blockY(i: Int): Int = positions[i * 3 + 1]
    fun blockZ(i: Int): Int = positions[i * 3 + 2]
    fun blockState(i: Int): Int = states[i]

    companion object {
        /** 已驗證能讀的最新 DataVersion(26.2)。更新的存檔拒收:方塊名可能在這一版不存在。 */
        const val MAX_DATA_VERSION = 4903

        fun parse(namespace: String, path: String, bytes: ByteArray): StructureTemplate {
            MapAssetId.validate(namespace, path)
            val id = MapAssetId(namespace, path, MapAssetId.revisionOf(bytes))
            try {
                return parse(id, Nbt.read(bytes))
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("$id:${error.message}", error)
            }
        }

        private fun parse(id: MapAssetId, root: Map<String, Any>): StructureTemplate {
            val dataVersion = (root["DataVersion"] as? Int) ?: throw IllegalArgumentException("缺 DataVersion")
            require(dataVersion <= MAX_DATA_VERSION) { "DataVersion $dataVersion 比伺服器支援的 $MAX_DATA_VERSION 新" }
            require(!root.containsKey("palettes")) { "不支援多組 palettes(隨機外觀),請存成單一 palette" }
            val size = ints(root["size"], "size")
            require(size.size == 3 && size.all { it in 1..MAX_SIDE }) { "size 不合法:${size.toList()}" }

            val rawPalette = list(root["palette"], "palette").map { entry ->
                val compound = compound(entry, "palette 項目")
                val name = compound["Name"] as? String ?: throw IllegalArgumentException("palette 項目缺 Name")
                val props = (compound["Properties"] as? Map<*, *>)?.entries?.joinToString(",") { "${it.key}=${it.value}" }
                if (props.isNullOrEmpty()) name else "$name[$props]"
            }
            val palette = ArrayList(rawPalette)
            val paletteIndex = HashMap<String, Int>()
            palette.forEachIndexed { i, state -> paletteIndex.putIfAbsent(state, i) }
            fun indexOf(state: String): Int = paletteIndex.getOrPut(state) { palette.add(state); palette.size - 1 }

            val blocks = list(root["blocks"], "blocks")
            val positions = IntArray(blocks.size * 3)
            val states = IntArray(blocks.size)
            val markers = ArrayList<MarkerSpec>()
            val connectors = ArrayList<ConnectorSpec>()
            val seen = HashSet<MapPos>()
            var ignoredBlockEntities = 0
            blocks.forEachIndexed { i, entry ->
                val block = compound(entry, "blocks 項目")
                val p = ints(block["pos"], "pos")
                require(p.size == 3) { "pos 不合法" }
                val pos = MapPos(p[0], p[1], p[2])
                require(pos.x in 0 until size[0] && pos.y in 0 until size[1] && pos.z in 0 until size[2]) { "方塊超出 size:$pos" }
                require(seen.add(pos)) { "同一格出現兩次:$pos" }
                val stateIndex = block["state"] as? Int ?: throw IllegalArgumentException("blocks 項目缺 state")
                require(stateIndex in rawPalette.indices) { "state 索引超出 palette:$stateIndex" }
                val state = rawPalette[stateIndex]
                val nbt = block["nbt"] as? Map<*, *>
                val blockName = state.substringBefore('[')
                var placed = stateIndex
                when {
                    blockName == STRUCTURE_BLOCK && nbt?.get("mode") == "data" -> {
                        (nbt["metadata"] as? String)?.let { MarkerSpec.parse(it, pos) }?.let(markers::add)
                        placed = indexOf(AIR)
                    }
                    blockName == JIGSAW && nbt != null -> {
                        connectors += jigsaw(pos, state, nbt)
                        placed = indexOf(connectors.last().finalState)
                    }
                    nbt != null -> ignoredBlockEntities++
                }
                positions[i * 3] = pos.x
                positions[i * 3 + 1] = pos.y
                positions[i * 3 + 2] = pos.z
                states[i] = placed
            }
            val ignoredEntities = (root["entities"] as? List<*>)?.size ?: 0
            return StructureTemplate(
                id, dataVersion, size[0], size[1], size[2], palette, positions, states,
                markers, connectors, ignoredBlockEntities, ignoredEntities,
            )
        }

        private fun jigsaw(pos: MapPos, state: String, nbt: Map<*, *>): ConnectorSpec {
            val orientation = Regex("orientation=([a-z]+)_([a-z]+)").find(state)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("jigsaw 缺 orientation:$pos")
            val front = BlockFace.valueOf(orientation.uppercase())
            fun field(key: String, fallback: String): String = (nbt[key] as? String)?.takeIf { it.isNotEmpty() } ?: fallback
            return ConnectorSpec(
                pos = pos,
                front = front,
                name = field("name", ConnectorSpec.EMPTY_POOL),
                target = field("target", ConnectorSpec.EMPTY_POOL),
                pool = field("pool", ConnectorSpec.EMPTY_POOL),
                finalState = field("final_state", AIR),
            )
        }

        private fun compound(value: Any?, what: String): Map<*, *> =
            value as? Map<*, *> ?: throw IllegalArgumentException("$what 不是 compound")

        private fun list(value: Any?, what: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("缺 $what")

        private fun ints(value: Any?, what: String): IntArray = when (value) {
            is IntArray -> value
            is List<*> -> IntArray(value.size) { (value[it] as? Int) ?: throw IllegalArgumentException("$what 不是整數陣列") }
            else -> throw IllegalArgumentException("缺 $what")
        }

        private const val STRUCTURE_BLOCK = "minecraft:structure_block"
        private const val JIGSAW = "minecraft:jigsaw"
        private const val AIR = "minecraft:air"
        private const val MAX_SIDE = 4096
    }
}
