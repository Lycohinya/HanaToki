package com.tinyyana.hanatoki.world

/**
 * 方塊(或任意可回滾狀態)diff 記錄的純邏輯部分:記錄順序、逆序回滾清單、依 key 分組
 * (ARCH §5.1「跨 region 的 diff rollback...同一 region 的條目可以合併成一次派工」)。
 * 刻意不綁 Bukkit `BlockData`——用泛型 [K]（座標鍵）/ [S]（狀態快照）讓這裡可以直接單元測試
 * (見 DiffLogTest),Bukkit 端的 `WorldDiffRecorder` 只是在外面包一層真正的 apply 動作。
 */
class DiffLog<K, S> {
    data class Entry<K, S>(val key: K, val before: S)

    private val entries = mutableListOf<Entry<K, S>>()

    @Synchronized
    fun record(key: K, before: S) {
        entries += Entry(key, before)
    }

    @Synchronized
    fun size(): Int = entries.size

    /** 逆序清單:後發生的變更先回滾(比照 stack 語意,後蓋掉的先復原)。*/
    @Synchronized
    fun reverseEntries(): List<Entry<K, S>> = entries.asReversed().toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** 依呼叫端算好的分組鍵(如座標所屬 region id)分組,組內維持逆序。*/
    fun groupedReverse(regionKeyOf: (K) -> Any): Map<Any, List<Entry<K, S>>> =
        reverseEntries().groupBy { regionKeyOf(it.key) }
}
