package com.tinyyana.hanatoki.expedition

import java.util.UUID

/**
 * 見證出口——照 [com.tinyyana.hanatoki.reward.RewardSink] 的形狀,只用 primitive。
 *
 * HanaToki 在整備包所屬的 instance **第一次**從 ACTIVE/CLEARING 轉進 RESTORING 的那一刻
 * (= [com.tinyyana.hanatoki.inventory.InstanceInventoryService] 的 `startRestore`,涵蓋通關/死亡/
 * 離場/斷線逾時/admin reset/關服/崩潰重啟全部路徑)為每一個攜入的整備包各呼叫一次,
 * 不論它有沒有被 `ExpeditionCustody.deploy` 過(用 [deployed] 分辨)。
 *
 * 跟 `RewardSink` 一樣是 fire-and-forget:HanaToki 不等待、不重試、不 ack。額度(`RewardQuota*`)
 * 用完**不會**擋這個呼叫——見證出口跟獎勵發放是兩條完全獨立的路徑。
 *
 * @param product 固定常數 `"expedition_kit"`(整備包契約唯一定義的產品類型)。
 * @param runId 這次 run 的 sessionId;找不到(理論上不會發生,防禦性 null)時為 null。
 */
fun interface ExpeditionSink {
    fun onResolved(
        kitId: UUID,
        deskId: UUID,
        packRevision: Long,
        ability: String,
        product: String,
        playerId: UUID,
        dungeonId: String,
        encounterId: String,
        deployed: Boolean,
        runId: UUID?,
        packedAtMs: Long,
        resolvedAtMs: Long,
    )
}
