package com.tinyyana.hanatoki.api

import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * 局內物品的所有權標記(ARCH §5.6)。HanaToki 是 provider,經 `ServicesManager` 註冊。
 *
 * ## 為什麼這個 primitive 住在引擎而不是道具插件
 *
 * 崩潰恢復時,引擎必須能自己認出「這個 ItemStack 是上一局的殘留」並清掉它——那一刻不保證
 * 任何道具插件已經啟用,甚至不保證它裝在這台伺服器上(ARCH §11 開源驗收:乾淨 Paper/Folia +
 * 只有 HanaToki 也要能跑)。所以「scope 標記怎麼寫、怎麼讀、誰有效」是引擎的責任。
 *
 * 道具插件(LycoItems)不另建一套平行的 scope 欄位,而是在鑄造局內武器時呼叫這裡的 [mark]
 * 蓋章——**同一份權威狀態,兩邊都認同一個 key**。
 *
 * ## 語意
 *
 * - 沒有標記 = 永久物品(global scope)。這是預設,既有的所有物品完全不受影響。
 * - 有標記 = 只在該 `instanceId` 的那一場 Run 裡合法。離開那場 Run 之後它就是垃圾,
 *   引擎會在還原/登入/跨世界時把它清掉。
 *
 * ⚠ 簽章只用 Bukkit/JDK 型別(`ItemStack`/`String`/`UUID`/`Boolean`),過 ARCH §4.0 的跨插件紅線。
 * `instanceId` 用 `String` 而不是 `UUID`:它最終要存進 PDC 的 `STRING`,在邊界上就用同一個型別,
 * 少一層來回轉換也少一個可能對不起來的地方。
 */
interface InstanceItems {

    /**
     * 在物品上蓋「這是 instanceId 這一局的局內物品」。回傳的是**傳進來的同一個** [ItemStack]
     * (已就地改過 meta),方便串接寫法。
     */
    fun mark(item: ItemStack, instanceId: String): ItemStack

    /** 這個物品屬於哪一局;沒有標記(= 永久物品)回 null。 */
    fun instanceIdOf(item: ItemStack): String?

    fun isInstanceScoped(item: ItemStack): Boolean

    /** 這位玩家現在進行中的那一局的 instanceId;不在任何 Run 裡回 null。 */
    fun activeInstanceIdOf(playerId: UUID): String?

    /**
     * 這個物品現在對這位玩家是不是合法的。
     *
     * 永久物品恆 true;局內物品只有在「玩家正在跑的就是同一局」時才 true。
     * 事件層的所有攔截(丟棄、拾取、放進容器、死亡掉落)都問這一句。
     */
    fun isLegalFor(playerId: UUID, item: ItemStack): Boolean
}
