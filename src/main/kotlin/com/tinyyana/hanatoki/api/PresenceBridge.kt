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
}
