package com.tinyyana.hanatoki.api

import java.util.UUID

/**
 * ARCH §4:HanaToki 是這個 port 的**提供者**(不是消費者)——MusicService/選單等外部插件
 * 透過 `ServicesManager` 拿到這個實作查詢。簽章 primitive-only(UUID/String/boolean),
 * 過 `tools/check-cross-plugin-kotlin.py` 的跨插件紅線檢查。
 */
interface PresenceBridge {
    fun isInside(playerId: UUID): Boolean
    fun dungeonIdOf(playerId: UUID): String?

    /**
     * 這位玩家的 session 落在哪一個世界(副本定義的 `world`);沒有 session 就是 null。
     *
     * ⚠ [isInside] 從 session 建立的那一刻起就是 true,而**傳送是進場交易的第 6 步**
     * (見 `instance/DungeonEntry` 的 KDoc):等場地蓋好的那幾秒裡,玩家還站在主世界,
     * `isInside` 卻已經回 true。要判斷「人真的到了沒」的呼叫端(背景音樂就是一例)必須
     * 拿這個世界名跟玩家當下的世界比,不能只問 [isInside]。
     */
    fun worldNameOf(playerId: UUID): String?
}
