package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.HanaTokiCore
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * 資料驅動內容的事件入口:局內道具右鍵、needs-flash 敵人的受擊攔截。
 *
 * 執行緒:事件在事發實體/玩家自己的 region 執行緒。**判定(取消與否)在這裡同步做**(只讀 volatile 的
 * [EnemyMachine.state]),其餘一律先 `ctx.submit` 回到 session 的 anchor region 再動狀態。
 */
class DataContentListener(private val core: HanaTokiCore, private val runtime: DataContentRuntime) : Listener {

    /**
     * 右鍵閃光。注意:
     * - 不能 `ignoreCancelled`:對空氣右鍵的事件常常一來就是 cancelled 狀態;
     * - 只認主手事件(`hand == HAND`):主副手各來一次,副手那次只負責取消(避免放置),不閃光;
     * - 一律拒絕使用道具與方塊互動——道具不能放置、不能開門、不能觸發原版行為。
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (!runtime.hasRuns) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        val toolId = runtime.toolItems.idOf(item) ?: return
        event.setUseItemInHand(Event.Result.DENY)
        event.setUseInteractedBlock(Event.Result.DENY)
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val run = runtime.runOfPlayer(player.uniqueId) ?: return
        // 持有者快照:事件當下的眼睛位置與視線方向(玩家自己的 region 執行緒上讀)。
        run.onFlash(player.uniqueId, toolId, player.eyeLocation.clone(), player.eyeLocation.direction.clone(), player.location.clone())
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (runtime.toolItems.idOf(event.itemInHand) != null) event.isCancelled = true
    }

    /**
     * needs-flash 敵人:所有傷害一律取消(牠不會「被打死」,只會被打散)。
     * 玩家(或玩家射出的彈射物)的攻擊:未僵直 → 打空(whiff);僵直中 → 打散。
     * 例外:虛空與 `/kill` 這類不能擋的傷害放行,走 onActorDeath 收尾。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        if (!runtime.hasRuns) return
        when (event.cause) {
            EntityDamageEvent.DamageCause.VOID, EntityDamageEvent.DamageCause.KILL, EntityDamageEvent.DamageCause.WORLD_BORDER -> return
            else -> {}
        }
        // harasses-players 的 suppress-melee:這種敵人打到玩家的原版近戰整個取消(只剩資料裡的效果)
        if (event.entity is Player) {
            val hit = (event as? EntityDamageByEntityEvent)?.damager ?: return
            if (hit is Player) return
            val (hitSession, hitActor) = core.actorController.actorIdOf(hit.uniqueId) ?: return
            val spec = runtime.runOf(hitSession)?.enemy(hitActor)?.spec ?: return
            if (spec.harassesPlayers?.suppressMelee == true) event.isCancelled = true
            return
        }
        val (sessionId, actorId) = core.actorController.actorIdOf(event.entity.uniqueId) ?: return
        val run = runtime.runOf(sessionId) ?: return
        val enemy = run.enemy(actorId) ?: return
        if (enemy.spec.needsFlash == null) return
        event.isCancelled = true
        val attacker = (event as? EntityDamageByEntityEvent)?.damager ?: return
        val player = when (attacker) {
            is Player -> attacker
            is Projectile -> attacker.shooter as? Player
            else -> null
        } ?: return
        run.onEnemyAttacked(enemy, player.uniqueId, event.entity.location.clone())
    }
}
