package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.folia.PlayerOp
import com.tinyyana.hanatoki.text.Texts
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * 反向防線:**局外的東西不准進局內**。
 *
 * [InstanceItemGuard] 守的是「局內物品不准流出去」;這一支守的是另一個方向——玩家在 Run 裡
 * 不該憑任何手段拿到非本局的物品。兩件事的攻擊面完全不同,所以不共用同一個類別。
 *
 * ## 為什麼不是攔事件
 *
 * 攔事件擋不住這件事。`Inventory.addItem` 是純 API 呼叫,**不發任何 Bukkit 事件**——任務獎勵、
 * 商城、抽獎、郵件、使魔、指令 kit,只要是別的插件直接塞背包,事件層一律看不到。逐一去跟
 * 每一支插件協調「請你在副本裡不要發」是列舉黑名單,漏一個就破功。
 *
 * 所以這裡改成守**不變式**:
 *
 * > 在 ACTIVE 的局內背包期間,玩家背包裡的每一個非空格位都必須是**這一局的**局內物品。
 *
 * 不變式是白名單,不管東西從哪條路進來——插件、指令、未來新裝的插件——下一次巡檢都會抓到。
 *
 * ## 判定
 *
 * 跟拾取、掃背包共用同一條規則([InstanceItemsImpl.verdictFor] / [RunItemLegality]):
 * 同一個 session 發出過的任何 token 都合法。2026-09-27 以前這裡只認「玩家自己的 token」,
 * 深域整隊共用首位隊員 token 的起始武器與掉落,每兩秒就被當成局外物品收走一次。
 *
 * ## 抓到之後怎麼處理
 *
 * - **永久物品**(沒有局內章):移進 [ReturnMailbox],離場、背包有空位時放回去。不再併進
 *   journal 的永久背包快照——那份快照滿了會整批遺失,也不該被巡檢改寫。
 * - **失效的局內物品**(異局、舊局、半殘標記):直接拿掉。它們不屬於任何人的永久背包,
 *   放進暫存箱就是把垃圾洗成永久道具。
 *
 * 拿走與入箱在同一個玩家 region task 裡完成,中間不讓出執行緒;暫存箱寫檔失敗時東西仍在
 * 記憶體裡,下一輪巡檢會重寫。
 *
 * ## 執行緒
 *
 * 巡檢由全域排程器起頭,每位玩家的背包讀寫都派到他自己的 EntityScheduler([PlayerOp]),
 * 快照寫回走 service 的非同步 journal I/O。這支類別自己不碰任何跨 region 狀態。
 */
class ForeignItemWarden(
    private val plugin: Plugin,
    private val service: InstanceInventoryService,
    private val texts: Texts,
) {

    private var handle: ScheduledTask? = null

    fun start(intervalTicks: Long = SWEEP_INTERVAL_TICKS) {
        stop()
        handle = plugin.server.globalRegionScheduler.runAtFixedRate(plugin, { _ -> sweepAll() }, intervalTicks, intervalTicks)
    }

    fun stop() {
        handle?.cancel()
        handle = null
    }

    /** 對每一位「手上是局內背包」的玩家巡一次。人數 = 同時進行中的局數,是個位數。 */
    fun sweepAll() {
        for (record in service.snapshotRecords()) {
            val instanceId = service.activeInstanceIdOf(record.playerId) ?: continue
            if (instanceId != record.instanceId) continue
            // keep-inventory:玩家手上就是他自己的永久背包,沒有「偷渡物」這回事。
            if (record.keepInventory) continue
            sweep(record.playerId, instanceId)
        }
        deliverReturns()
        // 寫檔失敗的暫存箱在這裡重寫(沒有待寫的就是 no-op)。
        service.flushReturns()
    }

    private fun sweep(playerId: UUID, instanceId: UUID) {
        val player = plugin.server.getPlayer(playerId) ?: return
        PlayerOp.dispatch(plugin, player) { p ->
            // 收斂中/已收斂的那一刻起就不要再動背包:還原是覆蓋寫,兩邊同時動會互相蓋掉。
            if (service.activeInstanceIdOf(playerId) != instanceId) return@dispatch
            val inventory = p.inventory
            val contents = inventory.contents
            val foreign = mutableListOf<ItemStack>()
            val invalid = mutableListOf<ItemStack>()
            for (slot in contents.indices) {
                val stack = contents[slot] ?: continue
                if (stack.type == Material.AIR) continue
                // ⚠ 不能只問 `isLegalFor`:它對永久物品回 true(外流方向),這裡是反向白名單
                //   ——局內只准有這個 session 的局內物品。三種結果分開處理,見類別 KDoc。
                when (service.items.verdictFor(playerId, stack)) {
                    RunItemVerdict.RUN_LEGAL -> continue
                    RunItemVerdict.PERMANENT -> foreign += stack.clone()
                    RunItemVerdict.RUN_ILLEGAL -> invalid += stack.clone()
                }
                inventory.setItem(slot, null)
            }
            if (invalid.isNotEmpty()) {
                plugin.logger.info(
                    "[HanaToki] instance=$instanceId 巡檢清掉 ${invalid.size} 組不屬於這個 session 的局內物品:" +
                        invalid.joinToString(",") { "${it.type}x${it.amount}" },
                )
            }
            if (foreign.isEmpty()) return@dispatch
            p.sendActionBar(texts.format("instance-item.foreign-held"))
            service.depositReturns(playerId, foreign, "instance=$instanceId 巡檢")
        }
    }

    /** playerId -> 上次送出「背包滿了還在等」提示的時間,避免每兩秒洗一次。 */
    private val waitingNoticeAt = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    /**
     * 暫存箱送件:在線、沒有未收斂交易的玩家,派工到他自己的 region 放回背包。
     * 放不下的留著等下一輪;提示每位玩家最多每 [WAITING_NOTICE_INTERVAL_MS] 一次。
     */
    private fun deliverReturns() {
        for (playerId in service.returns.playersWithPending()) {
            if (service.hasOpenRecord(playerId)) continue
            val player = plugin.server.getPlayer(playerId) ?: continue
            PlayerOp.dispatch(plugin, player) { p ->
                val (delivered, remaining) = service.deliverReturns(p)
                val now = System.currentTimeMillis()
                if (delivered > 0) {
                    p.sendMessage(texts.format("instance-item.returned", mapOf("count" to delivered.toString())))
                }
                if (remaining == 0) {
                    waitingNoticeAt.remove(playerId)
                    return@dispatch
                }
                val last = waitingNoticeAt[playerId]
                if (delivered == 0 && last != null && now - last < WAITING_NOTICE_INTERVAL_MS) return@dispatch
                waitingNoticeAt[playerId] = now
                p.sendMessage(texts.format("instance-item.returns-waiting", mapOf("count" to remaining.toString())))
            }
        }
    }

    private companion object {
        /**
         * 兩秒一次。再密不會更安全(塞進來的東西本來就要等下一次巡檢),再疏會讓玩家
         * 抱著別人給的東西打完一整場,體感上像是「東西被沒收」而不是「本來就進不來」。
         */
        const val SWEEP_INTERVAL_TICKS = 40L

        /** 「背包滿了,還有 N 件等空位」提示的最短間隔。 */
        const val WAITING_NOTICE_INTERVAL_MS = 300_000L
    }
}
