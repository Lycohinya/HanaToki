package com.tinyyana.hanatoki.world

import com.tinyyana.hanatoki.HanaTokiCore
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * 副本世界只能透過官方入口([com.tinyyana.hanatoki.api.DungeonAccess])進去,不能用 `/tpa`、
 * `/warp`、`/mv tp` 或任何其他傳送手段直接跳進一個正在跑的 session。
 *
 * ## 為什麼只擋 SESSION 模式,不擋 PERSISTENT
 *
 * 常駐副本(蒼櫻)的成員資格本來就是「人在不在那個世界」([HanaTokiCore.joinPersistentByWorld],
 * MIGRATION_PLAN §5.0 決策 D)——那個世界是設計上任何人都能走進來的常駐地形,擋它會直接打破
 * 既有行為。SESSION 模式(刀塚/深域)不一樣:同一個世界裡疊了好幾組並行的 slot,物理上「人在
 * 那裡」不代表「他屬於這一局」(`StageEngine.handleInteraction` 已經有一樣的警覺,見它的 KDoc)。
 * 這裡只補「進場」這一關:官方入口一律先 `sessionManager.enter(...)` 登記成員資格、才
 * `teleportAsync`([DungeonEntry] 的步驟順序見它的 KDoc),所以合法進場在這裡永遠放行;
 * 沒有登記卻想跳進去的,不論走 `/tpa`、`/warp` 還是任何別的路徑,一律擋下來。
 *
 * ## 兩層防線
 *
 * - [onTeleport]:`PlayerTeleportEvent` 在傳送**發生前**觸發,可以取消——這是主要防線,
 *   涵蓋 `/tpa`/`/tpaccept`(EssentialsX)、`/warp`、`mv tp`、`/tppos` 等所有會觸發這個事件的路徑。
 * - [onWorldChange]:`PlayerChangedWorldEvent` 不可取消(世界已經換了),只作安全網——
 *   把漏網的人直接送回去,不留在沒有 session 綁定的場地裡看別人打。
 */
class DungeonEntryGuard(private val core: HanaTokiCore) : Listener {

    private val blockedCount = java.util.concurrent.atomic.AtomicLong()

    val blocked: Long get() = blockedCount.get()

    /** true = 這位玩家出現在這個世界需要被擋下來(SESSION 模式副本 + 沒有登記 + 沒有 bypass)。 */
    private fun shouldBlock(player: Player, worldName: String): Boolean {
        if (core.persistentDungeonIdForWorld(worldName) != null) return false // 常駐副本:決策 D,不擋
        if (!core.isDungeonWorld(worldName)) return false
        if (player.hasPermission(BYPASS)) return false
        return core.sessionManager.sessionOf(player.uniqueId) == null
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val world = event.to.world ?: return
        if (!shouldBlock(event.player, world.name)) return
        event.isCancelled = true
        blockedCount.incrementAndGet()
        event.player.sendActionBar(core.texts.format("dungeon.no-entry", emptyMap()))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player
        if (!shouldBlock(player, player.world.name)) return
        blockedCount.incrementAndGet()
        core.sendHome(player.uniqueId)
        player.sendActionBar(core.texts.format("dungeon.no-entry", emptyMap()))
    }

    private companion object {
        /** 預設 op(2026-09-03 改:Yana「有管理員權限就可以過去副本看玩家」,見 plugin.yml)。 */
        const val BYPASS = "hanatoki.dungeon.bypass"
    }
}
