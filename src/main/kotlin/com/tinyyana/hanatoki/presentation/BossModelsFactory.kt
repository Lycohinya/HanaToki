package com.tinyyana.hanatoki.presentation

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * 建立 [BossModels] 的實作——BetterModel 沒裝/沒啟用就回 [NoOpBossModels]。
 *
 * 這個檔案刻意**不 import** `presentation.bettermodel.BetterModelBossModels`(那個類別的
 * package 裡才 import BetterModel 的 class,見它的 KDoc)——下面用完整路徑呼叫它的建構子,
 * 而且整段包在 `isPluginEnabled` 成立才會執行到的分支裡。JVM 的 class-loading 是 lazy 的:
 * `new` 這行 bytecode 只有真的被執行到才會觸發那個 class 的載入/驗證,BetterModel 沒裝時這裡
 * 永遠不會執行到那一行,`BetterModelBossModels` 的 class 也就永遠不會被載入——不會因為它的
 * 方法簽章裡有 BetterModel 的型別就在插件啟動時整個炸掉。
 */
object BossModelsFactory {
    fun create(plugin: Plugin): BossModels {
        if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel")) return NoOpBossModels
        return runCatching { com.tinyyana.hanatoki.presentation.bettermodel.BetterModelBossModels(plugin) }
            .onFailure { plugin.logger.warning("[HanaToki] BetterModel 偵測到但初始化失敗,退回無外觀模式:${it.message}") }
            .getOrDefault(NoOpBossModels)
    }
}
