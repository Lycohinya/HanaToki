package com.tinyyana.hanatoki.stage

import java.util.UUID

/**
 * ARCH §2/§8:一次 Instance 的內容邏輯狀態(現在在哪個 stage、trigger 冷卻/one-shot 旗標、
 * content 自訂的謎題狀態)。純邏輯、不碰 Bukkit——這是能被單元測試直接打的部分
 * (StageMachine 的行為驗證不需要真伺服器)。
 *
 * ⚠ 這個物件本身**不是** thread-confined 的保證來源:所有會呼叫這裡任何 mutator 的地方
 * 必須已經在 `InstanceDispatch.submit()` 裡面(ARCH §5.1②),同一個道理套用在 Phase 1 的
 * [com.tinyyana.hanatoki.instance.Session]。
 */
class InstanceState(val graph: StageGraph) {
    var currentStageId: String = graph.startStage
        private set

    private var stageEnteredAtMs: Long = 0L
    private val firedOnce = mutableSetOf<String>()
    private val lastFiredAtMs = mutableMapOf<String, Long>()
    private val bag = mutableMapOf<String, Any?>()

    fun stage(): StageDefinition = graph.stage(currentStageId)

    fun enterStage(stageId: String, nowMs: Long) {
        currentStageId = stageId
        stageEnteredAtMs = nowMs
        // one-shot 旗標是「stage 內」的一次性,不是整局一次性——重進同一 stage(例如錯解重置後
        // 迴到 puzzle stage)要能重新觸發同一批 trigger(ARCH §8 案例:燈重置後可以再點）。
        firedOnce.clear()
        lastFiredAtMs.clear()
    }

    fun stageElapsedMs(nowMs: Long): Long = nowMs - stageEnteredAtMs

    fun isStageTimedOut(nowMs: Long): Boolean {
        val limit = stage().timeoutSeconds ?: return false
        return stageElapsedMs(nowMs) >= limit * 1000
    }

    /** one-shot trigger:第一次呼叫回 true 並標記已觸發,之後同一 stage 內都回 false(冪等)。 */
    fun tryFireOnce(triggerId: String): Boolean {
        if (triggerId in firedOnce) return false
        firedOnce += triggerId
        return true
    }

    /** repeatable trigger:過了冷卻才回 true 並重記時間戳。 */
    fun tryFireRepeatable(triggerId: String, cooldownMs: Long, nowMs: Long): Boolean {
        val last = lastFiredAtMs[triggerId]
        if (last != null && nowMs - last < cooldownMs) return false
        lastFiredAtMs[triggerId] = nowMs
        return true
    }

    fun get(key: String): Any? = bag[key]
    fun set(key: String, value: Any?) { bag[key] = value }
    fun remove(key: String) { bag.remove(key) }

    /** 供 behavior 記錄 Resolution 用的統計欄位(給 [com.tinyyana.hanatoki.reward.CompletionResult.stats])。 */
    val stats = mutableMapOf<String, Long>()

    /** 一次 check 的進行中投票箱(多人聚合用),key 是 checkId,value 是 playerId->outcome。 */
    val pendingCheckVotes = mutableMapOf<String, MutableMap<UUID, String>>()
}
