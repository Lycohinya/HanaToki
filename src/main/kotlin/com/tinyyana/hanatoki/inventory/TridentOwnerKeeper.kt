package com.tinyyana.hanatoki.inventory

import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * 副本世界裡玩家的忠誠三叉戟,原版會讓它消失、場地掃描又看不到的兩條路(2026-09-29 Pandora
 * 忠誠三叉戟遺失,s01 Lecithin 26.2 實測與 `ThrownTrident`/`Entity` 反組譯):
 *
 * 1. **射空掉進虛空。** 沒碰到任何東西的三叉戟不會觸發忠誠(只有命中或插地之後才回頭),掉到世界
 *    底下就被 `onBelowWorld` 刪掉(`EntityRemoveEvent.Cause.OUT_OF_WORLD`)。潘朵拉的場地懸在虛空上,
 *    往場外丟一次就沒了。這裡在它被刪的同一刻(同一條執行緒)把原物收進主人的暫存箱,離場時放回。
 * 2. **主人死亡時。** 原版把它變成一個**沒有 thrower** 的掉落物,場地掃描認不出是誰的。在它落地之前
 *    把主人記到掉落物的 thrower 上,[SweepPolicy] 才能把它送回去。
 *
 * 只在副本世界、只對可撿回(pickup = ALLOWED)的玩家三叉戟做;不改拾取規則(thrower 不限制誰能撿)。
 * 回收以實體 UUID 為鑰匙,跟場地掃描共用同一份冪等紀錄(見 [ReturnMailbox.recover])。
 */
class TridentOwnerKeeper(
    private val isDungeonWorld: (String) -> Boolean,
    private val recover: (owner: UUID, entityId: UUID, stack: ItemStack) -> Boolean,
    private val notifyOwner: (owner: UUID) -> Unit,
    /** 丟出三叉戟的玩家目前在哪一局(null = 不在任何一局);收斂時用來找回場外的三叉戟(見 [SessionTridentTracker])。 */
    private val sessionOf: (UUID) -> UUID? = { null },
    private val tracker: SessionTridentTracker? = null,
    /** 成員斷線時送回他丟出去還沒回來的三叉戟(見 [SessionTridentTracker])。 */
    private val onOwnerQuit: (UUID) -> Unit = {},
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
        onOwnerQuit(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: org.bukkit.event.entity.ProjectileLaunchEvent) {
        val trident = event.entity as? Trident ?: return
        val owner = playerOwner(trident) ?: return
        val sessionId = sessionOf(owner) ?: return
        tracker?.track(sessionId, trident.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: EntityDropItemEvent) {
        val trident = event.entity as? Trident ?: return
        val owner = playerOwner(trident) ?: return
        if (event.itemDrop.thrower == null) event.itemDrop.thrower = owner
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EntityRemoveEvent) {
        if (event.cause != EntityRemoveEvent.Cause.OUT_OF_WORLD) return
        val trident = event.entity as? Trident ?: return
        val owner = playerOwner(trident) ?: return
        val stack = trident.itemStack.clone()
        if (runCatching { stack.serializeAsBytes() }.isFailure) return
        if (recover(owner, trident.uniqueId, stack)) notifyOwner(owner)
    }

    private fun playerOwner(trident: Trident): UUID? {
        if (trident.pickupStatus != AbstractArrow.PickupStatus.ALLOWED) return null
        val owner = trident.ownerUniqueId ?: return null
        return owner.takeIf { isDungeonWorld(trident.world.name) }
    }
}
