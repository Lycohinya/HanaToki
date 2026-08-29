package com.tinyyana.hanatoki.instance

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTest {
    private fun session(now: Long = 0L, timeLimitMs: Long = 60_000, graceMs: Long = 30_000) =
        Session(UUID.randomUUID(), "test-empty", "test-empty#0", now, timeLimitMs, graceMs)

    @Test
    fun `新成員預設是 ONLINE`() {
        val s = session()
        val p = UUID.randomUUID()
        s.addMember(p, 0)
        assertEquals(MemberState.ONLINE, s.stateOf(p))
    }

    @Test
    fun `未逾時前 isExpired 為 false,逾時後為 true`() {
        val s = session(timeLimitMs = 1000)
        assertFalse(s.isExpired(999))
        assertTrue(s.isExpired(1000))
    }

    @Test
    fun `離線後在 grace 內重連恢復 ONLINE`() {
        val s = session(graceMs = 1000)
        val p = UUID.randomUUID()
        s.addMember(p, 0)
        s.markOffline(p, 100)
        assertTrue(s.reconnect(p, 100 + 999))
        assertEquals(MemberState.ONLINE, s.stateOf(p))
    }

    @Test
    fun `離線超過 grace 後重連失敗,狀態變 DROPPED`() {
        val s = session(graceMs = 1000)
        val p = UUID.randomUUID()
        s.addMember(p, 0)
        s.markOffline(p, 100)
        assertFalse(s.reconnect(p, 100 + 1001))
        assertEquals(MemberState.DROPPED, s.stateOf(p))
    }

    @Test
    fun `sweepExpiredGrace 把逾期的 offline 成員轉成 DROPPED 並回傳名單`() {
        val s = session(graceMs = 1000)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        s.addMember(a, 0)
        s.addMember(b, 0)
        s.markOffline(a, 0)
        s.markOffline(b, 500)
        val dropped = s.sweepExpiredGrace(1001)
        assertEquals(listOf(a), dropped)
        assertEquals(MemberState.OFFLINE_GRACE, s.stateOf(b))
    }

    @Test
    fun `全員 DROPPED 時 isAllDropped 為 true`() {
        val s = session()
        val p = UUID.randomUUID()
        s.addMember(p, 0)
        assertFalse(s.isAllDropped())
        s.drop(p)
        assertTrue(s.isAllDropped())
    }

    @Test
    fun `沒有任何成員時 isAllDropped 為 false(避免剛建立就被當空 session 提前結算)`() {
        val s = session()
        assertFalse(s.isAllDropped())
    }

    @Test
    fun `activeMembers 排除已 DROPPED 的成員`() {
        val s = session()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        s.addMember(a, 0)
        s.addMember(b, 0)
        s.drop(a)
        assertEquals(listOf(b), s.activeMembers())
    }

    @Test
    fun `重連一個從未離線過的成員直接回 true(視為已在場)`() {
        val s = session()
        val p = UUID.randomUUID()
        s.addMember(p, 0)
        assertTrue(s.reconnect(p, 0))
    }

    @Test
    fun `remainingMs 不會變負數`() {
        val s = session(timeLimitMs = 1000)
        assertEquals(0, s.remainingMs(5000))
        assertEquals(500, s.remainingMs(500))
    }

    // ---- Endless Run(timeLimitMs = null)-----------------------------------
    //
    // 這一組就是「無時限不能用假無限值表達」的回歸測試。以前常駐副本塞 Long.MAX_VALUE,
    // remainingMs 會回傳 2.9 億年;而如果改用 0 表達,顯示層會畫成「時間到」。

    private fun endless(now: Long = 0L, graceMs: Long = 30_000) =
        Session(UUID.randomUUID(), "test-roguelike", "test-roguelike#0", now, null, graceMs)

    @Test
    fun `無時限 session 永遠不會逾時`() {
        val s = endless()
        assertFalse(s.isExpired(0))
        assertFalse(s.isExpired(Long.MAX_VALUE / 2))
    }

    @Test
    fun `無時限 session 的 hasTimeLimit 是 false,有時限的是 true`() {
        assertFalse(endless().hasTimeLimit())
        assertTrue(session(timeLimitMs = 1000).hasTimeLimit())
    }

    @Test
    fun `無時限 session 的 remainingMs 回傳 NO_TIME_LIMIT 而不是 0 或超大值`() {
        val s = endless()
        assertEquals(Session.NO_TIME_LIMIT, s.remainingMs(0))
        assertEquals(Session.NO_TIME_LIMIT, s.remainingMs(999_999_999L))
        assertTrue(Session.NO_TIME_LIMIT < 0, "哨兵值必須是負數,才不會被當成秒數畫進進度條")
    }

    @Test
    fun `elapsedMs 是單調不遞減的,而且不會是負數`() {
        val s = endless(now = 1_000)
        assertEquals(0, s.elapsedMs(500), "時鐘回頭時要夾在 0,不能出現負的已跑時間")
        assertEquals(0, s.elapsedMs(1_000))
        assertEquals(9_000, s.elapsedMs(10_000))
    }

    @Test
    fun `無時限 session 仍然會因為全員退出而收斂`() {
        val s = endless()
        val p = UUID.randomUUID()
        s.addMember(p, 0)
        s.drop(p)
        assertTrue(s.isAllDropped(), "沒有時限不代表沒有結束條件")
    }

    @Test
    fun `無時限 session 的離線 grace 照樣會把人 drop 掉`() {
        val s = endless(graceMs = 1000)
        val p = UUID.randomUUID()
        s.addMember(p, 0)
        s.markOffline(p, 100)
        assertEquals(listOf(p), s.sweepExpiredGrace(1_200))
        assertEquals(MemberState.DROPPED, s.stateOf(p))
    }

    @Test
    fun `常駐 session 即使帶了時限也永不逾時(persistent 優先)`() {
        val s = Session(UUID.randomUUID(), "sakura", "sakura#0", 0, 1_000, 30_000, persistent = true)
        assertFalse(s.isExpired(999_999))
        assertFalse(s.hasTimeLimit())
    }
}
