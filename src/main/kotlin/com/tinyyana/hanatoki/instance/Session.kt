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
    val timeLimitMs: Long,
    val graceMs: Long,
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
        members.isNotEmpty() && members.values.all { it.state == MemberState.DROPPED }

    fun isExpired(nowMs: Long): Boolean = nowMs - startedAtMs >= timeLimitMs

    fun remainingMs(nowMs: Long): Long = (timeLimitMs - (nowMs - startedAtMs)).coerceAtLeast(0)
}
