package com.tinyyana.hanatoki.reward

import java.util.UUID

/**
 * 內容層寫進 [com.tinyyana.hanatoki.stage.InstanceState.stats] 的統計鍵約定(1.0.0-rc.30)。
 *
 * 結算時同一次結算的所有成員共用同一份 stats 快照,所以「每位玩家各自打散幾隻」要靠逐人鍵:
 * `<stat>` 是整局合計,`<stat>.<玩家 UUID>` 是那位玩家自己的數字。消費端用 [CompletionResult.statFor] 取值。
 */
object RunStats {
    /** 玩家閃光後打散、或被 powered 的成員碰散的敵人數(光圈與 stage 清場不算)。資料驅動內容(`SessionRun`)自動記錄。 */
    const val DISPERSED = "dispersed"

    fun playerKey(stat: String, playerId: UUID): String = "$stat.$playerId"

    /** 整局合計與逐人鍵各 +1,回傳這位玩家累計到第幾個。 */
    fun record(stats: MutableMap<String, Long>, stat: String, playerId: UUID): Long {
        stats[stat] = (stats[stat] ?: 0L) + 1L
        val key = playerKey(stat, playerId)
        val mine = (stats[key] ?: 0L) + 1L
        stats[key] = mine
        return mine
    }
}
