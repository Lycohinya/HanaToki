package com.tinyyana.hanatoki.map

import org.bukkit.block.BlockFace
import org.bukkit.block.structure.StructureRotation

/** 方塊座標(區域或世界,由持有者決定)。 */
data class MapPos(val x: Int, val y: Int, val z: Int) {
    fun plus(other: MapPos): MapPos = MapPos(x + other.x, y + other.y, z + other.z)
    fun minus(other: MapPos): MapPos = MapPos(x - other.x, y - other.y, z - other.z)
    fun offset(face: BlockFace): MapPos = MapPos(x + face.modX, y + face.modY, z + face.modZ)
}

/** 含端點的方塊盒。 */
data class MapBounds(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
    fun intersects(other: MapBounds): Boolean =
        minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ

    fun translate(delta: MapPos): MapBounds =
        MapBounds(minX + delta.x, minY + delta.y, minZ + delta.z, maxX + delta.x, maxY + delta.y, maxZ + delta.z)
}

/**
 * 原版 Structure 的旋轉語意:以模組原點 (0,0,0) 為軸心、只轉水平面。
 * 方塊狀態本身(樓梯朝向等)的旋轉交給放置時的 `BlockData.rotate`,這裡只處理座標與方向。
 */
object MapRotation {
    fun rotate(pos: MapPos, rotation: StructureRotation): MapPos = when (rotation) {
        StructureRotation.NONE -> pos
        StructureRotation.CLOCKWISE_90 -> MapPos(-pos.z, pos.y, pos.x)
        StructureRotation.CLOCKWISE_180 -> MapPos(-pos.x, pos.y, -pos.z)
        StructureRotation.COUNTERCLOCKWISE_90 -> MapPos(pos.z, pos.y, -pos.x)
    }

    fun rotate(face: BlockFace, rotation: StructureRotation): BlockFace {
        if (face == BlockFace.UP || face == BlockFace.DOWN) return face
        val steps = when (rotation) {
            StructureRotation.NONE -> 0
            StructureRotation.CLOCKWISE_90 -> 1
            StructureRotation.CLOCKWISE_180 -> 2
            StructureRotation.COUNTERCLOCKWISE_90 -> 3
        }
        val index = HORIZONTAL.indexOf(face)
        require(index >= 0) { "只支援六個基本方向:$face" }
        return HORIZONTAL[(index + steps) % 4]
    }

    /** 模組 `size` 盒子轉完之後佔到的區域座標範圍。 */
    fun rotatedBounds(sizeX: Int, sizeY: Int, sizeZ: Int, rotation: StructureRotation): MapBounds {
        val a = rotate(MapPos(0, 0, 0), rotation)
        val b = rotate(MapPos(sizeX - 1, sizeY - 1, sizeZ - 1), rotation)
        return MapBounds(minOf(a.x, b.x), 0, minOf(a.z, b.z), maxOf(a.x, b.x), sizeY - 1, maxOf(a.z, b.z))
    }

    val ALL: List<StructureRotation> = listOf(
        StructureRotation.NONE,
        StructureRotation.CLOCKWISE_90,
        StructureRotation.CLOCKWISE_180,
        StructureRotation.COUNTERCLOCKWISE_90,
    )

    private val HORIZONTAL = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
}
