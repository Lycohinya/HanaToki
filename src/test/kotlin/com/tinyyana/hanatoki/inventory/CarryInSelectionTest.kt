package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.config.CarryInDef
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarryInSelectionTest {

    private val rule = CarryInDef("lophinya", "kit", max = 1)

    @Test
    fun `找到一格符合的物品就選中它`() {
        val kitId = UUID.randomUUID()
        val candidates = listOf(CarryCandidate(3, mapOf("lophinya:kit" to kitId.toString())))
        val selection = CarryInSelection.select(candidates, listOf(rule))
        assertEquals(listOf(CarrySelection(3, rule, kitId)), selection)
    }

    @Test
    fun `沒有任何格位帶那個 key 就選不到`() {
        val candidates = listOf(CarryCandidate(0, mapOf("other:key" to "x")))
        assertTrue(CarryInSelection.select(candidates, listOf(rule)).isEmpty())
    }

    @Test
    fun `值不是合法 UUID 的格位 fail closed 跳過`() {
        val candidates = listOf(CarryCandidate(0, mapOf("lophinya:kit" to "not-a-uuid")))
        assertTrue(CarryInSelection.select(candidates, listOf(rule)).isEmpty())
    }

    @Test
    fun `超過 max 的候選不會入選`() {
        val rule2 = rule.copy(max = 2)
        val ids = List(3) { UUID.randomUUID() }
        val candidates = ids.mapIndexed { i, id -> CarryCandidate(i, mapOf("lophinya:kit" to id.toString())) }
        val selection = CarryInSelection.select(candidates, listOf(rule2))
        assertEquals(2, selection.size)
        assertEquals(listOf(0, 1), selection.map { it.slotIndex })
    }

    @Test
    fun `同一格位不會被兩條規則重複選中`() {
        val kitId = UUID.randomUUID()
        val ruleA = CarryInDef("lophinya", "kit", max = 1)
        val ruleB = CarryInDef("lophinya", "kit", max = 1) // 故意跟 A 撞同一個 key
        val candidates = listOf(CarryCandidate(0, mapOf("lophinya:kit" to kitId.toString())))
        val selection = CarryInSelection.select(candidates, listOf(ruleA, ruleB))
        assertEquals(1, selection.size, "同一格位只該被算一次,不能被兩條規則各自算一次配額")
    }

    @Test
    fun `多條規則各自算配額互不影響`() {
        val ruleKit = CarryInDef("lophinya", "kit", max = 1)
        val ruleBadge = CarryInDef("lycohinya", "badge", max = 1)
        val kitId = UUID.randomUUID()
        val badgeId = UUID.randomUUID()
        val candidates = listOf(
            CarryCandidate(0, mapOf("lophinya:kit" to kitId.toString())),
            CarryCandidate(1, mapOf("lycohinya:badge" to badgeId.toString())),
        )
        val selection = CarryInSelection.select(candidates, listOf(ruleKit, ruleBadge))
        assertEquals(2, selection.size)
    }

    @Test
    fun `空規則或空候選都回空清單`() {
        assertTrue(CarryInSelection.select(emptyList(), listOf(rule)).isEmpty())
        assertTrue(CarryInSelection.select(listOf(CarryCandidate(0, mapOf("lophinya:kit" to UUID.randomUUID().toString()))), emptyList()).isEmpty())
    }
}
