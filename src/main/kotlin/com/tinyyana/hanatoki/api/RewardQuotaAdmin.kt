package com.tinyyana.hanatoki.api

import java.util.UUID

/**
 * 管理員手動加值「某位玩家在某座副本的通關獎勵額度」(ServicesManager 註冊)。
 *
 * 存在理由:額度算式與計數器全部住在**內容層**(見 [RewardQuotaLookup] 的 KDoc),外部管理
 * 工具(例如選單插件的 `/xxx admin` 指令)要補一名玩家卡住的額度,不能自己猜計數器 id 直接寫
 * 資料庫——那份 id 是內容層的設定值,猜錯或設定改過都會補到一個沒人讀的地方。這條寫入橋跟
 * [RewardQuotaLookup] 一樣,只是換成寫入方向。
 *
 * 跟 [RewardQuotaLookup] 分成兩支介面而不是合併:那支是唯讀查詢,這支會改變玩家資料,呼叫端
 * 的權限與稽核要求通常不同(同 [com.tinyyana.hanatoki.api.DungeonAccess] 跟
 * [com.tinyyana.hanatoki.api.PresenceBridge] 分開的理由)。
 *
 * ⚠ 簽章 primitive-only(UUID/String/Int),過 `tools/check-cross-plugin-kotlin.py` 的跨插件
 * 紅線;不使用 Kotlin 預設參數。
 */
interface RewardQuotaAdmin {

    /**
     * 幫 [playerId] 在 [dungeonId] 這座副本的額度加值 [amount] 份。加值的份數**不受額度上限
     * 封頂**(管理員補發本來就是為了蓋過異常狀況,見 [RewardQuotaLookup] 的
     * `QuotaSnapshot.bonus` 說明)。
     *
     * @return 加值後的總份數;`null` = 加值失敗(沒這座副本、內容層沒配獎勵規則)。
     */
    fun grantBonusQuota(playerId: UUID, dungeonId: String, amount: Int): Int?
}
