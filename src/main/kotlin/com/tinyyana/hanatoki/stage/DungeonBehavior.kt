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

    /**
     * actor/ 模組:某位 actor 綁定的實體死亡(Boss 型 actor 的勝利判定入口)。
     * callback 已在 anchor region 序列化執行,同其他 callback。
     */
    fun onActorDeath(ctx: StageContext, actorId: String) {}

    /** encounter/ 模組:全員離場或判定失敗導致的戰鬥失敗(v1 combat-test 用不到,先留 hook)。 */
    fun onEncounterFailed(ctx: StageContext, encounterId: String) {}

    /**
     * 某位成員的進場交易**整個做完**了(傳送落地、局內背包已換好)。
     *
     * 存在理由:`onStageEnter` 在 session 建立的當下就跑,那時人還沒傳送、局內背包也還沒清——
     * 在那裡發起始武器會被接下來的清空吃掉。要往局內背包放東西(起始武器、內容層自己鑄的
     * 局內物品)一律等這個回呼。常駐副本每個走進來的人都會各收到一次。
     */
    fun onMemberReady(ctx: StageContext, playerId: UUID) {}

    /**
     * 這一局結束了(任何原因:Resolution、逾時、全員離場、admin reset、關服)。
     * [reason] 是 [com.tinyyana.hanatoki.instance.EndReason] 的常數名。
     *
     * 此時引擎已經收掉 encounter/actor/prop/排程,場地回滾**還沒**開始;內容層在這裡清自己的
     * 登記表(Director 狀態、怪物表、玩家資源 budget)。不要在這裡再對世界做事。
     * 常駐副本(永不結束)只會在關服時收到。
     */
    fun onSessionEnd(ctx: StageContext, reason: String) {}
}

object DungeonBehaviorRegistry {
    private val behaviors = mutableMapOf<String, DungeonBehavior>()

    fun register(dungeonId: String, behavior: DungeonBehavior) {
        behaviors[dungeonId] = behavior
    }

    fun get(dungeonId: String): DungeonBehavior? = behaviors[dungeonId]
}
