package com.tinyyana.hanatoki.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlotPoolTest {
    @Test
    fun `沒有任何 slot 時配置回 null`() {
        val pool = SlotPool<String>()
        assertNull(pool.allocate("a"))
    }

    @Test
    fun `登記後可以配置到,anchor 原樣回傳`() {
        val pool = SlotPool<String>()
        pool.register("a", "a#0", "anchor-0")
        val slot = pool.allocate("a")
        assertEquals("a#0", slot?.slotId)
        assertEquals("anchor-0", slot?.anchor)
    }

    @Test
    fun `配置後同一個 slot 不能再被配置第二次`() {
        val pool = SlotPool<String>()
        pool.register("a", "a#0", "anchor-0")
        pool.allocate("a")
        assertNull(pool.allocate("a"))
    }

    @Test
    fun `free 之後可以再配置`() {
        val pool = SlotPool<String>()
        pool.register("a", "a#0", "anchor-0")
        pool.allocate("a")
        pool.free("a#0")
        val slot = pool.allocate("a")
        assertEquals("a#0", slot?.slotId)
    }

    @Test
    fun `不同 dungeonId 的 slot 互不影響`() {
        val pool = SlotPool<String>()
        pool.register("a", "a#0", "anchor-a")
        pool.register("b", "b#0", "anchor-b")
        pool.allocate("a")
        assertNull(pool.allocate("a"))
        assertEquals("b#0", pool.allocate("b")?.slotId)
    }

    @Test
    fun `hasFree 是無副作用查詢,不會消耗 slot`() {
        val pool = SlotPool<String>()
        pool.register("a", "a#0", "anchor-0")
        assertTrue(pool.hasFree("a"))
        assertTrue(pool.hasFree("a"))
        assertEquals("a#0", pool.allocate("a")?.slotId)
    }

    @Test
    fun `freeCount 與 totalCount 統計正確`() {
        val pool = SlotPool<String>()
        pool.register("a", "a#0", "x")
        pool.register("a", "a#1", "y")
        assertEquals(2, pool.totalCount("a"))
        assertEquals(2, pool.freeCount("a"))
        pool.allocate("a")
        assertEquals(1, pool.freeCount("a"))
    }

    @Test
    fun `unregisterAll 清空後全部查詢回空`() {
        val pool = SlotPool<String>()
        pool.register("a", "a#0", "x")
        pool.unregisterAll()
        assertFalse(pool.hasFree("a"))
        assertEquals(0, pool.totalCount("a"))
    }

    @Test
    fun `並行搶佔同一個 slot 只有一個執行緒能拿到`() {
        val pool = SlotPool<String>()
        pool.register("a", "a#0", "anchor-0")
        val winners = java.util.concurrent.ConcurrentLinkedQueue<AllocatedSlot<String>>()
        val threads = (1..16).map {
            Thread {
                pool.allocate("a")?.let { winners += it }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, winners.size)
    }
}
