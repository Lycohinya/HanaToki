package com.tinyyana.hanatoki.instance

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 場地 slot 池(ARCH §5.1①):一個 dungeonId 底下有一組預先建好的場地。
 *
 * 純邏輯、無副作用查表:`register`/`allocate`/`free`/`hasFree` 全部只碰
 * [ConcurrentHashMap] + [AtomicBoolean],任何執行緒可安全呼叫(`PresenceBridge` 的查詢
 * 也走這一層)。anchor 的實際型別由呼叫端決定(通常是 Bukkit `Location`)——engine 邏輯本身
 * 不需要知道它是什麼,這讓 [SlotPool] 可以脫離 Bukkit 直接單元測試(見 SlotPoolTest)。
 *
 * ⚠ 這裡只做「登記表管理」,不代表任何世界/實體寫入權——§5.1② 的重點:anchor 只是序列化點,
 * 不是所有權宣告。實際世界操作一律走 `folia/WorldOp`。
 */
class SlotPool<A> {
    private class Entry<A>(val slotId: String, val dungeonId: String, val anchor: A) {
        val occupied = AtomicBoolean(false)
    }

    private val slots = ConcurrentHashMap<String, Entry<A>>()

    /** 啟動/reload 時登記一個場地。重複呼叫同一 slotId 會覆蓋(reload 語意)。*/
    fun register(dungeonId: String, slotId: String, anchor: A) {
        slots[slotId] = Entry(slotId, dungeonId, anchor)
    }

    fun unregisterAll() = slots.clear()

    /** 無副作用查詢:目前這個副本是否有空 slot。*/
    fun hasFree(dungeonId: String): Boolean =
        slots.values.any { it.dungeonId == dungeonId && !it.occupied.get() }

    /** 無鎖搶佔配置:誰先 compareAndSet 成功誰拿到。找不到就回 null(呼叫端視為「客滿」)。*/
    fun allocate(dungeonId: String): AllocatedSlot<A>? {
        for (e in slots.values) {
            if (e.dungeonId == dungeonId && e.occupied.compareAndSet(false, true)) {
                return AllocatedSlot(e.slotId, e.anchor)
            }
        }
        return null
    }

    /** 歸還 slot。呼叫端要保證回滾(world diff revert)已經完成才呼叫這個
     * (ARCH §5.1「回滾未完成前,slot 分配表不得把該 slot 標為空閒」)。*/
    fun free(slotId: String) {
        slots[slotId]?.occupied?.set(false)
    }

    fun isOccupied(slotId: String): Boolean = slots[slotId]?.occupied?.get() ?: false

    fun anchorOf(slotId: String): A? = slots[slotId]?.anchor

    fun slotIds(dungeonId: String? = null): List<String> =
        slots.values.filter { dungeonId == null || it.dungeonId == dungeonId }.map { it.slotId }

    fun freeCount(dungeonId: String): Int =
        slots.values.count { it.dungeonId == dungeonId && !it.occupied.get() }

    fun totalCount(dungeonId: String): Int =
        slots.values.count { it.dungeonId == dungeonId }
}

data class AllocatedSlot<A>(val slotId: String, val anchor: A)
