package com.tinyyana.hanatoki.inventory

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 副本場地掃描的去留(2026-09-29 Pandora 忠誠三叉戟遺失)。修正前的規則是「Item 與 AbstractArrow
 * 一律移除」,這裡每一個 KEEP / RETURN_TO_OWNER 在修正前都是 REMOVE。
 */
class SweepPolicyTest {
    private val member = UUID(0, 1)
    private val stranger = UUID(0, 2)
    private val members = setOf(member)
    private val tokens = setOf("token-now")

    private fun decide(e: SweptEntity, phase: SweepPhase) = SweepPolicy.decide(e, phase, members, tokens)

    @Test
    fun `a member's trident survives the entry re-sweep`() {
        val trident = SweptEntity(SweptKind.TRIDENT, member, playerProperty = true)
        assertEquals(SweepAction.KEEP, decide(trident, SweepPhase.ENTRY))
    }

    @Test
    fun `a trident left behind goes back to its owner instead of being deleted`() {
        assertEquals(SweepAction.RETURN_TO_OWNER, decide(SweptEntity(SweptKind.TRIDENT, member, playerProperty = true), SweepPhase.CLEANUP))
        // 上一局別人留下的,開局時也送回去,不留給這一局的人撿
        assertEquals(SweepAction.RETURN_TO_OWNER, decide(SweptEntity(SweptKind.TRIDENT, stranger, playerProperty = true), SweepPhase.ENTRY))
    }

    @Test
    fun `mob and unowned tridents are still cleaned`() {
        // 溺屍的三叉戟 pickup = DISALLOWED
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.TRIDENT, stranger, playerProperty = false), SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.TRIDENT, null, playerProperty = false), SweepPhase.CLEANUP))
    }

    @Test
    fun `arrows keep the old cleanup except a member's during entry`() {
        assertEquals(SweepAction.KEEP, decide(SweptEntity(SweptKind.ARROW, member), SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.ARROW, stranger), SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.ARROW, member), SweepPhase.CLEANUP))
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.ARROW, null), SweepPhase.ENTRY))
    }

    @Test
    fun `stamped items belong to the session that issued the token`() {
        val current = SweptEntity(SweptKind.ITEM, null, instanceScoped = true, instanceToken = "token-now")
        val stale = SweptEntity(SweptKind.ITEM, null, instanceScoped = true, instanceToken = "token-old")
        val broken = SweptEntity(SweptKind.ITEM, null, instanceScoped = true, instanceToken = null)
        assertEquals(SweepAction.KEEP, decide(current, SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(stale, SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(broken, SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(current, SweepPhase.CLEANUP))
    }

    @Test
    fun `plain drops keep the old cleanup except a member's during entry, trident drops go home`() {
        assertEquals(SweepAction.KEEP, decide(SweptEntity(SweptKind.ITEM, member), SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.ITEM, stranger), SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.ITEM, null), SweepPhase.ENTRY))
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.ITEM, member), SweepPhase.CLEANUP))
        // 主人死亡時忠誠三叉戟變成的掉落物(TridentOwnerKeeper 記了主人)
        assertEquals(SweepAction.RETURN_TO_OWNER, decide(SweptEntity(SweptKind.ITEM, member, tridentStack = true), SweepPhase.CLEANUP))
        assertEquals(SweepAction.RETURN_TO_OWNER, decide(SweptEntity(SweptKind.ITEM, stranger, tridentStack = true), SweepPhase.ENTRY))
        // 認不出主人的三叉戟掉落物維持舊行為
        assertEquals(SweepAction.REMOVE, decide(SweptEntity(SweptKind.ITEM, null, tridentStack = true), SweepPhase.CLEANUP))
    }
}
