package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.inventory.RunDelivery

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 深域局內獎品發放的競態回歸,L1 版本。
 *
 * `RoguelikeBehavior.deliver()` / `giveLoadout` 的護盾分支會在**真正動玩家背包的最後一刻**
 * 過 [RunDelivery.canReceive];false 就把獎品改留成合法的場上掉落(不塞進 recipient 可能已
 * 還原的永久背包、也不靜靜刪掉)。這裡用一個小狀態機驗:2026-09-08 正式服「多人局副本內
 * 取得的物品最後出現在玩家局外永久背包」不會再發生。
 */
class RunItemLifecycleTest {

    private class World {
        /** playerId -> 他這一局的 per-player instanceId(startRestore 會移除)。 */
        val activeByPlayer = HashMap<UUID, String>()
        val members = LinkedHashSet<UUID>()
        val permanentInventory = HashMap<UUID, MutableList<String>>()
        val groundDrops = ArrayList<String>()

        fun join(id: UUID) {
            members += id
            activeByPlayer[id] = "inst-" + id.toString().take(4)
        }

        /** 死亡/離隊/撤離/斷線逾時:退出成員集合 + 非同步還原永久背包(startRestore)。 */
        fun leaveAndStartRestore(id: UUID) {
            members -= id
            activeByPlayer -= id
            permanentInventory.getOrPut(id) { mutableListOf() }
        }

        /**
         * deliver:排入 → (between 期間可能有事發生) → lambda 真的跑。
         * lambda 內先過 [RunDelivery.canReceive],false 就把獎品留成場上掉落。
         */
        fun deliverAfterDelay(recipient: UUID, itemStamp: String, between: () -> Unit) {
            between()
            val ok = RunDelivery.canReceive(
                online = true,
                instanceItemsPresent = true,
                recipientActiveInstanceId = activeByPlayer[recipient],
                sessionMembershipKnown = true,
                recipientIsActiveMember = recipient in members,
            )
            if (ok) {
                // 進的是他 active 的局內背包(模型不細分)。
            } else if (members.isEmpty()) {
                // session 沒人了,丟棄。
            } else {
                groundDrops += itemStamp
            }
        }
    }

    @Test
    fun `deliver 排隊後 recipient 開始 restore － 獎品落到場上而不是已還原的永久背包`() {
        val w = World()
        val leader = UUID.randomUUID()
        val m2 = UUID.randomUUID()
        listOf(leader, m2).forEach { w.join(it) }
        val prize = w.activeByPlayer.getValue(leader) // materialize 蓋 state.instanceId(= 某位隊員的章)

        w.deliverAfterDelay(leader, prize, between = { w.leaveAndStartRestore(leader) })

        assertTrue(w.permanentInventory.getValue(leader).isEmpty(), "leader 已還原的永久背包不得被寫入")
        assertEquals(listOf(prize), w.groundDrops, "獎品改留成場上掉落")
    }

    @Test
    fun `deliver 排隊後整局結束(沒有任何在場成員) － 獎品丟棄,不落地也不進背包`() {
        val w = World()
        val solo = UUID.randomUUID()
        w.join(solo)
        val prize = w.activeByPlayer.getValue(solo)

        w.deliverAfterDelay(solo, prize, between = { w.leaveAndStartRestore(solo) })

        assertTrue(w.groundDrops.isEmpty(), "session 已無人,不留場上垃圾")
        assertTrue(w.permanentInventory.getValue(solo).isEmpty(), "solo 的永久背包不得被寫入")
    }

    @Test
    fun `recipient 全程在場 － 正常收得到,不落地`() {
        val w = World()
        val solo = UUID.randomUUID()
        w.join(solo)
        val ok = RunDelivery.canReceive(
            online = true,
            instanceItemsPresent = true,
            recipientActiveInstanceId = w.activeByPlayer[solo],
            sessionMembershipKnown = true,
            recipientIsActiveMember = solo in w.members,
        )
        assertTrue(ok, "還在跑就正常收得到（不回歸單人局）")
        w.deliverAfterDelay(solo, w.activeByPlayer.getValue(solo), between = {})
        assertTrue(w.groundDrops.isEmpty())
    }

    @Test
    fun `擇祠選取與離場相撞 － pending 已消耗,獎品落到場上給隊友`() {
        val w = World()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        listOf(a, b).forEach { w.join(it) }
        val picked = w.activeByPlayer.getValue(a)

        w.deliverAfterDelay(a, picked, between = { w.leaveAndStartRestore(a) })

        assertEquals(listOf(picked), w.groundDrops)
    }

    @Test
    fun `InstanceItems service 不在時 － 一律放行(維持既有降級)`() {
        assertTrue(
            RunDelivery.canReceive(
                online = true,
                instanceItemsPresent = false,
                recipientActiveInstanceId = null,
                sessionMembershipKnown = true,
                recipientIsActiveMember = false,
            ),
        )
    }

    @Test
    fun `recipient 離線 － 不視為可接收`() {
        assertTrue(
            !RunDelivery.canReceive(
                online = false,
                instanceItemsPresent = true,
                recipientActiveInstanceId = "inst-x",
                sessionMembershipKnown = true,
                recipientIsActiveMember = true,
            ),
        )
    }
}
