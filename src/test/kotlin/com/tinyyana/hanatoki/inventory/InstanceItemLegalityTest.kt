package com.tinyyana.hanatoki.inventory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [InstanceItemLegality] 的真值表。
 *
 * 兩個問法共用同一份 instanceId 比對:
 * - `isLegalFor`(外流防線 InstanceItemGuard):永久物品恆合法。
 * - `heldLegallyInRun`(反向白名單 ForeignItemWarden):反向——非局內物品不算「這一局的」。
 *
 * 2026-09-09 深域組隊 bug 的兩個症狀都能對應到下面某一格:
 * - 隊長的 per-player instanceId 蓋在非隊長的起始裝上 → 反向白名單只認自己的話每 2 秒沒收一次。
 * - 持有者已 startRestore(activeInstanceId == null)→ deliver 排在 restore 之後才跑就把
 *   局內物品塞進已還原的永久背包。
 */
class InstanceItemLegalityTest {

    private val leaderInst = "inst-leader"
    private val memberInst = "inst-member"

    // ---- isLegalFor ---------------------------------------------------------

    @Test
    fun `isLegalFor 永久物品恆合法`() {
        assertTrue(InstanceItemLegality.isLegalFor(false, null, null, emptyList()))
        assertTrue(InstanceItemLegality.isLegalFor(false, null, memberInst, emptyList()))
    }

    @Test
    fun `isLegalFor 有 scope 但 id 讀不出來 = 半殘標記,不合法`() {
        assertFalse(InstanceItemLegality.isLegalFor(true, null, leaderInst, listOf(leaderInst)))
    }

    @Test
    fun `isLegalFor 持有者已不在 active Run = 不合法(restore 之後才跑的 deliver 洩漏路徑)`() {
        assertFalse(InstanceItemLegality.isLegalFor(true, leaderInst, null, listOf(memberInst)))
        assertFalse(InstanceItemLegality.isLegalFor(true, memberInst, null, listOf(memberInst)))
    }

    @Test
    fun `isLegalFor id == 持有者自己這一局的 instanceId = 合法`() {
        assertTrue(InstanceItemLegality.isLegalFor(true, memberInst, memberInst, emptyList()))
    }

    @Test
    fun `isLegalFor 隊友的章一樣合法(深域共享堆,誰走過去撿都行)`() {
        // 非隊長手上/腳邊的東西身上蓋的是隊長的 per-player instanceId。
        assertTrue(InstanceItemLegality.isLegalFor(true, leaderInst, memberInst, listOf(leaderInst, memberInst)))
    }

    @Test
    fun `isLegalFor 別的 session 的章 － 對不上、也不是在場隊友 = 不合法`() {
        assertFalse(InstanceItemLegality.isLegalFor(true, "inst-OTHER", memberInst, listOf(leaderInst, memberInst)))
    }

    @Test
    fun `isLegalFor 隊長離場後,他的章變成誰都配不上(症狀一的殘留 － 地面掉落沒被重蓋章時)`() {
        // 隊長 startRestore → 不在 activeByPlayer、也不在 activeMembers → memberActiveInstanceIds 不含他。
        assertFalse(InstanceItemLegality.isLegalFor(true, leaderInst, memberInst, listOf(memberInst)))
    }

    // ---- heldLegallyInRun -------------------------------------------------

    @Test
    fun `heldLegallyInRun 反向白名單 － 永久物品不算「這一局的」`() {
        assertFalse(InstanceItemLegality.heldLegallyInRun(false, null, memberInst, emptyList()))
    }

    @Test
    fun `heldLegallyInRun 非隊長手上蓋隊長章的起始裝 = 留得住(ForeignItemWarden 不沒收)`() {
        // 這一格是 2026-09-09 的核心回歸:舊版只比「== 持有者自己的 instanceId」→ 沒收。
        assertTrue(
            InstanceItemLegality.heldLegallyInRun(
                scoped = true,
                itemInstanceId = leaderInst,
                holderActiveInstanceId = memberInst,
                memberActiveInstanceIds = listOf(leaderInst, memberInst),
            ),
        )
    }

    @Test
    fun `heldLegallyInRun 自己的局內物品當然留得住`() {
        assertTrue(InstanceItemLegality.heldLegallyInRun(true, memberInst, memberInst, listOf(memberInst)))
    }

    @Test
    fun `heldLegallyInRun 別的 session 的局內物品 = 收走`() {
        assertFalse(InstanceItemLegality.heldLegallyInRun(true, "inst-OTHER", memberInst, listOf(leaderInst, memberInst)))
    }

    @Test
    fun `heldLegallyInRun 持有者已 startRestore = 收走(不再往已還原的背包放行)`() {
        assertFalse(InstanceItemLegality.heldLegallyInRun(true, memberInst, null, listOf(memberInst)))
    }
}
