package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.inventory.KeepInventoryRestore.SlotFact
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** keep-inventory 還原的決策(見 [KeepInventoryRestore] 的不變式):恰好一份、可重跑。 */
class KeepInventoryRestoreTest {
    private val kit = UUID.randomUUID()
    private val used = UUID.randomUUID()

    @Test
    fun `沒用掉的整備包在身上就脫章,不補第二份`() {
        val plan = KeepInventoryRestore.plan(listOf(SlotFact(3, true, kit)), listOf(kit to false))
        assertEquals(listOf(3), plan.unmark)
        assertEquals(emptyList(), plan.remove)
        assertEquals(emptyList(), plan.restoreFromJournal)
    }

    @Test
    fun `沒用掉的整備包不在身上就從 journal 補回`() {
        val plan = KeepInventoryRestore.plan(emptyList(), listOf(kit to false))
        assertEquals(listOf(kit), plan.restoreFromJournal)
    }

    @Test
    fun `重跑,已經脫章的那件照樣算找到,不會補出第二份`() {
        val plan = KeepInventoryRestore.plan(listOf(SlotFact(3, false, kit)), listOf(kit to false))
        assertEquals(emptyList(), plan.unmark)
        assertEquals(emptyList(), plan.restoreFromJournal)
    }

    @Test
    fun `用掉的那件若還蓋著章就移除,沒蓋章的同款不碰`() {
        val plan = KeepInventoryRestore.plan(
            listOf(SlotFact(1, true, used), SlotFact(2, false, used)),
            listOf(used to true),
        )
        assertEquals(listOf(1), plan.remove)
        assertEquals(emptyList(), plan.restoreFromJournal)
    }

    @Test
    fun `其他蓋著這一局章的局內物一律移除`() {
        val plan = KeepInventoryRestore.plan(listOf(SlotFact(0, true, null), SlotFact(4, true, kit)), listOf(kit to false))
        assertEquals(listOf(0), plan.remove)
        assertEquals(listOf(4), plan.unmark)
    }

    @Test
    fun `同一個識別值蓋章的第二件是局內複製,移除`() {
        val plan = KeepInventoryRestore.plan(listOf(SlotFact(0, true, kit), SlotFact(5, true, kit)), listOf(kit to false))
        assertEquals(listOf(0), plan.unmark)
        assertEquals(listOf(5), plan.remove)
    }
}
