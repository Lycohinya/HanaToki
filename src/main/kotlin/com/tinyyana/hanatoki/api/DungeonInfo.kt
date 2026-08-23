package com.tinyyana.hanatoki.api

/**
 * ARCH §10「UI 只提供資料能力」/ Phase 2 目標 §7:未來 GUI 需要的最小資料集,primitive-only
 * (跨插件安全,雖然目前只有 HanaToki 自己的 `/hanatoki admin debug` 消費它)。
 */
data class DungeonInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val expectedMinutes: String,
    val tags: List<String>,
    val freeSlots: Int,
    val totalSlots: Int,
    val activeSessionCount: Int,
) {
    val available: Boolean get() = freeSlots > 0
}
