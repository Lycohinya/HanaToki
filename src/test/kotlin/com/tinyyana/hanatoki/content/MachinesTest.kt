package com.tinyyana.hanatoki.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 鎖不變式:燈籠狀態不倒退、只完成一次;敵人打散只一次;計時不被轉場取消(自己的佇列)。 */
class MachinesTest {

    @Test
    fun `燈籠 unlit 到 burning 到 lit 單向,不倒退`() {
        val lantern = LanternMachine(100.0)
        assertEquals(LanternMachine.State.UNLIT, lantern.state)
        // 沒點燃之前 advance 什麼都不做
        assertEquals(LanternMachine.Outcome.NONE, lantern.advance(50, 1.0))
        assertEquals(LanternMachine.State.UNLIT, lantern.state)
        assertTrue(lantern.ignite())
        assertFalse(lantern.ignite(), "已在燒不能重複點燃(不重置進度)")
        lantern.advance(40, 1.0)
        assertEquals(0.4, lantern.fraction, 1e-9)
        assertFalse(lantern.ignite())
        assertEquals(0.4, lantern.fraction, 1e-9, "重複點燃不得重置進度")
    }

    @Test
    fun `燈籠只完成一次`() {
        val lantern = LanternMachine(100.0)
        lantern.ignite()
        var completed = 0
        repeat(200) {
            if (lantern.advance(2, 1.0) == LanternMachine.Outcome.COMPLETED) completed++
        }
        assertEquals(1, completed)
        assertEquals(LanternMachine.State.LIT, lantern.state)
        assertEquals(1.0, lantern.fraction)
        // lit 之後怎麼推、怎麼點都不倒退、不再完成
        assertEquals(LanternMachine.Outcome.NONE, lantern.advance(10, 1.0))
        assertEquals(LanternMachine.Outcome.NONE, lantern.advance(10, 0.0))
        assertFalse(lantern.ignite())
        assertEquals(LanternMachine.State.LIT, lantern.state)
    }

    @Test
    fun `燈籠無人時暫停不倒扣,有人回來繼續`() {
        val lantern = LanternMachine(100.0)
        lantern.ignite()
        lantern.advance(30, 1.0)
        assertEquals(LanternMachine.Outcome.PAUSED, lantern.advance(2, 0.0))
        assertEquals(LanternMachine.Outcome.NONE, lantern.advance(2, 0.0), "暫停只通知一次")
        assertEquals(0.3, lantern.fraction, 1e-9, "暫停期間進度不變")
        assertEquals(LanternMachine.Outcome.RESUMED, lantern.advance(10, 1.0))
        assertEquals(0.4, lantern.fraction, 1e-9)
        assertEquals(LanternMachine.State.BURNING, lantern.state)
    }

    @Test
    fun `多人倍率加快燒燈`() {
        val table = mapOf(2 to 1.5, 3 to 2.0)
        assertEquals(0.0, lanternMultiplier(table, 0))
        assertEquals(1.0, lanternMultiplier(table, 1))
        assertEquals(1.5, lanternMultiplier(table, 2))
        assertEquals(2.0, lanternMultiplier(table, 4))
        val solo = LanternMachine(100.0).also { it.ignite() }
        val duo = LanternMachine(100.0).also { it.ignite() }
        solo.advance(50, lanternMultiplier(table, 1))
        duo.advance(50, lanternMultiplier(table, 2))
        assertTrue(duo.fraction > solo.fraction)
    }

    @Test
    fun `柵門只開一次`() {
        val gate = GateMachine()
        assertFalse(gate.open)
        assertTrue(gate.open())
        assertFalse(gate.open())
        assertTrue(gate.open)
    }

    @Test
    fun `敵人未僵直、僵直、打散、消失只往前走`() {
        val e = EnemyMachine()
        assertFalse(e.stunned)
        assertTrue(e.stun())
        val epoch1 = e.stunEpoch
        assertTrue(e.stunned)
        // 再照一次刷新僵直,舊計時失效
        assertTrue(e.stun())
        assertFalse(e.recover(epoch1), "舊的僵直結束計時要失效")
        assertTrue(e.stunned)
        assertTrue(e.recover(e.stunEpoch))
        assertEquals(EnemyMachine.State.ALIVE, e.state)
        assertTrue(e.stun())
        assertTrue(e.dissolve())
        assertFalse(e.dissolve(), "打散只會發生一次")
        assertFalse(e.stun(), "打散後不能再被照到")
        assertFalse(e.recover(e.stunEpoch))
        assertEquals(EnemyMachine.State.DISSOLVING, e.state)
        assertTrue(e.gone())
        assertFalse(e.gone())
        assertFalse(e.dissolve())
    }

    @Test
    fun `未僵直的敵人也能被光圈直接打散`() {
        val e = EnemyMachine()
        assertTrue(e.dissolve())
        assertEquals(EnemyMachine.State.DISSOLVING, e.state)
    }

    @Test
    fun `計時佇列依到期時間與排入順序執行,清掉後不再執行`() {
        val queue = TimerQueue()
        val log = ArrayList<String>()
        queue.schedule(0, 10) { log += "b" }
        queue.schedule(0, 5) { log += "a" }
        queue.schedule(0, 10) { log += "c" }
        queue.runDue(4) { throw it }
        assertTrue(log.isEmpty())
        queue.runDue(10) { throw it }
        assertEquals(listOf("a", "b", "c"), log)
        queue.schedule(10, 5) { log += "x" }
        queue.clear()
        queue.runDue(100) { throw it }
        assertEquals(listOf("a", "b", "c"), log)
    }

    @Test
    fun `計時任務丟例外不影響其他任務`() {
        val queue = TimerQueue()
        var ran = false
        var caught: Throwable? = null
        queue.schedule(0, 1) { error("boom") }
        queue.schedule(0, 1) { ran = true }
        queue.runDue(1) { caught = it }
        assertTrue(ran)
        assertEquals("boom", caught?.message)
    }
}
