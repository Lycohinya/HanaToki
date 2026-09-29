package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.folia.WorldOp
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Trident
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次掃描的範圍與這一局的身分。
 *
 * @param isCurrent 每個實體動手前再問一次「這個 slot 還是這一局的嗎」:開局補掃是延遲 60 ticks 的任務,
 *   那之間這一局可能已經結束、slot 換給下一局——舊任務不能拿舊局的成員名單去掃新局的場地。
 * @param recover 把實體帶著的物品交給主人(見 [InstanceInventoryService.recoverFromArena]);
 *   回傳 false = 這個實體先前已經回收過。
 */
class SweepScope(
    val slotId: String,
    val sessionId: UUID?,
    val phase: SweepPhase,
    val members: Set<UUID>,
    val sessionTokens: Set<String>,
    val isInstanceScoped: (ItemStack) -> Boolean,
    val instanceTokenOf: (ItemStack) -> String?,
    val recover: (owner: UUID, entityId: UUID, stack: ItemStack) -> Boolean,
    val isCurrent: () -> Boolean = { true },
)

data class SweepReport(val removed: Int, val kept: Int, val returned: Int) {
    val touched: Boolean get() = removed + kept + returned > 0
}

/**
 * 副本場地的掉落物/投射物掃描:開局兩次(傳送前、落地後 60 ticks)與收斂回滾時一次。
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
 * 對 `world-auto-save: true`、地圖不回滾的常駐場地,這些殘骸會活到下一局。2026-09-01 起連沒有章的
 * 地面物品、插在地上的箭也一起清(真人回報殘留)。
 *
 * ## 去留(2026-09-29 Pandora 忠誠三叉戟遺失)
 *
 * 以前是「Item 與 AbstractArrow 一律移除」。三叉戟也是 AbstractArrow:落地後 60 ticks 那次補掃會刪掉
 * 玩家剛丟出去的三叉戟,收斂那次會刪掉主人已經離場、永遠回不去的忠誠三叉戟。現在每個實體照
 * [SweepPolicy] 決定留著、移除或送回主人;判定與動作都在該實體自己的 EntityScheduler 上做。
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
 * 才去讀它的實體清單;判定與移除再走該實體自己的 EntityScheduler([WorldOp.dispatch])。
 * 送回主人時,序列化、移除實體、放進暫存箱在同一個 task 裡完成,中間不讓出執行緒——
 * 實體與暫存箱裡的那一份不會同時存在(拾取也跑在同一條執行緒上,撿走了就不會再被回收)。
 *
 * 沒載入的 chunk 直接跳過而不是把它載進來:一次強制載入上百個 chunk 的成本遠高於這件事的價值,
 * 而且那些 chunk 裡的東西下次有人進場時會再被掃到(進場一定會把場地載回來)。
 */
object InstanceDropSweeper {

    /** anchor 往外掃的水平半徑(方塊)。見類別 KDoc「範圍」。 */
    const val SWEEP_RADIUS_BLOCKS = 96

    fun sweep(plugin: Plugin, anchor: Location, scope: SweepScope): CompletableFuture<SweepReport> {
        val world = anchor.world ?: return CompletableFuture.completedFuture(SweepReport(0, 0, 0))
        val removed = AtomicInteger()
        val kept = AtomicInteger()
        val returned = AtomicInteger()
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
                    if (!world.isChunkLoaded(cx, cz) || !scope.isCurrent()) {
                        done.complete(null)
                        return@dispatchAt
                    }
                    val chunk = world.getChunkAt(cx, cz, false)
                    if (!chunk.isEntitiesLoaded) {
                        done.complete(null)
                        return@dispatchAt
                    }
                    val handled = chunk.entities
                        .filter { it is Item || it is AbstractArrow }
                        .map { entity ->
                            WorldOp.dispatch(plugin, entity) { e ->
                                if (!e.isValid || !scope.isCurrent()) return@dispatch
                                when (handle(plugin, e, scope)) {
                                    SweepAction.KEEP -> kept.incrementAndGet()
                                    SweepAction.REMOVE -> removed.incrementAndGet()
                                    SweepAction.RETURN_TO_OWNER -> returned.incrementAndGet()
                                    null -> Unit
                                }
                            }
                        }
                    CompletableFuture.allOf(*handled.toTypedArray())
                        .whenComplete { _, _ -> done.complete(null) }
                }
            }
        }
        return CompletableFuture.allOf(*tasks.toTypedArray()).thenApply { SweepReport(removed.get(), kept.get(), returned.get()) }
    }

    /** 在實體自己的執行緒上:讀資料 → 判定 → 動手。回傳實際做了什麼(null = 不是這裡管的實體)。 */
    private fun handle(plugin: Plugin, e: Entity, scope: SweepScope): SweepAction? {
        val swept = describe(e, scope) ?: return null
        val action = SweepPolicy.decide(swept, scope.phase, scope.members, scope.sessionTokens)
        when (action) {
            SweepAction.KEEP -> Unit
            SweepAction.REMOVE -> e.remove()
            SweepAction.RETURN_TO_OWNER -> {
                val owner = swept.ownerId ?: return SweepAction.KEEP
                val stack = stackOf(e)?.clone() ?: return SweepAction.KEEP
                // 先確定序列化得了,再移除——移除之後才失敗就是把東西弄丟了。
                if (runCatching { stack.serializeAsBytes() }.isFailure) {
                    plugin.logger.warning("[HanaToki] slot=${scope.slotId} ${stack.type} 無法序列化,留在場上不回收(entity=${e.uniqueId})")
                    return SweepAction.KEEP
                }
                e.remove()
                scope.recover(owner, e.uniqueId, stack)
            }
        }
        return action
    }

    private fun describe(e: Entity, scope: SweepScope): SweptEntity? = when (e) {
        is Trident -> SweptEntity(
            SweptKind.TRIDENT,
            e.ownerUniqueId,
            playerProperty = e.ownerUniqueId != null && e.pickupStatus == AbstractArrow.PickupStatus.ALLOWED,
        )
        is AbstractArrow -> SweptEntity(SweptKind.ARROW, e.ownerUniqueId)
        is Item -> {
            val stack = e.itemStack
            val scoped = scope.isInstanceScoped(stack)
            SweptEntity(
                SweptKind.ITEM,
                e.thrower,
                instanceScoped = scoped,
                instanceToken = if (scoped) scope.instanceTokenOf(stack) else null,
                tridentStack = stack.type == Material.TRIDENT,
            )
        }
        else -> null
    }

    private fun stackOf(e: Entity): ItemStack? = when (e) {
        is Trident -> e.itemStack
        is Item -> e.itemStack
        else -> null
    }
}
