package com.tinyyana.hanatoki.expedition

import java.util.UUID

/**
 * 整備包的托管與首次有效部署(ARCH 風格:primitive-only 跨插件簽章,見 [com.tinyyana.hanatoki.api.InstanceItems]
 * 同一套規則)。由 HanaToki 註冊進 Bukkit `ServicesManager`,內容插件取用。
 *
 * ## 托管語意
 *
 * 整備包透過 `instance-inventory.carry-in`(見 [com.tinyyana.hanatoki.config.CarryInDef])帶進 run,
 * 帶進來的那一刻起就被 [com.tinyyana.hanatoki.api.InstanceItems] 蓋上 instance 章——丟不掉、
 * 不能被非本局的人撿、不能進任何容器、死亡不會掉落、跨世界會被清掉。這裡不需要另外實作
 * escrow:攜入機制本身就是 escrow。
 *
 * ## 首次有效部署
 *
 * [deploy] 是**唯一**的 commit 點:內容層判定「這一包真的改變了這次的解法」那一刻呼叫,
 * 成功(`true`)代表這個 kitId 第一次被消耗——物品從玩家背包真的被移除,且這個 kitId
 * 之後永遠 idempotent(同一個 kitId 再呼叫一次回 `false`,不會二次生效)。
 *
 * 失敗(`false`)的情況:玩家沒有帶這個 kitId 進來、已經消耗過、或這個 instance 已經不在
 * [com.tinyyana.hanatoki.inventory.InstanceJournal.JournalState.ACTIVE](離場/收斂中一律拒絕
 * 新的部署,交給 restore 的還原/丟棄決定)。
 *
 * 沒有被 [deploy] 過的 kit,在 restore()(通關/死亡/離場/斷線逾時/admin reset/關服/崩潰重啟,
 * 全部同一條路)時原樣回到玩家永久背包。
 */
interface ExpeditionCustody {
    /** 內容層在「這一包真的改變了這次的解法」那一刻呼叫;回傳是否成功消耗(冪等,只有第一次成立)。 */
    fun deploy(playerId: UUID, kitId: UUID, encounterId: String): Boolean

    /** 這個玩家這次 run 帶進來、還沒用掉的整備包 kitId。 */
    fun carried(playerId: UUID): List<UUID>
}
