package com.tinyyana.hanatoki.api

/**
 * 一次進場交易**真的做完之後**的結果(ARCH §5.2 規則 3「必須處理失敗分支」)。
 *
 * ## 為什麼需要它
 *
 * 舊的 [DungeonAccess.enterDungeon] 回 `true` 只代表「請求受理」:session 分到了、
 * `teleportAsync` 送出去了,但傳送成不成功沒人知道。傳送失敗時玩家還站在主世界,
 * `PresenceBridge.isInside` 卻已經回 true,之後的獎勵/廣播都把他算成在場——這個缺陷
 * 在引擎自己的 `docs/API.md` 就寫著,不是新發現。
 *
 * 這支型別把「請求受理」跟「真的進去了」分開:拿到 [succeeded] = true 的那一刻,玩家人
 * 確實在場地上、局內背包已經生效、Run 才算 ACTIVE。
 *
 * ## 型別邊界
 *
 * 只有 `String`/`Boolean` 的 getter,沒有 Kotlin 專屬型別、沒有預設參數(ARCH §4.0)。
 * `sessionId`/`instanceId` 用 `String` 而不是 `UUID` 是為了讓反射消費端(LycohinyaCore 走
 * 反射,不對 HanaToki 建編譯期依賴)不需要處理 null 型別轉換。
 */
interface DungeonEntryOutcome {

    /**
     * 結果碼。成功兩種、失敗六種,全部是穩定字串常數(見 [DungeonEntryStatus]):
     *
     * - `ENTERED` — 新開了一局,人已落地。
     * - `JOINED` — 加入了一個正在跑的常駐副本,人已落地。
     * - `NO_DUNGEON` — 沒有這座副本。
     * - `NO_SLOT` — 客滿。
     * - `PLAYER_OFFLINE` — 玩家不在線(或進場途中登出)。
     * - `TELEPORT_FAILED` — `teleportAsync` 回 false 或 exceptional。
     * - `INVENTORY_FAILED` — 局內背包交易失敗(journal 寫不進去之類)。
     * - `SHUTTING_DOWN` — 引擎正在停用,不再受理新進場。
     * - `ALREADY_INSIDE` — 已經在某座副本的 session 裡,先完成/撤離才可再進(2026-09-01)。
     */
    fun status(): String

    fun succeeded(): Boolean

    /** 成功時是這一局的 sessionId;失敗時 null。 */
    fun sessionId(): String?

    /** 有開局內背包時是這次交易的 instanceId;沒開或失敗時 null。 */
    fun instanceId(): String?

    /** 失敗原因(給 log/管理員看的中文說明);成功時 null。 */
    fun failureReason(): String?

    /**
     * 失敗時,已經建立的狀態是不是都清乾淨了(session 成員資格、slot 佔用、stage 狀態、
     * bossbar/排程、返回點登記、局內背包交易)。
     *
     * **失敗時這裡應該恆為 true**;false 代表回滾本身也出事了,呼叫端該把它當成需要人工
     * 介入的事件記下來,而不是只顯示一句「進場失敗」。成功時無意義,固定 false。
     */
    fun rolledBack(): Boolean
}

/** [DungeonEntryOutcome.status] 的字串常數。跨插件消費端請比對這些值,不要自己拼字串。 */
object DungeonEntryStatus {
    const val ENTERED = "ENTERED"
    const val JOINED = "JOINED"
    const val NO_DUNGEON = "NO_DUNGEON"
    const val NO_SLOT = "NO_SLOT"
    const val PLAYER_OFFLINE = "PLAYER_OFFLINE"
    const val TELEPORT_FAILED = "TELEPORT_FAILED"
    const val INVENTORY_FAILED = "INVENTORY_FAILED"
    const val SHUTTING_DOWN = "SHUTTING_DOWN"
    const val ALREADY_INSIDE = "ALREADY_INSIDE"
}
