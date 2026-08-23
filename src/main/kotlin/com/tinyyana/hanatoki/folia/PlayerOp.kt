package com.tinyyana.hanatoki.folia

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * ARCH §5.2 規則 2:「對玩家群發訊息/音效/title:逐一經該玩家的 EntityScheduler」。
 *
 * 為什麼需要這個而不是直接 `player.sendMessage()`:instance 的邏輯狀態序列化在 anchor 所屬
 * region([InstanceDispatch]),但**玩家不一定在那個 region**——玩家會走動,region 邊界會動態
 * 重劃,「場地夠小所以玩家跟 anchor 同 region」是假設不是保證(這正是 ARCH §5.1 修正過一次的
 * 同一類錯誤:anchor 的所有權不涵蓋場地內其他實體)。Adventure 的 `sendMessage`/`showTitle`/
 * `playSound` 官方沒有 thread-safety 保證,保守處理一律派工到玩家自己的 EntityScheduler。
 *
 * 玩家已離線/實體已 retired 時視為立即完成(不阻塞 completion barrier),不記警告——玩家登出
 * 是正常流程,不是需要維運注意的事件。
 */
object PlayerOp {

    /** 針對單一玩家的任意操作(訊息/title/音效/傳送...),派工到該玩家的 EntityScheduler。 */
    fun dispatch(plugin: Plugin, playerId: UUID, action: (Player) -> Unit): CompletableFuture<Void> {
        val player = plugin.server.getPlayer(playerId) ?: return CompletableFuture.completedFuture(null)
        return dispatch(plugin, player, action)
    }

    fun dispatch(plugin: Plugin, player: Player, action: (Player) -> Unit): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val scheduled = player.scheduler.run(
            plugin,
            { _ ->
                try {
                    action(player)
                } finally {
                    future.complete(null)
                }
            },
            { future.complete(null) }, // retired:玩家已登出,視為立即完成
        )
        if (scheduled == null) future.complete(null)
        return future
    }

    fun message(plugin: Plugin, playerId: UUID, text: Component): CompletableFuture<Void> =
        dispatch(plugin, playerId) { it.sendMessage(text) }

    fun actionBar(plugin: Plugin, playerId: UUID, text: Component): CompletableFuture<Void> =
        dispatch(plugin, playerId) { it.sendActionBar(text) }

    fun title(
        plugin: Plugin,
        playerId: UUID,
        title: Component,
        subtitle: Component,
        fadeInMs: Long,
        stayMs: Long,
        fadeOutMs: Long,
    ): CompletableFuture<Void> = dispatch(plugin, playerId) {
        it.showTitle(
            Title.title(
                title,
                subtitle,
                Title.Times.times(
                    Duration.ofMillis(fadeInMs),
                    Duration.ofMillis(stayMs),
                    Duration.ofMillis(fadeOutMs),
                ),
            ),
        )
    }

    /** 在指定座標播放音效,但只送給這一位玩家(場地演出——每位成員各自派工)。 */
    fun soundAt(
        plugin: Plugin,
        playerId: UUID,
        location: Location,
        sound: Sound,
        volume: Float,
        pitch: Float,
    ): CompletableFuture<Void> = dispatch(plugin, playerId) { it.playSound(location, sound, volume, pitch) }
}
