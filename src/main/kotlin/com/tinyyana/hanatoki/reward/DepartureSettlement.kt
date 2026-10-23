package com.tinyyana.hanatoki.reward

import com.tinyyana.hanatoki.content.CollectRules
import com.tinyyana.hanatoki.stage.InstanceState
import java.util.UUID

/**
 * 中途離場與局逾時的「已收集物」結算(1.0.0-rc.34)。純邏輯,不碰 Bukkit。
 *
 * 內容層宣告了 `collectible` 物件的副本,玩家每收下一處就在 stats 蓋逐人鍵 `collect.<物件 id>.<玩家 UUID>`
 * (見 [CollectRules])。通關(Resolution)才會把這些送進獎勵規則;中途離場或整局逾時的人原本什麼都領不到。
 * 這裡對**這位玩家自己**有收集帳的人補一筆 [CompletionResult],resultKey 是 [LEFT] 或 [TIMEOUT],
 * 讓 integration 的規則(例如只給一次性的終身首次獎勵)照常評估;它**不是**通關,引擎也不改變離場與收尾流程。
 *
 * - 只看自己的逐人鍵,不用 [CompletionResult.statFor](它在逐人鍵不存在時退回整局合計,隊友收了會害自己被當成收過)。
 * - 沒有任何 collectible 的副本沒有 `collect.*` 鍵,自然不觸發。
 * - 每位玩家每局最多一次([InstanceState.claimDeparture]):離場後局逾時、或個別結算後再離場都不會重複。
 */
internal object DepartureSettlement {
    /** 中途離場(自願離開、換世界、死亡後被請出、管理員踢出)。 */
    const val LEFT = "left"

    /** 整局逾時結束,結束當下還在名單上的成員。 */
    const val TIMEOUT = "timeout"

    private const val SNAPSHOT_ATTEMPTS = 4

    /** 這位玩家自己有沒有收過任何一處(逐人鍵 `collect.<物件 id>.<UUID>` > 0)。 */
    fun hasCollected(stats: Map<String, Long>, playerId: UUID): Boolean {
        val prefix = CollectRules.STAT_PREFIX + "."
        val suffix = ".$playerId"
        return stats.any { (key, value) ->
            value > 0L && key.length > prefix.length + suffix.length && key.startsWith(prefix) && key.endsWith(suffix)
        }
    }

    /**
     * 複製 stats。內容層在場地的 region 執行緒寫它,離場與逾時 tick 可能在別的執行緒讀:
     * 遇到並行修改就重試幾次,仍失敗才丟出(呼叫端會吞掉並記 log,不影響離場流程)。
     */
    fun snapshot(stats: Map<String, Long>): Map<String, Long> {
        var last: ConcurrentModificationException? = null
        repeat(SNAPSHOT_ATTEMPTS) {
            try {
                return java.util.Collections.unmodifiableMap(LinkedHashMap(stats))
            } catch (e: ConcurrentModificationException) {
                last = e
            }
        }
        throw last!!
    }

    /**
     * 產生這位玩家的離場結算;沒有收集帳、或這一局已經替他結算過 → null。
     * 同一次結算帶的是整份 stats 快照(跟通關結算一致,integration 要的 run id 等欄位都在裡面)。
     */
    fun plan(state: InstanceState, playerId: UUID, dungeonId: String, resultKey: String, durationMs: Long): CompletionResult? {
        val stats = snapshot(state.stats)
        if (!hasCollected(stats, playerId)) return null
        if (!state.claimDeparture(playerId)) return null
        return CompletionResult(
            completionId = UUID.randomUUID(),
            playerId = playerId,
            dungeonId = dungeonId,
            resultKey = resultKey,
            durationMs = durationMs.coerceAtLeast(0L),
            stats = stats,
        )
    }
}
