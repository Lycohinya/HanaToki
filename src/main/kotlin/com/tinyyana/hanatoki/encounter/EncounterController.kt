package com.tinyyana.hanatoki.encounter

import com.tinyyana.hanatoki.folia.WorldOp
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * ARCH §5 的最小 encounter/ 骨架:spawn、entity binding、death tracking、despawn/cleanup。
 * 刻意不做波次/Boss 控制器/AI——那些留給 Phase 4(ARCH §5 明文邊界)。
 *
 * 一次 encounter 的 key = `"<sessionId>#<encounterId>"`(session 結束時一併 cleanup,
 * 不需要跨 session 查找)。
 */
class EncounterController(private val plugin: Plugin) {
    private class Tracked(val remaining: MutableMap<UUID, Entity>, val onCleared: () -> Unit)

    private val active = ConcurrentHashMap<String, Tracked>()

    private fun key(sessionId: UUID, encounterId: String) = "$sessionId#$encounterId"

    /**
     * 在 [center] 周圍 [radius] 格內隨機散開生 [count] 隻 [entityType]。回傳的 future 在
     * spawn 完成(已在該座標的 region task 內執行完)時 complete。[onCleared] 在最後一隻死亡時
     * 呼叫——呼叫端(behavior)負責用它推進 stage/Resolution,不在這裡做狀態機決策。
     */
    fun spawn(
        sessionId: UUID,
        encounterId: String,
        center: Location,
        entityType: EntityType,
        count: Int,
        radius: Double,
        onCleared: () -> Unit,
    ): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        WorldOp.dispatchAt(plugin, center) { loc ->
            val world = loc.world
            if (world == null) {
                future.complete(Unit)
                return@dispatchAt
            }
            val remaining = mutableMapOf<UUID, Entity>()
            repeat(count) {
                val dx = (Math.random() * 2 - 1) * radius
                val dz = (Math.random() * 2 - 1) * radius
                val spawnLoc = loc.clone().add(dx, 0.0, dz)
                val entity = world.spawnEntity(spawnLoc, entityType)
                entity.isPersistent = false
                remaining[entity.uniqueId] = entity
            }
            active[key(sessionId, encounterId)] = Tracked(remaining, onCleared)
            future.complete(Unit)
        }
        return future
    }

    /** HanaTokiListener 的 EntityDeathEvent handler 轉呼叫這裡。找不到對應 encounter 就是 no-op。 */
    fun onEntityDeath(entityId: UUID) {
        for (tracked in active.values) {
            if (tracked.remaining.remove(entityId) != null && tracked.remaining.isEmpty()) {
                tracked.onCleared()
                return
            }
        }
    }

    /** 強制清場(instance 結束/timeout/admin reset):剩餘實體逐一經 EntityScheduler 移除。 */
    fun despawn(sessionId: UUID, encounterId: String) {
        val tracked = active.remove(key(sessionId, encounterId)) ?: return
        for (entity in tracked.remaining.values) {
            WorldOp.dispatch(plugin, entity) { it.remove() }
        }
    }

    /** session 結束時呼叫,清掉這個 session 底下所有還在追蹤的 encounter(不論 id)。 */
    fun despawnAllForSession(sessionId: UUID) {
        val prefix = "$sessionId#"
        val keys = active.keys.filter { it.startsWith(prefix) }
        for (k in keys) {
            active.remove(k)?.remaining?.values?.forEach { entity ->
                WorldOp.dispatch(plugin, entity) { it.remove() }
            }
        }
    }

    fun remainingCount(sessionId: UUID, encounterId: String): Int =
        active[key(sessionId, encounterId)]?.remaining?.size ?: 0
}
