package com.tinyyana.hanatoki.arena

import com.tinyyana.hanatoki.folia.ChunkCoord
import org.bukkit.Material
import org.bukkit.block.BlockFace

/** Compact immutable block plan; reconciles only changed cells, grouped by chunk. */
class ArenaPlan private constructor(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    private val palette: Array<PlannedBlock>,
    private val cells: ByteArray,
) {
    /** 計畫表裡真的有指定的格數(空氣也算;沒指定的格子不會被碰)。 */
    val plannedCells: Int = cells.count { it.toInt() != 0 }

    /** 相對 anchor 的偏移 → 計畫;沒指定(或超出範圍)回 null。 */
    fun at(dx: Int, dy: Int, dz: Int): PlannedBlock? {
        val x = dx - minX
        val y = dy - minY
        val z = dz - minZ
        if (x !in 0 until sizeX || y !in 0 until sizeY || z !in 0 until sizeZ) return null
        val idx = cells[index(x, y, z)].toInt() and 0xFF
        return if (idx == 0) null else palette[idx - 1]
    }

    private fun index(x: Int, y: Int, z: Int): Int = (x * sizeZ + z) * sizeY + y

    /** 場地覆蓋到的所有 chunk(絕對 chunk 座標),只列真的含有計畫格的那些。 */
    fun chunksOf(anchorX: Int, anchorZ: Int): List<ChunkCoord> {
        val out = ArrayList<ChunkCoord>()
        val x0 = anchorX + minX
        val z0 = anchorZ + minZ
        val cxMin = Math.floorDiv(x0, 16)
        val cxMax = Math.floorDiv(x0 + sizeX - 1, 16)
        val czMin = Math.floorDiv(z0, 16)
        val czMax = Math.floorDiv(z0 + sizeZ - 1, 16)
        for (cx in cxMin..cxMax) {
            for (cz in czMin..czMax) {
                if (hasPlannedIn(anchorX, anchorZ, cx, cz)) out += ChunkCoord(cx, cz)
            }
        }
        return out
    }

    private fun hasPlannedIn(anchorX: Int, anchorZ: Int, cx: Int, cz: Int): Boolean {
        var found = false
        forEachColumnIn(anchorX, anchorZ, cx, cz) { lx, lz ->
            if (found) return@forEachColumnIn
            val base = (lx * sizeZ + lz) * sizeY
            for (y in 0 until sizeY) if (cells[base + y].toInt() != 0) { found = true; break }
        }
        return found
    }

    /** 走遍這個 chunk 與計畫表範圍相交的每一根柱子(以計畫表的區域座標回報)。 */
    private inline fun forEachColumnIn(anchorX: Int, anchorZ: Int, cx: Int, cz: Int, body: (Int, Int) -> Unit) {
        val x0 = anchorX + minX
        val z0 = anchorZ + minZ
        val xs = maxOf(cx * 16, x0)
        val xe = minOf(cx * 16 + 15, x0 + sizeX - 1)
        val zs = maxOf(cz * 16, z0)
        val ze = minOf(cz * 16 + 15, z0 + sizeZ - 1)
        if (xs > xe || zs > ze) return
        for (x in xs..xe) for (z in zs..ze) body(x - x0, z - z0)
    }

    class ChunkReconcile(val checked: Int, val written: Int)

    /**
     * 巡檢一個 chunk:每一個計畫格讀現況,跟計畫不同才寫。[force] = 全部照寫、不讀。
     *
     * [current] 讀該絕對座標現在的材質;[stairFacing] 讀樓梯朝向(只對計畫有樓梯朝向的格子呼叫,
     * 不是樓梯就回 null);[write] 把計畫寫進世界。三個都由呼叫端在 region 執行緒上提供,
     * 這個方法本身不碰 Bukkit。
     */
    fun reconcileChunk(
        anchorX: Int,
        anchorY: Int,
        anchorZ: Int,
        cx: Int,
        cz: Int,
        force: Boolean,
        current: MaterialReader,
        stairFacing: FacingReader,
        write: BlockWriter,
    ): ChunkReconcile {
        var checked = 0
        var written = 0
        val x0 = anchorX + minX
        val y0 = anchorY + minY
        val z0 = anchorZ + minZ
        forEachColumnIn(anchorX, anchorZ, cx, cz) { lx, lz ->
            val base = (lx * sizeZ + lz) * sizeY
            val wx = x0 + lx
            val wz = z0 + lz
            for (ly in 0 until sizeY) {
                val idx = cells[base + ly].toInt() and 0xFF
                if (idx == 0) continue
                val planned = palette[idx - 1]
                val wy = y0 + ly
                checked++
                if (!force) {
                    if (current.read(wx, wy, wz) == planned.material) {
                        val ascent = planned.stairAscent ?: continue
                        if (stairFacing.read(wx, wy, wz) == ascent) continue
                    }
                }
                write.write(wx, wy, wz, planned)
                written++
            }
        }
        return ChunkReconcile(checked, written)
    }

    companion object {
        fun from(plan: Map<Offset, PlannedBlock>): ArenaPlan {
            require(plan.isNotEmpty()) { "空的計畫表" }
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
            for (o in plan.keys) {
                if (o.dx < minX) minX = o.dx; if (o.dx > maxX) maxX = o.dx
                if (o.dy < minY) minY = o.dy; if (o.dy > maxY) maxY = o.dy
                if (o.dz < minZ) minZ = o.dz; if (o.dz > maxZ) maxZ = o.dz
            }
            val sizeX = maxX - minX + 1
            val sizeY = maxY - minY + 1
            val sizeZ = maxZ - minZ + 1
            val paletteIndex = LinkedHashMap<PlannedBlock, Int>()
            val cells = ByteArray(sizeX * sizeY * sizeZ)
            for ((o, block) in plan) {
                val idx = paletteIndex.getOrPut(block) { paletteIndex.size + 1 }
                require(idx <= 255) { "計畫表用了超過 255 種方塊/朝向組合" }
                cells[((o.dx - minX) * sizeZ + (o.dz - minZ)) * sizeY + (o.dy - minY)] = idx.toByte()
            }
            return ArenaPlan(minX, minY, minZ, sizeX, sizeY, sizeZ, paletteIndex.keys.toTypedArray(), cells)
        }
    }
}

/** Java SAM callbacks keep Kotlin runtime types out of cross-plugin calls. */
fun interface MaterialReader { fun read(x: Int, y: Int, z: Int): Material }
fun interface FacingReader { fun read(x: Int, y: Int, z: Int): BlockFace? }
fun interface BlockWriter { fun write(x: Int, y: Int, z: Int, block: PlannedBlock) }
data class Offset(val dx: Int, val dy: Int, val dz: Int)
data class PlannedBlock(val material: Material, val stairAscent: BlockFace?) {
    constructor(material: Material) : this(material, null)
}
