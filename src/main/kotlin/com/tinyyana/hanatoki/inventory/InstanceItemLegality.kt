package com.tinyyana.hanatoki.inventory

/**
 * 局內物品合法性的**純判定**,從 Bukkit PDC 讀取裡抽出來,才能不啟伺服器就把每一種生命週期
 * 組合釘死(見 [InstanceItemLegalityTest])。
 *
 * 兩個問法,共用同一份「這件物品的 instanceId 對這位玩家算不算數」的核心:
 *
 * - [isLegalFor]（外流防線 [InstanceItemGuard]）:永久物品恆合法;局內物品只在對這位玩家算數時合法。
 * - [heldLegallyInRun]（反向防線 [ForeignItemWarden]）:反向白名單——在 ACTIVE 的局內背包期間，
 *   玩家手上的每一格**只有**「這一局的局內物品」算合法，永久物品也不例外（那是別的插件塞進來的）。
 *
 * ## 「這件物品的 instanceId 對這位玩家算不算數」
 *
 * 1. **持有者現在不在任何 active Run**（[holderActiveInstanceId] == null）→ 不算。
 *    進場交易還沒 activate、已經 startRestore、或整局結束之後，玩家手上就不該再有任何局內物品
 *    能被動背包的路徑放行——少了這一條，「deliver 派工排在 restore 之後才真正執行」會把局內物品
 *    塞進已還原的永久背包（2026-09-08 症狀二的引擎側一半）。
 * 2. id == 持有者自己這一局的 instanceId → 算（單人局、engine 起始裝、自己撿自己掉的）。
 * 3. id == **同 session 任一在場隊員**的 instanceId → 算。深域組隊的掉落／起始裝身上只蓋得了
 *    一位隊員的 per-player instanceId（`state.instanceId`），隊友撿到／被發到的東西身上是別人的章，
 *    要跨隊友查才不會「只有隊長能玩」（2026-09-02 修 isLegalFor、2026-09-09 補 ForeignItemWarden：
 *    這條防線之前只認持有者自己的章，非隊長的起始裝每 2 秒被沒收一次）。
 */
object InstanceItemLegality {

    /** [InstanceItemGuard]（外流方向）問的：這件物品現在對這位玩家合不合法。 */
    fun isLegalFor(
        scoped: Boolean,
        itemInstanceId: String?,
        holderActiveInstanceId: String?,
        memberActiveInstanceIds: Collection<String>,
    ): Boolean {
        if (!scoped) return true
        return instanceIdCounts(itemInstanceId, holderActiveInstanceId, memberActiveInstanceIds)
    }

    /** [ForeignItemWarden]（反向白名單）問的：這件物品是不是「這一局的局內物品」，可以留在背包裡。 */
    fun heldLegallyInRun(
        scoped: Boolean,
        itemInstanceId: String?,
        holderActiveInstanceId: String?,
        memberActiveInstanceIds: Collection<String>,
    ): Boolean {
        if (!scoped) return false // 反向白名單:非局內物品一律不該留在 Run 裡
        return instanceIdCounts(itemInstanceId, holderActiveInstanceId, memberActiveInstanceIds)
    }

    private fun instanceIdCounts(
        itemInstanceId: String?,
        holderActiveInstanceId: String?,
        memberActiveInstanceIds: Collection<String>,
    ): Boolean {
        if (itemInstanceId == null) return false
        if (holderActiveInstanceId == null) return false
        if (itemInstanceId == holderActiveInstanceId) return true
        return memberActiveInstanceIds.any { it == itemInstanceId }
    }
}
