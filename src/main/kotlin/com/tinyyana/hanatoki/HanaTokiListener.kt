package com.tinyyana.hanatoki

import com.tinyyana.hanatoki.folia.PlayerOp
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent

/**
 * ARCH §5.3:離線標記/重連只做 submit()——這裡的 handler 本身不碰狀態機內部,只轉呼叫
 * [HanaTokiCore] 對外的方法(它們內部才是真正的狀態變更)。事件在事發玩家的 region 觸發,
 * 但 [HanaTokiCore.sessionManager] 目前的操作都是無鎖 ConcurrentHashMap 查表 +
 * 單一 session 物件的方法呼叫,Phase 1 場景(單一 session 內操作自己的 members)不需要
 * 額外經 `instance.submit()`——若 Phase 2 引入跨 session 共享狀態,要重新檢視這一條。
 *
 * Phase 2 新增:interaction/encounter 事件一樣只做「查表轉發」,實際判定與世界動作在
 * [com.tinyyana.hanatoki.stage.StageEngine]/[com.tinyyana.hanatoki.encounter.EncounterController]
 * 裡面(它們自己負責走 `instance.submit()`/`WorldOp`,這裡不重複派工)。
 */
class HanaTokiListener(private val core: HanaTokiCore) : Listener {

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        core.sessionManager.markOffline(event.player.uniqueId, System.currentTimeMillis())
    }

    /**
     * 常駐副本的成員資格 = 「人在不在那個世界」(MIGRATION_PLAN §5.0 決策 D)。
     *
     * 為什麼不能只靠進場指令/選單:蒼櫻是一個常駐世界,玩家可以用 `/spawn`、傳送簽名、
     * 任何別的插件離開它。舊系統靠每次 tick 讀 `world.players` 所以「走掉就不算」是自動的;
     * HanaToki 的成員集合是登記表,不主動同步的話那個人會**繼續收到廣播、繼續算在破防檢定裡、
     * 通關時繼續拿花蜜**,人卻早就不在場了。
     */
    @EventHandler
    fun onWorldChange(event: org.bukkit.event.player.PlayerChangedWorldEvent) {
        val player = event.player
        val session = core.sessionManager.sessionOf(player.uniqueId)
        if (session != null && session.persistent &&
            core.persistentDungeonIdForWorld(player.world.name) != session.dungeonId
        ) {
            core.leavePersistent(player.uniqueId)
        }
        // session 型副本(深域這種)沒有「成員資格跟著世界走」這一條,所以玩家用 /home 走掉
        // 之後 session 會繼續跑——Threat 照升、怪照生、slot 照佔著(2026-09-01 真人回報)。
        if (session != null && !session.persistent) {
            val slotWorld = core.slotWorldName(session.slotId)
            if (slotWorld != null && player.world.name != slotWorld) {
                core.abandon(player.uniqueId, "left-world")
            }
        }
        core.joinPersistentByWorld(player.uniqueId, player.world.name)
        // 跨世界是局內物品最直接的洩漏路徑(用傳送簽名/`/spawn` 走出去)。到了新世界之後
        // 掃一次背包,不合法的局內物品當場清掉——事件在該玩家自己的 region 觸發,可以直接動背包。
        core.instanceItemGuard.purgeIllegal(player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // 登入時人就在常駐副本世界裡(在裡面登出的常態)。不傳送、不發訊息——舊系統就是原地
        // 上線,而且那個世界有真的地板,不需要任何安全網。
        if (core.persistentDungeonIdForWorld(player.world.name) != null) {
            core.sessionManager.reconnect(player.uniqueId, System.currentTimeMillis())
            core.joinPersistentByWorld(player.uniqueId, player.world.name)
            return
        }
        // ⚠ 重連判定必須排在局內背包恢復**之前**。
        //
        //   `recoverOnJoin` → `restore` → `startRestore` 的第一件事就是
        //   `activeByPlayer.remove(...)`,也就是把「這位玩家現在在跑哪一局」清掉。原本的順序
        //   (先恢復、再判定重連)對一個 grace 還沒過、session 還活著的斷線玩家來說,等於
        //   「人重連回副本裡了,但引擎認為他不在任何一局」——那一局地上的東西、身上的東西
        //   全部 `isLegalFor` = false,撿不起來也用不了。
        //
        //   `reconnect` 只是登記表操作(無 I/O、無派工),移到前面不會延後任何事;而
        //   **重連不到 session 的人恢復路徑完全不變**——崩潰重啟/grace 逾時/關服殘局的玩家
        //   `reconnect` 一律回 false,照樣走 `recoverOnJoin` 把欠他的永久背包還回去。
        val reconnected = core.sessionManager.reconnect(player.uniqueId, System.currentTimeMillis())
        // 局內背包:上次沒收斂完的交易在這裡接手(崩潰重啟、還原途中登出、死亡後直接離線)。
        if (!reconnected) core.instanceInventory.recoverOnJoin(player.uniqueId)
        // 背包掃描兩條路都要跑:重連回來的人身上也可能帶著**更早那一局**的殘留物品。
        core.instanceItemGuard.purgeIllegal(player)

        if (reconnected) {
            val session = core.sessionManager.sessionOf(player.uniqueId) ?: return
            val anchor = core.slotPool.anchorOf(session.slotId) ?: return
            val display = core.registry.definitions[session.dungeonId]?.display ?: session.dungeonId
            player.teleportAsync(anchor)
            PlayerOp.message(core.plugin, player.uniqueId, core.texts.format("session.reconnected", mapOf("dungeon" to display)))
            return
        }
        // 沒有 session 卻登入在副本世界裡 = 上線時被留在那裡了(grace 逾時、伺服器重啟、
        // 關服時來不及送出去)。副本世界沒有地板也沒有出口,不主動撿回來的話玩家只能自己
        // 求救。**這條是專屬副本世界必備的安全網,不是可有可無的貼心。**
        if (core.isDungeonWorld(player.world.name)) {
            core.sendHome(player.uniqueId)
            PlayerOp.message(core.plugin, player.uniqueId, core.texts.format("session.recovered"))
        }
    }

    /**
     * 在副本裡死亡:session 已由 [onPlayerDeath] 結束,但重生點如果還指向副本世界(沒有床/
     * 重生錨的玩家會回該世界出生點),玩家一按重生就又回到虛空世界的安全平台上。這裡直接把
     * 重生落點改成他的返回點。
     */
    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        // ⚠ 還原的排程要在**任何 early return 之前**:玩家在副本裡死掉、但重生點是主世界的床時,
        //   下面那個 `isDungeonWorld` 判斷會直接 return,而他一樣欠著一份永久背包。
        scheduleRestoreAfterRespawn(event.player)

        if (!core.isDungeonWorld(event.respawnLocation.world.name)) return
        val destination = core.returnPoints.destinationFor(event.player) ?: return
        event.respawnLocation = destination
    }

    /**
     * 死亡當下不能把永久背包寫回去(`PlayerDeathEvent` 之後原版才會把背包清成掉落物),
     * 所以還原被推遲到重生。這裡在**玩家自己的 EntityScheduler** 上排一 tick 之後執行,
     * 讓原版的重生流程先跑完。
     *
     * ⚠ 用 `PlayerRespawnEvent` 而不是 `PlayerPostRespawnEvent`:後者在 API 裡還在,
     * 但這台核心(Lecithin 26.2)**不會觸發它**——2026-08-29 實測,整個 L4 跑下來
     * 「(respawn)」的還原 log 一次都沒出現過。當時死亡路徑之所以還會過,是因為收斂流程
     * 意外地重複呼叫了還原,晚的那次剛好撞在重生之後;把重複呼叫收斂成單一管線之後,
     * 這個洞就露出來了。不要再依賴那個事件。
     */
    private fun scheduleRestoreAfterRespawn(player: org.bukkit.entity.Player) {
        player.scheduler.runDelayed(
            core.plugin,
            { _ -> core.instanceInventory.restoreForPlayer(player.uniqueId, "respawn") },
            // retired(重生途中又登出):journal 留在 RESTORING,下次登入的恢復會接手。
            null,
            1L,
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.PHYSICAL) return
        core.stageEngine.handleInteraction(event.player.uniqueId, block.location, event.action)
    }

    /**
     * 一個實體死亡可能是 encounter 的小怪,也可能是 Boss 型 actor——兩邊各自查表,查不到就是
     * no-op(絕大多數死亡事件跟 HanaToki 無關,兩次 ConcurrentHashMap 查表的成本可忽略)。
     *
     * 副本生成的實體**一律不掉落物品與經驗**:①actor 的裝備是演出道具(Boss 手上那把刀),
     * 掉出來就變成玩家可以帶走的複製品;②掉在場地上的東西會在整局結束的 diff 回滾之後留在
     * 那裡(回滾只還原方塊,不收拾掉落物),下一局玩家會撿到上一局的殘骸;③獎勵一律走
     * `RewardSink`(有 completionId 幂等、有額度閘門),掉落物是繞過那條線的免費產出。
     *
     * 用 `HIGHEST` 而不是 `MONITOR`:MONITOR 依約定不該改事件內容,而這裡要清 drops。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entityId = event.entity.uniqueId
        if (core.isDungeonOwnedEntity(entityId)) {
            event.drops.clear()
            event.droppedExp = 0
        }
        core.stageEngine.encounters.onEntityDeath(entityId)
        // 死亡座標在這裡(死亡 region)讀好傳進去,控制器不再跨 region 讀實體。
        core.stageEngine.dynamicEncounters.onEntityDeath(entityId, event.entity.location.clone())
        core.stageEngine.handleActorDeath(entityId)
    }

    /**
     * 沒有死亡事件就消失的實體(區塊卸載、別的插件 `remove()`、掉出世界、被撿走的掉落物)。
     * 死亡之後也會來一次——那時索引已經清了,是 no-op。少了這條,動態 encounter 會有永遠
     * 清不掉的場(見 DynamicEncounterController 的 KDoc)。
     */
    @EventHandler
    fun onEntityRemoved(event: com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent) {
        core.stageEngine.dynamicEncounters.onEntityRemovedFromWorld(event.entity.uniqueId)
    }

    /**
     * ARCH §5.3:副本內死亡 = 退出 Session(重生點交還一般死亡流程,不是 HanaToki 的責任)。
     *
     * ⚠ 常駐副本例外:那裡的成員資格是「人在不在世界裡」,而死掉的人重生之後**還在那個世界**
     * (沒有床就回世界出生點,蒼櫻的出生點就在競技場邊緣)。踢掉他等於「你死一次就不算參與者了,
     * 站在原地但收不到廣播也拿不到獎勵」——那不是現況行為。真的走掉的話 `onWorldChange` 會處理。
     */
    /**
     * ARCH §5.3 + §5.6:副本內死亡。
     *
     * 三件事,順序有意義:
     * 1. **先把局內物品從掉落清單移除**——不然它們會落在場地上,活過 session 結束
     *    (場地回滾只還原方塊,不收拾掉落物),下一局的人就撿得到。
     * 2. 定義有開 `death-resolution` 的話走一次 `resultKey=death` 的 Resolution
     *    (Roguelike 語意:死亡也是一種 Run 結果)。
     * 3. 否則維持既有行為:死亡 = 退出這一局。
     *
     * ⚠ 常駐副本例外(決策 D):那裡的成員資格是「人在不在世界裡」,死掉的人重生之後還在
     * 那個世界。踢掉他等於「死一次就不算參與者」,不是現況行為。
     *
     * 用 `HIGHEST` 是因為要改 `drops`(`MONITOR` 依約定不該改事件內容)。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val playerId = event.player.uniqueId
        val stripped = core.instanceItemGuard.stripInstanceItemsFromDrops(event.drops)
        if (stripped > 0) {
            core.plugin.logger.info("[HanaToki] ${event.player.name} 死亡時移除了 $stripped 組局內物品掉落")
        }
        if (core.sessionManager.sessionOf(playerId)?.persistent == true) return
        if (core.handlePlayerDeath(playerId)) return // 已走 Resolution,session 由那條路收斂
        core.kick(playerId)
    }

    /**
     * 保險絲:這台核心目前不觸發這個事件(見 [scheduleRestoreAfterRespawn] 的說明),
     * 留著是為了「哪天核心又開始送它」時仍然正確——還原是冪等的,重複呼叫沒有代價。
     */
    @EventHandler
    fun onPostRespawn(event: PlayerPostRespawnEvent) {
        core.instanceInventory.restoreForPlayer(event.player.uniqueId, "post-respawn")
    }
}
