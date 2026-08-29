package com.tinyyana.hanatoki.inventory

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 玩家進場前的永久背包。
 *
 * 涵蓋 `PlayerInventory.getContents()` 的全部格位——主背包、快捷列、四件盔甲、副手都在裡面
 * (26.2 的 `PlayerInventory` size = 41),外加 [heldSlot](手上拿著第幾格)。
 *
 * ## 不含什麼(刻意的)
 *
 * 終界箱、經驗值、藥水效果、生命值都**不在快照裡**,因為引擎不會動它們:局內背包只換
 * `getContents()` 這一段。終界箱不換的代價是它變成一條把局內物品帶出去的路,那條路由
 * [InstanceItems] 的事件層直接封死(禁止把 instance 物品放進任何非玩家自己的容器),
 * 比「再存一份終界箱快照」少一份要維護的權威狀態。
 *
 * @param itemBytes `ItemStack.serializeItemsAsBytes()` 的原始位元組(原版 NBT,跨版本升級由
 *   核心自己處理——這正是不用 `BukkitObjectOutputStream` 的理由:後者存的是 Bukkit 序列化
 *   格式,跨大版本升級沒有核心的資料修復器護航)。
 * @param contentsSize 拍快照當下的背包格數。還原時對不上就只還原重疊的部分並記警告,
 *   而不是整份放棄——格數會變的情境只有核心大改版,那時「還原大部分」遠好過「什麼都不還」。
 */
class InventorySnapshot(
    val itemBytes: ByteArray,
    val heldSlot: Int,
    val contentsSize: Int,
) {
    fun isEmptyPayload(): Boolean = itemBytes.isEmpty()

    companion object {

        /**
         * 讀取玩家目前的背包。
         *
         * ⚠ **必須在該玩家自己的 EntityScheduler 上呼叫**(ARCH §5.1④:背包是實體狀態)。
         * 這個方法自己不派工——呼叫端本來就已經在那條 task 裡(見 [InstanceInventoryService])。
         */
        fun capture(player: Player): InventorySnapshot {
            val contents = player.inventory.contents
            // serializeItemsAsBytes 對 null 元素的行為沒有寫進 API 契約,而空格位在
            // PlayerInventory 裡是 null 不是 AIR——先自己正規化成 AIR,還原時再轉回 null,
            // 這樣兩邊都不依賴未文件化的行為。
            val normalized = Array(contents.size) { i -> contents[i] ?: ItemStack(Material.AIR) }
            val bytes = ItemStack.serializeItemsAsBytes(normalized)
            return InventorySnapshot(bytes, player.inventory.heldItemSlot, contents.size)
        }

        /**
         * 把快照**覆蓋**回玩家背包。
         *
         * 「覆蓋」而不是「加回去」是整套崩潰安全的關鍵:覆蓋是冪等的,同一份快照還原一次、
         * 三次、崩潰後再還原一次,結果都是同一份永久背包。用 `addItem` 補回去的話,重跑一次
         * 就多一份——而恢復流程依定義是可能重跑的。
         *
         * 回傳 false = 快照解不開(不該發生;真的發生時呼叫端要保留 journal 給人工處理,
         * 不能當作已還原然後把紀錄刪掉)。
         *
         * ⚠ 同樣**必須在該玩家自己的 EntityScheduler 上呼叫**。
         */
        fun restore(player: Player, snapshot: InventorySnapshot): Boolean {
            val decoded = try {
                ItemStack.deserializeItemsFromBytes(snapshot.itemBytes)
            } catch (e: Exception) {
                return false
            }
            val inv = player.inventory
            val size = inv.contents.size
            val target = arrayOfNulls<ItemStack>(size)
            val copyLen = minOf(size, decoded.size)
            for (i in 0 until copyLen) {
                val item = decoded[i]
                target[i] = if (item == null || item.type == Material.AIR) null else item
            }
            inv.contents = target
            if (snapshot.heldSlot in 0..8) inv.heldItemSlot = snapshot.heldSlot
            return true
        }

        /**
         * 目前背包跟這份快照是不是同一份內容。
         *
         * 用途只有一個:進場交易在「拍快照 → 寫 journal(非同步 I/O)→ 清空背包」中間必然
         * 會讓出執行緒,這個比對是在真的清空之前確認那個空檔裡玩家沒有撿到/掉了東西。
         * 對不上就重拍一次再寫一次 journal——不比對的話,那個空檔拿到的東西會在還原時被
         * 舊快照默默蓋掉。
         */
        fun matches(player: Player, snapshot: InventorySnapshot): Boolean {
            val current = capture(player)
            return current.heldSlot == snapshot.heldSlot && current.itemBytes.contentEquals(snapshot.itemBytes)
        }
    }
}
