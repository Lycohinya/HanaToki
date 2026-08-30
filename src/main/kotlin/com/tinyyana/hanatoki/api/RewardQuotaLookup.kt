package com.tinyyana.hanatoki.api

import java.util.UUID

/**
 * 「某位玩家在某座副本還剩幾份通關獎勵額度」的唯讀查詢(ServicesManager 註冊)。
 *
 * 存在理由:副本的入口選單住在外部插件(Lycohinya 是 `LycohinyaCore` 的 `/lyco dungeon`),
 * 而額度的數字與算式住在**內容層**(Lycohinya 是 `LycoHanaToki` 的 `rewards.<dungeonId>`)。
 * 選單要顯示「今天進去還有沒有東西拿」就必須問得到那個數字——但**把數字抄一份到選單那邊的
 * 設定檔是錯的**:兩份設定一定會分岔(蒼櫻的 reward parity 已經有前例,見 LycoHanaToki
 * `config.yml` 的 `pale-cherry` 註解)。所以開這條唯讀查詢橋,數字永遠只有一份。
 *
 * ⚠ 引擎本身**不認識**「花蜜」或任何一種貨幣——這裡的語意只是「一種可重複刷的通關獎勵,
 * 一天回一份、沒領的可以存幾天」的漏桶額度,是 [com.tinyyana.hanatoki.reward.RewardSink]
 * 的實作方(內容層)自己定義的。引擎只負責把問題轉給它。
 *
 * ⚠ 簽章 primitive-only(UUID/String/IntArray),過 `tools/check-cross-plugin-kotlin.py` 的
 * 跨插件紅線;**不使用 Kotlin 預設參數**(跨 classloader 呼叫合成出來的 `$default` 橋接方法
 * 會 `NoSuchMethodError`)。回傳用 `IntArray` 而不是 data class,理由同上:跨 classloader
 * 傳自訂型別要兩邊的 Class 物件一致,而消費端刻意不對這個 repo 建編譯期依賴。
 */
interface RewardQuotaLookup {

    /**
     * 查某位玩家在 [dungeonId] 這座副本的通關獎勵額度。
     *
     * @return `null` = 查不到(沒這座副本、內容層沒給它配獎勵規則);否則是長度 2 或 3 的陣列:
     *   - `[0]` = 目前還剩多少額度(已含管理員加值,`0` = 這次進去拿不到獎勵)
     *   - `[1]` = 額度上限
     *   - `[2]` = **選填**的單位旗標:`0` = 以「份」計(每次結算扣一份),`1` = 以獎勵貨幣
     *     本身計(每次結算扣掉實際發放的量)。省略時等同 `0`。
     *
     *   兩格**同時**為 `-1` 代表這座副本不限量(內容層沒設任何閘門)。
     *   呼叫端要嘛照數字顯示、要嘛整段不顯示,不要拿 `-1` 去做算術。
     *
     * ⚠ 第三格是 2026-08-30 後加的(深域改成「一天最多收下 N 花蜜」的總量制)。**只讀前兩格
     *   的舊消費端仍然安全**:內容層只在總量制的副本上多送第三格,次數制的副本回來的長度
     *   仍然是 2,舊消費端讀到的數字與單位跟改動前逐字相同。舊消費端拿到長度 3 時會判定
     *   「語意對不上」而不顯示那一行(顯示不出來,但不會顯示錯的數字),新消費端才讀得懂。
     */
    fun quotaOf(playerId: UUID, dungeonId: String): IntArray?
}
