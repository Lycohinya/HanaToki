package com.tinyyana.hanatoki.content

import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * [BodyPort] 的 Bukkit 實作:讀寫玩家的遊戲模式、隱形、發光、無敵、移動速度修正。**只能在玩家自己的執行緒上建構與使用**
 * (EntityScheduler 回呼，或玩家自己的事件)。
 *
 * - 移動速度用 transient 屬性修正(固定 NamespacedKey)，不寫進玩家資料：斷線、重啟、當機都不會留下。
 * - 其餘狀態在 PDC 留「倒下前狀態」記號([marker])：伺服器在倒下期間關閉或當機，回呼不會跑，下次上線靠它還原。
 */
class BukkitBody(private val player: Player, plugin: Plugin) : BodyPort {
    private val speedKey = NamespacedKey(plugin, SPEED_KEY)
    private val markerKey = NamespacedKey(plugin, MARKER_KEY)

    override fun read(): BodyState = BodyState(
        gameMode = player.gameMode.name,
        invisible = player.isInvisible,
        glowing = player.isGlowing,
        invulnerable = player.isInvulnerable,
        speedModifier = player.getAttribute(Attribute.MOVEMENT_SPEED)?.getModifier(speedKey)?.amount ?: 0.0,
    )

    override fun apply(state: BodyState) {
        runCatching { player.gameMode = GameMode.valueOf(state.gameMode) }
        player.isInvisible = state.invisible
        player.isGlowing = state.glowing
        player.isInvulnerable = state.invulnerable
        val attribute = player.getAttribute(Attribute.MOVEMENT_SPEED) ?: return
        attribute.removeModifier(speedKey)
        if (state.speedModifier != 0.0) {
            attribute.addTransientModifier(AttributeModifier(speedKey, state.speedModifier, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
        }
    }

    override fun marker(): BodyState? {
        val raw = player.persistentDataContainer.get(markerKey, PersistentDataType.STRING) ?: return null
        return decode(raw)
    }

    override fun setMarker(state: BodyState?) {
        if (state == null) player.persistentDataContainer.remove(markerKey)
        else player.persistentDataContainer.set(markerKey, PersistentDataType.STRING, encode(state))
    }

    companion object {
        const val SPEED_KEY = "downed_slow"
        const val MARKER_KEY = "downed_body"

        fun encode(s: BodyState): String = "${s.gameMode}|${s.invisible}|${s.glowing}|${s.invulnerable}|${s.speedModifier}"

        fun decode(raw: String): BodyState? {
            val p = raw.split('|')
            if (p.size != 5) return null
            return BodyState(p[0], p[1].toBooleanStrictOrNull() ?: return null, p[2].toBooleanStrictOrNull() ?: return null,
                p[3].toBooleanStrictOrNull() ?: return null, p[4].toDoubleOrNull() ?: return null)
        }

        /**
         * 玩家登入時：身上還有倒下記號、又不是仍在倒下的局裡 = 上次關服或當機時留下的，整組還原。
         * 回傳是否還原了。在玩家自己的執行緒上呼叫（PlayerJoinEvent）。
         */
        fun restoreLeftover(player: Player, plugin: Plugin): Boolean {
            val body = BukkitBody(player, plugin)
            val original = body.marker() ?: return false
            body.apply(original)
            body.setMarker(null)
            return true
        }
    }
}
