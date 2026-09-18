package com.tinyyana.hanatoki.expedition

import com.tinyyana.hanatoki.inventory.JournalState
import java.util.UUID

/** 一個攜入 kit 目前的托管狀態(純資料,不含 ItemStack/PDC)。 */
data class KitStatus(val kitId: UUID, val consumed: Boolean)

/**
 * [ExpeditionCustody] 的合法性判斷,抽成不碰 Bukkit 的純函數(跟 [com.tinyyana.hanatoki.inventory.JournalRecovery]
 * 同一個理由:六種失敗路徑要能被單元測試直接打,不必靠真 Folia 環境驗證)。
 */
object ExpeditionCustodyDecision {

    /**
     * `deploy` 合不合法:instance 必須還在 [JournalState.ACTIVE](離場/收斂中一律拒絕——那些
     * 情境屬於 restore 的還原/丟棄決定,不該再讓內容層插一腳改變托管結果),而且這個 kitId
     * 存在、尚未被消耗過。
     *
     * 涵蓋的失敗路徑:
     * - 找不到這個 kitId(玩家根本沒帶這包進來,或早就 restore 完了)→ false。
     * - 已經被消耗過(冪等)→ false。
     * - instance 已經在 CLEARING/RESTORING/PREPARED(離場中/還沒真正開局)→ false。
     */
    fun canDeploy(state: JournalState, kits: List<KitStatus>, kitId: UUID): Boolean =
        state == JournalState.ACTIVE && kits.any { it.kitId == kitId && !it.consumed }

    /** 目前尚未消耗、仍在玩家手上的 kitId 清單。 */
    fun carried(kits: List<KitStatus>): List<UUID> = kits.filter { !it.consumed }.map { it.kitId }

    /** 收斂時,這個 kit 該不該原樣還給玩家(== 沒被部署過)。 */
    fun shouldReturn(status: KitStatus): Boolean = !status.consumed
}
