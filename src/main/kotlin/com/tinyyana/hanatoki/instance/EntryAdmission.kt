package com.tinyyana.hanatoki.instance

import com.tinyyana.hanatoki.api.DungeonEntryStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 進場的**最終把關**(admission)。所有入口(boolean、tracked、`/hanatoki enter`)最後都走
 * [DungeonEntry.enter],而 [DungeonEntry.enter] 在取 slot、背包保管、扣物**之前**先問這裡。
 *
 * ## 檢查順序(第一個不符合的就是結果)
 *
 * 1. 副本存在且仍在收人 → [DungeonEntryStatus.NO_DUNGEON]
 * 2. 名單非空、UUID 不重複、人數 ≤ `party-cap` → [DungeonEntryStatus.PARTY_INVALID]
 * 3. 全員在線 → [DungeonEntryStatus.PLAYER_OFFLINE]
 * 4. 沒有人在任何 session 裡、也沒有人有進行中的進場交易或尚未收斂的回滾/歸還
 *    → [DungeonEntryStatus.ALREADY_INSIDE]
 * 5. 內容資格([Ports.eligibilityBlock]) → [DungeonEntryStatus.NOT_ELIGIBLE]
 *
 * ## 為什麼要有 [admitAndReserve]
 *
 * 只靠呼叫端預檢擋不住「兩個請求同時通過」:檢查與 session 建立之間隔著非同步的記返回點。
 * 這裡在**同一把鎖**內檢查並把名單登記成 in-flight,第二個請求就會看到第一個的登記而被拒。
 * 登記在交易**真的收尾**(成功、或失敗回滾完成/等待逾時)時由呼叫端 [release]。
 *
 * ## 鎖
 *
 * [lock] 必須是 [com.tinyyana.hanatoki.HanaTokiCore] 的 content lifecycle lock(同一把鎖串行化
 * 註冊與進場):[Ports] 會問「副本還在不在收人」,而那個查詢本身就取這把鎖,所以不另立第二把鎖
 * ——兩把鎖互相等待是死結。[isPending] 只讀 [inFlight] 這張並發 set 與 [Ports.isSettling]
 * (都是無鎖查詢),任何執行緒可呼叫、不會卡在進場臨界區後面。
 *
 * 不碰 Bukkit:世界/玩家狀態都經 [Ports],單元測試直接打(見 EntryAdmissionTest)。
 */
class EntryAdmission(private val lock: Any, private val ports: Ports) {

    interface Ports {
        /** 這座副本的 `party-cap`;null = 沒有這座副本或已經停止收人。 */
        fun partyCap(dungeonId: String): Int?

        /** 玩家目前在線(以伺服器現況為準,不信呼叫端手上的 Player 物件)。 */
        fun isOnline(playerId: UUID): Boolean

        /** 給失敗原因用的名字(離線也要有字串可顯示)。 */
        fun nameOf(playerId: UUID): String

        /** 玩家已經在某個 session 的成員集合裡。 */
        fun isInSession(playerId: UUID): Boolean

        /**
         * 失敗後(或上一局結束後)的收斂還沒完成:還在被送出場地、或局內背包交易還沒收斂(PREPARED/
         * CLEARING/RESTORING)。無鎖查詢。
         */
        fun isSettling(playerId: UUID): Boolean

        /**
         * 內容資格(整備包、門檻、配額……)。回傳玩家可讀的繁中原因;null = 沒有意見。
         * 必須是**只讀、快速、執行緒安全**的查詢——它在進場臨界區內被呼叫。
         */
        fun eligibilityBlock(playerId: UUID, dungeonId: String): String?
    }

    /** 被拒絕的結果:[status] 是 [DungeonEntryStatus] 常數,[reason] 是玩家可讀的繁中說明。 */
    class Rejection(val status: String, val reason: String)

    private val inFlight: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** 只檢查、不登記(boolean 入口的同步預檢用)。 */
    fun check(memberIds: List<UUID>, dungeonId: String): Rejection? = synchronized(lock) {
        evaluate(memberIds, dungeonId)
    }

    /**
     * 在臨界區內檢查,通過就把整份名單登記成 in-flight。回傳 null = 已受理,**呼叫端必須在交易
     * 收尾時 [release]**;非 null = 被拒,沒有留下任何狀態。
     */
    fun admitAndReserve(memberIds: List<UUID>, dungeonId: String): Rejection? = synchronized(lock) {
        evaluate(memberIds, dungeonId)?.let { return@synchronized it }
        inFlight.addAll(memberIds)
        null
    }

    fun release(memberIds: Collection<UUID>) {
        inFlight.removeAll(memberIds.toSet())
    }

    /** 插件停用:所有登記一律作廢(交易的 future 之後才收尾也無所謂,[release] 對不存在的登記是 no-op)。 */
    fun clear() {
        inFlight.clear()
    }

    /** 進行中的進場交易,或失敗/結束後的收斂尚未完成。 */
    fun isPending(playerId: UUID): Boolean = playerId in inFlight || ports.isSettling(playerId)

    /** 單一玩家的預檢(只讀,不登記、不扣料)。null = 引擎層與內容資格都沒意見。 */
    fun blockReason(playerId: UUID, dungeonId: String): String? = check(listOf(playerId), dungeonId)?.reason

    private fun evaluate(memberIds: List<UUID>, dungeonId: String): Rejection? {
        val cap = ports.partyCap(dungeonId)
            ?: return Rejection(DungeonEntryStatus.NO_DUNGEON, "這座副本目前不存在或已停止收人")
        if (memberIds.isEmpty()) return Rejection(DungeonEntryStatus.PARTY_INVALID, "沒有可進場的玩家")
        if (memberIds.toSet().size != memberIds.size) {
            return Rejection(DungeonEntryStatus.PARTY_INVALID, "隊伍名單裡有重複的玩家")
        }
        if (memberIds.size > cap) {
            return Rejection(DungeonEntryStatus.PARTY_INVALID, "這座副本一次最多 $cap 位,這次有 ${memberIds.size} 位")
        }
        memberIds.firstOrNull { !ports.isOnline(it) }?.let {
            return Rejection(DungeonEntryStatus.PLAYER_OFFLINE, "${ports.nameOf(it)} 不在線")
        }
        memberIds.firstOrNull { ports.isInSession(it) }?.let {
            return Rejection(DungeonEntryStatus.ALREADY_INSIDE, "${ports.nameOf(it)} 已經在副本裡,先完成或撤離那一局")
        }
        memberIds.firstOrNull { isPending(it) }?.let {
            return Rejection(DungeonEntryStatus.ALREADY_INSIDE, "${ports.nameOf(it)} 上一次進場或離場還在收尾,稍後再試")
        }
        for (id in memberIds) {
            ports.eligibilityBlock(id, dungeonId)?.let { return Rejection(DungeonEntryStatus.NOT_ELIGIBLE, it) }
        }
        return null
    }
}
