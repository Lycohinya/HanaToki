package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.api.InstanceItems
import com.tinyyana.hanatoki.text.Texts
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * 局內道具(tool.yml)的物品面:鑄造、辨識、燈火數量顯示。
 *
 * - 辨識:PDC `hanatoki:tool_id`(字串)。事件層靠它分辨「這是不是某個 tool」,不比對顯示名。
 * - 局內物:鑄造時經 [InstanceItems.mark] 蓋 instance 章,離場帶不出去、不能放進容器(見 InstanceItemGuard)。
 * - 燈火數量:物品耐久條(最大耐久 = 格數上限,耐久損耗 = 已用掉的格數),最簡單且一直看得見;
 *   [ToolSpec.hudTextKey] 有設時另在 ActionBar 提示數字。
 *
 * ⚠ 材質一律在這裡才解析(`Material.matchMaterial` 在單元測試會炸)。
 */
class ToolItems(plugin: Plugin, private val texts: Texts) {
    private val toolKey = NamespacedKey(plugin, "tool_id")

    fun idOf(item: ItemStack?): String? {
        if (item == null || item.type.isAir) return null
        return item.itemMeta?.persistentDataContainer?.get(toolKey, PersistentDataType.STRING)
    }

    /** 鑄一件 tool。材質無法解析回 null(註冊時已驗證過,這裡是最後防線)。 */
    fun mint(spec: ToolSpec, charges: Int, items: InstanceItems?, instanceId: String?): ItemStack? {
        val material = Material.matchMaterial(spec.material)?.takeIf { it.isItem } ?: return null
        val stack = ItemStack(material, 1)
        stack.editMeta { meta ->
            meta.displayName(texts.format(spec.nameKey).decoration(TextDecoration.ITALIC, false))
            if (spec.loreKeys.isNotEmpty()) {
                meta.lore(spec.loreKeys.map<String, Component> { texts.format(it).decoration(TextDecoration.ITALIC, false) })
            }
            spec.itemModel?.let { key -> NamespacedKey.fromString(key)?.let(meta::setItemModel) }
            meta.persistentDataContainer.set(toolKey, PersistentDataType.STRING, spec.id)
            meta.setMaxStackSize(1)
            meta.isUnbreakable = false
            if (meta is Damageable) {
                meta.setMaxDamage(spec.maxCharges)
                meta.damage = (spec.maxCharges - charges.coerceIn(0, spec.maxCharges))
            }
        }
        if (items != null && instanceId != null) items.mark(stack, instanceId)
        return stack
    }

    /** 把玩家背包裡這個 tool 的耐久條對成 [charges] 格。必須在該玩家的 EntityScheduler 上呼叫。 */
    fun syncDurability(player: Player, spec: ToolSpec, charges: Int) {
        val contents = player.inventory.contents
        for (i in contents.indices) {
            val item = contents[i] ?: continue
            if (idOf(item) != spec.id) continue
            item.editMeta { meta ->
                if (meta is Damageable) meta.damage = (spec.maxCharges - charges.coerceIn(0, spec.maxCharges))
            }
            player.inventory.setItem(i, item)
            return
        }
    }
}
