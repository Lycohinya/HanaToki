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

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val reconnected = core.sessionManager.reconnect(player.uniqueId, System.currentTimeMillis())
        if (reconnected) {
            val session = core.sessionManager.sessionOf(player.uniqueId) ?: return
            val anchor = core.slotPool.anchorOf(session.slotId) ?: return
            val display = core.registry.definitions[session.dungeonId]?.display ?: session.dungeonId
            player.teleportAsync(anchor)
            PlayerOp.message(core.plugin, player.uniqueId, core.texts.format("session.reconnected", mapOf("dungeon" to display)))
        }
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
        core.stageEngine.handleActorDeath(entityId)
    }

    /** ARCH §5.3:副本內死亡 = 退出 Session(重生點交還一般死亡流程,不是 HanaToki 的責任)。 */
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        core.kick(event.player.uniqueId)
    }
}
