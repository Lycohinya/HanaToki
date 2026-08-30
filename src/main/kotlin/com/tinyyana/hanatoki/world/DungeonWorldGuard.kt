package com.tinyyana.hanatoki.world

import com.tinyyana.hanatoki.HanaTokiCore
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent

/**
 * 副本世界裡玩家**不能改地形**。
 *
 * ## 為什麼這件事在引擎層而不是內容層
 *
 * 每一座副本的場地都是程式化蓋出來的:刀塚整局結束逐格回滾、Roguelike 的三層地圖是持久的、
 * 蒼櫻是常駐地形世界。三種形態壞掉的方式不一樣,但**都不該讓玩家動一根手指**:
 * 回滾型的場地被玩家改過之後,diff log 只認自己記過的格子,玩家放的方塊會永久留在那裡;
 * 持久型的場地被挖掉就是壞了,下一個進來的人踩空;常駐世界更是直接污染正式地形。
 *
 * 2026-08-30 正式服實測:玩家在刀塚裡放了地獄石,而且留著——引擎完全沒有這道保護。
 *
 * 保護涵蓋「玩家能改變世界的常見入口」:放/挖方塊、倒/舀桶、掛畫與展示框、以及會變成方塊或
 * 實體佈景的 `EntityPlace`(盔甲座、船、礦車、終界水晶)。內容層自己用 `ctx.mutate` 蓋場地
 * 走的是插件路徑,不經過這些事件,完全不受影響。
 *
 * 帶 `hanatoki.build` 的人不受限(預設 op 也沒有——要手動給)。這是刻意的:管理員平常在副本裡
 * 也不該手滑改到場地,要改就明確開權限。
 */
class DungeonWorldGuard(private val core: HanaTokiCore) : Listener {

    /**
     * 這一輪擋掉的次數(`/hanatoki admin debug` 看得到,確認保護真的在生效)。
     * 用 AtomicLong 而不是 `@Volatile var` + `++`:多個 region thread 會同時擋,`++` 不是原子的,
     * 漏計會讓「保護有沒有生效」這件事被誤判。
     */
    private val blockedCount = java.util.concurrent.atomic.AtomicLong()

    val blocked: Long get() = blockedCount.get()

    private fun deny(player: Player, worldName: String): Boolean {
        if (!core.isDungeonWorld(worldName)) return false
        if (player.hasPermission(BYPASS)) return false
        blockedCount.incrementAndGet()
        player.sendActionBar(core.texts.format("dungeon.no-build", emptyMap()))
        return true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (deny(event.player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onMultiPlace(event: BlockMultiPlaceEvent) {
        if (deny(event.player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (deny(event.player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (deny(event.player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (deny(event.player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (deny(player, event.block.world.name)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val player = event.remover as? Player ?: return
        if (deny(player, event.entity.world.name)) event.isCancelled = true
    }

    /** 盔甲座/船/礦車/終界水晶:不是方塊,但一樣是玩家往場地裡塞東西。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        if (deny(player, event.entity.world.name)) event.isCancelled = true
    }

    private companion object {
        /** 預設沒有人有(連 op 都沒有):要在副本裡改場地必須明確開權限。 */
        const val BYPASS = "hanatoki.build"
    }
}
