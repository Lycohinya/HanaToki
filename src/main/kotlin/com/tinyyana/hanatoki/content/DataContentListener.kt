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
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
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

    /**
     * Boss 戰中的致死傷害（任何來源：招式、人偶、幽靈、摔落…）：玩家不死亡，進入倒下狀態。
     * 排在其他插件改完傷害之後（HIGHEST）；虛空、/kill、世界邊界放行（真的離開戰場就是離開）。
     * 事件執行緒只讀 volatile／並行狀態，狀態變更一律 submit 回 anchor 執行緒。
     * [EntityDamageEvent.getFinalDamage] 已經扣掉吸收（ABSORPTION 是它的 modifier），直接和血量比。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerLethal(event: EntityDamageEvent) {
        if (!runtime.hasRuns) return
        val victim = event.entity as? Player ?: return
        when (event.cause) {
            EntityDamageEvent.DamageCause.VOID, EntityDamageEvent.DamageCause.KILL, EntityDamageEvent.DamageCause.WORLD_BORDER -> return
            else -> {}
        }
        val run = runtime.runOfPlayer(victim.uniqueId) ?: return
        if (!run.lethalGuardActive()) return
        val downed = run.isDowned(victim.uniqueId)
        if (!downed && event.finalDamage < victim.health) return
        event.isCancelled = true
        if (!downed) run.onLethalDamage(victim.uniqueId)
    }

    /** 斷線：在玩家自己的執行緒上同步還原倒下的身體狀態（玩家資料存檔前），邏輯狀態留著（寬限期內回來還是倒下）。 */
    @EventHandler(priority = EventPriority.NORMAL)
    fun onQuit(event: PlayerQuitEvent) {
        if (!runtime.hasRuns) return
        runtime.runOfPlayer(event.player.uniqueId)?.onMemberQuit(event.player.uniqueId)
    }

    /**
     * 登入：晚於 HanaTokiListener 的重連判定（MONITOR）。仍在這一局而且還是倒下 → 重新套用；不是 → 身上若留著上次關服或當機時的
     * 倒下記號（PDC）就整組還原。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val run = runtime.runOfPlayer(player.uniqueId)
        if (run != null) run.onMemberReconnected(player.uniqueId)
        if (run == null || !run.isDowned(player.uniqueId)) BukkitBody.restoreLeftover(player, core.plugin)
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
        // 倒下的玩家不能攻擊(敵人、boss 都一樣)
        if (event is EntityDamageByEntityEvent) {
            val attacker = when (val d = event.damager) {
                is Player -> d
                is Projectile -> d.shooter as? Player
                else -> null
            }
            if (attacker != null && runtime.runOfPlayer(attacker.uniqueId)?.isDowned(attacker.uniqueId) == true) {
                event.isCancelled = true
                return
            }
        }
        // harasses-players 的 suppress-melee:這種敵人打到玩家的原版近戰整個取消(只剩資料裡的效果)
        if (event.entity is Player) {
            val hit = (event as? EntityDamageByEntityEvent)?.damager ?: return
            if (hit is Player) return
            val (hitSession, hitActor) = core.actorController.actorIdOf(hit.uniqueId) ?: return
            val spec = runtime.runOf(hitSession)?.enemy(hitActor)?.spec ?: return
            if (spec.contact?.suppressMelee == true) event.isCancelled = true
            return
        }
        val (sessionId, actorId) = core.actorController.actorIdOf(event.entity.uniqueId) ?: return
        val run = runtime.runOf(sessionId) ?: return
        // boss 載體:傷害一律取消，血量用命中數表示。只有玩家的近戰（ENTITY_ATTACK，不含橫掃）算一下有效攻擊，
        // 蓄力、冷卻與護盾判定交給 BossRun。Paper 在傷害事件之前就重置玩家的蓄力計時（Lecithin Player.java:1117 onAttack，
        // :1146 才 hurtOrSimulate），事件裡的 attackCooldown 幾乎是 0，所以 min-attack-strength 預設 0，限頻靠 hit-cooldown-ticks。
        if (run.isBossActor(actorId)) {
            event.isCancelled = true
            val byEntity = event as? EntityDamageByEntityEvent ?: return
            val hitter = byEntity.damager as? Player ?: return
            if (event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return
            if (hitter.attackCooldown < run.bossMinAttackStrength()) return
            run.onBossAttacked(hitter.uniqueId, event.entity.location.clone())
            return
        }
        val enemy = run.enemy(actorId) ?: return
        // needs-flash、人偶、追擊者、會逃的敵人都不吃一般傷害:只能被閃光打散(needs-flash)或被 powered 成員碰散
        if (!enemy.spec.immuneToDamage) return
        event.isCancelled = true
        if (enemy.spec.needsFlash == null) return
        val attacker = (event as? EntityDamageByEntityEvent)?.damager ?: return
        val player = when (attacker) {
            is Player -> attacker
            is Projectile -> attacker.shooter as? Player
            else -> null
        } ?: return
        run.onEnemyAttacked(enemy, player.uniqueId, event.entity.location.clone())
    }
}
