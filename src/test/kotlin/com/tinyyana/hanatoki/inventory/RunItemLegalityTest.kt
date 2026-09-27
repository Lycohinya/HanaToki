package com.tinyyana.hanatoki.inventory

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-09-27 深域多人事故的回歸測試:拾取、掃背包與巡檢共用 [RunItemLegality] 之後,
 * 隊友 token 的共用物品在任何一條防線上都合法,異局/失效/半殘物品在任何一條防線上都不合法。
 *
 * [Party] 模擬引擎的登記方式:每位成員各自一份 token(per-player journal),activate 時登記進
 * session 的 token 清單,離場時只拿掉他自己的 active 狀態,清單要等整局結束才收掉。
 */
class RunItemLegalityTest {

    private class Party(size: Int) {
        val sessionId: UUID = UUID.randomUUID()
        val registry = SessionTokenRegistry()
        val members = List(size) { UUID.randomUUID() }
        val tokens = members.associateWith { UUID.randomUUID() }
        val active = HashMap<UUID, UUID>()

        init {
            members.forEach { activate(it) }
        }

        fun activate(member: UUID) {
            registry.register(sessionId, tokens.getValue(member))
            active[member] = tokens.getValue(member)
        }

        /** 死亡/撤離/外部傳送/grace 逾時:startRestore 拿掉的是他的 active 狀態。 */
        fun leave(member: UUID) {
            active.remove(member)
        }

        fun verdict(holder: UUID, itemToken: String?, scoped: Boolean = true): RunItemVerdict {
            val own = active[holder]
            val sessionTokens = if (own == null) emptySet() else registry.tokensOf(sessionId) + own.toString()
            return RunItemLegality.classify(scoped, itemToken, own?.toString(), sessionTokens)
        }

        /** 深域的起始武器與掉落:整隊共用第一位 ready 成員的 token。 */
        fun sharedToken(): String = tokens.getValue(members.first()).toString()
    }

    @Test
    fun `permanent items are classified permanent regardless of run state`() {
        val party = Party(1)
        assertEquals(RunItemVerdict.PERMANENT, party.verdict(party.members[0], null, scoped = false))
        party.leave(party.members[0])
        assertEquals(RunItemVerdict.PERMANENT, party.verdict(party.members[0], null, scoped = false))
    }

    @Test
    fun `shared starter and drops stay legal for every member of a 1 to 3 player party across repeated sweeps`() {
        for (size in 1..3) {
            val party = Party(size)
            val shared = party.sharedToken()
            // 巡檢每兩秒一次:同一件物品連續被判定幾十次都不能翻成不合法。
            repeat(30) {
                party.members.forEach { member ->
                    assertEquals(RunItemVerdict.RUN_LEGAL, party.verdict(member, shared), "size=$size member=$member")
                }
            }
        }
    }

    @Test
    fun `items exchanged between teammates are legal for the receiver`() {
        val party = Party(3)
        val fromSecond = party.tokens.getValue(party.members[1]).toString()
        assertEquals(RunItemVerdict.RUN_LEGAL, party.verdict(party.members[0], fromSecond))
        assertEquals(RunItemVerdict.RUN_LEGAL, party.verdict(party.members[2], fromSecond))
    }

    @Test
    fun `token holder leaving first does not invalidate shared items for the rest`() {
        val party = Party(3)
        val shared = party.sharedToken()
        party.leave(party.members[0]) // 死亡 / 撤離 / 外部傳送
        assertEquals(RunItemVerdict.RUN_LEGAL, party.verdict(party.members[1], shared))
        assertEquals(RunItemVerdict.RUN_LEGAL, party.verdict(party.members[2], shared))
        // 已經離場的持有人自己手上的局內物品則全部失效(他的背包要被還原覆蓋)。
        assertEquals(RunItemVerdict.RUN_ILLEGAL, party.verdict(party.members[0], shared))
    }

    @Test
    fun `reconnecting within grace keeps items legal`() {
        val party = Party(2)
        val shared = party.sharedToken()
        // 斷線在 grace 內:引擎不 startRestore,active 狀態沒變。
        assertEquals(RunItemVerdict.RUN_LEGAL, party.verdict(party.members[1], shared))
    }

    @Test
    fun `items from another session are illegal even in the same dungeon`() {
        val a = Party(2)
        val b = Party(2)
        assertEquals(RunItemVerdict.RUN_ILLEGAL, a.verdict(a.members[0], b.sharedToken()))
        assertEquals(RunItemVerdict.RUN_ILLEGAL, b.verdict(b.members[1], a.tokens.getValue(a.members[1]).toString()))
    }

    @Test
    fun `expired items after the session is forgotten are illegal`() {
        val party = Party(2)
        val shared = party.sharedToken()
        party.members.forEach { party.leave(it) }
        party.registry.forget(party.sessionId)
        party.members.forEach { assertEquals(RunItemVerdict.RUN_ILLEGAL, party.verdict(it, shared)) }
    }

    @Test
    fun `crash recovery starts with an empty registry so every old run item is illegal`() {
        val before = Party(2)
        val shared = before.sharedToken()
        // 重啟:記憶體登記全空,玩家下一局拿到全新的 session 與 token。
        val after = Party(2)
        assertEquals(RunItemVerdict.RUN_ILLEGAL, after.verdict(after.members[0], shared))
    }

    @Test
    fun `broken scoped items are illegal`() {
        val party = Party(1)
        assertEquals(RunItemVerdict.RUN_ILLEGAL, party.verdict(party.members[0], null))
        assertEquals(RunItemVerdict.RUN_ILLEGAL, party.verdict(party.members[0], ""))
        assertEquals(RunItemVerdict.RUN_ILLEGAL, party.verdict(party.members[0], "not-a-token"))
    }

    @Test
    fun `player outside any run cannot hold run items`() {
        val party = Party(1)
        val own = party.tokens.getValue(party.members[0]).toString()
        party.leave(party.members[0])
        assertEquals(RunItemVerdict.RUN_ILLEGAL, party.verdict(party.members[0], own))
    }

    @Test
    fun `registry keeps tokens of departed members until the session is forgotten`() {
        val registry = SessionTokenRegistry()
        val session = UUID.randomUUID()
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        registry.register(session, t1)
        registry.register(session, t2)
        registry.register(session, t1) // 重複登記無副作用
        assertEquals(setOf(t1.toString(), t2.toString()), registry.tokensOf(session))
        registry.forget(session)
        assertTrue(registry.tokensOf(session).isEmpty())
    }

    @Test
    fun `warden sweep keeps legal items, sends permanent to returns and discards invalid run items`() {
        val party = Party(2)
        val holder = party.members[1]
        data class Stack(val name: String, val scoped: Boolean, val token: String?)
        val inventory = listOf(
            Stack("starter-sword", true, party.sharedToken()),
            Stack("own-food", true, party.tokens.getValue(holder).toString()),
            Stack("quest-reward", false, null),
            Stack("old-run-compass", true, UUID.randomUUID().toString()),
            Stack("half-marked", true, null),
        )
        val kept = mutableListOf<String>()
        val toReturns = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        for (s in inventory) {
            when (party.verdict(holder, s.token, s.scoped)) {
                RunItemVerdict.RUN_LEGAL -> kept += s.name
                RunItemVerdict.PERMANENT -> toReturns += s.name
                RunItemVerdict.RUN_ILLEGAL -> discarded += s.name
            }
        }
        assertEquals(listOf("starter-sword", "own-food"), kept)
        // 只有沒有局內章的永久物品會進暫存箱;失效的局內物品不會被洗成永久物。
        assertEquals(listOf("quest-reward"), toReturns)
        assertEquals(listOf("old-run-compass", "half-marked"), discarded)
    }
}
