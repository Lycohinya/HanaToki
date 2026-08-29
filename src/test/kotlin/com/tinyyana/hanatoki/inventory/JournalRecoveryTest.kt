package com.tinyyana.hanatoki.inventory

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「崩在這一步該怎麼恢復」的真值表。
 *
 * 這是整套崩潰安全唯一的判斷點,所以八種組合全部列出來——用迴圈掃過去會讓「PREPARED 為什麼
 * 特別」這件事消失在程式碼裡,而那正是最容易改錯的一格。
 */
class JournalRecoveryTest {

    @Test
    fun `PREPARED 不寫背包(那時引擎還沒動過它,寫進去就是複製)`() {
        assertEquals(RecoveryAction.DISCARD, JournalRecovery.actionFor(JournalState.PREPARED, hasSnapshot = true))
    }

    @Test
    fun `CLEARING 覆蓋還原(可能已經清掉一半)`() {
        assertEquals(RecoveryAction.RESTORE_SNAPSHOT, JournalRecovery.actionFor(JournalState.CLEARING, hasSnapshot = true))
    }

    @Test
    fun `ACTIVE 覆蓋還原`() {
        assertEquals(RecoveryAction.RESTORE_SNAPSHOT, JournalRecovery.actionFor(JournalState.ACTIVE, hasSnapshot = true))
    }

    @Test
    fun `RESTORING 覆蓋還原(重跑安全)`() {
        assertEquals(RecoveryAction.RESTORE_SNAPSHOT, JournalRecovery.actionFor(JournalState.RESTORING, hasSnapshot = true))
    }

    @Test
    fun `沒有快照時一律 DISCARD,不論狀態`() {
        for (state in JournalState.entries) {
            assertEquals(
                RecoveryAction.DISCARD,
                JournalRecovery.actionFor(state, hasSnapshot = false),
                "state=$state 沒有快照時不該嘗試還原",
            )
        }
    }

    @Test
    fun `只有 PREPARED 會在有快照時放棄還原`() {
        val discarding = JournalState.entries.filter {
            JournalRecovery.actionFor(it, hasSnapshot = true) == RecoveryAction.DISCARD
        }
        assertEquals(listOf(JournalState.PREPARED), discarding)
    }
}
