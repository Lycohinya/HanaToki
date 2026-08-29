package com.tinyyana.hanatoki.instance

import java.util.UUID

enum class MemberState { ONLINE, OFFLINE_GRACE, DROPPED }

data class Member(
    val playerId: UUID,
    val joinedAtMs: Long,
    var state: MemberState = MemberState.ONLINE,
    var offlineSinceMs: Long? = null,
)

enum class EndReason { TIMEOUT, ALL_DROPPED, ADMIN_RESET, ABANDONED, RESOLVED }

/**
 * Instance 裡的玩家集合與每人狀態(ARCH §2「Session」)。純邏輯類別,不碰 Bukkit——
 * 時間全部用呼叫端傳入的 epoch millis 表示,方便單元測試控制時間推進(見 SessionTest)。
 *
 * 這個類別本身不是 thread-confined 的保證來源——實際套用時,所有會改這裡狀態的呼叫都必須
 * 經 `instance.submit()`(ARCH §5.1②)序列化,這裡只提供狀態機本身。
 */
class Session(
    val sessionId: UUID,
    val dungeonId: String,
    val slotId: String,
    val startedAtMs: Long,
    /**
     * 這一局的時限(毫秒)。**null = 沒有時限**(Endless Run,MIGRATION_PLAN §5.6)。
     *
     * ⚠ 刻意用 null 而不是 `Long.MAX_VALUE`/一個很大的秒數:假無限值會在
     * 「還剩多久」這類算式裡溢位成負數,也讓 `remainingMs` 印出一個沒有意義的天文數字
     * ——2026-08-29 之前常駐副本就是塞 `Long.MAX_VALUE`,`sessionRemainingSeconds()`
     * 回傳的是 2.9 億年。沒有時限就明確表達成「沒有」,呼叫端必須自己決定要顯示什麼。
     */
    val timeLimitMs: Long?,
    val graceMs: Long,
    /**
     * 常駐形態([com.tinyyana.hanatoki.config.ExecutionMode.PERSISTENT])。
     *
     * MIGRATION_PLAN §5.0 決策 A:整個 instance 生命週期的分岔在這裡收斂成**兩個述詞**——
     * [isExpired] 與 [isAllDropped] 恆 false,也就是「時間」與「人數」都不再是結束條件。
     * 其餘狀態機行為(加入/離線 grace/重連/drop)完全共用,常駐副本的成員照樣會因為離線逾時
     * 被 drop,只是最後一個人 drop 掉不會把 instance 一起收走。
     */
    val persistent: Boolean = false,
) {
    private val members = linkedMapOf<UUID, Member>()

    fun addMember(playerId: UUID, nowMs: Long) {
        members[playerId] = Member(playerId, nowMs)
    }

    fun memberIds(): List<UUID> = members.keys.toList()

    fun stateOf(playerId: UUID): MemberState? = members[playerId]?.state

    /** 玩家登出:標記 offline + 記錄離線時刻,grace 倒數從這裡開始算。*/
    fun markOffline(playerId: UUID, nowMs: Long) {
        val m = members[playerId] ?: return
        if (m.state == MemberState.ONLINE) {
            m.state = MemberState.OFFLINE_GRACE
            m.offlineSinceMs = nowMs
        }
    }

    /** 玩家重連:grace 內 → 恢復 ONLINE,回傳 true;grace 已過 or 早已 DROPPED → 回傳 false。*/
    fun reconnect(playerId: UUID, nowMs: Long): Boolean {
        val m = members[playerId] ?: return false
        return when (m.state) {
            MemberState.ONLINE -> true // 沒掉線過,視為已在場
            MemberState.DROPPED -> false
            MemberState.OFFLINE_GRACE -> {
                val since = m.offlineSinceMs ?: return false
                if (nowMs - since <= graceMs) {
                    m.state = MemberState.ONLINE
                    m.offlineSinceMs = null
                    true
                } else {
                    m.state = MemberState.DROPPED
                    false
                }
            }
        }
    }

    /** 主動踢出(管理員 kick / 玩家自己離開),不經 grace 判定。*/
    fun drop(playerId: UUID) {
        members[playerId]?.state = MemberState.DROPPED
    }

    /** 每次 tick 呼叫:把逾期還在 OFFLINE_GRACE 的成員轉成 DROPPED。回傳這次新掉出去的名單。*/
    fun sweepExpiredGrace(nowMs: Long): List<UUID> {
        val dropped = mutableListOf<UUID>()
        for (m in members.values) {
            if (m.state == MemberState.OFFLINE_GRACE) {
                val since = m.offlineSinceMs ?: continue
                if (nowMs - since > graceMs) {
                    m.state = MemberState.DROPPED
                    dropped += m.playerId
                }
            }
        }
        return dropped
    }

    fun activeMembers(): List<UUID> =
        members.values.filter { it.state != MemberState.DROPPED }.map { it.playerId }

    fun isAllDropped(): Boolean =
        !persistent && members.isNotEmpty() && members.values.all { it.state == MemberState.DROPPED }

    /** 這一局有沒有時限。false = Endless(見 [timeLimitMs])。常駐副本恆 false。 */
    fun hasTimeLimit(): Boolean = !persistent && timeLimitMs != null

    fun isExpired(nowMs: Long): Boolean {
        val limit = timeLimitMs ?: return false
        return !persistent && nowMs - startedAtMs >= limit
    }

    /**
     * 還剩多少毫秒。**沒有時限時回傳 [NO_TIME_LIMIT](-1)**,不是 0、也不是一個超大值——
     * 0 會被顯示層當成「時間到」畫成空的進度條,超大值則是本來要避免的假無限。
     */
    fun remainingMs(nowMs: Long): Long {
        val limit = timeLimitMs ?: return NO_TIME_LIMIT
        return (limit - (nowMs - startedAtMs)).coerceAtLeast(0)
    }

    /** 這一局已經跑了多久。Endless Run 的顯示層要的是這個,不是倒數。 */
    fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0)

    companion object {
        /** [remainingMs] 對無時限 session 的回傳值(見該方法 KDoc)。 */
        const val NO_TIME_LIMIT: Long = -1L
    }
}
