package com.tinyyana.hanatoki.inventory

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.logging.Logger

/**
 * keep-inventory 模式的還原(見 [com.tinyyana.hanatoki.config.InstanceInventoryDef.keepInventory])。
 *
 * ## 不變式
 *
 * 永久背包從頭到尾都在玩家身上,所以還原**不是覆蓋**,是三件事:
 * 1. 沒被消耗的攜入物:身上找得到就脫章,找不到(例如跨世界時被局內物品清理先收走)就從
 *    journal 的原始位元組補回一份。
 * 2. 已消耗的攜入物:身上若還有蓋章的那件就移除(正常情況下 deploy 當下已經移除了)。
 * 3. 其他蓋著這一局章的東西(loadout、內容層發的局內物品):移除。
 *
 * **可重跑**:第二次跑的時候,攜入物已經脫章、局內物品已經不在——判斷「找不找得到」看的是
 * 攜入物自己的識別值(kitId),不看有沒有章,所以不會補出第二份。這是崩潰發生在「還原完成、
 * journal 還沒刪」之間時仍然恰好一份的理由。
 *
 * 判斷抽成 [plan](純函數,單元測試直接打),[apply] 只負責讀背包與執行。
 */
internal object KeepInventoryRestore {

    /** 背包裡一格的三個事實:這一格有沒有這一局的章、有沒有攜入識別值(是哪一個)。 */
    data class SlotFact(val index: Int, val stampedThisRun: Boolean, val carryId: UUID?)

    data class Plan(val remove: List<Int>, val unmark: List<Int>, val restoreFromJournal: List<UUID>)

    fun plan(slots: List<SlotFact>, escrow: List<Pair<UUID, Boolean>>): Plan {
        val consumed = escrow.filter { it.second }.map { it.first }.toSet()
        val pending = escrow.filter { !it.second }.map { it.first }.toMutableSet()
        val found = mutableSetOf<UUID>()
        val remove = mutableListOf<Int>()
        val unmark = mutableListOf<Int>()
        for (slot in slots) {
            val carry = slot.carryId
            when {
                carry != null && carry in pending && carry !in found -> {
                    found += carry
                    if (slot.stampedThisRun) unmark += slot.index
                }
                // 已消耗那件還躺在身上(不應發生):蓋章的才移除——沒蓋章的是玩家自己另外的東西,不碰。
                carry != null && carry in consumed -> if (slot.stampedThisRun) remove += slot.index
                // 同一個識別值出現第二件而且蓋著這一局的章:只可能是局內複製出來的,移除。
                slot.stampedThisRun -> remove += slot.index
            }
        }
        return Plan(remove, unmark, (pending - found).toList())
    }

    /** 回傳 true = 已完成(可以刪 journal);false = 有攜入物解不開,journal 必須保留給人工處理。 */
    fun apply(player: Player, instanceId: String, carryIn: List<CarryInEscrow>, items: InstanceItemsImpl, logger: Logger): Boolean {
        val inventory = player.inventory
        val contents = inventory.contents
        val keys = carryIn.map { NamespacedKey(it.pdcNamespace, it.pdcKey) }.distinct()
        val facts = contents.indices.mapNotNull { index ->
            val stack = contents[index] ?: return@mapNotNull null
            if (stack.type == Material.AIR) return@mapNotNull null
            val stamped = items.isInstanceScoped(stack) && items.instanceIdOf(stack) == instanceId
            val pdc = stack.itemMeta?.persistentDataContainer
            val carry = pdc?.let { container ->
                keys.firstNotNullOfOrNull { key -> container.get(key, PersistentDataType.STRING) }
                    ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                    ?.takeIf { id -> carryIn.any { it.kitId == id } }
            }
            if (!stamped && carry == null) null else SlotFact(index, stamped, carry)
        }
        val plan = plan(facts, carryIn.map { it.kitId to it.consumed })
        plan.remove.forEach { inventory.setItem(it, null) }
        plan.unmark.forEach { index -> contents[index]?.let { inventory.setItem(index, items.unmark(it)) } }
        var intact = true
        for (kitId in plan.restoreFromJournal) {
            val escrow = carryIn.first { it.kitId == kitId }
            val stack = runCatching { ItemStack.deserializeItemsFromBytes(escrow.itemBytes).getOrNull(0) }.getOrNull()
            if (stack == null || stack.type == Material.AIR) {
                logger.severe("[HanaToki] instance=$instanceId kitId=$kitId 攜入物品補回時解不開,journal 保留供人工處理")
                intact = false
                continue
            }
            inventory.addItem(stack).values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        if (plan.remove.isNotEmpty() || plan.unmark.isNotEmpty() || plan.restoreFromJournal.isNotEmpty()) {
            logger.info(
                "[HanaToki] instance=$instanceId keep-inventory 還原:移除 ${plan.remove.size} 格局內物、" +
                    "脫章 ${plan.unmark.size} 件攜入物、補回 ${plan.restoreFromJournal.size} 件",
            )
        }
        return intact
    }
}
