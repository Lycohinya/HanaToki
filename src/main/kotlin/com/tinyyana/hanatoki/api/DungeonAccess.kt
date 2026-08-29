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
 * ⚠ 簽章 primitive-only(UUID/String/Boolean),過 `tools/check-cross-plugin-kotlin.py` 的紅線。
 */
interface DungeonAccess {

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
}
