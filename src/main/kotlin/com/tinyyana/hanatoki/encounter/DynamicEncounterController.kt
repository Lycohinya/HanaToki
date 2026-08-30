package com.tinyyana.hanatoki.encounter

import com.tinyyana.hanatoki.config.DynamicEncounterLimits
import com.tinyyana.hanatoki.folia.InstanceDispatch
import com.tinyyana.hanatoki.folia.WorldOp
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * [DynamicEncounterHandle] 的實作面:把 [DynamicEncounterLedger] 的純邏輯接上 Folia 派工。
 *
 * 執行緒規則(ARCH §5.1/§5.2):
 * - 每一筆 spawn 各自派到**自己座標**的 RegionScheduler([WorldOp.dispatchAt])——anchor 的
 *   所有權不涵蓋整個場地,一批怪散在兩個 region 就是兩個 task。
 * - 生出來之後對實體做的任何事(mutate/remove)一律走**該實體的 EntityScheduler**。
 * - ledger 的狀態變更在鎖內完成;**回呼**([DynamicEncounterCallbacks])則序列化到 anchor
 *   region,內容層在裡面可以直接動 instance 狀態。
 *
 * ## 部分失敗回滾
 *
 * 先跟 ledger 訂名額 → 逐筆派工生成 → 全部回來之後才決定:任何一筆失敗(世界不存在、
 * `spawnEntity` 丟例外、initializer 丟例外)就把**已經生出來的**逐一移除、釋放名額、回 FAILED。
 * 只有全部成功才 register——所以 ledger 裡永遠不會有「登記了但有幾隻其實沒生出來」的場。
 *
 * ## 生成與登記之間的縫
 *
 * 實體在自己 region 生出來、登記在 anchor region,中間有一小段時間它已經存在卻還沒進索引。
 * 在那個縫裡死掉/消失的實體,事件到了查不到索引會被忽略,那一場就永遠清不掉。所以登記完
 * 之後對每一隻做一次 **liveness probe**(排一個空 task 到它的 EntityScheduler;retired 回呼
 * 或排不進去 = 它已經不在了)→ 當作 `lost` 處理。這是 ARCH §12「entity 會在派工與執行之間
 * retired」那條教訓的直接套用。
 */
class DynamicEncounterController(private val plugin: Plugin) {

    val ledger = DynamicEncounterLedger()

    /** 追蹤中的實體參考(怪物與掉落物)。移除實體要有參考才能派到它的 scheduler。 */
    private val entities = ConcurrentHashMap<UUID, Entity>()
    private val callbacks = ConcurrentHashMap<String, DynamicEncounterCallbacks>()
    private val anchors = ConcurrentHashMap<String, Location>()

    /** debug:三種離場原因各發生幾次(`lost` 高代表有實體沒經死亡就消失——區塊卸載或別的插件在清)。 */
    private val goneCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun handleFor(sessionId: UUID, anchor: Location, limits: DynamicEncounterLimits): DynamicEncounterHandle =
        Handle(sessionId, anchor, limits)

    // ---- spawn ---------------------------------------------------------------------------

    fun spawn(
        sessionId: UUID,
        anchor: Location,
        limits: DynamicEncounterLimits,
        templateId: String,
        spawns: List<EntitySpawn>,
        cb: DynamicEncounterCallbacks,
    ): CompletableFuture<DynamicSpawnResult> {
        if (spawns.isEmpty()) return rejected("empty batch")
        // 型別先驗:打錯字是設定問題,不該先生了三隻才在第四隻炸掉再回滾。
        val types = ArrayList<EntityType>(spawns.size)
        for (s in spawns) {
            val type = runCatching { EntityType.valueOf(s.entityTypeName) }.getOrNull()
                ?: return rejected("unknown entity type ${s.entityTypeName}")
            if (s.location.world == null) return rejected("spawn location has no world")
            types += type
        }
        ledger.tryReserve(sessionId, spawns.size, limits)?.let { return rejected(it) }

        val future = CompletableFuture<DynamicSpawnResult>()
        val spawned = ConcurrentHashMap<Int, Entity>()
        val errors = ConcurrentHashMap<Int, String>()
        val tasks = spawns.mapIndexed { index, spec ->
            WorldOp.dispatchAt(plugin, spec.location) { loc ->
                try {
                    val world = loc.world ?: error("world unloaded")
                    val entity = world.spawnEntity(loc, types[index])
                    // 先進表再初始化:initializer 丟例外時這隻已經在 spawned 裡,回滾才收得到它。
                    spawned[index] = entity
                    entity.isPersistent = false
                    spec.initializer?.accept(entity)
                } catch (t: Throwable) {
                    errors[index] = "${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }
        CompletableFuture.allOf(*tasks.toTypedArray()).whenComplete { _, _ ->
            InstanceDispatch.submit(plugin, anchor) {
                if (errors.isNotEmpty() || spawned.size != spawns.size) {
                    val reason = errors.values.firstOrNull() ?: "spawn task did not run (${spawned.size}/${spawns.size})"
                    plugin.logger.warning("[HanaToki] dynamic encounter $templateId 生成失敗,回收 ${spawned.size} 隻:$reason")
                    val removals = spawned.values.map { e -> WorldOp.dispatch(plugin, e) { it.remove() } }
                    ledger.releaseReservation(sessionId, spawns.size)
                    CompletableFuture.allOf(*removals.toTypedArray()).whenComplete { _, _ ->
                        future.complete(DynamicSpawnResult(DynamicSpawnResult.STATUS_FAILED, null, emptyList(), reason))
                    }
                    return@submit
                }
                val ordered = spawns.indices.map { spawned.getValue(it) }
                val tracked = ledger.register(sessionId, templateId, ordered.map { it.uniqueId })
                ordered.forEach { entities[it.uniqueId] = it }
                callbacks[tracked.runtimeId] = cb
                anchors[tracked.runtimeId] = anchor
                future.complete(DynamicSpawnResult(DynamicSpawnResult.STATUS_SPAWNED, tracked.runtimeId, tracked.entityIds, null))
                ordered.forEach { probeLiveness(it) }
            }
        }
        return future
    }

    private fun rejected(reason: String): CompletableFuture<DynamicSpawnResult> =
        CompletableFuture.completedFuture(DynamicSpawnResult(DynamicSpawnResult.STATUS_REJECTED, null, emptyList(), reason))

    /** 見類別 KDoc「生成與登記之間的縫」。 */
    private fun probeLiveness(entity: Entity) {
        val scheduled = entity.scheduler.run(
            plugin,
            { _ -> },
            { onGone(entity.uniqueId, DynamicEncounterCallbacks.REASON_LOST, null) },
        )
        if (scheduled == null) onGone(entity.uniqueId, DynamicEncounterCallbacks.REASON_LOST, null)
    }

    // ---- 離場事件(HanaTokiListener 轉呼叫;在事發實體的 region 觸發)---------------------

    /** EntityDeathEvent。[location] 是死亡當下的座標(在死亡 region 讀的,傳進來就不再跨 region 讀)。 */
    fun onEntityDeath(entityId: UUID, location: Location) {
        onGone(entityId, DynamicEncounterCallbacks.REASON_KILLED, location)
    }

    /** EntityRemoveFromWorldEvent:死亡之後也會來一次(索引已清,no-op);沒死就消失的走 lost。 */
    fun onEntityRemovedFromWorld(entityId: UUID) {
        if (ledger.dropGone(entityId)) {
            entities.remove(entityId)
            return
        }
        onGone(entityId, DynamicEncounterCallbacks.REASON_LOST, null)
    }

    private fun onGone(entityId: UUID, reason: String, location: Location?) {
        val runtimeId = ledger.encounterOf(entityId) ?: return
        val anchor = anchors[runtimeId] ?: return
        InstanceDispatch.submit(plugin, anchor) {
            val outcome = ledger.entityGone(entityId) ?: return@submit
            entities.remove(entityId)
            goneCounts.computeIfAbsent(reason) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
            val cb = callbacks[runtimeId] ?: return@submit
            if (reason == DynamicEncounterCallbacks.REASON_KILLED && location != null) {
                safely("onEntityKilled") { cb.onEntityKilled(runtimeId, entityId, location) }
            }
            safely("onEntityRemoved") { cb.onEntityRemoved(runtimeId, entityId, reason) }
            if (outcome.cleared) {
                callbacks.remove(runtimeId)
                anchors.remove(runtimeId)
                safely("onCleared") { cb.onCleared(runtimeId) }
            }
        }
    }

    /** 內容層的回呼丟例外不能把 ledger 弄壞——記 log、繼續。 */
    private inline fun safely(what: String, action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            plugin.logger.warning("[HanaToki] dynamic encounter 回呼 $what 丟出例外:${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---- despawn -------------------------------------------------------------------------

    fun despawn(runtimeId: String): CompletableFuture<Void> {
        val anchor = anchors[runtimeId] ?: return CompletableFuture.completedFuture(null)
        val done = CompletableFuture<Void>()
        InstanceDispatch.submit(plugin, anchor) {
            val gone = ledger.despawn(runtimeId)
            if (gone == null) {
                done.complete(null)
                return@submit
            }
            val removals = gone.mapNotNull { id -> entities.remove(id)?.let { e -> WorldOp.dispatch(plugin, e) { it.remove() } } }
            val cb = callbacks.remove(runtimeId)
            anchors.remove(runtimeId)
            if (cb != null) {
                gone.forEach { id -> safely("onEntityRemoved") { cb.onEntityRemoved(runtimeId, id, DynamicEncounterCallbacks.REASON_DESPAWNED) } }
            }
            CompletableFuture.allOf(*removals.toTypedArray()).whenComplete { _, _ -> done.complete(null) }
        }
        return done
    }

    /** session 結束時呼叫:所有場 + 所有追蹤中的掉落物。 */
    fun despawnAllForSession(sessionId: UUID): CompletableFuture<Void> {
        val encounterDone = ledger.runtimeIdsOf(sessionId).map { despawn(it) }
        val dropDone = ledger.takeDropsOf(sessionId)
            .mapNotNull { id -> entities.remove(id)?.let { e -> WorldOp.dispatch(plugin, e) { it.remove() } } }
        return CompletableFuture.allOf(*(encounterDone + dropDone).toTypedArray())
    }

    // ---- entity ops ----------------------------------------------------------------------

    fun mutate(entityId: UUID, action: Consumer<Entity>): CompletableFuture<Void> {
        val entity = entities[entityId] ?: return CompletableFuture.completedFuture(null)
        return WorldOp.dispatch(plugin, entity) { action.accept(it) }
    }

    fun dropItem(sessionId: UUID, limits: DynamicEncounterLimits, location: Location, stack: ItemStack): CompletableFuture<UUID?> {
        if (location.world == null) return CompletableFuture.completedFuture(null)
        if (!ledger.tryReserveDrop(sessionId, limits)) return CompletableFuture.completedFuture(null)
        val future = CompletableFuture<UUID?>()
        WorldOp.dispatchAt(plugin, location) { loc ->
            val world = loc.world
            if (world == null) {
                ledger.releaseDropReservation(sessionId)
                future.complete(null)
                return@dispatchAt
            }
            val item = world.dropItem(loc, stack)
            item.pickupDelay = 10
            entities[item.uniqueId] = item
            ledger.registerDrop(sessionId, item.uniqueId)
            future.complete(item.uniqueId)
        }.whenComplete { _, t -> if (t != null) future.complete(null) }
        return future
    }

    fun isTracked(entityId: UUID): Boolean = ledger.isTracked(entityId) || ledger.isTrackedDrop(entityId)

    /** `/hanatoki admin debug` 用:場數 / 實體 / 掉落物 / 參考表大小。 */
    fun debugTotals(): String {
        val t = ledger.totals()
        val gone = goneCounts.entries.joinToString(" ") { "${it.key}=${it.value.get()}" }.ifEmpty { "-" }
        return "dynamic encounters=${t[0]} entities=${t[1]} drops=${t[2]} refs=${entities.size} callbacks=${callbacks.size} gone[$gone]"
    }

    private inner class Handle(
        private val sessionId: UUID,
        private val anchor: Location,
        private val limits: DynamicEncounterLimits,
    ) : DynamicEncounterHandle {
        override fun spawn(templateId: String, spawns: List<EntitySpawn>, callbacks: DynamicEncounterCallbacks) =
            this@DynamicEncounterController.spawn(sessionId, anchor, limits, templateId, spawns, callbacks)

        override fun despawn(runtimeId: String): CompletableFuture<Void> {
            // 只准收自己 session 的場。
            if (ledger.tracked(runtimeId)?.sessionId != sessionId) return CompletableFuture.completedFuture(null)
            return this@DynamicEncounterController.despawn(runtimeId)
        }

        override fun despawnAll(): CompletableFuture<Void> = despawnAllForSession(sessionId)

        override fun mutate(entityId: UUID, action: Consumer<Entity>): CompletableFuture<Void> {
            val rid = ledger.encounterOf(entityId) ?: return CompletableFuture.completedFuture(null)
            if (ledger.tracked(rid)?.sessionId != sessionId) return CompletableFuture.completedFuture(null)
            return this@DynamicEncounterController.mutate(entityId, action)
        }

        override fun entitiesOf(runtimeId: String): List<UUID> = ledger.tracked(runtimeId)?.remaining?.toList() ?: emptyList()
        override fun remaining(runtimeId: String): Int = ledger.tracked(runtimeId)?.remaining?.size ?: 0
        override fun templateOf(runtimeId: String): String? = ledger.tracked(runtimeId)?.templateId
        override fun activeRuntimeIds(): List<String> = ledger.runtimeIdsOf(sessionId)
        override fun activeEncounterCount(): Int = ledger.activeCountOf(sessionId)
        override fun trackedEntityCount(): Int = ledger.entityCountOf(sessionId)
        override fun trackedDropCount(): Int = ledger.dropCountOf(sessionId)
        override fun isTracked(entityId: UUID): Boolean = ledger.isTracked(entityId)
        override fun dropItem(location: Location, stack: ItemStack): CompletableFuture<UUID?> =
            this@DynamicEncounterController.dropItem(sessionId, limits, location, stack)
    }
}
