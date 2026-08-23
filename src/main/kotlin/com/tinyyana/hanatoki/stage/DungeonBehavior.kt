package com.tinyyana.hanatoki.stage

import java.util.UUID

/**
 * ARCH §3「Kotlin extension point」:內容特定的判定邏輯(「正解序列是什麼」「錯解要不要送
 * ambush」)寫在這裡,一個 dungeonId 對應一個實作,在 [DungeonBehaviorRegistry] 註冊。
 * 引擎本身(StageEngine)只知道「轉發事件給 behavior,behavior 呼叫 ctx 上的方法產生效果」,
 * 不內建任何「怎樣算正解」的通用規則——這是刻意的,對照 ARCH「不做巨大 DSL」。
 *
 * 所有 callback 都保證已經在 `InstanceDispatch.submit()` 序列化的執行緒上執行(ARCH §5.1②)。
 * callback 內只准呼叫 [ctx] 上的方法(它們各自知道要不要另外派工到③);**不得**直接碰
 * Bukkit 的 `setBlock`/`spawnEntity`/entity API。
 */
interface DungeonBehavior {
    fun onStageEnter(ctx: StageContext, stageId: String) {}
    fun onStageExit(ctx: StageContext, stageId: String) {}
    fun onStageTimeout(ctx: StageContext, stageId: String) {
        ctx.resolve("timeout")
    }

    /** 玩家觸發一個具名 interaction(方塊右鍵/踩踏板/實體互動——由 registry 決定物理綁定)。 */
    fun onInteraction(ctx: StageContext, interactionId: String, playerId: UUID) {}

    /** 一次 check 聚合完成後的 outcome(ARCH §9:success/fail/crit/fumble 皆可作 trigger 條件)。 */
    fun onCheckOutcome(ctx: StageContext, checkId: String, outcome: String) {}

    /** encounter/ 模組:這一波敵人全滅。 */
    fun onEncounterCleared(ctx: StageContext, encounterId: String) {}

    /** encounter/ 模組:全員離場或判定失敗導致的戰鬥失敗(v1 combat-test 用不到,先留 hook)。 */
    fun onEncounterFailed(ctx: StageContext, encounterId: String) {}
}

object DungeonBehaviorRegistry {
    private val behaviors = mutableMapOf<String, DungeonBehavior>()

    fun register(dungeonId: String, behavior: DungeonBehavior) {
        behaviors[dungeonId] = behavior
    }

    fun get(dungeonId: String): DungeonBehavior? = behaviors[dungeonId]
}
