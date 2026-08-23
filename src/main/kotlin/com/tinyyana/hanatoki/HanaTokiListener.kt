package com.tinyyana.hanatoki

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
            player.teleportAsync(anchor)
            player.sendMessage("§a已重連回副本 ${session.dungeonId}")
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.PHYSICAL) return
        core.stageEngine.handleInteraction(event.player.uniqueId, block.location, event.action)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        core.stageEngine.encounters.onEntityDeath(event.entity.uniqueId)
    }

    /** ARCH §5.3:副本內死亡 = 退出 Session(重生點交還一般死亡流程,不是 HanaToki 的責任)。 */
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        core.kick(event.player.uniqueId)
    }
}
