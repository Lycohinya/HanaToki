package com.tinyyana.hanatoki.inventory

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-09 稽核問題 1 的迴歸測試:「見證有沒有發過」不能綁在 [JournalState] 上。
 *
 * 這是整個修法唯一的判斷點([ExpeditionEvidenceGuard]),照 [JournalRecoveryTest] 的做法抽成
 * 純函數直接打——不需要真的跑一次關服/重啟就能證明「舊 bug 的那個狀態組合」現在會判斷成
 * 要發送。
 */
class ExpeditionEvidenceGuardTest {

    private fun record(state: JournalState, evidenceDispatched: Boolean) = JournalRecord(
        instanceId = UUID.randomUUID(),
        playerId = UUID.randomUUID(),
        dungeonId = "test-roguelike",
        slotId = "test-roguelike#0",
        sessionId = UUID.randomUUID(),
        state = state,
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        returnPoint = null,
        snapshot = InventorySnapshot(byteArrayOf(1), heldSlot = 0, contentsSize = 41),
        carryIn = emptyList(),
        evidenceDispatched = evidenceDispatched,
    )

    @Test
    fun `還沒發過的紀錄要發送`() {
        assertTrue(ExpeditionEvidenceGuard.shouldDispatch(record(JournalState.ACTIVE, evidenceDispatched = false)))
    }

    @Test
    fun `已經發過的紀錄不再發送`() {
        assertFalse(ExpeditionEvidenceGuard.shouldDispatch(record(JournalState.RESTORING, evidenceDispatched = true)))
    }

    /**
     * **這一格就是舊 bug 本身**:`shutdownFlush()` 把 state 直接跳成 RESTORING,卻從來沒有真的
     * 發送過見證(`evidenceDispatched` 依然是預設值 false)。舊版判斷式是
     * `state != JournalState.RESTORING`,對這個組合會算出「已經是 RESTORING,不用再發」——
     * 於是這批見證永久遺失,這正是稽核報告描述的症狀。
     *
     * 新判斷式只看 [JournalRecord.evidenceDispatched],對同一個組合要判斷成「還沒發過,要發」。
     */
    @Test
    fun `state 已經是 RESTORING 但 evidenceDispatched 是 false(關服漏發的那個組合)仍然要發送`() {
        val record = record(JournalState.RESTORING, evidenceDispatched = false)
        assertTrue(
            ExpeditionEvidenceGuard.shouldDispatch(record),
            "state=RESTORING 但 evidenceDispatched=false 必須判斷成『要發送』," +
                "不然就是重現 2026-09 稽核問題 1(關服見證永久遺失)",
        )
    }

    @Test
    fun `崩潰重啟的常態組合(state 還是 ACTIVE_CLEARING,一定沒發過)也要發送`() {
        assertTrue(ExpeditionEvidenceGuard.shouldDispatch(record(JournalState.ACTIVE, evidenceDispatched = false)))
        assertTrue(ExpeditionEvidenceGuard.shouldDispatch(record(JournalState.CLEARING, evidenceDispatched = false)))
    }
}
