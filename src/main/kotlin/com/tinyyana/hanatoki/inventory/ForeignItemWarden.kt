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
 * ## 抓到之後怎麼處理
 *
 * **不是刪掉,是移進那份還沒還給玩家的永久背包快照**([InstanceInventoryService.quarantine])。
 * 快照本來就是「離場時要覆蓋回去的那份背包」,所以東西並沒有消失,只是提早回到它該在的地方
 * ——玩家撤離/死亡/斷線收斂時照樣拿得到,而且沿用既有那條崩潰安全的還原路徑,不需要第二套
 * 恢復語意。
 *
 * 順序是**先從背包拿走,再寫快照**。中間崩潰的話東西會遺失一件(記在 log 裡);反過來寫的話
 * 中間崩潰會變成複製一件——而複製正好就是這條防線要擋的事。寧可掉也不要多。
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
            sweep(record.playerId, instanceId)
        }
    }

    private fun sweep(playerId: UUID, instanceId: UUID) {
        val player = plugin.server.getPlayer(playerId) ?: return
        PlayerOp.dispatch(plugin, player) { p ->
            // 收斂中/已收斂的那一刻起就不要再動背包:還原是覆蓋寫,兩邊同時動會互相蓋掉。
            if (service.activeInstanceIdOf(playerId) != instanceId) return@dispatch
            val inventory = p.inventory
            val contents = inventory.contents
            val seized = mutableListOf<ItemStack>()
            for (slot in contents.indices) {
                val stack = contents[slot] ?: continue
                if (stack.type == Material.AIR) continue
                // ⚠ 不能用 `isLegalFor`:那一支守的是**外流**方向,對沒有局內章的永久物品
                //   一律回 true(「永久物品,永遠合法」)。這裡要的是反向白名單——
                //   在 Run 裡只有「這一局的局內物品」算合法,其他一律收走。
                val instanceOfStack = service.items.instanceIdOf(stack)
                if (service.items.isInstanceScoped(stack) && instanceOfStack == instanceId.toString()) continue
                seized += stack.clone()
                inventory.setItem(slot, null)
            }
            if (seized.isEmpty()) return@dispatch
            p.sendActionBar(texts.format("instance-item.foreign-held"))
            plugin.logger.info(
                "[HanaToki] instance=$instanceId 巡檢到 ${seized.size} 件不屬於這一局的物品,已移進待還原的永久背包:" +
                    seized.joinToString(",") { "${it.type}x${it.amount}" },
            )
            service.quarantine(instanceId, seized)
        }
    }

    private companion object {
        /**
         * 兩秒一次。再密不會更安全(塞進來的東西本來就要等下一次巡檢),再疏會讓玩家
         * 抱著別人給的東西打完一整場,體感上像是「東西被沒收」而不是「本來就進不來」。
         */
        const val SWEEP_INTERVAL_TICKS = 40L
    }
}
