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
}
