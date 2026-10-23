package com.tinyyana.hanatoki.reward

import java.util.UUID

/**
 * ARCH §4:Resolution 的產出,HanaToki 定義、integration 消費。`completionId` 是重播/重試
 * 安全的幂等 key——同一次 Resolution 只產生一次,之後不論送達幾次都帶同一個 id。
 */
data class CompletionResult(
    val completionId: UUID,
    val playerId: UUID,
    val dungeonId: String,
    val resultKey: String,
    val durationMs: Long,
    val stats: Map<String, Long> = emptyMap(),
) {
    /**
     * 這位玩家自己的統計值:先找逐人鍵 [RunStats.playerKey](內容層為每位成員各記一份),沒有才退回整局共用的 [stat]。
     * 同一次結算所有成員共用同一份 [stats] 快照,所以「每人打散幾隻」這種逐人數字只能靠逐人鍵區分。
     */
    fun statFor(stat: String): Long = stats[RunStats.playerKey(stat, playerId)] ?: stats[stat] ?: 0L
}

/**
 * ARCH §4:integration 註冊給 HanaToki 呼叫,fire-and-forget(無回傳值)——I/O 由實作端自行
 * 排到合適的執行緒,HanaToki 呼叫這裡時不等待、不阻塞。
 */
fun interface RewardSink {
    fun onCompletion(result: CompletionResult)
}
