package com.tinyyana.hanatoki.instance

import com.tinyyana.hanatoki.api.DungeonEntryStatus
import com.tinyyana.hanatoki.api.EntryEligibility
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 內容資格的多 provider 判定([EntryEligibilityChain]),以及它接進 [EntryAdmission] 之後
 * 對 1..party-cap 人名單的逐人檢查與人數邊界(`DungeonAccess.enterDungeonPartyTracked` 走的就是這條 admission)。
 */
class EntryEligibilityChainTest {

    private val warnings = mutableListOf<String>()
    private val providers = mutableListOf<EntryEligibilityChain.Provider>()
    private val chain = EntryEligibilityChain(
        providers = { providers.toList() },
        warn = { warnings += it },
        errorReason = { "進場資格暫時查不到,稍後再試" },
    )

    private fun provider(owner: String, fn: (UUID, String) -> String?) {
        providers += EntryEligibilityChain.Provider(owner, EntryEligibility { p, d -> fn(p, d) })
    }

    @Test
    fun `no provider means no opinion`() {
        assertNull(chain.block(UUID.randomUUID(), "mist"))
    }

    @Test
    fun `null and blank answers allow, any non-blank answer blocks with its reason`() {
        val p = UUID.randomUUID()
        provider("Allow") { _, _ -> null }
        provider("Blank") { _, _ -> "  " }
        assertNull(chain.block(p, "mist"))
        provider("Prologue") { _, d -> if (d == "mist") "先跟使魔聊完序章" else null }
        assertEquals("先跟使魔聊完序章", chain.block(p, "mist"))
        assertNull(chain.block(p, "other"))
    }

    @Test
    fun `first blocking provider wins in registration order`() {
        val p = UUID.randomUUID()
        provider("A") { _, _ -> null }
        provider("B") { _, _ -> "B 擋下" }
        provider("C") { _, _ -> "C 擋下" }
        assertEquals("B 擋下", chain.block(p, "mist"))
    }

    @Test
    fun `throwing provider blocks with engine text and logs owner`() {
        val p = UUID.randomUUID()
        provider("Allow") { _, _ -> null }
        provider("Broken") { _, _ -> throw IllegalStateException("cache not ready") }
        provider("Never") { _, _ -> error("不該被問到") }
        assertEquals("進場資格暫時查不到,稍後再試", chain.block(p, "mist"))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Broken"))
    }

    @Test
    fun `linkage error from a provider also blocks`() {
        provider("Stale") { _, _ -> throw NoSuchMethodError("old jar") }
        assertEquals("進場資格暫時查不到,稍後再試", chain.block(UUID.randomUUID(), "mist"))
        assertEquals(1, warnings.size)
    }

    // ---- 接進 EntryAdmission:逐人檢查 + party 人數邊界 ----

    private class Ports(private val chain: EntryEligibilityChain, val cap: Int) : EntryAdmission.Ports {
        val online = HashSet<UUID>()
        override fun partyCap(dungeonId: String): Int? = if (dungeonId == "mist") cap else null
        override fun isOnline(playerId: UUID): Boolean = playerId in online
        override fun nameOf(playerId: UUID): String = playerId.toString().take(4)
        override fun isInSession(playerId: UUID): Boolean = false
        override fun isSettling(playerId: UUID): Boolean = false
        override fun eligibilityBlock(playerId: UUID, dungeonId: String): String? = chain.block(playerId, dungeonId)
    }

    private fun admission(cap: Int): Pair<Ports, EntryAdmission> {
        val ports = Ports(chain, cap)
        return ports to EntryAdmission(Any(), ports)
    }

    private fun Ports.players(n: Int): List<UUID> = List(n) { UUID.randomUUID().also { online += it } }

    @Test
    fun `party of 1 to cap is admitted, cap plus 1, empty and duplicates are PARTY_INVALID`() {
        val (ports, a) = admission(cap = 4)
        val party = ports.players(5)
        for (n in 1..4) assertNull(a.check(party.take(n), "mist"), "party of $n should pass")
        assertEquals(DungeonEntryStatus.PARTY_INVALID, a.check(party, "mist")?.status)
        assertEquals(DungeonEntryStatus.PARTY_INVALID, a.check(emptyList(), "mist")?.status)
        assertEquals(
            DungeonEntryStatus.PARTY_INVALID,
            a.check(listOf(party[0], party[1], party[0]), "mist")?.status,
        )
    }

    @Test
    fun `only the last of four members blocked still rejects the whole party with that reason`() {
        val (ports, a) = admission(cap = 4)
        val party = ports.players(4)
        val asked = mutableListOf<UUID>()
        provider("Prologue") { p, _ ->
            asked += p
            if (p == party[3]) "第四位還沒確認序章" else null
        }
        val r = a.check(party, "mist")!!
        assertEquals(DungeonEntryStatus.NOT_ELIGIBLE, r.status)
        assertEquals("第四位還沒確認序章", r.reason)
        assertEquals(party, asked)
        // entryBlockReason 走的預檢回報同一個原因
        assertEquals("第四位還沒確認序章", a.blockReason(party[3], "mist"))
        assertNull(a.blockReason(party[0], "mist"))
    }

    @Test
    fun `provider exception rejects admission as NOT_ELIGIBLE and leaves no reservation`() {
        val (ports, a) = admission(cap = 4)
        val party = ports.players(2)
        provider("Broken") { _, _ -> throw RuntimeException("boom") }
        val r = a.admitAndReserve(party, "mist")!!
        assertEquals(DungeonEntryStatus.NOT_ELIGIBLE, r.status)
        assertEquals("進場資格暫時查不到,稍後再試", r.reason)
        party.forEach { assertTrue(!a.isPending(it)) }
    }
}
