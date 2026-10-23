package com.tinyyana.hanatoki.content

import java.util.UUID

/**
 * rc.25:誰收到過目標列(objective)。目標列走 ActionBar 的 `hanatoki.stage` 組，LycoLib 會讓最後一則停留到
 * duration 結束(約 1.5 秒)，所以局結束、成員離場、換到沒有目標的 stage 時要主動把動作列清成空白；
 * 這個類別只負責「該清誰」，清除本身(玩家執行緒上送空白)在 SessionRun。只在 session 的 anchor region 執行緒使用。
 */
class ObjectiveHudTracker {
    private val shown = LinkedHashSet<UUID>()

    fun shown(id: UUID) { shown += id }

    /** 這位成員離場:回傳他是否收過目標列(要清)。 */
    fun forget(id: UUID): Boolean = shown.remove(id)

    /** 全部清掉並回傳要清的人(局結束、換到沒有目標的 stage)。 */
    fun drain(): List<UUID> {
        val all = shown.toList()
        shown.clear()
        return all
    }

    val size: Int get() = shown.size
}
