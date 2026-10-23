package com.tinyyana.hanatoki.prop

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * 浮空展示物的 ItemStack 組裝(rc.33)。
 *
 * [model] 非 null 時寫進 `custom_model_data.strings`,和 LycoLib `LycoIcons.apply` 是同一條管道(LycoItems 的玩家物品也走這條):
 * 有資源包的玩家看到自訂圖示,沒資源包的玩家看到原版 [material]。刻意**不用** `item_model` component——客戶端找不到模型時會渲染成紫黑破圖。
 * 不依賴 LycoLib:引擎保持內容無關,model 字串由內容資料給。
 */
object PropItems {
    fun build(material: Material, model: String?): ItemStack {
        val stack = ItemStack(material)
        if (model == null) return stack
        val meta = stack.itemMeta ?: return stack
        val component = meta.customModelDataComponent
        component.strings = listOf(model)
        meta.setCustomModelDataComponent(component)
        stack.itemMeta = meta
        return stack
    }
}
