package com.tinyyana.hanatoki.stage

/**
 * ARCH §2「Stage」:狀態機節點的靜態定義(YAML)。決策邏輯(哪個 interaction 該轉去哪個
 * stage、什麼算「正解」)不在這裡——那是 content 的事,由 [DungeonBehavior] 的 Kotlin
 * 擴充點實作(ARCH「不做巨大 DSL」的邊界)。這裡只描述「有哪些 stage、逾時去哪」。
 */
data class StageDefinition(
    val id: String,
    val timeoutSeconds: Long? = null,
    val timeoutTransition: String? = null,
)

data class StageGraph(
    val startStage: String,
    val stages: Map<String, StageDefinition>,
) {
    fun stage(id: String): StageDefinition = stages[id]
        ?: throw IllegalArgumentException("stage 圖沒有 id=$id")
}
