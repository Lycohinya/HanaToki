package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.api.InstanceItems
import com.tinyyana.hanatoki.folia.WorldOp
import org.bukkit.Location
import org.bukkit.entity.Item
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * session 收斂時掃掉場地上殘留的局內掉落物。
 *
 * ## 為什麼需要這一層(ledger 不夠)
 *
 * [com.tinyyana.hanatoki.encounter.DynamicEncounterController.despawnAllForSession] 只收
 * **還登記在 ledger 裡**的掉落物,而有兩條路會讓物品掉在地上卻不在 ledger 裡:
 *
 * 1. 內容層直接用 `world.dropItem*` 丟出來的東西(背包滿的溢位)從來沒進過 ledger。
 * 2. `EntityRemoveFromWorldEvent` **區塊卸載時也會觸發**,那時 `dropGone` 已經把它從 ledger
 *    取消追蹤,但物品其實還躺在那個區塊裡,區塊再載入時它就回來了。
 *
 * 對 `world-auto-save: true`、地圖不回滾的常駐場地(Roguelike),這些殘骸會活到下一局:
 * 下一局的玩家看得到它、走過去撿——`isLegalFor` 卻因為 instance_id 對不上而把拾取取消掉。
 * 所以這裡不靠任何追蹤紀錄,直接照座標掃一次,凡是帶局內章的 [Item] 一律移除。
 *
 * ## 範圍
 *
 * slot 之間的間距(`slot-spacing-blocks`)可以到 1024,但實際場地遠小於它,掃滿整個間距等於
 * 白白派幾千個 chunk task。[SWEEP_RADIUS_BLOCKS] 取一個保守常數:夠大到蓋住現有場地
 * (Roguelike 三層回環地圖在 anchor ±64 以內),又小到不會碰到隔壁 slot。Y 不設限——
 * 掉落物會掉進地板縫、掉到下一層,用高度篩選只會漏掉。
 *
 * ## Folia
 *
 * anchor 所在 region 的所有權**不涵蓋整個場地**,所以不能從 anchor region 直接讀別處的實體。
 * 這裡逐 chunk 派工([WorldOp.dispatchAt] 到該 chunk 中心),在那個 chunk 的擁有者 region 上
 * 才去讀它的實體清單;移除再走該實體自己的 EntityScheduler([WorldOp.dispatch])。
 *
 * 沒載入的 chunk 直接跳過而不是把它載進來:一次強制載入上百個 chunk 的成本遠高於這件事的價值,
 * 而且那些 chunk 裡的物品下次有人進場時會再被掃到(進場一定會把場地載回來)。
 */
object InstanceDropSweeper {

    /** anchor 往外掃的水平半徑(方塊)。見類別 KDoc「範圍」。 */
    const val SWEEP_RADIUS_BLOCKS = 96

    /** 回傳這次移除了幾個局內掉落物。 */
    fun sweep(plugin: Plugin, items: InstanceItems, anchor: Location): CompletableFuture<Int> {
        val world = anchor.world ?: return CompletableFuture.completedFuture(0)
        val removed = AtomicInteger()
        val chunkRadius = SWEEP_RADIUS_BLOCKS shr 4
        val baseX = anchor.blockX shr 4
        val baseZ = anchor.blockZ shr 4
        val tasks = ArrayList<CompletableFuture<Void>>()
        for (cx in (baseX - chunkRadius)..(baseX + chunkRadius)) {
            for (cz in (baseZ - chunkRadius)..(baseZ + chunkRadius)) {
                val done = CompletableFuture<Void>()
                tasks += done
                val probe = Location(world, (cx shl 4) + 8.0, anchor.y, (cz shl 4) + 8.0)
                WorldOp.dispatchAt(plugin, probe) {
                    if (!world.isChunkLoaded(cx, cz)) {
                        done.complete(null)
                        return@dispatchAt
                    }
                    val chunk = world.getChunkAt(cx, cz, false)
                    if (!chunk.isEntitiesLoaded) {
                        done.complete(null)
                        return@dispatchAt
                    }
                    val removals = chunk.entities.mapNotNull { entity ->
                        val item = entity as? Item ?: return@mapNotNull null
                        if (!items.isInstanceScoped(item.itemStack)) return@mapNotNull null
                        removed.incrementAndGet()
                        WorldOp.dispatch(plugin, item) { it.remove() }
                    }
                    CompletableFuture.allOf(*removals.toTypedArray())
                        .whenComplete { _, _ -> done.complete(null) }
                }
            }
        }
        return CompletableFuture.allOf(*tasks.toTypedArray()).thenApply { removed.get() }
    }
}
