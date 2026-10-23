package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.reward.CompletionResult
import com.tinyyana.hanatoki.reward.RunStats
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.30 整合:每人打散統計（RunStats、CompletionResult.statFor）與敵人 `dispersed-tip`。 */
class Rc30StatsTest {
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    private fun enemiesWith(tip: String): String = ContentFixtures.FILES.getValue("enemies.yml").replace(
        "    needs-flash: { whiff-cue: whiff, stun-cue: ghost_stun, dissolve-cue: ghost_dissolve, refill-cue: page_refill }",
        "    needs-flash: { whiff-cue: whiff, stun-cue: ghost_stun, dissolve-cue: ghost_dissolve, refill-cue: page_refill }\n    dispersed-tip: $tip",
    )

    @Test
    fun `舊資料沒有 dispersed-tip`() {
        assertNull(ContentFixtures.parse().enemies.getValue("small_ghost").dispersedTip)
    }

    @Test
    fun `dispersed-tip 解析，預設值與壞資料`() {
        val full = ContentFixtures.parse(mapOf("enemies.yml" to enemiesWith("{ text: demo.tip, every: 3, count: 2, hold-seconds: 1.5 }")))
        assertEquals(DispersedTip("demo.tip", 3, 2, 1.5), full.enemies.getValue("small_ghost").dispersedTip)
        val minimal = ContentFixtures.parse(mapOf("enemies.yml" to enemiesWith("{ text: demo.tip }")))
        assertEquals(DispersedTip("demo.tip", 1, 1, 2.0), minimal.enemies.getValue("small_ghost").dispersedTip)
        val noText = assertFailsWith<ContentDataException> { ContentFixtures.parse(mapOf("enemies.yml" to enemiesWith("{ every: 3 }"))) }.errors
        assertTrue(noText.any { "dispersed-tip.text" in it && "缺少必要欄位" in it }, noText.toString())
        val extra = assertFailsWith<ContentDataException> { ContentFixtures.parse(mapOf("enemies.yml" to enemiesWith("{ text: demo.tip, extra: 1 }"))) }.errors
        assertTrue(extra.any { "extra" in it }, extra.toString())
        val missing = ContentValidator.validateTexts(full) { it == "demo.title" }
        assertTrue(missing.any { "enemies.small_ghost.dispersed-tip.text" in it && "demo.tip" in it }, missing.toString())
    }

    @Test
    fun `提示只在累計第 every、2×every 隻時送`() {
        val tip = DispersedTip("k", 3, 1, 2.0)
        assertFalse(tip.due(0)); assertFalse(tip.due(1)); assertFalse(tip.due(2))
        assertTrue(tip.due(3)); assertFalse(tip.due(4)); assertTrue(tip.due(6))
        assertTrue(DispersedTip("k", 1, 1, 2.0).due(1))
    }

    @Test
    fun `RunStats 整局合計與逐人鍵各加一，回傳這位玩家累計數`() {
        val stats = HashMap<String, Long>()
        assertEquals(1L, RunStats.record(stats, RunStats.DISPERSED, alice))
        assertEquals(2L, RunStats.record(stats, RunStats.DISPERSED, alice))
        assertEquals(1L, RunStats.record(stats, RunStats.DISPERSED, bob))
        assertEquals(3L, stats.getValue(RunStats.DISPERSED))
        assertEquals(2L, stats.getValue(RunStats.playerKey(RunStats.DISPERSED, alice)))
        assertEquals(1L, stats.getValue(RunStats.playerKey(RunStats.DISPERSED, bob)))
    }

    @Test
    fun `statFor 先找自己的逐人鍵，沒有才退回整局值`() {
        val stats = mapOf(RunStats.DISPERSED to 3L, RunStats.playerKey(RunStats.DISPERSED, alice) to 2L)
        fun result(player: UUID) = CompletionResult(UUID.randomUUID(), player, "d", "cleared", 1000L, stats)
        assertEquals(2L, result(alice).statFor(RunStats.DISPERSED))
        assertEquals(3L, result(bob).statFor(RunStats.DISPERSED), "沒有逐人鍵就退回整局值")
        assertEquals(0L, result(alice).statFor("nothing"))
    }
}
