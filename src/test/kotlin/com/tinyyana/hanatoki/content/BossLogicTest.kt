package com.tinyyana.hanatoki.content

import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.23 Boss 純邏輯：判定區幾何、招式抽選、單人單預警、血量與階段、寶石、倒下追蹤、身體狀態帳本、敵人掃描輪替。 */
class BossLogicTest {

    // ---- 判定區幾何 ----

    @Test
    fun `圓：水平距離與高度差都要在範圍內`() {
        assertTrue(inCircle(1.0, 0.0, 1.0, 2.0))
        assertFalse(inCircle(2.0, 0.0, 2.0, 2.0))
        assertFalse(inCircle(0.0, 5.0, 0.0, 2.0), "不同層不算")
    }

    @Test
    fun `掃地環形波：掃過的區間內被打到，站在波前外側或內側很遠的不會`() {
        // 波前從 3 推到 4.5，帶寬 2：命中 [2, 5.5]
        assertTrue(inRingSweep(3.0, 3.0, 4.5, 2.0))
        assertTrue(inRingSweep(5.4, 3.0, 4.5, 2.0))
        assertFalse(inRingSweep(5.6, 3.0, 4.5, 2.0))
        assertFalse(inRingSweep(1.9, 3.0, 4.5, 2.0))
        // 第一段（波前從 0 起）涵蓋圓心
        assertTrue(inRingSweep(0.0, 0.0, 1.0, 2.0))
    }

    @Test
    fun `環形波取樣再稀疏也不會漏掉被跨過的人`() {
        // 波前每次推 6 格（遠大於帶寬 2），站在 8 的人必須在某一段被掃到
        val player = 8.0
        var hit = 0
        var prev = 0.0
        var front = 0.0
        while (front < 20.0) {
            prev = front
            front += 6.0
            if (inRingSweep(player, prev, front, 2.0)) hit++
        }
        assertTrue(hit >= 1)
    }

    @Test
    fun `十字：兩條垂直的線，楔形空格安全`() {
        // angleOffset 45 度：線在 y=x 與 y=-x
        assertTrue(inCross(5.0, 0.0, 5.0, 14.0, 1.2, 45.0), "在對角線上")
        assertTrue(inCross(5.0, 0.0, -5.0, 14.0, 1.2, 45.0), "另一條對角線")
        assertFalse(inCross(5.0, 0.0, 0.0, 14.0, 1.2, 45.0), "正東方的空格安全")
        assertFalse(inCross(0.0, 0.0, 6.0, 14.0, 1.2, 45.0), "正南方的空格安全")
        assertTrue(inCross(0.2, 0.0, 0.1, 14.0, 1.2, 45.0), "原點在線上")
        assertFalse(inCross(12.0, 0.0, 12.0, 10.0, 1.2, 45.0), "超過線長（對角線距離 16.9 > 10）")
        // 0 度：線在座標軸上
        assertTrue(inCross(6.0, 0.0, 0.5, 14.0, 1.2, 0.0))
        assertFalse(inCross(6.0, 0.0, 6.0, 14.0, 1.2, 0.0))
    }

    @Test
    fun `迴旋光束環：缺口裡安全，缺口外與環內外的距離規則`() {
        // 缺口中心朝東（0 度），張角 80：±40 度內安全
        assertFalse(inSpinBeam(6.0, 0.0, 0.0, 2.0, 11.0, 0.0, 80.0), "缺口正中")
        assertFalse(inSpinBeam(6.0, 0.0, 3.0, 2.0, 11.0, 0.0, 80.0), "缺口邊緣內（約 26.6 度）")
        assertTrue(inSpinBeam(6.0, 0.0, 6.0, 2.0, 11.0, 0.0, 80.0), "45 度，在缺口外")
        assertTrue(inSpinBeam(-6.0, 0.0, 0.0, 2.0, 11.0, 0.0, 80.0), "背面")
        assertFalse(inSpinBeam(1.0, 0.0, 1.0, 2.0, 11.0, 0.0, 80.0), "內圈太近，安全")
        assertFalse(inSpinBeam(0.0, 0.0, 15.0, 2.0, 11.0, 0.0, 80.0), "超過外圈，安全")
        // 缺口轉到朝北（90 度），原本在缺口裡的人變成中招
        assertTrue(inSpinBeam(6.0, 0.0, 0.0, 2.0, 11.0, 90.0, 80.0))
        assertFalse(inSpinBeam(0.0, 0.0, 6.0, 2.0, 11.0, 90.0, 80.0))
    }

    @Test
    fun `角度差跨過 0 與 360 度也算最小夾角`() {
        assertEquals(20.0, angleDiffDegrees(350.0, 10.0), 1.0E-9)
        assertEquals(180.0, angleDiffDegrees(0.0, 180.0), 1.0E-9)
        assertEquals(10.0, angleDiffDegrees(-5.0, 5.0), 1.0E-9)
        assertEquals(0.0, angleDegrees(1.0, 0.0), 1.0E-9)
        assertEquals(90.0, angleDegrees(0.0, 1.0), 1.0E-9)
        assertEquals(270.0, angleDegrees(0.0, -1.0), 1.0E-9)
    }

    @Test
    fun `滑行一步不會超過目標`() {
        val (p, arrived) = glideStep(Pt(0.0, 0.0, 0.0), Pt(10.0, 0.0, 0.0), 0.12)
        assertFalse(arrived)
        assertEquals(0.12, p.x, 1.0E-9)
        val (q, done) = glideStep(Pt(9.95, 0.0, 0.0), Pt(10.0, 0.0, 0.0), 0.12)
        assertTrue(done)
        assertEquals(10.0, q.x, 1.0E-9)
    }

    // ---- 敵人掃描輪替（H1 遺留：take(24) 沒有輪替）----

    @Test
    fun `超過單輪上限的敵人跨輪輪替，兩輪內每隻都評估到`() {
        val ids = (1..30).map { "wave-$it" }
        val seen = HashSet<String>()
        var cursor: String? = null
        repeat(2) {
            val window = rotatingWindow(ids, cursor, 24) { it }
            assertEquals(24, window.size)
            seen += window
            cursor = window.last()
        }
        assertEquals(ids.toSet(), seen)
    }

    @Test
    fun `輪替視窗：數量不超過上限時全取，沒有重複，游標換新後仍接著走`() {
        val few = listOf("b", "a", "c")
        assertEquals(listOf("a", "b", "c"), rotatingWindow(few, "b", 24) { it })
        val many = (1..10).map { "e%02d".format(it) }
        val w1 = rotatingWindow(many, null, 4) { it }
        assertEquals(listOf("e01", "e02", "e03", "e04"), w1)
        val w2 = rotatingWindow(many, w1.last(), 4) { it }
        assertEquals(listOf("e05", "e06", "e07", "e08"), w2)
        val w3 = rotatingWindow(many, w2.last(), 4) { it }
        assertEquals(listOf("e09", "e10", "e01", "e02"), w3, "尾端接回開頭")
    }

    @Test
    fun `輪替期間敵人增減，游標以 key 為準不會漏掉或卡住`() {
        val all = (1..30).map { "w%02d".format(it) }.toMutableList()
        val first = rotatingWindow(all, null, 24) { it }
        assertEquals("w24", first.last())
        all.remove("w25") // 游標之後的一隻消失
        val second = rotatingWindow(all, first.last(), 24) { it }
        assertTrue("w26" in second && "w25" !in second)
        assertEquals(24, second.size)
        // 游標那一隻自己消失了：從第一個比它大的接著
        all.remove("w24")
        val third = rotatingWindow(all, "w24", 4) { it }
        assertEquals(listOf("w26", "w27", "w28", "w29"), third)
    }

    // ---- 招式抽選 ----

    private val moves = listOf(
        MoveCandidate("sweep", 3, 120, 1),
        MoveCandidate("strike", 3, 0, 1),
        MoveCandidate("cross", 2, 0, 2),
        MoveCandidate("spin", 2, 0, 3),
    )

    @Test
    fun `招式依階段開放，沒開放的永遠抽不到`() {
        val random = Random(1)
        val picked = (1..500).map { MovePicker.pick(moves, 1, 10_000, emptyMap(), null, { true }, random)!! }.toSet()
        assertEquals(setOf("sweep", "strike"), picked)
        val p2 = (1..500).map { MovePicker.pick(moves, 2, 10_000, emptyMap(), null, { true }, random)!! }.toSet()
        assertEquals(setOf("sweep", "strike", "cross"), p2)
        val p3 = (1..800).map { MovePicker.pick(moves, 3, 10_000, emptyMap(), null, { true }, random)!! }.toSet()
        assertEquals(setOf("sweep", "strike", "cross", "spin"), p3)
    }

    @Test
    fun `冷卻中的招式抽不到，冷卻過了再開放`() {
        val random = Random(2)
        val used = mapOf("sweep" to 1_000L)
        repeat(100) { assertEquals("strike", MovePicker.pick(moves, 1, 1_050, used, null, { true }, random), "冷卻 120 tick 還沒過") }
        val later = (1..200).map { MovePicker.pick(moves, 1, 1_200, used, null, { true }, random)!! }.toSet()
        assertTrue("sweep" in later)
    }

    @Test
    fun `有兩招以上可選時不連續兩次同一招；只有一招時照用`() {
        val random = Random(3)
        repeat(200) { assertTrue(MovePicker.pick(moves, 1, 10_000, emptyMap(), "sweep", { true }, random) == "strike") }
        val only = listOf(MoveCandidate("a", 1, 0, 1))
        assertEquals("a", MovePicker.pick(only, 1, 0, emptyMap(), "a", { true }, random))
    }

    @Test
    fun `available 擋掉的招式不抽，全部不可用回 null`() {
        val random = Random(4)
        assertEquals("strike", MovePicker.pick(moves, 1, 10_000, emptyMap(), null, { it != "sweep" }, random))
        assertNull(MovePicker.pick(moves, 1, 10_000, emptyMap(), null, { false }, random))
    }

    @Test
    fun `權重越大越常被抽到`() {
        val random = Random(5)
        val two = listOf(MoveCandidate("heavy", 9, 0, 1), MoveCandidate("light", 1, 0, 1))
        val counts = HashMap<String, Int>()
        repeat(4000) { counts.merge(MovePicker.pick(two, 1, 0, emptyMap(), null, { true }, random)!!, 1, Int::plus) }
        assertTrue(counts.getValue("heavy") > counts.getValue("light") * 4, counts.toString())
    }

    // ---- 單人單預警 ----

    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val cara = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    @Test
    fun `同一位玩家同時最多一個預警，結束後才能再開`() {
        val ledger = TelegraphLedger()
        assertTrue(ledger.tryBegin(alice, "cast-1"))
        assertFalse(ledger.tryBegin(alice, "cast-2"), "已有預警的目標被跳過")
        assertTrue(ledger.tryBegin(bob, "cast-2"), "別的玩家不受影響")
        assertEquals(listOf(alice), ledger.end("cast-1"))
        assertTrue(ledger.tryBegin(alice, "cast-3"))
        assertEquals(2, ledger.size)
    }

    @Test
    fun `整批預警：只要有人已有預警就整批延後，什麼都不登記`() {
        val ledger = TelegraphLedger()
        assertTrue(ledger.tryBegin(alice, "cast-1"))
        assertFalse(ledger.tryBeginAll(listOf(alice, bob, cara), "cast-2"))
        assertFalse(ledger.isActive(bob), "沒有半套登記")
        ledger.end("cast-1")
        assertTrue(ledger.tryBeginAll(listOf(alice, bob, cara), "cast-2"))
        assertEquals(3, ledger.size)
        ledger.clear()
        assertEquals(0, ledger.size)
    }

    // ---- 血量（命中數）與階段 ----

    @Test
    fun `人數倍率換算總命中數`() {
        val table = mapOf(2 to BossMultipliers(1.6, 1.0), 3 to BossMultipliers(2.2, 0.8))
        assertEquals(40.0, scaledMaxHits(40.0, table, 1))
        assertEquals(64.0, scaledMaxHits(40.0, table, 2))
        assertEquals(88.0, scaledMaxHits(40.0, table, 3))
        assertEquals(88.0, scaledMaxHits(40.0, table, 5), "超過最大 key 用最大 key")
        assertEquals(0.8, damageMultiplier(table, 3))
        assertEquals(1.0, damageMultiplier(table, 1))
    }

    @Test
    fun `血量門檻決定階段，歸零才算打倒`() {
        val health = BossHealth(40.0, listOf(1.0, 0.55, 0.25))
        assertEquals(1, health.phaseByHealth())
        repeat(17) { health.hit(1.0) } // 剩 23/40 = 0.575
        assertEquals(1, health.phaseByHealth())
        health.hit(1.0) // 剩 22/40 = 0.55：剛好踩線進第二階段
        assertEquals(2, health.phaseByHealth())
        repeat(12) { health.hit(1.0) } // 剩 10/40 = 0.25
        assertEquals(3, health.phaseByHealth())
        assertFalse(health.defeated)
        repeat(9) { health.hit(1.0) }
        assertFalse(health.defeated)
        health.hit(1.0)
        assertTrue(health.defeated)
        assertEquals(0.0, health.fraction)
        health.hit(5.0)
        assertEquals(40.0, health.taken, "不會扣到負數")
        health.reset()
        assertEquals(1.0, health.fraction)
        assertEquals(1, health.phaseByHealth())
    }

    @Test
    fun `一下很重的命中可以直接跨過階段，權重可調`() {
        val health = BossHealth(40.0, listOf(1.0, 0.55, 0.25))
        health.hit(32.0) // 剩 8/40 = 0.2
        assertEquals(3, health.phaseByHealth())
        val light = BossHealth(10.0, listOf(1.0))
        repeat(10) { light.hit(0.5) }
        assertFalse(light.defeated)
        assertEquals(5.0, light.remaining)
        assertFailsWith<IllegalArgumentException> { BossHealth(0.0, listOf(1.0)) }
    }

    // ---- 寶石 ----

    @Test
    fun `寶石數量等於亮著的場邊燈，熄一盞就恢復一顆，四盞同時亮才算全碎`() {
        val gems = GemTracker(listOf("l1", "l2", "l3", "l4"))
        assertTrue(gems.setLit("l1", true))
        assertFalse(gems.setLit("l1", true), "重複點亮不算變化")
        gems.setLit("l2", true); gems.setLit("l3", true)
        assertEquals(3, gems.litCount)
        assertFalse(gems.allLit)
        assertTrue(gems.setLit("l2", false), "被熄掉，寶石恢復")
        gems.setLit("l4", true)
        assertFalse(gems.allLit, "l2 熄了，四盞沒有同時亮著")
        gems.setLit("l2", true)
        assertTrue(gems.allLit)
        assertEquals(listOf("l1", "l2", "l3", "l4"), gems.litLamps())
        gems.clear()
        assertEquals(0, gems.litCount)
        assertFailsWith<IllegalArgumentException> { gems.setLit("nope", true) }
    }

    // ---- 倒下追蹤與全滅 ----

    @Test
    fun `全滅：在線的人全部倒下才算，離線的人不算存活也不擋全滅`() {
        val downed = setOf(alice)
        // alice 倒下、bob 還站著
        assertFalse(isWipe(listOf(alice, bob), { true }, { it in downed }))
        // bob 斷線（寬限期內）：在線的只剩 alice 且倒下 → 全滅
        assertTrue(isWipe(listOf(alice, bob), { it == alice }, { it in downed }))
        // 沒有任何人在線：不算全滅（沒有人可以重來）
        assertFalse(isWipe(listOf(alice, bob), { false }, { it in downed }))
        assertFalse(isWipe(emptyList(), { true }, { true }))
        assertTrue(isWipe(listOf(alice, bob), { true }, { true }))
    }

    @Test
    fun `倒下追蹤：同一個人只能倒下一次，復活後可再次倒下`() {
        val tracker = DownedTracker()
        assertTrue(tracker.down(alice))
        assertFalse(tracker.down(alice))
        assertTrue(tracker.contains(alice))
        assertTrue(tracker.revive(alice))
        assertFalse(tracker.revive(alice))
        assertTrue(tracker.down(alice))
        tracker.down(bob)
        assertEquals(setOf(alice, bob), tracker.clear().toSet())
        assertEquals(0, tracker.size)
    }

    // ---- 倒下身體狀態帳本 ----

    class FakeBody(var state: BodyState, var markerState: BodyState? = null) : BodyPort {
        var applies = 0
        override fun read() = state
        override fun apply(state: BodyState) { this.state = state; applies++ }
        override fun marker() = markerState
        override fun setMarker(state: BodyState?) { markerState = state }
    }

    private val original = BodyState("SURVIVAL", invisible = false, glowing = false, invulnerable = false, speedModifier = 0.0)
    private val downedState = BodyState("ADVENTURE", invisible = true, glowing = true, invulnerable = true, speedModifier = -0.8)

    @Test
    fun `套用倒下狀態只記一次原本狀態，還原整組套回並清掉記號`() {
        val ledger = DownedBodyLedger()
        val body = FakeBody(original)
        ledger.apply(alice, body, downedState)
        assertEquals(downedState, body.state)
        assertEquals(original, body.markerState, "PDC 記號存的是原本狀態")
        // 重複套用（例如重連後）不會把倒下狀態當成原本狀態
        ledger.apply(alice, body, downedState)
        assertTrue(ledger.restore(alice, body))
        assertEquals(original, body.state)
        assertNull(body.markerState)
        assertFalse(ledger.isApplied(alice))
    }

    @Test
    fun `還原冪等：第二次什麼都不做`() {
        val ledger = DownedBodyLedger()
        val body = FakeBody(original)
        ledger.apply(alice, body, downedState)
        assertTrue(ledger.restore(alice, body))
        val applies = body.applies
        assertFalse(ledger.restore(alice, body))
        assertEquals(applies, body.applies, "沒有動玩家")
    }

    @Test
    fun `記憶體帳本遺失（關服或當機）時，靠玩家身上的記號還原`() {
        val first = DownedBodyLedger()
        val body = FakeBody(original)
        first.apply(alice, body, downedState)
        // 重啟：新的帳本，玩家資料裡還留著倒下的狀態與記號
        val second = DownedBodyLedger()
        assertTrue(second.restore(alice, body))
        assertEquals(original, body.state)
        assertNull(body.markerState)
    }

    @Test
    fun `斷線期間重新套用，原本狀態仍是最初那一份`() {
        val ledger = DownedBodyLedger()
        val body = FakeBody(original)
        ledger.apply(alice, body, downedState)
        ledger.restore(alice, body) // 斷線時同步還原
        // 重連：玩家資料是還原後的原本狀態；再次套用，原本狀態還是 original
        ledger.apply(alice, body, downedState)
        assertEquals(downedState, body.state)
        ledger.restore(alice, body)
        assertEquals(original, body.state)
    }

    // ---- 機器 ----

    @Test
    fun `場邊燈重置：只有明確呼叫 reset 才會回到未點亮，一般 ignite 流程不倒退`() {
        val lantern = LanternMachine(60.0)
        assertTrue(lantern.ignite())
        assertEquals(LanternMachine.Outcome.COMPLETED, lantern.advance(60, 1.0))
        assertEquals(LanternMachine.State.LIT, lantern.state)
        assertFalse(lantern.ignite(), "亮了不能再點")
        lantern.reset()
        assertEquals(LanternMachine.State.UNLIT, lantern.state)
        assertEquals(0.0, lantern.progressTicks)
        assertTrue(lantern.ignite())
        assertEquals(LanternMachine.Outcome.COMPLETED, lantern.advance(60, 1.0), "重置後可以再燒滿")
    }

    @Test
    fun `南瓜燈下架上架：下架後拿不到也不會重生，上架立刻可拿`() {
        val pumpkin = PowerUpMachine(consume = true, respawnTicks = 100, poweredTicks = 120)
        pumpkin.forceTaken()
        assertFalse(pumpkin.canTake(0))
        assertFalse(pumpkin.tryRespawn(10_000), "下架不自動重生")
        pumpkin.forceReady()
        assertTrue(pumpkin.take(0))
        assertEquals(PowerUpMachine.State.TAKEN, pumpkin.state)
    }
}
