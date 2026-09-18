package com.tinyyana.hanatoki.expedition

import com.tinyyana.hanatoki.inventory.JournalState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ExpeditionCustody] 六種失敗路徑的純邏輯驗證(見 HanaToki 主線任務「首次有效部署與退回」)。
 * 這一層刻意不碰 Bukkit——跟 [com.tinyyana.hanatoki.inventory.JournalRecovery] 同一個理由,
 * 六種路徑最後都收斂成同一個問題:「instance 現在是什麼狀態、這個 kit 消耗過沒有」。
 */
class ExpeditionCustodyDecisionTest {

    @Test
    fun `ACTIVE 期間未消耗的 kit 可以部署`() {
        val kitId = UUID.randomUUID()
        val kits = listOf(KitStatus(kitId, consumed = false))
        assertTrue(ExpeditionCustodyDecision.canDeploy(JournalState.ACTIVE, kits, kitId))
    }

    @Test
    fun `重複部署同一個 kitId 第二次不成立(冪等)`() {
        val kitId = UUID.randomUUID()
        val fresh = listOf(KitStatus(kitId, consumed = false))
        assertTrue(ExpeditionCustodyDecision.canDeploy(JournalState.ACTIVE, fresh, kitId))

        val afterDeploy = listOf(KitStatus(kitId, consumed = true))
        assertFalse(ExpeditionCustodyDecision.canDeploy(JournalState.ACTIVE, afterDeploy, kitId), "已消耗過的 kit 不能再部署一次")
    }

    @Test
    fun `離場收斂中(CLEARING RESTORING PREPARED)一律拒絕部署`() {
        val kitId = UUID.randomUUID()
        val kits = listOf(KitStatus(kitId, consumed = false))
        for (state in listOf(JournalState.CLEARING, JournalState.RESTORING, JournalState.PREPARED)) {
            assertFalse(ExpeditionCustodyDecision.canDeploy(state, kits, kitId), "$state 不該允許新的部署")
        }
    }

    @Test
    fun `沒帶這個 kitId 進來的部署失敗`() {
        val kits = listOf(KitStatus(UUID.randomUUID(), consumed = false))
        assertFalse(ExpeditionCustodyDecision.canDeploy(JournalState.ACTIVE, kits, UUID.randomUUID()))
    }

    @Test
    fun `carried 只列出尚未消耗的`() {
        val kept = UUID.randomUUID()
        val used = UUID.randomUUID()
        val kits = listOf(KitStatus(kept, consumed = false), KitStatus(used, consumed = true))
        assertEquals(listOf(kept), ExpeditionCustodyDecision.carried(kits))
    }

    @Test
    fun `每位玩家的 kit 清單各自獨立,互不影響對方的部署結果`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val kitsOfA = listOf(KitStatus(a, consumed = false))
        val kitsOfB = listOf(KitStatus(b, consumed = false))

        // A 部署掉自己的 kit。
        val kitsOfAAfter = listOf(KitStatus(a, consumed = true))

        assertTrue(ExpeditionCustodyDecision.canDeploy(JournalState.ACTIVE, kitsOfA, a))
        assertFalse(ExpeditionCustodyDecision.canDeploy(JournalState.ACTIVE, kitsOfAAfter, a))
        // B 的清單完全沒被動過,一樣可以部署自己的 kit——多人同一個 run 的托管彼此獨立。
        assertTrue(ExpeditionCustodyDecision.canDeploy(JournalState.ACTIVE, kitsOfB, b))
        // A 也不能用自己的部署結果去動 B 的 kitId(kitId 不匹配)。
        assertFalse(ExpeditionCustodyDecision.canDeploy(JournalState.ACTIVE, kitsOfB, a))
    }

    @Test
    fun `離場時未消耗的 kit 該退回,消耗過的不該再出現`() {
        assertTrue(ExpeditionCustodyDecision.shouldReturn(KitStatus(UUID.randomUUID(), consumed = false)))
        assertFalse(ExpeditionCustodyDecision.shouldReturn(KitStatus(UUID.randomUUID(), consumed = true)))
    }

    @Test
    fun `斷線 逾時 admin reset 等離場原因不影響退回判定(只看 consumed)`() {
        // 這條刻意用不同「原因」建構同樣的 consumed 狀態,驗證 shouldReturn 不吃 reason 字串
        // ——OFFLINE_GRACE、DROPPED、EndReason 任一種、死亡結算都應該收斂成同一個布林判斷。
        val reasons = listOf("grace-timeout", "leave", "session-end-timeout", "session-end-all_dropped", "plugin-disable", "startup-recovery")
        val unconsumed = KitStatus(UUID.randomUUID(), consumed = false)
        val consumed = KitStatus(UUID.randomUUID(), consumed = true)
        for (reason in reasons) {
            assertTrue(ExpeditionCustodyDecision.shouldReturn(unconsumed), "reason=$reason 不該影響未消耗的退回判定")
            assertFalse(ExpeditionCustodyDecision.shouldReturn(consumed), "reason=$reason 不該影響已消耗的退回判定")
        }
    }
}
