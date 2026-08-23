package com.tinyyana.hanatoki.world

import kotlin.test.Test
import kotlin.test.assertEquals

class DiffLogTest {
    @Test
    fun `reverseEntries 回傳的順序與記錄順序相反`() {
        val log = DiffLog<Int, String>()
        log.record(1, "a")
        log.record(2, "b")
        log.record(3, "c")
        assertEquals(listOf(3, 2, 1), log.reverseEntries().map { it.key })
    }

    @Test
    fun `size 反映記錄筆數,clear 之後歸零`() {
        val log = DiffLog<Int, String>()
        log.record(1, "a")
        log.record(2, "b")
        assertEquals(2, log.size())
        log.clear()
        assertEquals(0, log.size())
        assertEquals(emptyList(), log.reverseEntries())
    }

    @Test
    fun `同一個 key 記錄多次時,每一筆都保留(不去重),回滾要照序全部處理`() {
        val log = DiffLog<Int, String>()
        log.record(1, "a")
        log.record(1, "b")
        assertEquals(2, log.size())
        assertEquals(listOf("b", "a"), log.reverseEntries().map { it.before })
    }

    @Test
    fun `groupedReverse 依分組鍵分組,組內維持逆序`() {
        val log = DiffLog<Pair<Int, Int>, String>()
        log.record(0 to 0, "a") // region 0
        log.record(1 to 0, "b") // region 1
        log.record(0 to 1, "c") // region 0
        val grouped = log.groupedReverse { (region, _) -> region }
        assertEquals(listOf(0 to 1, 0 to 0), grouped[0]!!.map { it.key })
        assertEquals(listOf(1 to 0), grouped[1]!!.map { it.key })
    }
}
