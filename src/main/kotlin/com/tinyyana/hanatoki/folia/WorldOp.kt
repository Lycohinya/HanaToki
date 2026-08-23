package com.tinyyana.hanatoki.folia

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

/**
 * ARCH §5.1③ 的世界/實體 mutation 派工工具。世界座標一律派到該座標實際擁有權的 region
 * (`regionScheduler.execute(plugin, location) { ... }`);實體一律走該實體自己的
 * `EntityScheduler`。Stage/Encounter/Interaction/world 的實作碼一律呼叫這裡,
 * **不得**直接呼叫 `Bukkit.getRegionScheduler()`/`entity.getScheduler()`(§5.2 規則 8 的
 * code review 檢查點)。
 *
 * 每個 dispatch 回傳 [CompletableFuture],供呼叫端用 `CompletableFuture.allOf(...)` 組
 * completion barrier(ARCH §5.1「狀態轉移等待多個 world-op 完成後才推進 instance 狀態」)。
 */
object WorldOp {

    /** 針對世界座標的 mutation(setBlock、放粒子、structure place...)。*/
    fun dispatch(plugin: Plugin, location: Location, action: (Block) -> Unit): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        Bukkit.getRegionScheduler().execute(plugin, location) {
            try {
                action(location.block)
            } finally {
                future.complete(null)
            }
        }
        return future
    }

    /** 同上,但呼叫端要拿的是 Location 本身(例如要在附近生粒子/召喚實體,而不是改方塊)。*/
    fun dispatchAt(plugin: Plugin, location: Location, action: (Location) -> Unit): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        Bukkit.getRegionScheduler().execute(plugin, location) {
            try {
                action(location)
            } finally {
                future.complete(null)
            }
        }
        return future
    }

    /**
     * 針對實體的 mutation(Boss/小怪/Actor/玩家操作)。一律用 EntityScheduler,
     * 禁止用 RegionScheduler 排實體任務(entity 會走出 region,JavaDoc 明言不適用)。
     * 實體已 retired(被移除/despawn)時視為立即完成,不阻塞 completion barrier,
     * 只由呼叫端自行記警告 log。
     */
    fun dispatch(plugin: Plugin, entity: Entity, action: (Entity) -> Unit): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val scheduled = entity.scheduler.run(
            plugin,
            { _ ->
                try {
                    action(entity)
                } finally {
                    future.complete(null)
                }
            },
            { future.complete(null) }, // retired callback
        )
        if (scheduled == null) {
            future.complete(null)
        }
        return future
    }
}
