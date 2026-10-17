package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.api.ContentRegistration
import com.tinyyana.hanatoki.config.DungeonDefinition
import com.tinyyana.hanatoki.config.DungeonDefinitionParser
import com.tinyyana.hanatoki.map.ClassLoaderMapAssetSource
import com.tinyyana.hanatoki.map.DirectoryMapAssetSource
import com.tinyyana.hanatoki.map.MapAssetLibrary
import com.tinyyana.hanatoki.map.MapAssetRef
import com.tinyyana.hanatoki.map.MapAssetSource
import com.tinyyana.hanatoki.map.MapLayout
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MapPos
import com.tinyyana.hanatoki.map.PlacedPiece
import com.tinyyana.hanatoki.map.StructureTemplate
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.structure.StructureRotation
import org.bukkit.entity.EntityType
import org.bukkit.entity.Mob
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * 資料驅動內容的註冊:內容插件只給一個資料夾(+ texts),所有檢查通過才交給 `registerContent`,
 * 任何一筆錯誤 → 整份拒絕(future 以 [ContentDataException] 失敗),不半載入。
 *
 * 資料夾結構:`dungeons.yml`(引擎的副本定義)、`stages.yml` `scene.yml` `objects.yml` `enemies.yml`
 * `encounters.yml` `tool.yml` `hints.yml`,以及 `map-assets/<namespace>/structure/<path>.nbt`
 * (資料夾裡沒有的資產退回內容插件 jar 內同路徑的 `map-assets/` 資源)。見 docs/CONTENT-DATA.md。
 *
 * ⚠ 在呼叫端執行緒做檔案 IO 與驗證(內容插件的 onEnable)。
 */
object DataContentRegistrar {
    fun register(core: HanaTokiCore, owner: Plugin, dir: File, texts: Map<String, String>): CompletableFuture<ContentRegistration> {
        val loaded = try {
            load(core, owner, dir, texts)
        } catch (t: Throwable) {
            if (t is ContentDataException) t.errors.forEach { core.plugin.logger.severe("[HanaToki] ${dir.name}: $it") }
            return CompletableFuture.failedFuture(t)
        }
        val (content, warnings) = loaded
        warnings.forEach { core.plugin.logger.warning("[HanaToki] ${content.spec.dungeonId}: $it") }
        val behavior = core.dataContent.behaviorFor(content)
        return core.registerContent(owner, File(dir, "dungeons.yml"), texts, mapOf(content.spec.dungeonId to behavior))
    }

    /** 讀檔、解析、驗證。純資料部分在 [ContentParser]/[ContentValidator](可單元測試),這裡補需要 Bukkit/檔案的部分。 */
    internal fun load(core: HanaTokiCore, owner: Plugin, dir: File, texts: Map<String, String>): Pair<LoadedContent, List<String>> {
        require(dir.isDirectory) { "資料驅動內容的資料夾不存在:${dir.path}" }
        val raws = LinkedHashMap<String, Map<String, Any?>>()
        for (name in ContentFileNames.ALL) {
            val file = File(dir, name)
            if (file.isFile) raws[name] = ContentYaml.parse(name, file.readText(Charsets.UTF_8))
        }
        val spec = ContentParser(raws).parse()
        val errors = ArrayList<String>()
        errors += ContentValidator.validateReferences(spec)
        errors += ContentValidator.validateTexts(spec) { key -> key in texts || core.texts.rawOrNull(key) != null }

        // dungeons.yml:正好一座副本,id 等於 stages.yml 的 dungeon
        val def = readDungeon(File(dir, "dungeons.yml"), spec, errors)

        // 場景資產:讀入每個 piece 的 .nbt(讀不到/格式錯 = 錯誤,指名 piece),再用「相對 anchor 的排版」核對 marker
        val source = CompositeAssetSource(
            listOf(DirectoryMapAssetSource(File(dir, "map-assets").toPath()), ClassLoaderMapAssetSource(owner.javaClass.classLoader, "map-assets")),
        )
        val library = MapAssetLibrary(source)
        val templates = LinkedHashMap<String, StructureTemplate>()
        for (piece in spec.scene.pieces.values) {
            try {
                templates[piece.id] = library.template(MapAssetRef.parse(piece.asset, "minecraft"))
            } catch (t: Exception) {
                errors += "scene.yml pieces.${piece.id}.asset: 資產「${piece.asset}」讀取失敗:${t.message}"
            }
        }
        var markers: List<MapMarker> = emptyList()
        if (templates.size == spec.scene.pieces.size) {
            try {
                val layout = MapLayout(
                    spec.scene.pieces.values.map { piece ->
                        PlacedPiece(templates.getValue(piece.id), MapPos(piece.ox, piece.oy, piece.oz), rotation(piece.rotationDegrees))
                    },
                )
                markers = layout.markers()
            } catch (t: Exception) {
                errors += "scene.yml pieces: 排版失敗:${t.message}"
            }
            errors += ContentValidator.validateScene(spec, markers)
            if (def != null) errors += ContentValidator.validateDungeon(spec, def, markers.firstOrNull { it.id == spec.scene.entry })
        } else if (def != null) {
            errors += ContentValidator.validateDungeon(spec, def, null)
        }
        errors += runtimeChecks(spec)
        if (errors.isNotEmpty()) throw ContentDataException(errors.distinct())
        return LoadedContent(spec, templates) to ContentValidator.warnings(spec)
    }

    private fun rotation(degrees: Int) = when (degrees) {
        90 -> StructureRotation.CLOCKWISE_90
        180 -> StructureRotation.CLOCKWISE_180
        270 -> StructureRotation.COUNTERCLOCKWISE_90
        else -> StructureRotation.NONE
    }

    private fun readDungeon(file: File, spec: ContentSpec, errors: MutableList<String>): DungeonDefinition? {
        if (!file.isFile) { errors += "dungeons.yml: 缺少引擎副本定義檔"; return null }
        val root = try { ContentYaml.parse("dungeons.yml", file.readText(Charsets.UTF_8)) } catch (e: ContentDataException) { errors += e.errors; return null }
        @Suppress("UNCHECKED_CAST")
        val dungeons = root["dungeons"] as? Map<String, Any?>
        if (dungeons == null) { errors += "dungeons.yml dungeons: 缺少區塊"; return null }
        if (dungeons.keys != setOf(spec.dungeonId)) {
            errors += "dungeons.yml dungeons: 這個資料夾必須正好定義一座副本「${spec.dungeonId}」(目前:${dungeons.keys.joinToString()})"
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val raw = dungeons[spec.dungeonId] as? Map<String, Any?>
        if (raw == null) { errors += "dungeons.yml dungeons.${spec.dungeonId}: 必須是區塊"; return null }
        return try {
            DungeonDefinitionParser.parse(spec.dungeonId, raw)
        } catch (e: DungeonDefinitionParser.DefinitionError) {
            errors += "dungeons.yml dungeons.${spec.dungeonId}: ${e.message}"
            null
        }
    }

    /** 需要 Bukkit registry 的檢查:材質、實體型別、方塊字串、item model key。 */
    private fun runtimeChecks(spec: ContentSpec): List<String> {
        val errors = ArrayList<String>()
        for (tool in spec.tools.values) {
            val m = Material.matchMaterial(tool.material)
            if (m == null || !m.isItem) errors += "tool.yml tools.${tool.id}.material: 「${tool.material}」不是有效的物品材質"
            tool.itemModel?.let { if (NamespacedKey.fromString(it) == null) errors += "tool.yml tools.${tool.id}.item-model: 「$it」不是 namespace:key" }
        }
        for (enemy in spec.enemies.values) {
            val type = runCatching { EntityType.valueOf(enemy.baseEntity) }.getOrNull()
            val cls = type?.entityClass
            if (type == null) errors += "enemies.yml enemies.${enemy.id}.base-entity: 「${enemy.baseEntity}」不是 EntityType"
            else if (cls == null || !Mob::class.java.isAssignableFrom(cls)) errors += "enemies.yml enemies.${enemy.id}.base-entity: 「${enemy.baseEntity}」必須是會移動的 Mob(才能追人)"
        }
        fun checkBlock(file: String, path: String, block: String) {
            try { Bukkit.createBlockData(block) } catch (t: Exception) { errors += "$file $path: 方塊「$block」無法解析" }
        }
        for (obj in spec.objects.values) {
            for ((state, effect) in obj.states) effect.blocks.forEachIndexed { i, b -> checkBlock(ContentFileNames.OBJECTS, "objects.${obj.id}.states.$state.blocks[$i].block", b.block) }
            if (obj is LanternSpec) obj.milestones.forEachIndexed { mi, m -> m.effect.blocks.forEachIndexed { i, b -> checkBlock(ContentFileNames.OBJECTS, "objects.${obj.id}.milestones[$mi].blocks[$i].block", b.block) } }
        }
        return errors
    }

    private class CompositeAssetSource(private val sources: List<MapAssetSource>) : MapAssetSource {
        override fun structure(ref: MapAssetRef): ByteArray? = sources.firstNotNullOfOrNull { it.structure(ref) }
        override fun templatePool(ref: MapAssetRef): ByteArray? = sources.firstNotNullOfOrNull { it.templatePool(ref) }
    }
}
