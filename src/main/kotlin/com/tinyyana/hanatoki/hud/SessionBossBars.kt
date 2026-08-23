package com.tinyyana.hanatoki.hud

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 每個 session 一條 boss bar(畫面最上方那條)。
 *
 * ## 為什麼需要
 *
 * 2026-08-24 真人回饋:「限時 300 秒,玩家沒看到計時的地方」。副本有硬性時限卻沒有任何常駐
 * 顯示,玩家只能靠猜。同一條 bar 也順便解決「Boss 剩多少血看不出來」——這兩件事都屬於
 * 「整場都要看得到」,動作列不行(招式預告會一直蓋掉它),聊天欄更不行(會洗版)。
 *
 * ## 為什麼是引擎而不是內容層
 *
 * 「這一局還剩多久」是 Session 的屬性,不是刀塚的屬性——任何有時限的副本都需要它。
 * 內容層只決定 bar 上寫什麼、進度條代表什麼(見 [com.tinyyana.hanatoki.stage.StageContext.bossBar])。
 *
 * ## Folia
 *
 * Adventure 的 `BossBar` 是純資料物件,`Audience.showBossBar` 才是對玩家送封包——所以
 * 加人/移除一律經該玩家自己的 EntityScheduler(見 [com.tinyyana.hanatoki.folia.PlayerOp]),
 * 這裡只保管物件本身(ConcurrentHashMap,任何執行緒可安全查表)。
 */
class SessionBossBars(private val plugin: Plugin) {

    private val bars = ConcurrentHashMap<UUID, BossBar>()
    /** 每條 bar 目前送給了誰。玩家離線/被踢時要把 bar 收回去,不然它會留在客戶端畫面上。 */
    private val viewers = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /**
     * 更新(必要時建立)這個 session 的 bar,並確保 [memberIds] 每個人都看得到。
     *
     * @param text MiniMessage 原文
     * @param progress 0.0–1.0,超出範圍會被夾住
     * @param colorName [BossBar.Color] 的常數名(例如 `"RED"`);不認得就用 `WHITE`
     */
    fun update(sessionId: UUID, memberIds: List<UUID>, text: String, progress: Double, colorName: String) =
        update(sessionId, memberIds, text, progress, colorName, BossBar.Overlay.PROGRESS.name)

    /**
     * @param overlayName [BossBar.Overlay] 的常數名(例如 `"NOTCHED_10"` = 舊 Bukkit API 的
     *   `SEGMENTED_10`)。不認得就用實心 `PROGRESS`。
     */
    fun update(
        sessionId: UUID,
        memberIds: List<UUID>,
        text: String,
        progress: Double,
        colorName: String,
        overlayName: String,
    ) {
        val color = runCatching { BossBar.Color.valueOf(colorName.uppercase()) }.getOrDefault(BossBar.Color.WHITE)
        val overlay = runCatching { BossBar.Overlay.valueOf(overlayName.uppercase()) }.getOrDefault(BossBar.Overlay.PROGRESS)
        val title: Component = runCatching { MINI.deserialize(text) }.getOrDefault(Component.text(text))
        val clamped = progress.coerceIn(0.0, 1.0).toFloat()

        val bar = bars.computeIfAbsent(sessionId) {
            BossBar.bossBar(title, clamped, color, overlay)
        }
        bar.name(title)
        bar.progress(clamped)
        bar.color(color)
        bar.overlay(overlay)

        val shown = viewers.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        for (playerId in memberIds) {
            if (!shown.add(playerId)) continue
            com.tinyyana.hanatoki.folia.PlayerOp.dispatch(plugin, playerId) { it.showBossBar(bar) }
        }
        // 已經不在成員名單裡的人要收回去(有人中途離開/被踢)。
        shown.filter { it !in memberIds }.forEach { gone ->
            shown.remove(gone)
            com.tinyyana.hanatoki.folia.PlayerOp.dispatch(plugin, gone) { it.hideBossBar(bar) }
        }
    }

    /** session 結束/轉場不需要 bar 時呼叫。對所有看過的人收回,並丟掉登記。 */
    fun clear(sessionId: UUID) {
        val bar = bars.remove(sessionId) ?: return
        viewers.remove(sessionId)?.forEach { playerId ->
            com.tinyyana.hanatoki.folia.PlayerOp.dispatch(plugin, playerId) { it.hideBossBar(bar) }
        }
    }

    private companion object {
        val MINI: MiniMessage = MiniMessage.miniMessage()
    }
}
