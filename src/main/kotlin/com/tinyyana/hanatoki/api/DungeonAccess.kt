package com.tinyyana.hanatoki.api

import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * 「把某個玩家送進/送出某座副本」的對外入口(ServicesManager 註冊,provider 是 HanaToki)。
 *
 * 存在理由:蒼櫻遷移之後,玩家看到的入口仍然是 `LycohinyaCore` 的 `/lyco dungeon` 選單
 * (那份選單有描述、Boss 說明、花蜜額度三色狀態、破防檢定四行教學,全部是 Lycohinya 的內容,
 * 不該搬進通用引擎)。Core 需要一條「按下去之後真的把人送進去」的線,而 Core **不對 HanaToki
 * 建編譯期依賴**(它是可能開源的獨立 repo)——所以跟 [PresenceBridge] 一樣走 ServicesManager +
 * 反射查詢(Core 端見 `integration/HanaTokiDungeonBridge`)。
 *
 * 跟 [PresenceBridge] 分成兩支介面而不是合併:那支的語意是**唯讀查詢**(音樂/顯示層在用,
 * 每次玩家移動都可能問),這支會改變世界狀態。合起來的話唯讀消費端會拿到一支能傳送玩家的把手。
 *
 * ⚠ 簽章只用 JDK 型別(UUID/String/Boolean,以及 `List`/`Map`——Kotlin 的 `List`/`Map` 在 bytecode
 * 就是 `java.util.List`/`java.util.Map`,是跨 classloader 安全的 mapped type),不用 Kotlin 專屬型別
 * (`Pair`、function type、`Result`、預設參數),過 `tools/check-cross-plugin-kotlin.py` 的紅線。
 */
interface DungeonAccess {

    /**
     * Current content catalog. JDK values only; callers may overlay their own menu presentation.
     *
     * 每列的 key:`id`、`display`、`description`、`world`、`party-cap`(十進位字串、≥1,
     * 來源是 `dungeons.yml` 的 `party-cap`;組隊入口用它決定這座副本一次最多收幾位)。
     */
    fun listDungeons(): List<Map<String, String>>

    /** 這個 id 有沒有對應的副本定義(選單要不要顯示「目前無法進入」)。 */
    fun hasDungeon(dungeonId: String): Boolean

    /**
     * 把玩家送進副本。回傳 false = 沒這座副本、客滿、或玩家不在線(呼叫端自己決定訊息)。
     *
     * 回傳 true 只代表「進場請求已受理」——傳送在 Folia 上一律是非同步的,同 Core 既有的
     * `DungeonService.enter` 語意。
     */
    fun enterDungeon(playerId: UUID, dungeonId: String): Boolean

    /**
     * 把兩個玩家一起送進副本(2026-08-24 新增:刀塚 `party-cap: 2` 的兩人組隊入口)。
     * 兩人其中一個不在線 / 副本不存在 / 沒有空位都回 false,呼叫端自己決定訊息。
     *
     * 刻意是雙人專用簽章而不是 `List<UUID>`——目前唯一用得到的副本(刀塚)party-cap 就是 2,
     * 沒有第三人以上的實際案例;真的出現才擴,不猜測性先做泛型 party API(ARCH §12)。
     */
    fun enterDungeonDuo(playerId: UUID, partnerId: UUID, dungeonId: String): Boolean

    /**
     * 把三個玩家一起送進副本(2026-09-02 新增:深域 `party-cap: 3` 的組隊入口)。
     * 任一人不在線 / 副本不存在 / 沒有空位都回 false,呼叫端自己決定訊息。
     *
     * 當時同 [enterDungeonDuo] 的理由只加 3 人簽章(深域)。1 人走 [enterDungeon]、2 人走
     * [enterDungeonDuo]、3 人走這支;任意人數的 tracked 版見 [enterDungeonPartyTracked]。
     */
    fun enterDungeonTrio(playerId: UUID, partner1Id: UUID, partner2Id: UUID, dungeonId: String): Boolean

    /** 把玩家送出他所在的副本(回進場前的位置,沒登記就回重生點/第一個非副本世界)。 */
    fun leaveDungeon(playerId: UUID): Boolean

    /**
     * 進場,而且**等交易真的做完**才 complete(2026-08-29 新增)。
     *
     * 跟 [enterDungeon] 的差別就是那個 `Boolean` 沒辦法表達的東西:傳送到底成不成功、
     * 失敗的原因是什麼、已經建立的狀態有沒有清乾淨。要顯示「進場失敗,原因是…」或要在
     * 進場成功之後才做下一步(發訊息、記錄、開 UI)的呼叫端,用這一支。
     *
     * [enterDungeon] 沒有被取代也沒有改語意——既有呼叫端(LycohinyaCore 的 `/lyco dungeon`
     * 走反射)不需要重編。
     *
     * ⚠ 回傳的 future 在**任意執行緒** complete(內部串了 `teleportAsync` 與非同步 I/O)。
     * 接著要對玩家做事的話,自己派回該玩家的 scheduler(ARCH §5.2 規則 2)。
     */
    fun enterDungeonTracked(playerId: UUID, dungeonId: String): CompletableFuture<DungeonEntryOutcome>

    /** [enterDungeonTracked] 的雙人版(語意全有全無:一個人失敗兩個人都不進去)。 */
    fun enterDungeonDuoTracked(
        playerId: UUID,
        partnerId: UUID,
        dungeonId: String,
    ): CompletableFuture<DungeonEntryOutcome>

    /**
     * [enterDungeonTracked] 的三人版(語意全有全無:任何一人失敗三個人都不進去)。
     *
     * 1 人走 [enterDungeonTracked]、2 人走 [enterDungeonDuoTracked]、3 人走這支。名單檢查
     * (空、重複、超過 party-cap、離線、已在局內、pending、內容資格)由進場最終 admission
     * 負責,失敗以 [DungeonEntryStatus] 的 `PARTY_INVALID`/`PLAYER_OFFLINE`/`ALREADY_INSIDE`/
     * `NOT_ELIGIBLE` 回報,且**不留任何狀態**(沒取 slot、沒動背包)。
     */
    fun enterDungeonTrioTracked(
        playerId: UUID,
        partner1Id: UUID,
        partner2Id: UUID,
        dungeonId: String,
    ): CompletableFuture<DungeonEntryOutcome>

    /**
     * [enterDungeonTrioTracked] 的任意人數版(1.0.0-rc.17 新增:Boss 1–4 人的組隊入口)。
     * 語意與 Trio 相同:全有全無,名單檢查由進場最終 admission 負責,失敗以
     * `PARTY_INVALID`(空、重複、超過該副本 `party-cap`)/`PLAYER_OFFLINE`/`ALREADY_INSIDE`/
     * `NOT_ELIGIBLE` 回報,且**不留任何狀態**。
     *
     * - [players] 在呼叫當下複製;順序保留(第一位是隊長,與 Duo/Trio 的第一個參數相同)。
     *   元素必須非 null。
     * - 有人不在線時,status 依 admission 的檢查順序決定:名單本身不合法(空、重複、超過
     *   `party-cap`)先回 `PARTY_INVALID`,否則 `PLAYER_OFFLINE`(原因帶玩家名字)。
     * - 跨插件型別:參數 bytecode 是 `java.util.List<java.util.UUID>`,Java 呼叫端可直接傳
     *   `java.util.List.of(...)`/`ArrayList`。
     * - future 在**任意執行緒** complete,同 [enterDungeonTracked]。
     */
    fun enterDungeonPartyTracked(players: List<UUID>, dungeonId: String): CompletableFuture<DungeonEntryOutcome>

    /**
     * 這位玩家是不是還有「沒收斂完」的進場相關狀態:進行中的進場交易,或失敗/結束後的回滾、
     * 局內背包保管歸還、送出場地還沒完成。true 時再進場一定會被 admission 以 `ALREADY_INSIDE`
     * 擋下,呼叫端(組隊大廳)應該等它變 false 再重試或拆隊。
     *
     * 只讀、無鎖,任何執行緒可呼叫。plugin 停用時所有登記清空。
     */
    fun isEntryPending(playerId: UUID): Boolean

    /**
     * 以引擎自己的 admission 預檢單一玩家:回傳玩家可讀的繁中原因,null = 目前沒有擋。
     *
     * 只涵蓋引擎層條件(副本不存在/停收、不在線、已在局內、pending)與內容資格(所有
     * [EntryEligibility] provider 的回答,任一擋下就回它的原因);
     * **只讀**,不扣料、不保留名額,所以通過預檢不保證進得去(最終 admission 在進場交易內
     * 重查,客滿也只在那時才知道)。
     */
    fun entryBlockReason(playerId: UUID, dungeonId: String): String?
}
