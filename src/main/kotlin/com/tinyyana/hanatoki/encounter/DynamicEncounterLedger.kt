package com.tinyyana.hanatoki.encounter

import com.tinyyana.hanatoki.config.DynamicEncounterLimits
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 動態 encounter 的登記表——**純邏輯、不碰 Bukkit**,所以「並存 / 部分失敗回滾 / 死亡與清場
 * 競態只能有一個終態 / onCleared 恰好一次 / cap 有界」全部可以直接單元測試
 * (見 DynamicEncounterLedgerTest)。世界動作與派工在 [DynamicEncounterController]。
 *
 * ## 為什麼是 synchronized 而不是「序列化到 anchor region」
 *
 * 舊 [EncounterController] 把 remaining 的變更序列化在 anchor region,那對「一個 session、
 * 一場 encounter」夠用。這裡多了兩件跨場的事:**cap 是整個 session 的**(三場同時 spawn 要
 * 一起算),**reservation 發生在 spawn 呼叫端的執行緒**(不保證是 anchor region)。所有 mutator
 * 都是幾次 map 讀寫、沒有 I/O、沒有派工,一把鎖最直白;查詢走 ConcurrentHashMap 無鎖讀。
 *
 * ## 終態
 *
 * 一場 encounter 只有三種狀態:ACTIVE → CLEARED(最後一隻被殺死)或 ACTIVE → DESPAWNED
 * (被強制收掉)。兩條路都是在鎖內做「還是 ACTIVE 才改」,所以「最後一隻死亡」撞上
 * 「session 結束清場」只會有一邊成立——這就是 onCleared 恰好一次的保證來源。
 */
class DynamicEncounterLedger {

    enum class State { ACTIVE, CLEARED, DESPAWNED }

    class Tracked(
        val runtimeId: String,
        val sessionId: UUID,
        val templateId: String,
        /** 生出來時的全部實體(不會變)。 */
        val entityIds: List<UUID>,
    ) {
        val remaining: MutableSet<UUID> = ConcurrentHashMap.newKeySet<UUID>().also { it.addAll(entityIds) }

        @Volatile
        var state: State = State.ACTIVE
            internal set
    }

    /** [entityGone] 的結果:這隻屬於哪一場、這一場是不是因此清空。 */
    class GoneOutcome(val tracked: Tracked, val cleared: Boolean)

    private class SessionCounters {
        /** 進行中的場數(含 reservation 中、還沒 register 的)。 */
        var activeEncounters = 0

        /** 追蹤中的實體數(含 reservation)。 */
        var entities = 0

        var drops = 0
    }

    private val lock = Any()
    private val active = ConcurrentHashMap<String, Tracked>()
    private val entityIndex = ConcurrentHashMap<UUID, String>()
    private val bySession = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val counters = ConcurrentHashMap<UUID, SessionCounters>()
    private val dropsBySession = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private val dropIndex = ConcurrentHashMap<UUID, UUID>()

    // ---- spawn:reservation → register / release ---------------------------------------

    /**
     * 在真的去生實體之前先把名額訂下來。回 null = 訂到了;回字串 = 被哪一條 cap 擋下。
     * 訂到之後**一定**要呼叫 [register] 或 [releaseReservation] 其中一個,不然名額會漏。
     */
    fun tryReserve(sessionId: UUID, count: Int, limits: DynamicEncounterLimits): String? = synchronized(lock) {
        if (count <= 0) return "empty batch"
        val c = counters.computeIfAbsent(sessionId) { SessionCounters() }
        if (c.activeEncounters >= limits.maxActive) {
            return "active encounter cap ${limits.maxActive} reached"
        }
        if (c.entities + count > limits.maxEntities) {
            return "entity cap ${limits.maxEntities} would be exceeded (${c.entities} + $count)"
        }
        c.activeEncounters += 1
        c.entities += count
        null
    }

    fun releaseReservation(sessionId: UUID, count: Int) = synchronized(lock) {
        val c = counters[sessionId] ?: return
        c.activeEncounters = (c.activeEncounters - 1).coerceAtLeast(0)
        c.entities = (c.entities - count).coerceAtLeast(0)
        if (c.activeEncounters == 0 && c.entities == 0 && c.drops == 0) counters.remove(sessionId)
    }

    /** 全部生成功之後登記。名額已在 [tryReserve] 算過,這裡不再加。 */
    fun register(sessionId: UUID, templateId: String, entityIds: List<UUID>): Tracked = synchronized(lock) {
        val tracked = Tracked(UUID.randomUUID().toString(), sessionId, templateId, entityIds.toList())
        active[tracked.runtimeId] = tracked
        bySession.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() } += tracked.runtimeId
        entityIds.forEach { entityIndex[it] = tracked.runtimeId }
        tracked
    }

    // ---- 離場 ----------------------------------------------------------------------------

    /**
     * 某隻實體不再存在(死亡或消失)。回 null = 不是追蹤中的實體、或這一場已經不是 ACTIVE
     * (已被 despawn 收掉)——兩種都不該再產生任何回呼。
     */
    fun entityGone(entityId: UUID): GoneOutcome? = synchronized(lock) {
        val runtimeId = entityIndex.remove(entityId) ?: return null
        val tracked = active[runtimeId] ?: return null
        if (tracked.state != State.ACTIVE) return null
        if (!tracked.remaining.remove(entityId)) return null
        counters[tracked.sessionId]?.let { it.entities = (it.entities - 1).coerceAtLeast(0) }
        if (tracked.remaining.isNotEmpty()) return GoneOutcome(tracked, cleared = false)
        tracked.state = State.CLEARED
        retire(tracked)
        GoneOutcome(tracked, cleared = true)
    }

    /**
     * 強制收掉一場。回傳**還活著、要由呼叫端去移除**的實體 id;回 null = 這一場不存在或已經
     * 有終態(不會有第二次)。
     */
    fun despawn(runtimeId: String): List<UUID>? = synchronized(lock) {
        val tracked = active[runtimeId] ?: return null
        if (tracked.state != State.ACTIVE) return null
        tracked.state = State.DESPAWNED
        val remaining = tracked.remaining.toList()
        remaining.forEach { entityIndex.remove(it) }
        tracked.remaining.clear()
        counters[tracked.sessionId]?.let { it.entities = (it.entities - remaining.size).coerceAtLeast(0) }
        retire(tracked)
        remaining
    }

    /** 已經有終態的場從表裡拿掉、把場數還回去。呼叫端已持鎖。 */
    private fun retire(tracked: Tracked) {
        active.remove(tracked.runtimeId)
        bySession[tracked.sessionId]?.let { set ->
            set.remove(tracked.runtimeId)
            if (set.isEmpty()) bySession.remove(tracked.sessionId)
        }
        counters[tracked.sessionId]?.let { c ->
            c.activeEncounters = (c.activeEncounters - 1).coerceAtLeast(0)
            if (c.activeEncounters == 0 && c.entities == 0 && c.drops == 0) counters.remove(tracked.sessionId)
        }
    }

    // ---- 掉落物 --------------------------------------------------------------------------

    fun tryReserveDrop(sessionId: UUID, limits: DynamicEncounterLimits): Boolean = synchronized(lock) {
        val c = counters.computeIfAbsent(sessionId) { SessionCounters() }
        if (c.drops >= limits.maxDrops) return false
        c.drops += 1
        true
    }

    fun releaseDropReservation(sessionId: UUID) = synchronized(lock) {
        val c = counters[sessionId] ?: return
        c.drops = (c.drops - 1).coerceAtLeast(0)
    }

    fun registerDrop(sessionId: UUID, itemEntityId: UUID) = synchronized(lock) {
        dropsBySession.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() } += itemEntityId
        dropIndex[itemEntityId] = sessionId
    }

    /** 掉落物不在了(被撿、被清)。回 true = 它是我們追蹤的掉落物。 */
    fun dropGone(itemEntityId: UUID): Boolean = synchronized(lock) {
        val sessionId = dropIndex.remove(itemEntityId) ?: return false
        dropsBySession[sessionId]?.let { set ->
            set.remove(itemEntityId)
            if (set.isEmpty()) dropsBySession.remove(sessionId)
        }
        counters[sessionId]?.let { it.drops = (it.drops - 1).coerceAtLeast(0) }
        true
    }

    /** session 結束:把追蹤中的掉落物全部拿掉並回傳,由呼叫端去移除實體。 */
    fun takeDropsOf(sessionId: UUID): List<UUID> = synchronized(lock) {
        val ids = dropsBySession.remove(sessionId)?.toList() ?: emptyList()
        ids.forEach { dropIndex.remove(it) }
        counters[sessionId]?.let { it.drops = 0 }
        ids
    }

    // ---- 查詢(無鎖)---------------------------------------------------------------------

    fun encounterOf(entityId: UUID): String? = entityIndex[entityId]
    fun isTracked(entityId: UUID): Boolean = entityIndex.containsKey(entityId)
    fun isTrackedDrop(entityId: UUID): Boolean = dropIndex.containsKey(entityId)
    fun tracked(runtimeId: String): Tracked? = active[runtimeId]
    fun runtimeIdsOf(sessionId: UUID): List<String> = bySession[sessionId]?.toList() ?: emptyList()
    fun activeCountOf(sessionId: UUID): Int = bySession[sessionId]?.size ?: 0
    fun entityCountOf(sessionId: UUID): Int = synchronized(lock) { counters[sessionId]?.entities ?: 0 }
    fun dropCountOf(sessionId: UUID): Int = synchronized(lock) { counters[sessionId]?.drops ?: 0 }

    /** debug:整個 JVM 目前追蹤的場數/實體數/掉落物數(有界性的可觀測面)。 */
    fun totals(): IntArray = intArrayOf(active.size, entityIndex.size, dropIndex.size)
}
