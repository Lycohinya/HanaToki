package com.tinyyana.hanatoki.map

import com.tinyyana.hanatoki.folia.ChunkCoord
import org.bukkit.block.structure.StructureRotation

/** 一塊模組放在哪、轉幾度。[origin] 是模組區域原點 (0,0,0) 對應的座標。 */
data class PlacedPiece(val template: StructureTemplate, val origin: MapPos, val rotation: StructureRotation) {
    val bounds: MapBounds =
        MapRotation.rotatedBounds(template.sizeX, template.sizeY, template.sizeZ, rotation).translate(origin)

    fun markers(): List<MapMarker> = template.markers.map { it.transformed(origin, rotation, template.id) }
}

/**
 * 空間排版結果(還沒碰世界)。固定地圖是一塊、Jigsaw 組裝是很多塊——兩者是同一個型別,
 * 放置、marker、ownership、cleanup 都走同一條路。
 *
 * 座標在 [translate] 之前是以起始模組原點為 (0,0,0) 的相對值;交給 placement 前要平移到世界座標
 * (通常用 [alignMarker] 讓入口的 `player_spawn` 對齊 slot anchor)。
 */
class MapLayout(pieces: List<PlacedPiece>) {
    val pieces: List<PlacedPiece> = pieces.toList()

    init {
        require(this.pieces.isNotEmpty()) { "空的地圖排版" }
        for (i in this.pieces.indices) for (j in i + 1 until this.pieces.size) {
            require(!this.pieces[i].bounds.intersects(this.pieces[j].bounds)) {
                "模組重疊:${this.pieces[i].template.id} 與 ${this.pieces[j].template.id}"
            }
        }
    }

    val bounds: MapBounds = this.pieces.map { it.bounds }.reduce { a, b ->
        MapBounds(minOf(a.minX, b.minX), minOf(a.minY, b.minY), minOf(a.minZ, b.minZ), maxOf(a.maxX, b.maxX), maxOf(a.maxY, b.maxY), maxOf(a.maxZ, b.maxZ))
    }

    fun markers(): List<MapMarker> = pieces.flatMap { it.markers() }

    fun translate(delta: MapPos): MapLayout = MapLayout(pieces.map { it.copy(origin = it.origin.plus(delta)) })

    /** 平移整張圖,讓第一個 [kind] marker 落在 [target]。找不到該 marker 直接失敗,不猜位置。 */
    fun alignMarker(kind: MarkerKind, target: MapPos): MapLayout {
        val marker = markers().firstOrNull { it.kind == kind }
            ?: throw IllegalArgumentException("排版裡沒有 ${kind.id} marker 可以對齊")
        return translate(target.minus(MapPos(marker.x, marker.y, marker.z)))
    }

    /** 放置用:依 chunk 分組的方塊(世界座標、尚未旋轉的狀態字串 + 這格要套的旋轉)。 */
    fun cellsByChunk(): Map<ChunkCoord, ChunkCells> {
        val builders = LinkedHashMap<ChunkCoord, ChunkCells.Builder>()
        val stateKeys = LinkedHashMap<StateKey, Int>()
        for (piece in pieces) {
            val template = piece.template
            for (i in 0 until template.blockCount) {
                val world = piece.origin.plus(MapRotation.rotate(MapPos(template.blockX(i), template.blockY(i), template.blockZ(i)), piece.rotation))
                val key = StateKey(template.palette[template.blockState(i)], piece.rotation)
                val keyIndex = stateKeys.getOrPut(key) { stateKeys.size }
                builders.getOrPut(ChunkCoord(Math.floorDiv(world.x, 16), Math.floorDiv(world.z, 16))) { ChunkCells.Builder() }
                    .add(world.x, world.y, world.z, keyIndex)
            }
        }
        val keys = stateKeys.keys.toList()
        return builders.mapValues { it.value.build(keys) }
    }

    companion object {
        fun single(template: StructureTemplate, origin: MapPos, rotation: StructureRotation): MapLayout =
            MapLayout(listOf(PlacedPiece(template, origin, rotation)))
    }
}

/** 一個方塊狀態要以哪個旋轉放下去(同一個狀態字串在不同旋轉的模組裡是不同的 BlockData)。 */
data class StateKey(val state: String, val rotation: StructureRotation)

/** 一個 chunk 裡要放的格子。[keys] 整張圖共用。 */
class ChunkCells private constructor(private val xyz: IntArray, private val stateIndex: IntArray, val keys: List<StateKey>) {
    val size: Int get() = stateIndex.size
    fun x(i: Int): Int = xyz[i * 3]
    fun y(i: Int): Int = xyz[i * 3 + 1]
    fun z(i: Int): Int = xyz[i * 3 + 2]
    fun key(i: Int): Int = stateIndex[i]

    class Builder {
        private var xyz = IntArray(48)
        private var states = IntArray(16)
        private var count = 0

        fun add(x: Int, y: Int, z: Int, key: Int) {
            if (count == states.size) {
                states = states.copyOf(count * 2)
                xyz = xyz.copyOf(count * 6)
            }
            xyz[count * 3] = x; xyz[count * 3 + 1] = y; xyz[count * 3 + 2] = z
            states[count++] = key
        }

        fun build(keys: List<StateKey>): ChunkCells = ChunkCells(xyz.copyOf(count * 3), states.copyOf(count), keys)
    }
}
