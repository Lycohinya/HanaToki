package com.tinyyana.hanatoki.hud

import com.tinyyana.lycoLib.api.hud.HudBridge
import com.tinyyana.lycoLib.api.hud.HudNotification
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * 經 LycoLib HudBridge 排隊的出口。**只有 LycoLib 已啟用時才可以建構**(見 [ActionBars.select]),
 * 否則這個類別的載入會因找不到 LycoLib 的型別而失敗。
 *
 * 所有副本 ActionBar 共用同一個 group [GROUP],刻意不拆「每秒狀態」與「一次性事件」兩組:
 * ActionBar 只有一格、通知不插隊,拆成兩組的話,每秒刷新的狀態會一直佔著那一格,一次性事件
 * (階段切換、拒絕提示)要排到狀態停止刷新才輪得到,等於被餓死。同組取代則是最新的一則直接
 * 原位換上並重新計時,與直送時「最後一則贏」的行為一致。
 *
 * 優先級與停留時間在 LycoLib 的 hud.yml `groups: hanatoki.stage`(低於 COMBAT,所以居合等技能提示蓋得過它)。
 */
class LycoHudActionBarSink(private val fallback: ActionBarSink = DirectActionBarSink) : ActionBarSink {
    override fun show(player: Player, text: Component) {
        val bridge = HudBridge.get()
        // LycoLib 啟用了但 bridge 沒裝(hud.yml 讀不進來)或已停用:Unavailable 會全部略過,副本提示會憑空消失,改直送
        if (bridge === HudBridge.Unavailable) {
            fallback.show(player, text)
            return
        }
        bridge.notify(player, HudNotification(GROUP, text))
    }

    companion object {
        /** 對應 LycoLib hud.yml 的 groups 鍵。 */
        const val GROUP = "hanatoki.stage"
    }
}
