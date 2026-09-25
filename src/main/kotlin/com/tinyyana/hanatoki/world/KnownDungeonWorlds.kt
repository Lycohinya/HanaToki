package com.tinyyana.hanatoki.world

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * HanaToki 建過的虛空副本世界:名稱 → 是否保留存檔。存在 `known-worlds.yml`,讓引擎在**內容插件
 * 還沒註冊定義之前**就認得自己的世界,開機時搶在 Multiverse 之前用虛空生成器載入它們。
 *
 * 2026-09-25 正式服:刀塚在 Multiverse 的清單裡還留著舊時代「生成器=LycohinyaCore」的登記,Core
 * 9/20 拿掉生成器之後,Multiverse 開機先用原版生成器載入它,副本場地泡進一般地形裡。Multiverse
 * 的 auto-import-3rd-party-worlds 也會把副本世界收編成沒有生成器的登記。不管 Multiverse 裡寫了
 * 什麼,只要世界已經先被這裡用正確的生成器載入,它就換不掉。
 *
 * 出貨版附一份目前各副本世界的清單當種子(第一次開機就有保護);之後每次建立/載入副本世界都會
 * 更新,新副本不用改這份檔。
 */
class KnownDungeonWorlds(private val file: File) {

    /** 名稱 → auto-save。只收虛空世界;指定了地形生成器的世界不在這裡(它們的生成器要等內容插件註冊)。 */
    fun read(): Map<String, Boolean> {
        if (!file.isFile) return emptyMap()
        val yaml = YamlConfiguration.loadConfiguration(file)
        val section = yaml.getConfigurationSection("worlds") ?: return emptyMap()
        return section.getKeys(false).associateWith { section.getBoolean("$it.auto-save", false) }
    }

    fun remember(worldName: String, autoSave: Boolean) = update { it.set("worlds.$worldName.auto-save", autoSave) }

    fun forget(worldName: String) = update { it.set("worlds.$worldName", null) }

    @Synchronized
    private fun update(change: (YamlConfiguration) -> Unit) {
        val yaml = if (file.isFile) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        val before = yaml.saveToString()
        change(yaml)
        if (yaml.saveToString() != before) yaml.save(file)
    }

    companion object {
        /**
         * 不保留存檔的副本世界(每局重蓋場地),載入前清掉的資料夾。只有區塊與實體;`data/`、
         * `paper-world.yml` 等設定不動。
         */
        val CHUNK_DATA = listOf("region", "entities", "poi")
    }
}
