package com.tinyyana.hanatoki.check

/**
 * ARCH §2「Check」的聚合方式。純邏輯、不碰任何骰值——只對一串 outcome 字串計數。
 *
 * `AVERAGE`(架構文件 §2 提到的「平均」)刻意不做:outcome 是不透明字串
 * (`"success"`/`"crit"`/`"fumble"`...),沒有數值可以平均;要做「平均」必須讓 HanaToki
 * 認識骰值總分,直接違反「HanaToki 不知道 D20 是什麼」的邊界。目前唯一的現況案例
 * (副本破防)是「過半成功」,MAJORITY 已覆蓋——AVERAGE 沒有具體需求前不猜測性設計,
 * 留在 `docs/OPEN_QUESTIONS.md` 供未來若真的出現需求再定義它該聚合什麼。
 */
enum class CheckAggregation { INDIVIDUAL, MAJORITY }

object CheckAggregator {
    /**
     * [outcomes] 是每位參與者的 outcome key(呼叫端已經把 outcome 分類成「算成功」與否 —
     * 這裡只認 [isSuccess] 判準,不假設哪個字串代表成功,因為不同 check 定義可能有不同的
     * 成功語彙,由呼叫端(behavior 實作)決定)。
     *
     * MAJORITY:成功人數 * 2 >= 總人數(比照現況 `DungeonBossController.triggerBreakCheck` 的
     * 「過半」判準,`successes * 2 >= players.size`)。
     */
    fun aggregate(aggregation: CheckAggregation, outcomes: Map<java.util.UUID, String>, isSuccess: (String) -> Boolean): Boolean {
        if (outcomes.isEmpty()) return false
        return when (aggregation) {
            CheckAggregation.INDIVIDUAL -> outcomes.values.all(isSuccess)
            CheckAggregation.MAJORITY -> outcomes.values.count(isSuccess) * 2 >= outcomes.size
        }
    }
}
