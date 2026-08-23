package com.tinyyana.hanatoki.text

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * 最小訊息外部化(CLAUDE.md「玩家可見文字進 messages 設定檔」)。刻意不接 LycoLib
 * `Messages`/MiniMessage——HanaToki 是可能開源的通用引擎,LycoLib 是 softdepend(ARCH §11),
 * 一個純 YamlConfiguration + `{token}` 取代就夠用,不為此拉一條新依賴。
 */
class Texts {
    private var config = YamlConfiguration()

    fun reload(file: File) {
        config = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    fun format(key: String, vararg params: Pair<String, String>): String {
        var text = config.getString(key) ?: return key
        for ((k, v) in params) text = text.replace("{$k}", v)
        return text
    }
}
