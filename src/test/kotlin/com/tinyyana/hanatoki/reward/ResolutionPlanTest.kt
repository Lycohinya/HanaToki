package com.tinyyana.hanatoki.reward

import com.tinyyana.hanatoki.stage.InstanceState
import com.tinyyana.hanatoki.stage.StageDefinition
import com.tinyyana.hanatoki.stage.StageGraph
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `resolve`/`resolveEach` 共用的結算清單:逐人 key、同一份 stats 快照、一個 stage 只結算一次。
 * (HanaTokiCore.resolveSession 傳常數 key、resolveSessionEach 查 map,兩者都經過 [ResolutionPlan.claim]。)
 */
class ResolutionPlanTest {
    private fun state() = InstanceState(StageGraph("boss", mapOf("boss" to StageDefinition("boss"))))

    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()

    /** HanaTokiCore.resolveSessionEach 用的就是這一支。 */
    private fun eachKeys(map: Map<UUID, String>): (UUID) -> String? = ResolutionPlan.eachKeyFor(map)

    @Test
    fun `eachKeyFor copies the map at call time`() {
        val map = HashMap<UUID, String>().apply { put(a, "clear") }
        val keyFor = ResolutionPlan.eachKeyFor(map)
        map[a] = "changed"; map[b] = "late"
        assertEquals("clear", keyFor(a))
        assertNull(keyFor(b))
    }

    @Test
    fun `resolveEach gives each listed member their own key and skips unlisted and blank`() {
        val s = state()
        val results = ResolutionPlan.claim(
            s, listOf(a, b, c), "mist", 1234L,
            eachKeys(mapOf(a to "clear", b to "  ")),
        )!!
        assertEquals(listOf(a), results.map { it.playerId })
        assertEquals("clear", results.single().resultKey)
        assertEquals("mist", results.single().dungeonId)
        assertEquals(1234L, results.single().durationMs)
    }

    @Test
    fun `non-member entries are ignored`() {
        val outsider = UUID.randomUUID()
        val results = ResolutionPlan.claim(
            state(), listOf(a, b), "mist", 0L,
            eachKeys(mapOf(a to "clear", b to "mist-none", outsider to "clear")),
        )!!
        assertEquals(listOf(a to "clear", b to "mist-none"), results.map { it.playerId to it.resultKey })
    }

    @Test
    fun `all results share one stats snapshot (same run id) but have distinct completion ids`() {
        val s = state()
        s.stats["run.id_hi"] = 42L
        s.stats["run.id_lo"] = 7L
        val results = ResolutionPlan.claim(
            s, listOf(a, b, c), "mist", 0L,
            eachKeys(mapOf(a to "clear", b to "clear", c to "mist-none")),
        )!!
        assertEquals(3, results.size)
        assertEquals(3, results.map { it.completionId }.toSet().size)
        results.forEach { assertSame(results.first().stats, it.stats) }
        assertEquals(42L, results.first().stats["run.id_hi"])
        // 快照在認領當下拍:之後改 state.stats 不影響已產生的結果
        s.stats["run.id_hi"] = 99L
        assertEquals(42L, results.first().stats["run.id_hi"])
    }

    @Test
    fun `second resolveEach in the same stage produces nothing`() {
        val s = state()
        assertNotNull(ResolutionPlan.claim(s, listOf(a), "mist", 0L, eachKeys(mapOf(a to "clear"))))
        assertNull(ResolutionPlan.claim(s, listOf(a), "mist", 0L, eachKeys(mapOf(a to "clear"))))
    }

    @Test
    fun `resolve then resolveEach (and the reverse) only settles once`() {
        val s1 = state()
        assertEquals(2, ResolutionPlan.claim(s1, listOf(a, b), "mist", 0L) { "clear" }!!.size)
        assertNull(ResolutionPlan.claim(s1, listOf(a, b), "mist", 0L, eachKeys(mapOf(a to "clear"))))

        val s2 = state()
        assertNotNull(ResolutionPlan.claim(s2, listOf(a, b), "mist", 0L, eachKeys(mapOf(a to "clear"))))
        assertNull(ResolutionPlan.claim(s2, listOf(a, b), "mist", 0L) { "clear" })
    }

    @Test
    fun `empty map still claims the stage (whole run closes like resolve) with no results`() {
        val s = state()
        val results = ResolutionPlan.claim(s, listOf(a, b), "mist", 0L, eachKeys(emptyMap()))
        assertNotNull(results)
        assertTrue(results.isEmpty())
        assertNull(ResolutionPlan.claim(s, listOf(a, b), "mist", 0L) { "clear" })
    }

    @Test
    fun `resolve keeps old behaviour - same key for every member, even blank`() {
        val results = ResolutionPlan.claim(state(), listOf(a, b), "mist", 0L) { "" }!!
        assertEquals(listOf("", ""), results.map { it.resultKey })
    }

    @Test
    fun `new stage resets the claim`() {
        val s = state()
        assertNotNull(ResolutionPlan.claim(s, listOf(a), "mist", 0L) { "clear" })
        s.enterStage("boss", 10L)
        assertNotNull(ResolutionPlan.claim(s, listOf(a), "mist", 0L, eachKeys(mapOf(a to "clear"))))
    }
}
