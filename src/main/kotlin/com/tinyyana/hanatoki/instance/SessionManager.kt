package com.tinyyana.hanatoki.instance

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class EnterResult<out A> {
    object NoSlot : EnterResult<Nothing>()
    data class Entered<A>(val session: Session, val anchor: A) : EnterResult<A>()
}

data class EndedSession(val sessionId: UUID, val dungeonId: String, val slotId: String, val reason: EndReason)

/**
 * 串起 [SlotPool] 分配與 [Session] lifecycle 的登記表(ARCH §5.1①「全域註冊表」)。
 * 純邏輯,不碰 Bukkit;呼叫端(HanaTokiPlugin)負責把這裡的操作包進 `instance.submit()`
 * 或直接呼叫無副作用的查詢方法(如 `sessionOf`)。
 *
 * 每個 dungeonId 各自的 session/slot 狀態靠這一層做「查表」,實際的世界重置動作
 * (diff rollback)由呼叫端在 `endSession` 前後自行接上(§5.1「回滾未完成前 slot 不得標為空閒」
 * ——這裡的 `endSession` 因此不直接呼叫 `slotPool.free`,而是由呼叫端在確認回滾完成後呼叫
 * [releaseSlotAfterRollback])。
 */
class SessionManager<A>(private val slotPool: SlotPool<A>) {
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val bySlot = ConcurrentHashMap<String, UUID>()
    private val byPlayer = ConcurrentHashMap<UUID, UUID>()

    fun enter(
        dungeonId: String,
        players: List<UUID>,
        nowMs: Long,
        timeLimitMs: Long,
        graceMs: Long,
    ): EnterResult<A> {
        val slot = slotPool.allocate(dungeonId) ?: return EnterResult.NoSlot
        val session = Session(UUID.randomUUID(), dungeonId, slot.slotId, nowMs, timeLimitMs, graceMs)
        players.forEach { session.addMember(it, nowMs); byPlayer[it] = session.sessionId }
        sessions[session.sessionId] = session
        bySlot[slot.slotId] = session.sessionId
        return EnterResult.Entered(session, slot.anchor)
    }

    fun sessionOf(playerId: UUID): Session? = byPlayer[playerId]?.let { sessions[it] }

    fun sessionBySlot(slotId: String): Session? = bySlot[slotId]?.let { sessions[it] }

    fun sessionById(sessionId: UUID): Session? = sessions[sessionId]

    fun markOffline(playerId: UUID, nowMs: Long) {
        sessionOf(playerId)?.markOffline(playerId, nowMs)
    }

    fun reconnect(playerId: UUID, nowMs: Long): Boolean = sessionOf(playerId)?.reconnect(playerId, nowMs) ?: false

    /** 管理員/玩家自願離開單一成員;整局全空的話一併結束 session(回傳結束事件,由呼叫端做世界回滾)。*/
    fun kick(playerId: UUID): EndedSession? {
        val session = sessionOf(playerId) ?: return null
        session.drop(playerId)
        byPlayer.remove(playerId)
        if (session.isAllDropped()) {
            return endSessionBookkeeping(session, EndReason.ADMIN_RESET)
        }
        return null
    }

    /** 每次 tick 呼叫:處理逾時 grace、逾時計時器。回傳這次因此結束的 session(呼叫端接手世界回滾 + 之後呼叫 [releaseSlotAfterRollback])。*/
    fun tick(nowMs: Long): List<EndedSession> {
        val ended = mutableListOf<EndedSession>()
        for (session in sessions.values.toList()) {
            val droppedThisTick = session.sweepExpiredGrace(nowMs)
            droppedThisTick.forEach { byPlayer.remove(it) }
            val timedOut = session.isExpired(nowMs)
            val allGone = session.isAllDropped()
            if (timedOut || allGone) {
                endSessionBookkeeping(session, if (timedOut) EndReason.TIMEOUT else EndReason.ALL_DROPPED)
                    ?.let { ended += it }
            }
        }
        return ended
    }

    /** Stage 引擎的 Resolution 用:直接以 sessionId 結束(不論成員 online/offline 狀態)。 */
    fun endSession(sessionId: UUID, reason: EndReason): EndedSession? =
        endSessionBookkeeping(sessions[sessionId] ?: return null, reason)

    /**
     * 從登記表移除 session/player 對照(不動 slot 佔用狀態——那要等回滾完成)。
     *
     * ⚠ `sessions.remove(...)` 的回傳值就是**原子認領**:同一個 session 被兩條路徑同時結束
     * (例如 behavior 在 Boss 死亡時 `resolve()`,同一刻逾時 tick 也判定該收)只有一條會拿到
     * 非 null,另一條回 null。少了這條,`resolveSession` 會對同一局發兩次獎——`completionId`
     * 每次 Resolution 都是新的,integration 端的幂等去重擋不住「同一局兩個不同 completionId」。
     */
    private fun endSessionBookkeeping(session: Session, reason: EndReason): EndedSession? {
        sessions.remove(session.sessionId) ?: return null
        bySlot.remove(session.slotId)
        session.memberIds().forEach { byPlayer.remove(it) }
        return EndedSession(session.sessionId, session.dungeonId, session.slotId, reason)
    }

    /** 世界 diff 回滾完成後呼叫,slot 才真正回到可分配池(ARCH §5.1 硬規則)。*/
    fun releaseSlotAfterRollback(slotId: String) {
        slotPool.free(slotId)
    }

    /** onDisable 收斂用:立即結束全部 session(視為 abandoned),回傳給呼叫端逐一處理回滾與送人。*/
    fun endAll(reason: EndReason): List<EndedSession> =
        sessions.values.toList().mapNotNull { endSessionBookkeeping(it, reason) }

    fun snapshot(): List<Session> = sessions.values.toList()
}
