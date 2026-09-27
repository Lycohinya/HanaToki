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
     * 查「這位玩家目前這個 session 發出過的全部 instance token」(含他自己的)。由
     * [InstanceInventoryService] 提供(同插件內,不跨邊界)。不在任何 session 裡就回空集合。
     *
     * 存在理由見 [RunItemLegality]:多人副本(深域)的起始武器與掉落是整隊共用的,
     * 但 `instanceId` 是 per-player 的背包交易 token,合法性要看 session 發過哪些 token,
     * 不能只認自己那一份,也不能只認「現在還在場」的隊員那幾份。
     */
    private val sessionTokensOf: (UUID) -> Set<String> = { emptySet() },
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

    /**
     * 拿掉 instance 章(keep-inventory 模式的攜入物歸還,見 [KeepInventoryRestore])。
     * 物品本身與它原本的 PDC(整備包契約的六個 key)不動。
     */
    internal fun unmark(item: ItemStack): ItemStack {
        item.editMeta { meta ->
            meta.persistentDataContainer.remove(scopeKey)
            meta.persistentDataContainer.remove(instanceKey)
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

    override fun isLegalFor(playerId: UUID, item: ItemStack): Boolean =
        verdictFor(playerId, item) != RunItemVerdict.RUN_ILLEGAL

    /**
     * 所有執行期合法性判定的唯一入口(拾取、掃背包、巡檢都走這裡),規則見 [RunItemLegality]。
     * 永久物品回 [RunItemVerdict.PERMANENT]——對「外流」方向它合法,對巡檢的「局內只准有局內物品」
     * 方向它是要移出去的局外物品,由呼叫端依方向解讀。
     */
    fun verdictFor(playerId: UUID, item: ItemStack): RunItemVerdict {
        val scoped = isInstanceScoped(item)
        if (!scoped) return RunItemVerdict.PERMANENT
        return RunItemLegality.classify(
            scoped = true,
            itemToken = instanceIdOf(item),
            playerToken = activeLookup(playerId)?.toString(),
            sessionTokens = sessionTokensOf(playerId),
        )
    }

    private companion object {
        const val SCOPE_INSTANCE = "INSTANCE"
    }
}
