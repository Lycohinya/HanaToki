package com.tinyyana.hanatoki.instance

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class EnterResult<out A> {
    object NoSlot : EnterResult<Nothing>()

    /** 新開了一個 instance(呼叫端要啟動 stage 狀態機)。 */
    data class Entered<A>(val session: Session, val anchor: A) : EnterResult<A>()

    /**
     * 加入了一個**已經在跑**的 instance(常駐副本,MIGRATION_PLAN §5.0 決策 B)。
     *
     * 跟 [Entered] 分開是必要的而不是潔癖:呼叫端看到 [Entered] 會 `stageEngine.startFor(...)`,
     * 對常駐副本那等於「每有一個人走進來就把整座副本重置回 dormant」——Boss 打到一半有人進場
     * 會直接消失。
     */
    data class Joined<A>(val session: Session, val anchor: A) : EnterResult<A>()
}

/**
 * @param memberIds 這局結束時登記在案的**全部**成員(含已 DROPPED 的)。呼叫端要靠它把人送出
 *   副本世界——session 一旦結束,`sessionOf` 就查不到人了,那時再問「誰在這一局」已經來不及。
 */
data class EndedSession(
    val sessionId: UUID,
    val dungeonId: String,
    val slotId: String,
    val reason: EndReason,
    val memberIds: List<UUID> = emptyList(),
)

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

    /** 常駐副本的 dungeonId -> 那唯一一個 instance。MIGRATION_PLAN §5.0 決策 B 的查表面。 */
    private val persistentByDungeon = ConcurrentHashMap<String, UUID>()

    /** 只保護常駐副本的「查 + 建 + 登記」三步(見 [enterPersistent])。 */
    private val persistentEnterLock = Any()

    /**
     * @param persistent 常駐形態(見 [Session.persistent])。true 時同一個 [dungeonId] 只會有一個
     *   instance:已經存在就把 [players] 併進它的成員集合並回傳 [EnterResult.Joined]。
     */
    fun enter(
        dungeonId: String,
        players: List<UUID>,
        nowMs: Long,
        timeLimitMs: Long,
        graceMs: Long,
        persistent: Boolean = false,
    ): EnterResult<A> {
        if (persistent) return enterPersistent(dungeonId, players, nowMs, timeLimitMs, graceMs)
        val slot = slotPool.allocate(dungeonId) ?: return EnterResult.NoSlot
        val session = Session(UUID.randomUUID(), dungeonId, slot.slotId, nowMs, timeLimitMs, graceMs, false)
        addMembers(session, players, nowMs)
        sessions[session.sessionId] = session
        bySlot[slot.slotId] = session.sessionId
        return EnterResult.Entered(session, slot.anchor)
    }

    /**
     * 常駐副本:同一個 dungeonId 只會有一個 instance。
     *
     * ⚠ 「先查有沒有、沒有才建」如果不上鎖有真的競態:兩個人同時走進蒼櫻,兩條執行緒都看到
     * 沒有 instance,兩邊都去 `slotPool.allocate`,只有一個拿得到——**另一個人會收到「客滿」**,
     * 而那座副本明明只有他們兩個。
     *
     * 這裡用一把小鎖而不是 `computeIfAbsent`/CAS 迴圈:鎖裡面只有幾次 map 讀寫,沒有 I/O、沒有
     * 派工、不會阻塞;而 CAS 迴圈在「session 已從 `sessions` 移除但 `persistentByDungeon` 還沒
     * 清掉」那個瞬間會空轉。進場是低頻操作,可讀性值這個代價。
     */
    private fun enterPersistent(
        dungeonId: String,
        players: List<UUID>,
        nowMs: Long,
        timeLimitMs: Long,
        graceMs: Long,
    ): EnterResult<A> = synchronized(persistentEnterLock) {
        val existing = persistentByDungeon[dungeonId]?.let { sessions[it] }
        if (existing != null) {
            val anchor = slotPool.anchorOf(existing.slotId) ?: return@synchronized EnterResult.NoSlot
            addMembers(existing, players, nowMs)
            return@synchronized EnterResult.Joined(existing, anchor)
        }
        val slot = slotPool.allocate(dungeonId) ?: return@synchronized EnterResult.NoSlot
        val created = Session(UUID.randomUUID(), dungeonId, slot.slotId, nowMs, timeLimitMs, graceMs, true)
        sessions[created.sessionId] = created
        bySlot[slot.slotId] = created.sessionId
        persistentByDungeon[dungeonId] = created.sessionId
        addMembers(created, players, nowMs)
        EnterResult.Entered(created, slot.anchor)
    }

    /**
     * 把人加進成員集合。**已經在名單上的人不重加**(除非早就被 DROPPED 出去了)——重加會把
     * [Session.addMember] 建的 `Member` 換成全新的一份,把 joinedAt 與離線 grace 的計時一起洗掉。
     * 常駐副本會反覆對同一個人走這條路徑(每次跨世界、每次登入),所以這條防護是必要的;
     * session 形態的 `enter` 一定是全新的 session,成員本來就都是新的,行為完全不變。
     */
    private fun addMembers(session: Session, players: List<UUID>, nowMs: Long) {
        for (playerId in players) {
            val state = session.stateOf(playerId)
            if (state == null || state == MemberState.DROPPED) session.addMember(playerId, nowMs)
            byPlayer[playerId] = session.sessionId
        }
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
        persistentByDungeon.remove(session.dungeonId, session.sessionId)
        val members = session.memberIds()
        members.forEach { byPlayer.remove(it) }
        return EndedSession(session.sessionId, session.dungeonId, session.slotId, reason, members)
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
