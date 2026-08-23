package com.tinyyana.hanatoki.world

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 「進副本前站在哪」的登記表。
 *
 * 副本場地在 Phase 4 之前跟玩家的生存主世界共用座標,所以「離開副本」只是 session 結束、
 * 人原地站著,不需要傳送。副本搬進 [DungeonWorldProvisioner] 建的專屬世界之後,這件事變成
 * **必須做**:session 結束(通關/逾時/死亡/管理員 reset/插件停用)如果不把人送回去,玩家就
 * 被留在一個沒有地板、沒有其他玩家、沒有出口的虛空世界裡。
 *
 * 刻意只存在記憶體:伺服器重啟後登記表會空,那時走 [fallbackFor] 的重生點/主世界路徑——
 * 那是「回到一個合理的地方」而不是「被卡住」,不值得為它加一份要維護的持久化檔案。
 */
class ReturnPointRegistry(private val isDungeonWorld: (String) -> Boolean) {

    private val points = ConcurrentHashMap<UUID, Location>()

    /**
     * 記下玩家進場前的位置。**已經在副本世界裡的位置不記**——連續進兩座副本(或重連後又進場)
     * 時,第二次記下的會是第一座副本的場地座標,把人送回那裡等於沒送出去。
     */
    fun remember(player: Player) {
        val here = player.location
        if (isDungeonWorld(here.world.name)) return
        points[player.uniqueId] = here.clone()
    }

    /** 取出並移除登記(送人回去是一次性的);沒有登記就回 null,由呼叫端走 [fallbackFor]。 */
    fun take(playerId: UUID): Location? = points.remove(playerId)

    fun forget(playerId: UUID) {
        points.remove(playerId)
    }

    /**
     * 沒有登記時的落腳處:玩家自己的重生點 → 第一個非副本世界的出生點。
     * 兩個都拿不到才回 null(那代表整台伺服器只剩副本世界,不是這裡該處理的情況)。
     */
    fun fallbackFor(player: Player): Location? {
        player.respawnLocation?.takeIf { !isDungeonWorld(it.world.name) }?.let { return it }
        return Bukkit.getWorlds().firstOrNull { !isDungeonWorld(it.name) }?.spawnLocation
    }

    /** 送人回去要用的座標(登記優先,否則 fallback)。 */
    fun destinationFor(player: Player): Location? = take(player.uniqueId) ?: fallbackFor(player)
}
