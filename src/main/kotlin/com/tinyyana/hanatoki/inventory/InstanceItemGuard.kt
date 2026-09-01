package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.text.Texts
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * 局內物品的洩漏防線。
 *
 * ## 設計原則:**寧可擋過頭,也不要漏一個**
 *
 * 一個局內物品洩漏到永久世界的成本是不可逆的(玩家拿到了不該有的裝備,而且會傳給別人);
 * 所以:**局內物品不准被非本局的人撿、不准進任何容器**。
 *
 * 「不准丟」曾經也在清單上(怕掉落物活過 session 變成洩漏路徑),2026-09-01 Yana 拍板拿掉:
 * 丟在地上走原版機制(五分鐘自然消失、自己撿得回來),session 收斂時 [InstanceDropSweeper]
 * 會把場地上殘留的局內掉落物整片掃掉——洩漏路徑由「非本局不能撿 + 收斂掃地」守著,
 * 不需要犧牲「把不要的東西丟掉」這個基本操作。
 *
 * ## 覆蓋的路徑
 *
 * | 路徑 | 處理 |
 * |---|---|
 * | 手動丟出 (`Q`) | **放行**(原版機制;收斂時掃地) |
 * | 被撿起 | 非本局的人一律取消 |
 * | 放進箱子/終界箱/任何開著的容器 | 取消 |
 * | 漏斗/礦車自動搬運 | 取消 |
 * | 局內打開終界箱(實體或 /ec) | 取消——進場前先塞終界箱、局內再拿出來就是偷渡(2026-09-01) |
 * | 死亡掉落 | 從 drops 移除(直接消失,不落地) |
 * | 跨世界、登入 | 掃背包,清掉所有不合法的局內物品 |
 *
 * 終界箱靠「不准進任何容器」這一條擋住,所以不需要另外拍一份終界箱快照
 * (見 [InventorySnapshot] 的「不含什麼」)。
 */
class InstanceItemGuard(
    private val plugin: Plugin,
    private val service: InstanceInventoryService,
    private val texts: Texts,
) : Listener {

    private val items get() = service.items

    // ---- 拾取 ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val stack = event.item.itemStack
        if (!items.isInstanceScoped(stack)) return
        val player = event.entity as? Player ?: run {
            // 非玩家實體(村民等)撿局內物品一律擋——那條路會把它帶進交易/掉落表。
            event.isCancelled = true
            return
        }
        if (items.isLegalFor(player.uniqueId, stack)) return
        event.isCancelled = true
        // 沒有提示的話,撿不起來的東西看起來就是卡 bug(玩家會反覆走過去踩、以為是延遲)。
        // 走動作列而不是聊天:拾取事件是每 tick 觸發的,聊天會被洗版。
        val now = System.currentTimeMillis()
        val last = lastPickupNotice[player.uniqueId]
        if (last != null && now - last < PICKUP_NOTICE_COOLDOWN_MS) return
        lastPickupNotice[player.uniqueId] = now
        // 事件在該玩家自己的 region 觸發,可以直接對他送(ARCH §5.2 規則 2 的例外:對象就是事發者)。
        player.sendActionBar(texts.format("instance-item.cannot-pick-up"))
    }

    /** playerId -> 上次送出撿不起來提示的時間。見 [onPickup]。 */
    private val lastPickupNotice = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>()

    // ---- 容器 ---------------------------------------------------------------

    /**
     * 局內(有 active instance 背包的人)禁止打開終界箱:終界箱的內容不在局內背包快照裡,
     * 進場前先 `/ec` 塞滿、局內再開出來就是把外部物資偷渡進 Run(2026-09-01 Yana 抓到)。
     * 攔 InventoryOpen 一次擋掉實體終界箱與 Essentials `/ec` 兩條路——都是 openInventory。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        if (event.inventory.type != InventoryType.ENDER_CHEST) return
        val player = event.player as? Player ?: return
        if (service.activeInstanceIdOf(player.uniqueId) == null) return
        event.isCancelled = true
        player.sendMessage(texts.format("instance-item.no-ender-chest"))
    }

    /**
     * 有開著的容器時,任何牽涉到局內物品的點擊都擋掉。
     *
     * 判斷「有沒有開容器」用 `view.topInventory.type != CRAFTING`——那個 type 就是玩家自己的
     * 4 格合成格,代表「沒有開任何東西,只是打開自己的背包」。這比逐一列舉箱子/桶/終界箱/
     * 潛影盒/告示牌…可靠得多,而且新增的容器類型自動被涵蓋。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.type == InventoryType.CRAFTING) return
        val involved = listOfNotNull(event.currentItem, event.cursor)
        if (involved.none { items.isInstanceScoped(it) }) return
        event.isCancelled = true
        (event.whoClicked as? Player)?.sendMessage(texts.format("instance-item.cannot-store"))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.type == InventoryType.CRAFTING) return
        if (!items.isInstanceScoped(event.oldCursor)) return
        event.isCancelled = true
        (event.whoClicked as? Player)?.sendMessage(texts.format("instance-item.cannot-store"))
    }

    /** 漏斗/礦車:局內物品不該有機會進到這條路,但它是繞過點擊事件的唯一搬運管道。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMove(event: InventoryMoveItemEvent) {
        if (items.isInstanceScoped(event.item)) event.isCancelled = true
    }

    // ---- 掃背包 -------------------------------------------------------------

    /**
     * 清掉玩家背包裡所有**現在不合法**的局內物品,回傳清掉幾個。
     *
     * 合法性由 [com.tinyyana.hanatoki.api.InstanceItems.isLegalFor] 判定:Run 還在跑就留著,
     * Run 已經結束/重啟後不存在就清掉。永久物品永遠不會被碰到。
     *
     * ⚠ 必須在該玩家自己的 EntityScheduler 上呼叫(ARCH §5.1④)。事件 handler 本來就在那裡。
     */
    fun purgeIllegal(player: Player): Int {
        val contents = player.inventory.contents
        var removed = 0
        for (i in contents.indices) {
            val stack: ItemStack = contents[i] ?: continue
            if (!items.isInstanceScoped(stack)) continue
            if (items.isLegalFor(player.uniqueId, stack)) continue
            player.inventory.setItem(i, null)
            removed++
        }
        if (removed > 0) {
            plugin.logger.info("[HanaToki] 清掉 ${player.name} 身上 $removed 組已失效的局內物品")
        }
        return removed
    }

    /** 死亡掉落裡的局內物品直接移除(不落地、不進墓碑插件的收集範圍)。回傳移除幾組。 */
    fun stripInstanceItemsFromDrops(drops: MutableList<ItemStack>): Int {
        val before = drops.size
        drops.removeIf { items.isInstanceScoped(it) }
        return before - drops.size
    }

    private companion object {
        /** 同一位玩家兩次「撿不起來」提示之間的最短間隔。 */
        const val PICKUP_NOTICE_COOLDOWN_MS = 3000L
    }
}
