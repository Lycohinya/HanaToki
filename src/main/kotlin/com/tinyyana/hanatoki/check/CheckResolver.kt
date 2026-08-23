package com.tinyyana.hanatoki.check

import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * ARCH §4/§9:HanaToki 是這個 port 的**消費者**——integration(LycoHanaToki)註冊實作,轉呼叫
 * Core 的 `ResonanceCheckBridge`。HanaToki 完全不知道 D20/DC 是什麼,只認 outcome 字串。
 *
 * `CompletableFuture` 是 JDK 型別,不是 Kotlin 專屬型別,過 `check-cross-plugin-kotlin.py` 的
 * 「公開簽章只准 UUID/String/JDK/primitive」紅線——`fun interface` 本身也是 Kotlin 語法糖,
 * 但編出來就是一個普通 SAM interface,同 repo 既有先例(`RewardGrantListener`)已這樣用。
 *
 * 不阻塞 region thread:實作端必須自行把耗時工作丟到合適的執行緒,回傳的 future 在任意執行緒
 * complete 都可以——呼叫端（HanaToki 的 stage/check 模組）收到結果後一律再 `instance.submit()`
 * 一次才推進狀態機,不假設回呼在哪個執行緒上。
 */
fun interface CheckResolver {
    /** 回傳 outcome key,例如 `"success"` / `"fail"` / `"crit"` / `"fumble"`。 */
    fun resolve(playerId: UUID, checkId: String): CompletableFuture<String>
}

/**
 * UI 用的檢定說明(DC、白話描述)。與 [CheckResolver] 分開註冊,因為它是同步查詢——
 * 描述文字不需要非同步計算,合併成一支介面只會逼呼叫端多包一層 future。
 */
fun interface CheckDescriptor {
    fun describe(checkId: String): String
}
