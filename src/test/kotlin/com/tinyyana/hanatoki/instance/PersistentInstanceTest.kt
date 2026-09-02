package com.tinyyana.hanatoki.instance

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 常駐形態 instance 的狀態機(MIGRATION_PLAN §5.0 決策 A/B)。
 *
 * 這一組測試釘住的是「蒼櫻沒有失去舊系統已經具備的能力」裡最容易在重構中被弄壞的兩件事:
 * **常駐 instance 不會因為時間或沒人而消失**,以及**同一座常駐副本永遠只有一個 instance**
 * (第二個人走進來是加入,不是開新局)。
 */
class PersistentInstanceTest {

    private fun setup(slotCount: Int = 1): SessionManager<String> {
        val pool = SlotPool<String>()
        repeat(slotCount) { pool.register("d", "d#$it", "anchor-$it") }
        return SessionManager(pool)
    }

    @Test
    fun `常駐 session 不會逾時`() {
        val session = Session(UUID.randomUUID(), "d", "d#0", 0, 1_000, 500, persistent = true)
        session.addMember(UUID.randomUUID(), 0)
        assertFalse(session.isExpired(999_999_999))
    }

    @Test
    fun `常駐 session 全員退出仍然存活`() {
        val p = UUID.randomUUID()
        val session = Session(UUID.randomUUID(), "d", "d#0", 0, 1_000, 500, persistent = true)
        session.addMember(p, 0)
        session.drop(p)
        assertFalse(session.isAllDropped())
    }

    @Test
    fun `同一座常駐副本第二次進場是 Joined,共用同一個 instance 與 slot`() {
        val mgr = setup(slotCount = 1)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val first = mgr.enter("d", listOf(a), 0, Long.MAX_VALUE, 30_000, persistent = true)
        assertTrue(first is EnterResult.Entered)
        val second = mgr.enter("d", listOf(b), 100, Long.MAX_VALUE, 30_000, persistent = true)
        assertTrue(second is EnterResult.Joined)
        assertEquals(first.session.sessionId, second.session.sessionId)
        assertEquals("anchor-0", second.anchor)
        assertEquals(2, second.session.activeMembers().size)
    }

    @Test
    fun `重複加入同一個人不會把既有成員狀態洗掉`() {
        val mgr = setup()
        val p = UUID.randomUUID()
        mgr.enter("d", listOf(p), 0, Long.MAX_VALUE, 30_000, persistent = true)
        val session = assertNotNull(mgr.sessionOf(p))
        mgr.markOffline(p, 10)
        // 同一個人再次「走進世界」——不該把 OFFLINE_GRACE 直接洗成新的 ONLINE 成員,
        // 那會讓離線 grace 的計時無限重置。
        mgr.enter("d", listOf(p), 20, Long.MAX_VALUE, 30_000, persistent = true)
        assertEquals(MemberState.OFFLINE_GRACE, session.stateOf(p))
    }

    @Test
    fun `常駐 instance 的最後一名成員離開後 tick 不會結束它`() {
        val mgr = setup()
        val p = UUID.randomUUID()
        mgr.enter("d", listOf(p), 0, Long.MAX_VALUE, 30_000, persistent = true)
        assertNull(mgr.kick(p))
        assertEquals(emptyList(), mgr.tick(999_999).ended)
        assertEquals(1, mgr.snapshot().size)
        // slot 仍然被那個常駐 instance 佔著,不會被別人分走。
        assertEquals(EnterResult.NoSlot, mgr.enter("other", listOf(UUID.randomUUID()), 0, 1000, 1000))
    }

    @Test
    fun `常駐 instance 結束之後同一 dungeonId 可以重新建立`() {
        val mgr = setup()
        val p = UUID.randomUUID()
        val first = mgr.enter("d", listOf(p), 0, Long.MAX_VALUE, 30_000, persistent = true)
        assertTrue(first is EnterResult.Entered)
        // 關服收斂:endAll 之後索引要乾淨,不然重開的 instance 會查到一個已經死掉的 session。
        mgr.endAll(EndReason.ABANDONED)
        mgr.releaseSlotAfterRollback("d#0")
        val again = mgr.enter("d", listOf(p), 0, Long.MAX_VALUE, 30_000, persistent = true)
        assertTrue(again is EnterResult.Entered)
        assertTrue(again.session.sessionId != first.session.sessionId)
    }

    @Test
    fun `session 形態不受影響,仍然逾時並結束`() {
        val mgr = setup()
        val p = UUID.randomUUID()
        mgr.enter("d", listOf(p), 0, 1_000, 30_000)
        val ended = mgr.tick(1_000).ended
        assertEquals(1, ended.size)
        assertEquals(EndReason.TIMEOUT, ended[0].reason)
    }
}
