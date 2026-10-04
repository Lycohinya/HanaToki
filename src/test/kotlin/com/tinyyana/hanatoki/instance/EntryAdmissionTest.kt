package com.tinyyana.hanatoki.instance

import com.tinyyana.hanatoki.api.DungeonEntryStatus
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 進場最終 admission 的純邏輯:檢查順序、失敗不留狀態、同時到達的請求只有一個通過,
 * 以及 [EntryAdmission.isPending] 的生命週期(交易中 true、收尾後 false、收斂中 true、停用清空)。
 */
class EntryAdmissionTest {

    private class Fake : EntryAdmission.Ports {
        val caps = HashMap<String, Int>()
        val online = HashSet<UUID>()
        val inSession = HashSet<UUID>()
        val settling = HashSet<UUID>()
        val blocked = HashMap<UUID, String>()
        val names = HashMap<UUID, String>()

        override fun partyCap(dungeonId: String): Int? = caps[dungeonId]
        override fun isOnline(playerId: UUID): Boolean = playerId in online
        override fun nameOf(playerId: UUID): String = names[playerId] ?: "?"
        override fun isInSession(playerId: UUID): Boolean = playerId in inSession
        override fun isSettling(playerId: UUID): Boolean = playerId in settling
        override fun eligibilityBlock(playerId: UUID, dungeonId: String): String? = blocked[playerId]

        fun player(name: String): UUID = UUID.randomUUID().also { online += it; names[it] = name }
    }

    private val lock = Any()

    private fun setup(): Pair<Fake, EntryAdmission> {
        val f = Fake().apply { caps["tachizuka"] = 2; caps["deep"] = 3 }
        return f to EntryAdmission(lock, f)
    }

    @Test
    fun `unknown or closed dungeon is NO_DUNGEON`() {
        val (f, a) = setup()
        val p = f.player("A")
        assertEquals(DungeonEntryStatus.NO_DUNGEON, a.check(listOf(p), "nope")?.status)
    }

    @Test
    fun `empty list, duplicate uuid and over party-cap are PARTY_INVALID`() {
        val (f, a) = setup()
        val p1 = f.player("A"); val p2 = f.player("B"); val p3 = f.player("C")
        assertEquals(DungeonEntryStatus.PARTY_INVALID, a.check(emptyList(), "deep")?.status)
        assertEquals(DungeonEntryStatus.PARTY_INVALID, a.check(listOf(p1, p1), "deep")?.status)
        assertEquals(DungeonEntryStatus.PARTY_INVALID, a.check(listOf(p1, p2, p3), "tachizuka")?.status)
        assertNull(a.check(listOf(p1, p2), "tachizuka"))
        assertNull(a.check(listOf(p1, p2, p3), "deep"))
        assertTrue(a.check(listOf(p1, p2, p3), "tachizuka")!!.reason.contains("2"))
    }

    @Test
    fun `offline member is PLAYER_OFFLINE and names who`() {
        val (f, a) = setup()
        val p1 = f.player("A"); val p2 = f.player("B")
        f.online -= p2
        val r = a.check(listOf(p1, p2), "tachizuka")!!
        assertEquals(DungeonEntryStatus.PLAYER_OFFLINE, r.status)
        assertTrue(r.reason.contains("B"))
    }

    @Test
    fun `member already in a session or settling is ALREADY_INSIDE`() {
        val (f, a) = setup()
        val p1 = f.player("A"); val p2 = f.player("B")
        f.inSession += p2
        assertEquals(DungeonEntryStatus.ALREADY_INSIDE, a.check(listOf(p1, p2), "tachizuka")?.status)
        f.inSession.clear()
        f.settling += p1
        assertEquals(DungeonEntryStatus.ALREADY_INSIDE, a.check(listOf(p1, p2), "tachizuka")?.status)
        f.settling.clear()
        assertNull(a.check(listOf(p1, p2), "tachizuka"))
    }

    @Test
    fun `content eligibility is NOT_ELIGIBLE with the readable reason passed through`() {
        val (f, a) = setup()
        val p1 = f.player("A")
        f.blocked[p1] = "A 還沒有整備包,先到整備台做一包"
        val r = a.check(listOf(p1), "deep")!!
        assertEquals(DungeonEntryStatus.NOT_ELIGIBLE, r.status)
        assertEquals("A 還沒有整備包,先到整備台做一包", r.reason)
        assertEquals("A 還沒有整備包,先到整備台做一包", a.blockReason(p1, "deep"))
    }

    @Test
    fun `order is cap before offline before inside before eligibility`() {
        val (f, a) = setup()
        val p1 = f.player("A"); val p2 = f.player("B"); val p3 = f.player("C")
        f.online -= p2; f.inSession += p3; f.blocked[p1] = "x"
        assertEquals(DungeonEntryStatus.PARTY_INVALID, a.check(listOf(p1, p2, p3), "tachizuka")?.status)
        assertEquals(DungeonEntryStatus.PLAYER_OFFLINE, a.check(listOf(p1, p2), "tachizuka")?.status)
        assertEquals(DungeonEntryStatus.ALREADY_INSIDE, a.check(listOf(p1, p3), "tachizuka")?.status)
        assertEquals(DungeonEntryStatus.NOT_ELIGIBLE, a.check(listOf(p1), "tachizuka")?.status)
    }

    @Test
    fun `check and blockReason are read-only`() {
        val (f, a) = setup()
        val p1 = f.player("A")
        assertNull(a.blockReason(p1, "deep"))
        assertNull(a.check(listOf(p1), "deep"))
        assertFalse(a.isPending(p1))
        assertNull(a.admitAndReserve(listOf(p1), "deep")) // 之前的預檢沒有佔位
    }

    @Test
    fun `rejected request leaves no reservation`() {
        val (f, a) = setup()
        val p1 = f.player("A"); val p2 = f.player("B"); val p3 = f.player("C")
        assertNotNull(a.admitAndReserve(listOf(p1, p2, p3), "tachizuka"))
        assertFalse(a.isPending(p1))
        assertFalse(a.isPending(p2))
        assertFalse(a.isPending(p3))
    }

    @Test
    fun `second request for a reserved player is rejected until released`() {
        val (f, a) = setup()
        val p1 = f.player("A"); val p2 = f.player("B")
        assertNull(a.admitAndReserve(listOf(p1, p2), "tachizuka"))
        assertTrue(a.isPending(p1))
        assertTrue(a.isPending(p2))
        // 任一重疊的名單都被擋成 ALREADY_INSIDE,且不會登記新的人
        val p3 = f.player("C")
        assertEquals(DungeonEntryStatus.ALREADY_INSIDE, a.admitAndReserve(listOf(p2, p3), "tachizuka")?.status)
        assertFalse(a.isPending(p3))
        assertNotNull(a.blockReason(p1, "deep"))
        a.release(listOf(p1, p2))
        assertFalse(a.isPending(p1))
        assertNull(a.admitAndReserve(listOf(p2, p3), "tachizuka"))
    }

    @Test
    fun `pending also covers settling after the transaction ends and clear drops reservations`() {
        val (f, a) = setup()
        val p1 = f.player("A")
        assertNull(a.admitAndReserve(listOf(p1), "deep"))
        // 失敗回滾:交易收尾(release)但送出場地/背包歸還還在收斂 → 仍 pending
        f.settling += p1
        a.release(listOf(p1))
        assertTrue(a.isPending(p1))
        f.settling -= p1
        assertFalse(a.isPending(p1))
        // 停用:登記清空,之後遲到的 release 是 no-op
        assertNull(a.admitAndReserve(listOf(p1), "deep"))
        a.clear()
        assertFalse(a.isPending(p1))
        a.release(listOf(p1))
        assertFalse(a.isPending(p1))
    }

    @Test
    fun `concurrent requests for the same player admit exactly one`() {
        val (f, a) = setup()
        val p1 = f.player("A")
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val admitted = AtomicInteger()
        val rejected = AtomicInteger()
        val wrongStatus = AtomicInteger()
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                val r = a.admitAndReserve(listOf(p1), "deep")
                if (r == null) admitted.incrementAndGet() else {
                    if (r.status != DungeonEntryStatus.ALREADY_INSIDE) wrongStatus.incrementAndGet()
                    rejected.incrementAndGet()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, admitted.get())
        assertEquals(threads - 1, rejected.get())
        assertEquals(0, wrongStatus.get())
    }
}
