package com.tinyyana.hanatoki.inventory

/**
 * 「這位玩家現在還能不能收到一份局內獎品」的**純判定**,從 Bukkit 讀取裡抽出來,才能不啟
 * 伺服器就把生命週期競態釘死(見 RunItemLifecycleTest)。
 *
 * Content delivery / `giveLoadout` 的護盾分支會在**真正動玩家背包的最後一刻**問
 * 這一句;false 就把獎品改留成合法的場上掉落(章不變,是還在場某位隊員的 instanceId),
 * 不塞進他可能已還原的永久背包、也不靜靜刪掉。
 *
 * 2026-09-08 正式服症狀二的根因:這件事以前只在 async 工作排入前檢查過一次「Player 在不在線」。
 * 排入到 lambda 真的跑之間,recipient 可能死亡/離隊/撤離/斷線,而
 * `InstanceInventoryService.startRestore` 已經在把他的永久背包還回去。
 */
object RunDelivery {

    /**
     * @param online recipient 現在在不在線
     * @param instanceItemsPresent HanaToki 的 `InstanceItems` service 在不在(不在就沒有 scope
     *   概念,維持既有降級:一律放行)
     * @param recipientActiveInstanceId recipient 現在握著的 active 局內背包 id;null = 他已經
     *   不在任何 active Run(進場交易還沒 activate、已 startRestore、或整局結束)
     * @param sessionMembershipKnown 呼叫端有沒有 session 視圖(`ctx != null`)
     * @param recipientIsActiveMember recipient 還是不是這一局 session 的在場成員
     */
    fun canReceive(
        online: Boolean,
        instanceItemsPresent: Boolean,
        recipientActiveInstanceId: String?,
        sessionMembershipKnown: Boolean,
        recipientIsActiveMember: Boolean,
    ): Boolean {
        if (!online) return false
        if (!instanceItemsPresent) return true
        if (recipientActiveInstanceId == null) return false
        return !sessionMembershipKnown || recipientIsActiveMember
    }
}
