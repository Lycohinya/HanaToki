package com.tinyyana.hanatoki.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 最小訊息外部化(CLAUDE.md「玩家可見文字進 messages 設定檔」)。
 *
 * 用 MiniMessage 而不是 legacy `§`:MiniMessage 隨 `paper-api` 傳遞依賴進來(LycoLib 也是
 * 這樣用的,零額外宣告),而全 repo 的玩家文字慣例就是 MiniMessage + hex 色(不用原版 16 色名)。
 * 刻意**不**接 LycoLib 的 `Messages`——HanaToki 是可能開源的通用引擎,LycoLib 只是 softdepend
 * (ARCH §11),為了一個 `{token}` 取代拉一條新依賴不划算。
 *
 * ## 內容層的文字放哪
 *
 * 引擎自帶的 `messages.yml` 只放引擎本身的 `session.*` 與內建 probe 副本的文字。**正式副本的
 * 台詞/演出文字屬 integration 插件**(ARCH §11),由它自己的資源檔載入後呼叫 [merge] 併進來
 * ——這樣 `ctx.message("kagerou.line-1")` 這種寫法對引擎自帶與內容層的 key 一視同仁,content
 * 不需要自己拿一套平行的文字 API。
 */
class Texts {
    private var config = YamlConfiguration()

    /** integration 併進來的內容層文字(key -> MiniMessage 原文)。查詢時優先於引擎自帶的檔案。 */
    private val overlay = ConcurrentHashMap<String, String>()

    fun reload(file: File) {
        config = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    /**
     * 併入內容層文字。key 衝突時後併入的覆蓋先前的;重複呼叫是冪等的(重載內容插件時直接再併一次)。
     * 簽章只有 JDK 型別,可以安全地被別的插件呼叫。
     */
    fun merge(entries: Map<String, String>) {
        overlay.putAll(entries)
    }

    /** 移除某個 key 前綴底下的內容層文字(內容插件 onDisable 時收乾淨,避免熱插拔後殘留舊文案)。 */
    fun removeByPrefix(prefix: String) {
        overlay.keys.removeIf { it.startsWith(prefix) }
    }

    fun rawOrNull(key: String): String? = overlay[key] ?: config.getString(key)

    /**
     * 找不到 key 時回傳 key 本身(而不是空字串)——測試與 L4 一眼就看得出是漏了文案,
     * 不會變成「玩家看到一片空白但沒人發現」。
     *
     * [params] 是 `{token}` 取代,在 MiniMessage 解析**之前**做:所有參數都是引擎自己產生的
     * (數字、config 裡的顯示名),不是玩家輸入,不做標籤轉義。
     */
    fun format(key: String, params: Map<String, String>): Component {
        var text = rawOrNull(key) ?: return Component.text(key)
        for ((k, v) in params) text = text.replace("{$k}", v)
        return MINI.deserialize(text)
    }

    /** 沒有參數的常見情況(絕大多數文案沒有 token)。 */
    fun format(key: String): Component = format(key, emptyMap())

    private companion object {
        val MINI: MiniMessage = MiniMessage.miniMessage()
    }
}
