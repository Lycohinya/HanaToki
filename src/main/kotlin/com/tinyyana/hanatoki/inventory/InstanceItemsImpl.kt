package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.api.InstanceItems
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * [InstanceItems] 的實作:兩個 PDC key,沒有別的。
 *
 * ## key 設計
 *
 * `hanatoki:instance_scope` 存 scope 名稱(目前只有 `INSTANCE` 一種值),
 * `hanatoki:instance_id` 存那一局的 instanceId。
 *
 * 為什麼分兩個 key 而不是只存 instanceId:**「這是局內物品」跟「屬於哪一局」是兩個不同的
 * 問題**,而攔截層問的絕大多數是前者(丟棄/放容器/死亡掉落都只需要知道「這東西不是永久的」)。
 * 只有 id 的話,任何一個 id 讀不出來的損壞物品都會被當成永久物品放行——那正好是最該擋下來的
 * 情況。分開之後,scope 在、id 不在 = 壞掉的局內物品 = 不合法,預設安全。
 *
 * 這個「新增 optional key,不存在就是舊語意」的形狀跟 LycoItems 既有的 `item_skin` /
 * `installed_cores` 完全一致,不需要任何資料遷移。
 */
class InstanceItemsImpl(
    plugin: Plugin,
    /** 查「這位玩家現在在跑哪一局」。由 [InstanceInventoryService] 提供(同插件內,不跨邊界)。 */
    private val activeLookup: (UUID) -> UUID?,
    /**
     * 查「這位玩家現在跟誰同一個 session」(含自己)。由 [InstanceInventoryService] 轉接
     * `SessionManager`(同插件內,不跨邊界)。查不到 session 就回空集合。
     *
     * 存在理由見 [isLegalFor]:多人副本(深域)的掉落是地面共享堆,但 `instanceId` 是
     * per-player 的安全背包交易概念(每位隊員各自一份、值互不相同),兩者語意不同,
     * 合法性判定要跨隊友查,不能只認自己那一份。
     */
    private val sessionMembersOf: (UUID) -> Collection<UUID> = { emptyList() },
) : InstanceItems {

    private val scopeKey = NamespacedKey(plugin, "instance_scope")
    private val instanceKey = NamespacedKey(plugin, "instance_id")

    override fun mark(item: ItemStack, instanceId: String): ItemStack {
        item.editMeta { meta ->
            meta.persistentDataContainer.set(scopeKey, PersistentDataType.STRING, SCOPE_INSTANCE)
            meta.persistentDataContainer.set(instanceKey, PersistentDataType.STRING, instanceId)
        }
        return item
    }

    override fun instanceIdOf(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(instanceKey, PersistentDataType.STRING)
    }

    override fun isInstanceScoped(item: ItemStack): Boolean {
        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        // scope 或 id 任一個在就算局內物品(見類別 KDoc:半殘的標記要往「不合法」倒)。
        return pdc.get(scopeKey, PersistentDataType.STRING) == SCOPE_INSTANCE ||
            pdc.has(instanceKey, PersistentDataType.STRING)
    }

    override fun activeInstanceIdOf(playerId: UUID): String? = activeLookup(playerId)?.toString()

    override fun isLegalFor(playerId: UUID, item: ItemStack): Boolean {
        if (!isInstanceScoped(item)) return true // 永久物品,永遠合法
        val itemInstance = instanceIdOf(item) ?: return false // 標記半殘 → 不合法
        if (itemInstance == activeLookup(playerId)?.toString()) return true
        // 隊友的掉落物一樣合法(2026-09-02 修:深域組隊掉落是地面共享堆,誰走過去撿都行——
        // 但物品身上只蓋得了一位隊員的 instanceId,不能只認跟自己完全相同的那一份)。
        return sessionMembersOf(playerId).any { it != playerId && activeLookup(it)?.toString() == itemInstance }
    }

    private companion object {
        const val SCOPE_INSTANCE = "INSTANCE"
    }
}
