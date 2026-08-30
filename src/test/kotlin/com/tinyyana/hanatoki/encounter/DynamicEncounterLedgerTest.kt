package com.tinyyana.hanatoki.encounter

import com.tinyyana.hanatoki.config.DynamicEncounterLimits
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicEncounterLedgerTest {
    private val limits = DynamicEncounterLimits(maxActive = 2, maxEntities = 5, maxDrops = 2)
    private val session = UUID.randomUUID()

    private fun ids(n: Int) = List(n) { UUID.randomUUID() }

    private fun spawn(ledger: DynamicEncounterLedger, template: String, n: Int): DynamicEncounterLedger.Tracked {
        assertNull(ledger.tryReserve(session, n, limits))
        return ledger.register(session, template, ids(n))
    }

    @Test
    fun `同一個 template 可以並存,各自有不同的 runtime id 與獨立的 remaining`() {
        val ledger = DynamicEncounterLedger()
        val a = spawn(ledger, "pack.husk", 2)
        val b = spawn(ledger, "pack.husk", 2)
        assertNotEquals(a.runtimeId, b.runtimeId)
        assertEquals(2, ledger.activeCountOf(session))
        // 清掉 a 的兩隻,b 完全不受影響
        assertFalse(ledger.entityGone(a.entityIds[0])!!.cleared)
        assertTrue(ledger.entityGone(a.entityIds[1])!!.cleared)
        assertEquals(1, ledger.activeCountOf(session))
        assertEquals(2, ledger.tracked(b.runtimeId)!!.remaining.size)
        assertEquals("pack.husk", ledger.tracked(b.runtimeId)!!.templateId)
    }

    @Test
    fun `cap 場數與實體數都有上限,超過就拒絕而且不改任何計數`() {
        val ledger = DynamicEncounterLedger()
        spawn(ledger, "a", 2)
        spawn(ledger, "b", 2)
        assertNotNull(ledger.tryReserve(session, 1, limits)) // maxActive = 2
        assertEquals(4, ledger.entityCountOf(session))
        // 清掉一場之後場數解除,但實體上限 5 仍然擋 2 隻
        val a = ledger.runtimeIdsOf(session).first()
        ledger.despawn(a)
        assertNotNull(ledger.tryReserve(session, 4, limits))
        assertNull(ledger.tryReserve(session, 3, limits))
        assertEquals(5, ledger.entityCountOf(session))
    }

    @Test
    fun `partial failure reservation 釋放後計數歸零,表裡沒有東西`() {
        val ledger = DynamicEncounterLedger()
        assertNull(ledger.tryReserve(session, 3, limits))
        assertEquals(3, ledger.entityCountOf(session))
        ledger.releaseReservation(session, 3)
        assertEquals(0, ledger.entityCountOf(session))
        assertEquals(0, ledger.activeCountOf(session))
        assertTrue(ledger.totals().all { it == 0 })
    }

    @Test
    fun `onCleared 恰好一次 最後一隻離場之後再收到同一隻的事件是 no-op`() {
        val ledger = DynamicEncounterLedger()
        val t = spawn(ledger, "a", 1)
        val first = ledger.entityGone(t.entityIds[0])
        assertNotNull(first)
        assertTrue(first.cleared)
        assertNull(ledger.entityGone(t.entityIds[0]))
        assertNull(ledger.tracked(t.runtimeId))
        assertEquals(DynamicEncounterLedger.State.CLEARED, t.state)
        assertTrue(ledger.totals().all { it == 0 })
    }

    @Test
    fun `死亡撞上清場只有一個終態 despawn 之後的死亡事件不產生任何結果`() {
        val ledger = DynamicEncounterLedger()
        val t = spawn(ledger, "a", 2)
        val gone = ledger.despawn(t.runtimeId)
        assertEquals(t.entityIds.toSet(), gone!!.toSet())
        assertEquals(DynamicEncounterLedger.State.DESPAWNED, t.state)
        assertNull(ledger.entityGone(t.entityIds[0]))
        assertNull(ledger.despawn(t.runtimeId))
        assertEquals(0, ledger.entityCountOf(session))
        assertTrue(ledger.totals().all { it == 0 })
    }

    @Test
    fun `反過來 已經清場的場再 despawn 是 no-op`() {
        val ledger = DynamicEncounterLedger()
        val t = spawn(ledger, "a", 1)
        assertTrue(ledger.entityGone(t.entityIds[0])!!.cleared)
        assertNull(ledger.despawn(t.runtimeId))
    }

    @Test
    fun `未知實體不屬於任何場`() {
        val ledger = DynamicEncounterLedger()
        spawn(ledger, "a", 1)
        assertNull(ledger.entityGone(UUID.randomUUID()))
        assertNull(ledger.encounterOf(UUID.randomUUID()))
    }

    @Test
    fun `掉落物 有上限,被撿走會釋放,session 結束一次拿走全部`() {
        val ledger = DynamicEncounterLedger()
        assertTrue(ledger.tryReserveDrop(session, limits))
        assertTrue(ledger.tryReserveDrop(session, limits))
        assertFalse(ledger.tryReserveDrop(session, limits))
        val d1 = UUID.randomUUID()
        val d2 = UUID.randomUUID()
        ledger.registerDrop(session, d1)
        ledger.registerDrop(session, d2)
        assertTrue(ledger.isTrackedDrop(d1))
        assertTrue(ledger.dropGone(d1))
        assertFalse(ledger.dropGone(d1))
        assertTrue(ledger.tryReserveDrop(session, limits))
        assertEquals(listOf(d2), ledger.takeDropsOf(session))
        assertEquals(0, ledger.dropCountOf(session))
        assertFalse(ledger.isTrackedDrop(d2))
    }

    @Test
    fun `兩個 session 的 cap 互不影響`() {
        val ledger = DynamicEncounterLedger()
        val other = UUID.randomUUID()
        spawn(ledger, "a", 5)
        assertNull(ledger.tryReserve(other, 5, limits))
        assertEquals(5, ledger.entityCountOf(other))
        assertEquals(listOf<String>(), ledger.runtimeIdsOf(other))
    }
}
