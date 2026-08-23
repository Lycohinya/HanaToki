package com.tinyyana.hanatoki.instance

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTest {
    private fun setup(slotCount: Int = 1): SessionManager<String> {
        val pool = SlotPool<String>()
        repeat(slotCount) { pool.register("d", "d#$it", "anchor-$it") }
        return SessionManager(pool)
    }

    @Test
    fun `進場成功時回傳 Entered 並帶對應 anchor`() {
        val mgr = setup()
        val p = UUID.randomUUID()
        val result = mgr.enter("d", listOf(p), 0, 60_000, 30_000)
        assertTrue(result is EnterResult.Entered)
        assertEquals("anchor-0", result.anchor)
    }

    @Test
    fun `slot 用完時進場回 NoSlot`() {
        val mgr = setup(slotCount = 1)
        mgr.enter("d", listOf(UUID.randomUUID()), 0, 60_000, 30_000)
        val result = mgr.enter("d", listOf(UUID.randomUUID()), 0, 60_000, 30_000)
        assertEquals(EnterResult.NoSlot, result)
    }

    @Test
    fun `tick 逾時後結束 session 並回傳 EndedSession,但不釋放 slot(呼叫端要等回滾完成)`() {
        val mgr = setup()
        val p = UUID.randomUUID()
        mgr.enter("d", listOf(p), 0, 1000, 30_000)
        val ended = mgr.tick(1000)
        assertEquals(1, ended.size)
        assertEquals(EndReason.TIMEOUT, ended[0].reason)
        assertNull(mgr.sessionOf(p))
    }

    @Test
    fun `未逾時前 tick 不會結束 session`() {
        val mgr = setup()
        mgr.enter("d", listOf(UUID.randomUUID()), 0, 1000, 30_000)
        assertEquals(0, mgr.tick(500).size)
    }

    @Test
    fun `kick 單一成員後,若整局變空,回傳 EndedSession`() {
        val mgr = setup()
        val p = UUID.randomUUID()
        mgr.enter("d", listOf(p), 0, 60_000, 30_000)
        val ended = mgr.kick(p)
        assertEquals(EndReason.ADMIN_RESET, ended?.reason)
    }

    @Test
    fun `kick 其中一人,其他成員還在時不結束 session`() {
        val mgr = setup()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        mgr.enter("d", listOf(a, b), 0, 60_000, 30_000)
        val ended = mgr.kick(a)
        assertEquals(null, ended)
        assertNull(mgr.sessionOf(a))
        assertTrue(mgr.sessionOf(b) != null)
    }

    @Test
    fun `releaseSlotAfterRollback 之後 slot 才真正可以再被配置`() {
        val mgr = setup(slotCount = 1)
        val p = UUID.randomUUID()
        mgr.enter("d", listOf(p), 0, 1000, 30_000)
        val ended = mgr.tick(1000)
        // 回滾「完成前」不能再進場——這是 pool 直接測試過的行為，這裡驗證 SessionManager
        // 沒有在 endSessionBookkeeping 裡提早呼叫 slotPool.free。
        val blocked = mgr.enter("d", listOf(UUID.randomUUID()), 1000, 1000, 30_000)
        assertEquals(EnterResult.NoSlot, blocked)

        mgr.releaseSlotAfterRollback(ended[0].slotId)
        val afterRelease = mgr.enter("d", listOf(UUID.randomUUID()), 1000, 1000, 30_000)
        assertTrue(afterRelease is EnterResult.Entered)
    }

    @Test
    fun `endAll 立即結束所有 session,reason 照傳入值`() {
        val mgr = setup(slotCount = 2)
        mgr.enter("d", listOf(UUID.randomUUID()), 0, 60_000, 30_000)
        mgr.enter("d", listOf(UUID.randomUUID()), 0, 60_000, 30_000)
        val ended = mgr.endAll(EndReason.ABANDONED)
        assertEquals(2, ended.size)
        assertTrue(ended.all { it.reason == EndReason.ABANDONED })
        assertEquals(0, mgr.snapshot().size)
    }
}
