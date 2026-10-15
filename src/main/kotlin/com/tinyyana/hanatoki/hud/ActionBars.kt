package com.tinyyana.hanatoki.hud

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * 副本對玩家 ActionBar 的唯一出口。
 *
 * 為什麼不直接 `player.sendActionBar`:ActionBar 只有一格,居合等時間關鍵的技能提示經 LycoLib 的
 * ActionBarArbiter 仲裁;副本每秒刷新的狀態如果直送,會無視仲裁把它們蓋掉。LycoLib 在的時候改交給
 * HudBridge 排隊(優先級與停留時間由 LycoLib 的 hud.yml 依 group 決定,這裡不放任何數值);
 * LycoLib 不在就維持直送——引擎只有 HanaToki 也要能跑通(ARCH §11)。
 *
 * 這個檔案**不 import** LycoLib:LycoLib 的型別只出現在 [LycoHudActionBarSink],而它只在 [select]
 * 判定 LycoLib 已啟用時才會被建構(同 presentation/BossModelsFactory 的 class-loading guard)。
 */
interface ActionBarSink {
    /** 呼叫端須已在該玩家的 region/entity scheduler 上(或事件就在該玩家的 region 觸發)。 */
    fun show(player: Player, text: Component)
}

/** 直送:LycoLib 不存在,或它的 HudBridge 尚未就緒時的行為(與接入 LycoLib 前相同)。 */
object DirectActionBarSink : ActionBarSink {
    override fun show(player: Player, text: Component) = player.sendActionBar(text)
}

object ActionBars {
    @Volatile
    private var sink: ActionBarSink = DirectActionBarSink

    fun show(player: Player, text: Component) = sink.show(player, text)

    fun install(newSink: ActionBarSink) {
        sink = newSink
    }

    /**
     * 選出出口:LycoLib 沒啟用就直送,[lycoLibSink] 完全不會被呼叫;啟用了但建構失敗(版本不合等)
     * 也退回直送並回報 [onFailure],不讓副本整個失去 ActionBar。
     */
    fun select(
        lycoLibEnabled: Boolean,
        lycoLibSink: () -> ActionBarSink,
        onFailure: (Throwable) -> Unit,
    ): ActionBarSink {
        if (!lycoLibEnabled) return DirectActionBarSink
        return try {
            lycoLibSink()
        } catch (t: Throwable) {
            onFailure(t)
            DirectActionBarSink
        }
    }
}
