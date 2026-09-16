package com.tinyyana.hanatoki.map

/**
 * 一個 chunk 內「這次 generation 改過哪些格子、改之前是什麼、放下去的是什麼」。
 *
 * ownership 判定:cleanup 時現況**完整等於**當初放下去的狀態([S] 的 equals,對 BlockData 是整個方塊狀態,
 * 不是 Material)才還原;不等於就代表玩家或其他系統之後動過,那一格不再屬於這次 generation,保留原樣。
 * 放置前後相同的格子根本不記,所以 cleanup 也永遠不會碰它們。
 *
 * 單一 chunk 只會在擁有它的 region 執行緒上被讀寫,所以這裡不加鎖。泛型是為了讓規則可以單元測試。
 */
class OwnedCells<S : Any> {
    private var xyz = IntArray(48)
    private val before = ArrayList<S>()
    private val placed = ArrayList<S>()

    val size: Int get() = before.size

    fun record(x: Int, y: Int, z: Int, beforeState: S, placedState: S) {
        val i = before.size
        if (i * 3 == xyz.size) xyz = xyz.copyOf(xyz.size * 2)
        xyz[i * 3] = x; xyz[i * 3 + 1] = y; xyz[i * 3 + 2] = z
        before += beforeState
        placed += placedState
    }

    /** 逆序還原。回傳 `[還原格數, 因為被別人改過而保留的格數]`。 */
    fun revert(current: CellReader<S>, restore: CellWriter<S>): IntArray {
        var reverted = 0
        var foreign = 0
        for (i in before.size - 1 downTo 0) {
            val x = xyz[i * 3]; val y = xyz[i * 3 + 1]; val z = xyz[i * 3 + 2]
            if (current.read(x, y, z) == placed[i]) {
                restore.write(x, y, z, before[i])
                reverted++
            } else {
                foreign++
            }
        }
        return intArrayOf(reverted, foreign)
    }
}

fun interface CellReader<S> { fun read(x: Int, y: Int, z: Int): S }
fun interface CellWriter<S> { fun write(x: Int, y: Int, z: Int, state: S) }
