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
    /**
     * 進入 stage **之前**要先準備好的東西(場地、必要的 chunk)。引擎在把玩家傳進來之前會等
     * 這個 future 完成(見 [com.tinyyana.hanatoki.instance.DungeonEntry]);完成後才呼叫
     * [onStageEnter]。失敗(exceptional)= 這一局進不了,進場交易整筆回滾。
     *
     * 2026-09-03 正式服:深域開場重蓋場地要 2~5 秒,而玩家在 `onStageEnter` 一發出去就被傳進
     * 還沒有地板的虛空——自由落體等場地生成。把「蓋場地」放在這裡、傳送放在它之後,就是修法。
     * 預設立即完成:既有副本(刀塚、蒼櫻)不需要改。
     */
    fun prepareStage(ctx: StageContext, stageId: String): java.util.concurrent.CompletableFuture<Void> =
        java.util.concurrent.CompletableFuture.completedFuture(null)

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

    /**
     * 某位成員**個別**離開了這一局,但 session 本身還活著(還有別的成員在場)。
     * [reason] 是自由字串(目前用得到的:`"death"` 死亡結算離場、`"kick"` 主動/管理員退出)。
     *
     * 存在理由:多人副本裡一人死亡/離開不該連坐結束其他人的 run(見
     * [com.tinyyana.hanatoki.HanaTokiCore.handlePlayerDeath] 的多人分支)——但引擎已經把這個人
     * 從 [StageContext.activeMembers] 移除、背包也已還原送回家,內容層要在這裡清自己那份平行的
     * 成員登記表(Director 狀態、玩家資源 budget),不然它會繼續把一個已經不在場的人算進 HUD/
     * 事件路由。呼叫時 [ctx] 的 `activeMembers()` 已經**不含** [playerId]。
     *
     * 跟 [onSessionEnd] 的差別:那個是「全部人都沒了,session 本身收掉」;這個是「少一個人,
     * session 繼續」。全員逐一死亡耗盡的情況下,最後一人一樣會走 [onSessionEnd]
     * (`kick()` 對最後一位成員回傳的是 `EndedSession`,不是 no-op),不會漏收。
     */
    fun onMemberLeft(ctx: StageContext, playerId: UUID, reason: String) {}
}

object DungeonBehaviorRegistry {
    private val behaviors = mutableMapOf<String, DungeonBehavior>()

    fun register(dungeonId: String, behavior: DungeonBehavior) {
        behaviors[dungeonId] = behavior
    }

    fun get(dungeonId: String): DungeonBehavior? = behaviors[dungeonId]
}
