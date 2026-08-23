package com.tinyyana.hanatoki.check

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckAggregatorTest {
    private fun outcomes(vararg values: String) = values.associateBy { UUID.randomUUID() }

    @Test
    fun `INDIVIDUAL 要求全員成功`() {
        val isSuccess = { o: String -> o == "success" }
        assertTrue(CheckAggregator.aggregate(CheckAggregation.INDIVIDUAL, outcomes("success", "success"), isSuccess))
        assertFalse(CheckAggregator.aggregate(CheckAggregation.INDIVIDUAL, outcomes("success", "fail"), isSuccess))
    }

    @Test
    fun `MAJORITY 過半成功即算成功(比照現況破防判準)`() {
        val isSuccess = { o: String -> o == "success" }
        assertTrue(CheckAggregator.aggregate(CheckAggregation.MAJORITY, outcomes("success", "success", "fail"), isSuccess))
        assertFalse(CheckAggregator.aggregate(CheckAggregation.MAJORITY, outcomes("success", "fail", "fail"), isSuccess))
    }

    @Test
    fun `空清單一律回 false`() {
        val isSuccess = { o: String -> o == "success" }
        assertFalse(CheckAggregator.aggregate(CheckAggregation.MAJORITY, emptyMap(), isSuccess))
    }
}
