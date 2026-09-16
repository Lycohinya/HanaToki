package com.tinyyana.hanatoki.map

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.structure.StructureRotation

/**
 * 地圖語意。只描述「這裡是什麼位置」,不帶任何副本規則——生什麼怪、掉什麼 loot、門何時開,
 * 全部由拿到 [MapMarker] 的內容插件決定。
 */
enum class MarkerKind(val id: String) {
    PLAYER_SPAWN("player_spawn"),
    MOB_SPAWN("mob_spawn"),
    LOOT("loot"),
    DOOR("door"),
    TRIGGER("trigger");

    companion object {
        fun byId(id: String): MarkerKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 模組裡的一個 marker(區域座標)。資產端的寫法:Structure Block 設成 **Data** 模式,
 * metadata 欄位填 `<kind> [key=value]...`,例如 `mob_spawn id=guard group=wave1 facing=south`。
 *
 * - `facing` 是保留鍵,會跟著模組旋轉;其餘鍵原樣交給內容插件。
 * - metadata 以 `#` 開頭視為註解 marker,解析時略過(給建築者留言用)。
 * - 未知 kind 是資產錯誤,載入時直接拒絕——拼錯字不能變成「少了一個出生點」。
 */
data class MarkerSpec(val kind: MarkerKind, val pos: MapPos, val facing: BlockFace?, val properties: Map<String, String>) {
    companion object {
        /** 回 null = 註解 marker。 */
        fun parse(metadata: String, pos: MapPos): MarkerSpec? {
            val trimmed = metadata.trim()
            if (trimmed.startsWith("#")) return null
            require(trimmed.isNotEmpty()) { "marker metadata 是空的(${pos.x},${pos.y},${pos.z})" }
            val tokens = trimmed.split(WHITESPACE)
            val kind = MarkerKind.byId(tokens[0])
                ?: throw IllegalArgumentException("未知的 marker 種類「${tokens[0]}」(${pos.x},${pos.y},${pos.z})")
            val props = LinkedHashMap<String, String>()
            for (token in tokens.drop(1)) {
                val eq = token.indexOf('=')
                require(eq > 0 && eq < token.length - 1) { "marker 參數要寫成 key=value:「$token」(${pos.x},${pos.y},${pos.z})" }
                val key = token.substring(0, eq)
                require(props.put(key, token.substring(eq + 1)) == null) { "marker 參數重複:「$key」(${pos.x},${pos.y},${pos.z})" }
            }
            val facing = props.remove("facing")?.let { raw ->
                runCatching { BlockFace.valueOf(raw.uppercase()) }.getOrNull()
                    ?.takeIf { it in FACINGS }
                    ?: throw IllegalArgumentException("marker facing 只接受 north/south/east/west/up/down:「$raw」")
            }
            return MarkerSpec(kind, pos, facing, props)
        }

        private val WHITESPACE = Regex("\\s+")
        private val FACINGS = setOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)
    }

    fun transformed(origin: MapPos, rotation: StructureRotation, asset: MapAssetId): MapMarker {
        val world = origin.plus(MapRotation.rotate(pos, rotation))
        return MapMarker(kind, asset, world.x, world.y, world.z, facing?.let { MapRotation.rotate(it, rotation) }, properties)
    }
}

/** 放置完成後交給內容插件的 marker(世界方塊座標)。 */
data class MapMarker(
    val kind: MarkerKind,
    val asset: MapAssetId,
    val x: Int,
    val y: Int,
    val z: Int,
    val facing: BlockFace?,
    val properties: Map<String, String>,
) {
    /** 資產裡的 `id=` 參數;沒寫就是 null。 */
    val id: String? get() = properties["id"]

    /** 方塊底面中心(站上去的位置)。 */
    fun location(world: World): Location = Location(world, x + 0.5, y.toDouble(), z + 0.5)
}
