package com.tinyyana.hanatoki.reward

import com.tinyyana.hanatoki.stage.InstanceState
import com.tinyyana.hanatoki.stage.StageDefinition
import com.tinyyana.hanatoki.stage.StageGraph
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.34:中途離場／逾時的收集結算(純邏輯部分)。接線在 HanaTokiCore(kick／abandon／tick),要伺服器才能跑。 */
class DepartureSettlementTest {
    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b2")

    private fun state() = InstanceState(StageGraph("front", mapOf("front" to StageDefinition("front"))))

    @Test
    fun `結果 key 是 left 與 timeout`() {
        assertEquals("left", DepartureSettlement.LEFT)
        assertEquals("timeout", DepartureSettlement.TIMEOUT)
    }

    @Test
    fun `沒有任何收集帳的局不觸發 其他副本的 stats 也不會誤中`() {
        val s = state()
        assertNull(DepartureSettlement.plan(s, alice, "d", "left", 1000))
        s.stats["dispersed"] = 5
        s.stats[RunStats.playerKey("dispersed", alice)] = 5
        s.stats["kills.$alice"] = 3
        assertNull(DepartureSettlement.plan(s, alice, "d", "left", 1000))
        // 沒觸發就不能燒掉認領:之後真的收了還是要能結算
        s.stats["collect.spot_a.$alice"] = 1
        assertNotNull(DepartureSettlement.plan(s, alice, "d", "left", 1000))
    }

    @Test
    fun `只看自己的逐人鍵 隊友收了與整局合計都不算`() {
        val s = state()
        s.stats["collect.spot_a"] = 1
        s.stats["collect.spot_a.$bob"] = 1
        assertFalse(DepartureSettlement.hasCollected(s.stats, alice))
        assertNull(DepartureSettlement.plan(s, alice, "d", "left", 1000))
        assertTrue(DepartureSettlement.hasCollected(s.stats, bob))
    }

    @Test
    fun `數值為 0 不算收過 只有前綴或只有後綴也不算`() {
        assertFalse(DepartureSettlement.hasCollected(mapOf("collect.spot_a.$alice" to 0L), alice))
        assertFalse(DepartureSettlement.hasCollected(mapOf("collect.$alice" to 1L), alice))
        assertFalse(DepartureSettlement.hasCollected(mapOf("other.spot_a.$alice" to 1L), alice))
        assertFalse(DepartureSettlement.hasCollected(mapOf("collected.spot_a.$alice" to 1L), alice))
        assertTrue(DepartureSettlement.hasCollected(mapOf("collect.spot_a.$alice" to 1L), alice))
    }

    @Test
    fun `有收集帳的人產生一筆結算 帶整份 stats 快照`() {
        val s = state()
        s.stats["collect.spot_a"] = 2
        s.stats[RunStats.playerKey("collect.spot_a", alice)] = 1
        s.stats[RunStats.playerKey("collect.spot_a", bob)] = 1
        s.stats["run.id_hi"] = 7
        val r = assertNotNull(DepartureSettlement.plan(s, alice, "mist-patrol", "timeout", 90_000))
        assertEquals(alice, r.playerId)
        assertEquals("mist-patrol", r.dungeonId)
        assertEquals("timeout", r.resultKey)
        assertEquals(90_000L, r.durationMs)
        assertEquals(1L, r.stats["collect.spot_a.$alice"])
        assertEquals(7L, r.stats["run.id_hi"])
        // 快照:之後 stats 再變不影響已產生的結算
        s.stats["collect.spot_b.$alice"] = 1
        assertNull(r.stats["collect.spot_b.$alice"])
    }

    @Test
    fun `每位玩家每局只結算一次 另一位照常`() {
        val s = state()
        s.stats["collect.spot_a.$alice"] = 1
        s.stats["collect.spot_a.$bob"] = 1
        val first = assertNotNull(DepartureSettlement.plan(s, alice, "d", "left", 10))
        assertNull(DepartureSettlement.plan(s, alice, "d", "timeout", 20), "先離場後逾時不重複")
        val other = assertNotNull(DepartureSettlement.plan(s, bob, "d", "timeout", 20))
        assertNotEquals(first.completionId, other.completionId)
    }

    @Test
    fun `個別結算先認領的人 逾時不再補`() {
        val s = state()
        s.stats["collect.spot_a.$alice"] = 1
        assertTrue(s.claimDeparture(alice)) // resolveMember 的做法
        assertNull(DepartureSettlement.plan(s, alice, "d", "timeout", 20))
    }

    @Test
    fun `時間為負數時夾成 0`() {
        val s = state()
        s.stats["collect.spot_a.$alice"] = 1
        assertEquals(0L, DepartureSettlement.plan(s, alice, "d", "left", -5)!!.durationMs)
    }

    @Test
    fun `snapshot 遇到並行修改會重試 一直失敗才丟出`() {
        var calls = 0
        val flaky = object : AbstractMap<String, Long>() {
            override val entries: Set<Map.Entry<String, Long>>
                get() {
                    calls++
                    if (calls <= 2) throw ConcurrentModificationException()
                    return mapOf("collect.x.$alice" to 1L).entries
                }
        }
        assertEquals(mapOf("collect.x.$alice" to 1L), DepartureSettlement.snapshot(flaky))
        assertTrue(calls >= 3, "calls=$calls")
        val broken = object : AbstractMap<String, Long>() {
            override val entries: Set<Map.Entry<String, Long>> get() = throw ConcurrentModificationException()
        }
        kotlin.test.assertFailsWith<ConcurrentModificationException> { DepartureSettlement.snapshot(broken) }
    }

}
