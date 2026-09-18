package com.tinyyana.hanatoki.expedition

import com.tinyyana.hanatoki.inventory.InstanceInventoryService
import java.util.UUID

/**
 * [ExpeditionCustody] 的實作,薄薄一層轉給 [InstanceInventoryService]——真正的托管/消耗/還原
 * 狀態機都在那裡(它已經是局內背包交易的唯一權威來源,不需要另開一份平行帳本)。
 *
 * `deploy` 是同步呼叫,見 [InstanceInventoryService.consumeCarriedKit] KDoc 的執行緒契約:
 * 呼叫端必須已經在觸發這次部署的那位玩家自己的 region 序列執行區(典型情境是這位玩家自己
 * 觸發的 interaction callback)。
 */
class ExpeditionCustodyImpl(private val service: InstanceInventoryService) : ExpeditionCustody {

    override fun deploy(playerId: UUID, kitId: UUID, encounterId: String): Boolean =
        service.consumeCarriedKit(playerId, kitId, encounterId)

    override fun carried(playerId: UUID): List<UUID> = service.carriedKitsOf(playerId)
}
