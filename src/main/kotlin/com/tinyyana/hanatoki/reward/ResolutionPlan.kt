package com.tinyyana.hanatoki.reward

import com.tinyyana.hanatoki.stage.InstanceState
import java.util.UUID

/**
 * 一次 Resolution 要送出的 [CompletionResult] 清單(純邏輯,不碰 Bukkit)。
 *
 * [com.tinyyana.hanatoki.HanaTokiCore] 的 `resolve(resultKey)` 與 `resolveEach(map)` 共用這一條:
 * 兩者都先在這裡 [InstanceState.claimResolution],所以同一個 stage 裡先 `resolve` 再 `resolveEach`
 * (或反過來、或同一支呼叫兩次)只有第一次產生結果。
 *
 * 這裡只產生清單,不派送也不結束 session——呼叫端拿到清單後照既有順序走
 * (session 型:`endSession` 認領成功才派送,再收尾)。
 */
internal object ResolutionPlan {

    /**
     * `resolveEach` 的 key 查詢:map 在這裡複製一份(呼叫端之後改 map 不影響這次結算),
     * 沒列入或值為空白 → null(這位成員不產生結果)。
     */
    fun eachKeyFor(resultKeyByPlayer: Map<UUID, String>): (UUID) -> String? {
        val copy = HashMap(resultKeyByPlayer)
        return { playerId -> copy[playerId]?.takeIf { it.isNotBlank() } }
    }

    /**
     * 認領這一段 stage 的結算並產生逐人結果。回傳 null = 這一段已經結算過(什麼都不送)。
     *
     * - [keyFor] 回 null → 這位成員**不產生**任何 [CompletionResult](空白 key 要不要當成未列入,
     *   由呼叫端的 [keyFor] 決定:`resolve` 維持原樣照送,`resolveEach` 視為未列入)。
     * - 同一次結算的所有結果共用**同一份** stats 快照(認領當下拍一次,之後 [InstanceState.stats]
     *   再怎麼改都不影響已產生的結果)。內容層蓋在 stats 裡的 run id(例如 LycoHanaToki 的
     *   `run.id_hi/run.id_lo`)因此對每個人都相同。
     * - completionId 每人各一個(冪等 key 是「這一局 × 這位玩家」)。
     */
    fun claim(
        state: InstanceState,
        members: List<UUID>,
        dungeonId: String,
        durationMs: Long,
        keyFor: (UUID) -> String?,
    ): List<CompletionResult>? {
        if (!state.claimResolution()) return null
        val stats: Map<String, Long> = java.util.Collections.unmodifiableMap(LinkedHashMap(state.stats))
        return members.mapNotNull { playerId ->
            val key = keyFor(playerId) ?: return@mapNotNull null
            CompletionResult(
                completionId = UUID.randomUUID(),
                playerId = playerId,
                dungeonId = dungeonId,
                resultKey = key,
                durationMs = durationMs,
                stats = stats,
            )
        }
    }
}
