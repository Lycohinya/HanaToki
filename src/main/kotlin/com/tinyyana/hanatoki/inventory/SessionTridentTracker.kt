package com.tinyyana.hanatoki.inventory

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 這一局成員丟出去的三叉戟(實體 UUID),收斂時用來找回場地掃描範圍外的那些。
 *
 * ## 為什麼掃描不夠(2026-09-29 s01 實測)
 *
 * 往陽台外平丟的三叉戟飛一百多格才開始往下掉,在那之前就離開了會 tick 的區塊,停在半空中——
 * 不在掃描半徑內、也還沒掉到世界底下([TridentOwnerKeeper] 的虛空回收要等它再動起來才會觸發,
 * 那可能是好幾天後有人再進這個 slot)。收斂時依這份名單用 UUID 直接找實體,找得到就送回主人。
 *
 * 成員中途斷線時也要當下送回他自己的那幾把:主人不在,忠誠不會把它帶回去,而 reconnect grace
 * 逾時(兩分鐘)之後才收斂時,沒人的場地區塊早就卸載了,掃描與 UUID 都找不到它(2026-09-29 s01 實測)。
 *
 * 只記在 session 期間由成員丟出的;收斂後整批忘掉。重啟後是空的:那時留在場上的三叉戟由之後的
 * 場地掃描與虛空回收接手(一樣是送回主人,不會刪)。
 */
class SessionTridentTracker {
    private val bySession = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    fun track(sessionId: UUID, tridentId: UUID) {
        bySession.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(tridentId)
    }

    /** 取出並忘掉這一局的名單。 */
    fun drain(sessionId: UUID): Set<UUID> = bySession.remove(sessionId).orEmpty()

    /** 只看不取(成員中途斷線時送回他自己的那幾把,其他人的留著給收斂)。 */
    fun peek(sessionId: UUID): Set<UUID> = bySession[sessionId]?.toSet().orEmpty()

    fun trackedCount(): Int = bySession.values.sumOf { it.size }
}
