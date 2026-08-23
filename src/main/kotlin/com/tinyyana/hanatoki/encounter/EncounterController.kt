package com.tinyyana.hanatoki.encounter

import com.tinyyana.hanatoki.folia.InstanceDispatch
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
 * 刻意不做波次/Boss 控制器/AI——Boss 型內容用 `actor/` + 內容自己的招式迴圈實作
 * (ARCH §12「先有案例再抽象」,和風決鬥是第一個案例,重複出現的模式才抽上來)。
 *
 * 一次 encounter 的 key = `"<sessionId>#<encounterId>"`(session 結束時一併 cleanup,
 * 不需要跨 session 查找)。
 *
 * ## Folia:remaining/清空判定收斂回 instance 序列化(2026-08-23 修正)
 *
 * 每一隻怪死在**自己所屬 region 的執行緒**上——三隻怪散在三個 region 就是三條執行緒同時進
 * [onEntityDeath]。舊版直接在 handler 裡改一個普通 `MutableMap` 再問 `isEmpty()`,有兩個真實
 * 競態:①普通 Map 的併發改寫本身就是資料損壞;②就算換成 `ConcurrentHashMap`,「移除自己那隻
 * → 檢查是不是空了」這兩步不是原子的,最後兩隻同時死掉會讓**兩條執行緒都看到空的**,`onCleared`
 * 被呼叫兩次(對 Boss/Resolution 型內容就是重複結算)。
 *
 * 修法照 ARCH §5.1②:remaining 的變更與清空判定**一律 submit 回該 instance 的 anchor region**
 * 序列化執行,再加上 `active.remove(key)` 這個原子認領當第二道保險。死亡事件本身仍在原 region
 * 觸發,handler 只做無鎖查表 + 派工(§5.2 規則 1)。
 */
class EncounterController(private val plugin: Plugin) {
    private class Tracked(
        val anchor: Location,
        val remaining: MutableSet<UUID>,
        val entities: MutableList<Entity>,
        val onCleared: () -> Unit,
    )

    private val active = ConcurrentHashMap<String, Tracked>()

    /** entityId -> encounter key。死亡事件的無鎖前置查表(避免掃過所有 encounter)。 */
    private val entityIndex = ConcurrentHashMap<UUID, String>()

    private fun key(sessionId: UUID, encounterId: String) = "$sessionId#$encounterId"

    /**
     * 在 [center] 周圍 [radius] 格內隨機散開生 [count] 隻 [entityType]。回傳的 future 在
     * spawn 完成(已在該座標的 region task 內執行完)時 complete。
     *
     * [onCleared] 在最後一隻死亡時呼叫,**保證已經在 [anchor] 所屬 region 的 task 內、且整場
     * 只會被呼叫一次**——呼叫端(behavior)負責用它推進 stage/Resolution,不在這裡做狀態機決策。
     */
    fun spawn(
        sessionId: UUID,
        encounterId: String,
        anchor: Location,
        center: Location,
        entityType: EntityType,
        count: Int,
        radius: Double,
        onCleared: () -> Unit,
    ): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        val k = key(sessionId, encounterId)
        WorldOp.dispatchAt(plugin, center) { loc ->
            val world = loc.world
            if (world == null) {
                future.complete(null)
                return@dispatchAt
            }
            val remaining = mutableSetOf<UUID>()
            val entities = mutableListOf<Entity>()
            repeat(count) {
                val dx = (Math.random() * 2 - 1) * radius
                val dz = (Math.random() * 2 - 1) * radius
                val spawnLoc = loc.clone().add(dx, 0.0, dz)
                val entity = world.spawnEntity(spawnLoc, entityType)
                entity.isPersistent = false
                remaining += entity.uniqueId
                entities += entity
            }
            // 先公佈 Tracked 再建索引:反過來的話,一隻剛生出來就死掉的怪會查到索引卻找不到
            // Tracked(這一整段都在同一個 region task 內,實際上不會發生,但順序寫對比較不用推理)。
            active[k] = Tracked(anchor, remaining, entities, onCleared)
            remaining.forEach { entityIndex[it] = k }
            future.complete(null)
        }
        return future
    }

    /** HanaTokiListener 的 EntityDeathEvent handler 轉呼叫這裡。找不到對應 encounter 就是 no-op。 */
    fun onEntityDeath(entityId: UUID) {
        val k = entityIndex.remove(entityId) ?: return
        val tracked = active[k] ?: return
        InstanceDispatch.submit(plugin, tracked.anchor) {
            // 這裡起是 anchor region 序列化區:remaining 的讀寫與「是不是空了」的判定都在這一段,
            // 不會有兩條執行緒同時看到空集合。
            if (!tracked.remaining.remove(entityId)) return@submit
            if (tracked.remaining.isNotEmpty()) return@submit
            // 原子認領:確定是自己把它從 active 拿走的,才呼叫 onCleared(第二道保險)。
            if (active.remove(k) == null) return@submit
            tracked.onCleared()
        }
    }

    /** 強制清場(instance 結束/timeout/admin reset):剩餘實體逐一經 EntityScheduler 移除。 */
    fun despawn(sessionId: UUID, encounterId: String) {
        removeTracked(key(sessionId, encounterId))
    }

    /** session 結束時呼叫,清掉這個 session 底下所有還在追蹤的 encounter(不論 id)。 */
    fun despawnAllForSession(sessionId: UUID) {
        val prefix = "$sessionId#"
        active.keys.filter { it.startsWith(prefix) }.forEach { removeTracked(it) }
    }

    private fun removeTracked(k: String) {
        val tracked = active.remove(k) ?: return
        // entityIndex 一併清掉,免得清場後才送達的死亡事件又去查一個已經不存在的 encounter。
        tracked.entities.forEach { entityIndex.remove(it.uniqueId) }
        tracked.entities.forEach { entity -> WorldOp.dispatch(plugin, entity) { it.remove() } }
    }

    /** 這個實體是不是某個進行中 encounter 生出來的(死亡掉落物清除用的無鎖查表)。 */
    fun isTracked(entityId: UUID): Boolean = entityIndex.containsKey(entityId)

    fun remainingCount(sessionId: UUID, encounterId: String): Int =
        active[key(sessionId, encounterId)]?.remaining?.size ?: 0
}
