package com.tinyyana.hanatoki.runtime

import com.tinyyana.hanatoki.runtime.RunPerf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunPerfTest {
    private val second = 1_000_000_000L

    @Test
    fun `quiet ticks never warn and summary counts them`() {
        val perf = RunPerf(second)
        var t = 10 * second
        repeat(5) {
            assertNull(perf.record(t, t + 2_000_000L))
            t += second
        }
        assertEquals(5, perf.ticks)
        assertEquals(0, perf.slowTicks)
        assertEquals(0, perf.lateTicks)
        assertTrue(perf.summary.contains("selfMax=2.0ms"), perf.summary)
    }

    @Test
    fun `slow self time warns once per cooldown`() {
        val perf = RunPerf(second)
        val first = perf.record(0, 40_000_000L)
        assertNotNull(first)
        assertTrue(first.contains("慢"), first)
        // 同樣慢,但在冷卻內 → 不再警告,計數照加
        assertNull(perf.record(second, second + 40_000_000L))
        assertEquals(2, perf.slowTicks)
        // 冷卻過了 → 再警告一次
        assertNotNull(perf.record(31 * second, 31 * second + 40_000_000L))
    }

    @Test
    fun `late interval with cheap self is flagged as region stall`() {
        val perf = RunPerf(second)
        assertNull(perf.record(0, 1_000_000L))
        val warning = perf.record(4 * second, 4 * second + 1_000_000L)
        assertNotNull(warning)
        assertTrue(warning.contains("晚") && !warning.contains("慢"), warning)
        assertEquals(1, perf.lateTicks)
        assertTrue(perf.intervalMaxNanos >= 3 * second)
    }
}
