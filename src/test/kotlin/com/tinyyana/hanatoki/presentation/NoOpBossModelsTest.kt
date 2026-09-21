package com.tinyyana.hanatoki.presentation

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NoOpBossModels] 是 BetterModel 沒裝/沒啟用時的退回實作(見 [BossModelsFactory])——
 * 不碰 `bind`(它的參數需要真的 Bukkit `Entity`,不在單元測試範圍內,`available = false`
 * 已經保證它一律回 null,見它的實作),只驗證其餘方法對「BetterModel 不存在」這個狀態
 * 安全地什麼都不做。
 */
class NoOpBossModelsTest {
    @Test
    fun `available 是 false`() {
        assertFalse(NoOpBossModels.available)
    }

    @Test
    fun `closeOwner closeForEntity closeAll 都不會丟例外`() {
        NoOpBossModels.closeOwner("session-1")
        NoOpBossModels.closeForEntity(UUID.randomUUID())
        NoOpBossModels.closeAll()
    }

    @Test
    fun `modelIds 與 animationNames 回空集合`() {
        assertTrue(NoOpBossModels.modelIds().isEmpty())
        assertTrue(NoOpBossModels.animationNames("pandora_echo").isEmpty())
    }
}
