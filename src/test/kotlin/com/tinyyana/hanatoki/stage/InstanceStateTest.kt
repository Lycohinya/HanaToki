package com.tinyyana.hanatoki.stage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceStateTest {
    private fun graph() = StageGraph(
        "entry",
        mapOf(
            "entry" to StageDefinition("entry"),
            "puzzle" to StageDefinition("puzzle", timeoutSeconds = 10),
        ),
    )

    @Test
    fun `one-shot trigger 只能觸發一次`() {
        val s = InstanceState(graph())
        assertTrue(s.tryFireOnce("lamp_a"))
        assertFalse(s.tryFireOnce("lamp_a"))
    }

    @Test
    fun `重進同一 stage 會重置 one-shot 旗標(錯解重置語意)`() {
        val s = InstanceState(graph())
        s.enterStage("puzzle", 0)
        assertTrue(s.tryFireOnce("lamp_a"))
        s.enterStage("puzzle", 100)
        assertTrue(s.tryFireOnce("lamp_a")) // 重進後又能再觸發一次
    }

    @Test
    fun `repeatable trigger 冷卻內第二次呼叫失敗`() {
        val s = InstanceState(graph())
        assertTrue(s.tryFireRepeatable("mural", 5000, 0))
        assertFalse(s.tryFireRepeatable("mural", 5000, 4000))
        assertTrue(s.tryFireRepeatable("mural", 5000, 6000))
    }

    @Test
    fun `stage timeout 依 timeoutSeconds 判定`() {
        val s = InstanceState(graph())
        s.enterStage("puzzle", 0)
        assertFalse(s.isStageTimedOut(9_999))
        assertTrue(s.isStageTimedOut(10_000))
    }

    @Test
    fun `沒有 timeoutSeconds 的 stage 永不逾時`() {
        val s = InstanceState(graph())
        s.enterStage("entry", 0)
        assertFalse(s.isStageTimedOut(Long.MAX_VALUE - 1))
    }

    @Test
    fun `一個 stage 只認領得到一次 Resolution,轉場後重新可用`() {
        val s = InstanceState(graph())
        s.enterStage("entry", 0)
        assertTrue(s.claimResolution())
        assertFalse(s.claimResolution())
        // 常駐副本靠這條「每個 stage 一次」的語意表達「一輪 Boss 只發一次獎」:
        // Boss 死 → resolve → 轉回待機 → 下一輪重新可用。
        s.enterStage("puzzle", 100)
        assertTrue(s.claimResolution())
    }

    @Test
    fun `state bag 讀寫`() {
        val s = InstanceState(graph())
        assertEquals(null, s.get("lit"))
        s.set("lit", listOf("a"))
        assertEquals(listOf("a"), s.get("lit"))
    }
}
