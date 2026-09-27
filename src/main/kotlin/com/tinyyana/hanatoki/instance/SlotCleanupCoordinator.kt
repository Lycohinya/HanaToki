package com.tinyyana.hanatoki.instance

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 收斂時一位成員現在的位置/狀態(由呼叫端在該玩家自己的 region 上讀)。 */
enum class MemberStatus {
    /** 不在線。登入時由 join 流程接手(送出副本世界、還原背包),不擋回滾。 */
    OFFLINE,

    /** 死亡中。原版重生/登入傳送才是合法的離場時機,這時對屍體傳送一定失敗。 */
    DEAD,

    /** 已經不在這個 slot 的場地上(自己走了、已經送回家、或已經在別的 session 的場地)。 */
    OUTSIDE,

    /** 還站在這個 slot 的場地上,要送他出去。 */
    INSIDE,

    /** 讀不到(實體 retired 等),這一輪先等。 */
    UNKNOWN,
}

enum class CleanupPhase { STAGE_ENDING, EVACUATING, ROLLING_BACK, RELEASED }

/**
 * session 結束後的場地收斂:**還背包 → 送人離場 → 回滾場地 → 歸還 slot**,做成可恢復、
 * 可重入、冪等的生命週期。
 *
 * ## 為什麼要有這個類別(2026-09-27 刀塚事故)
 *
 * 舊版是一條 `thenCompose` 鏈:`sendHome` 失敗(傳送回 false、丟例外、實體 retired)整條鏈就
 * exceptional,後面的回滾與 `releaseSlotAfterRollback` 永遠不會跑。而 session 的登記表
 * (`sessions`/`byPlayer`/`bySlot`)在鏈開始之前就已經刪掉了,tick 也不會再看到它——
 * 那個 slot 從此永遠 occupied,沒有任何路徑能把它收回來,打到第 12 局入口就滿了。
 * 死亡是最常見的觸發:對還沒重生的玩家傳送,核心直接拒絕。
 *
 * ## 不變式
 *
 * 1. **有人還站在場地上就不回滾、不歸還**(專屬副本世界回滾的終點是虛空)。所以沒有
 *    「失敗也 finally release」:失敗的一步留在原地,由 [pumpAll](每秒一次)重試。
 * 2. **每個 slot 恰好歸還一次**([Cleanup.released] 的 CAS)。重複的 [begin]、同時的 pump、
 *    admin reset 都只會推進同一筆收斂,不會多放一次——多放一次等於讓兩個 session 共用場地。
 * 3. **收斂紀錄獨立於 session 登記表**,在歸還之前一直留在 [cleanups] 裡,admin 指令看得到、
 *    drain 等得到,不會因為 session index 已經刪掉而失聯。
 *
 * 不碰 Bukkit:世界/玩家的動作都經 [Ports],單元測試直接打(見 SlotCleanupCoordinatorTest)。
 */
class SlotCleanupCoordinator(
    private val ports: Ports,
    private val now: () -> Long = System::currentTimeMillis,
) {

    interface Ports {
        /** 還原這位玩家的永久背包(冪等;沒有交易就立即 true)。false = 還沒還成(離線/死亡/retired)。 */
        fun restore(playerId: UUID, reason: String): CompletableFuture<Boolean>

        fun status(playerId: UUID, cleanup: CleanupView): CompletableFuture<MemberStatus>

        /** 把人送出場地。true = 傳送真的落地;false/exceptional = 這次沒送成,稍後重試。 */
        fun evacuate(playerId: UUID, cleanup: CleanupView, attempt: Int): CompletableFuture<Boolean>

        /** 回滾場地(diff、地圖 generation、殘留掉落物)。**不歸還 slot**。 */
        fun rollback(cleanup: CleanupView): CompletableFuture<Void>

        /** 全員已離場、場地已回滾:歸還 slot 並收掉這個 session 的其他登記。只會被呼叫一次。 */
        fun release(cleanup: CleanupView)

        fun info(message: String)

        fun warn(message: String)
    }

    /** 一筆收斂對外可見的身分(給 [Ports] 與 admin 指令)。 */
    class CleanupView(
        val sessionId: UUID,
        val slotId: String,
        val dungeonId: String,
        val reason: String,
        /** 內容插件停用時整個世界都要清空;null = 一般收斂。 */
        val drainingWorld: String?,
        /** true = 這個 session 不佔 slot(常駐副本、或進場失敗的常駐 join),收斂不回滾也不歸還。 */
        val holdsSlot: Boolean,
    )

    private inner class Cleanup(val view: CleanupView, members: Collection<UUID>) {
        val startedAtMs = now()
        val pending: MutableSet<UUID> = ConcurrentHashMap.newKeySet<UUID>().apply { addAll(members) }
        val attempts = ConcurrentHashMap<UUID, Int>()
        val nextAttemptAtMs = ConcurrentHashMap<UUID, Long>()
        val inFlight = AtomicBoolean(false)
        val rollbackStarted = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val rollbackFailures = AtomicInteger()
        val done = CompletableFuture<Void>()

        @Volatile var phase = CleanupPhase.STAGE_ENDING
        @Volatile var lastError: String? = null
        @Volatile var lastEscalationAtMs = startedAtMs
    }

    private val cleanups = ConcurrentHashMap<UUID, Cleanup>()

    /**
     * 開始(或接上)一個 session 的收斂。同一個 session 重複呼叫回傳同一個 future,不會重跑。
     * 回傳的 future 在 slot 真的歸還之後才完成;卡住的收斂會一直未完成,呼叫端要自己決定等多久。
     */
    fun begin(
        view: CleanupView,
        members: Collection<UUID>,
        stageEnd: CompletableFuture<Void>,
    ): CompletableFuture<Void> {
        val created = Cleanup(view, members)
        val existing = cleanups.putIfAbsent(view.sessionId, created)
        if (existing != null) {
            // 另一條結束路徑搶先了:新到的成員併進同一筆(已離場的人重新確認一次就會再被移除),
            // 再推一次。已經開始回滾的就不再加人——那時人已經確定都不在場上。
            if (existing.phase == CleanupPhase.STAGE_ENDING || existing.phase == CleanupPhase.EVACUATING) {
                existing.pending.addAll(members)
            }
            pump(existing)
            return existing.done
        }
        ports.info("[HanaToki] slot=${view.slotId} session=${view.sessionId} 開始收斂(${view.reason},成員 ${members.size} 位)")
        stageEnd.handle { _, error ->
            if (error != null) created.lastError = "stage end: ${rootMessage(error)}"
            if (error != null) ports.warn("[HanaToki] slot=${view.slotId} stage 收尾丟出例外,照樣繼續收斂:${rootMessage(error)}")
            created.phase = CleanupPhase.EVACUATING
            pump(created)
            null
        }
        return created.done
    }

    /** 每秒呼叫一次(core tick)。卡住的收斂在這裡重試,不需要任何人記得它。 */
    fun pumpAll() {
        cleanups.values.forEach { pump(it) }
    }

    /** admin reset 用:這個 slot 有沒有還沒歸還的收斂;有的話立刻推一次。 */
    fun pumpSlot(slotId: String): Boolean {
        val matching = cleanups.values.filter { it.view.slotId == slotId }
        matching.forEach { c ->
            // 管理員介入:跳過等待中的退避,馬上再試。
            c.nextAttemptAtMs.clear()
            pump(c)
        }
        return matching.isNotEmpty()
    }

    fun hasPendingFor(slotId: String): Boolean = cleanups.values.any { it.view.slotId == slotId }

    /** 這些副本還沒歸還的收斂(drain 用)。 */
    fun pendingFor(dungeonIds: Set<String>): List<CompletableFuture<Void>> =
        cleanups.values.filter { it.view.dungeonId in dungeonIds }.map { it.done }

    /** admin 指令用的現況描述,一筆一行。 */
    fun describe(): List<String> = cleanups.values.sortedBy { it.startedAtMs }.map { c ->
        val age = (now() - c.startedAtMs) / 1000
        val attempts = c.attempts.entries.joinToString(",") { "${it.key.toString().take(8)}×${it.value}" }
        "slot=${c.view.slotId} session=${c.view.sessionId.toString().take(8)} phase=${c.phase} " +
            "待離場=${c.pending.size} 嘗試=[$attempts] ${age}s" + (c.lastError?.let { " 最後錯誤=$it" } ?: "")
    }

    private fun pump(c: Cleanup) {
        if (c.released.get()) return
        if (c.phase == CleanupPhase.STAGE_ENDING) {
            // stage 收尾一直沒回來也不能讓場地永遠卡著:逾時照樣往下走(收尾只是清排程/實體)。
            if (now() - c.startedAtMs < STAGE_END_TIMEOUT_MS) return
            ports.warn("[HanaToki] slot=${c.view.slotId} stage 收尾超過 ${STAGE_END_TIMEOUT_MS / 1000} 秒沒有完成,照樣繼續收斂")
            c.phase = CleanupPhase.EVACUATING
        }
        if (!c.inFlight.compareAndSet(false, true)) return
        val step = try {
            advance(c)
        } catch (e: Throwable) {
            CompletableFuture.failedFuture<Any?>(e)
        }
        step.whenComplete { _, error ->
            if (error != null) {
                c.lastError = rootMessage(error)
                ports.warn("[HanaToki] slot=${c.view.slotId} 收斂這一輪出錯,下一輪重試:${rootMessage(error)}")
            }
            c.inFlight.set(false)
        }
    }

    private fun advance(c: Cleanup): CompletableFuture<*> = when (c.phase) {
        CleanupPhase.EVACUATING -> {
            val checks = c.pending.toList().map { member -> evaluate(c, member).exceptionally { error ->
                c.lastError = "${member.toString().take(8)}: ${rootMessage(error)}"
                null
            } }
            CompletableFuture.allOf(*checks.toTypedArray()).thenCompose {
                if (c.pending.isEmpty()) startRollback(c) else {
                    escalate(c)
                    CompletableFuture.completedFuture(null)
                }
            }
        }
        // 回滾失敗會把 rollbackStarted 放回 false 讓這裡重來(見 startRollback)。
        CleanupPhase.ROLLING_BACK -> if (!c.rollbackStarted.get()) startRollback(c) else CompletableFuture.completedFuture(null)
        else -> CompletableFuture.completedFuture(null)
    }

    private fun evaluate(c: Cleanup, member: UUID): CompletableFuture<Void> =
        safely { ports.status(member, c.view) }.thenCompose { status ->
            when (status) {
                MemberStatus.OFFLINE, MemberStatus.OUTSIDE -> {
                    // 不在場上的人照樣要還背包(冪等);還不成的(離線)journal 留著等登入。
                    safely { ports.restore(member, "session-end-${c.view.reason}") }
                    c.pending.remove(member)
                    CompletableFuture.completedFuture(null)
                }
                MemberStatus.INSIDE -> tryEvacuate(c, member)
                // 死亡中/讀不到:等。重生事件會把重生點改到返回點,之後這裡會讀到 OUTSIDE。
                else -> CompletableFuture.completedFuture(null)
            }
        }

    private fun tryEvacuate(c: Cleanup, member: UUID): CompletableFuture<Void> {
        val t = now()
        if (t < (c.nextAttemptAtMs[member] ?: 0L)) return CompletableFuture.completedFuture(null)
        val attempt = c.attempts.merge(member, 1, Int::plus)!!
        c.nextAttemptAtMs[member] = t + backoffMs(attempt)
        // ⚠ 先還背包、確定還完才送人(跨世界傳送會讓還原派工撞上 retired,見 HanaTokiCore)。
        return safely { ports.restore(member, "session-end-${c.view.reason}") }
            .handle { ok, _ -> ok == true }
            .thenCompose { restored ->
                if (!restored && attempt < RESTORE_BEFORE_EVACUATE_ATTEMPTS) {
                    c.lastError = "${member.toString().take(8)}: 背包還沒還完,稍後再送"
                    return@thenCompose CompletableFuture.completedFuture(null)
                }
                if (!restored) {
                    ports.warn("[HanaToki] slot=${c.view.slotId} 玩家 $member 的背包重試 $attempt 次仍未還原,先送離場(journal 保留,登入時再還)")
                }
                safely { ports.evacuate(member, c.view, attempt) }
                    .handle { ok, error ->
                        if (error != null) c.lastError = "${member.toString().take(8)}: 傳送丟例外 ${rootMessage(error)}"
                        else if (ok != true) c.lastError = "${member.toString().take(8)}: 傳送沒有成功"
                        ok == true
                    }
                    .thenCompose { teleported ->
                        if (!teleported) return@thenCompose CompletableFuture.completedFuture(null)
                        // 傳送回報成功不算數,要讀到人真的已經不在場上才放行回滾。
                        safely { ports.status(member, c.view) }.thenAccept { after ->
                            if (after == MemberStatus.OUTSIDE || after == MemberStatus.OFFLINE) c.pending.remove(member)
                        }
                    }
            }
    }

    private fun startRollback(c: Cleanup): CompletableFuture<Void> {
        if (!c.rollbackStarted.compareAndSet(false, true)) return CompletableFuture.completedFuture(null)
        c.phase = CleanupPhase.ROLLING_BACK
        if (!c.view.holdsSlot) {
            finish(c)
            return CompletableFuture.completedFuture(null)
        }
        return safely { ports.rollback(c.view) }.handle { _, error ->
            if (error == null) {
                finish(c)
                return@handle null
            }
            val failures = c.rollbackFailures.incrementAndGet()
            c.lastError = "rollback: ${rootMessage(error)}"
            if (failures < MAX_ROLLBACK_ATTEMPTS) {
                ports.warn("[HanaToki] slot=${c.view.slotId} 場地回滾失敗(第 $failures 次),下一輪重試:${rootMessage(error)}")
                c.rollbackStarted.set(false)
            } else {
                // 人已經確定都離場了,只剩場地可能沒還原乾淨。永遠佔著 slot 會讓入口滿掉,
                // 所以這裡歸還並記 severe 等級的 warn,讓管理員知道這個 slot 值得看一眼。
                ports.warn("[HanaToki] slot=${c.view.slotId} 場地回滾連續失敗 $failures 次,人都已離場,歸還 slot;場地可能殘留上一局的變更")
                finish(c)
            }
            null
        }
    }

    private fun finish(c: Cleanup) {
        if (!c.released.compareAndSet(false, true)) return
        if (c.view.holdsSlot) ports.release(c.view)
        c.phase = CleanupPhase.RELEASED
        cleanups.remove(c.view.sessionId, c)
        ports.info("[HanaToki] slot=${c.view.slotId} session=${c.view.sessionId} 收斂完成,${(now() - c.startedAtMs) / 1000}s")
        c.done.complete(null)
    }

    private fun escalate(c: Cleanup) {
        val t = now()
        if (t - c.lastEscalationAtMs < ESCALATION_INTERVAL_MS) return
        c.lastEscalationAtMs = t
        ports.warn(
            "[HanaToki] slot=${c.view.slotId} session=${c.view.sessionId} 收斂已等 ${(t - c.startedAtMs) / 1000}s," +
                "還有 ${c.pending.size} 位沒離場:${c.pending.joinToString(",")}" + (c.lastError?.let { " 最後錯誤=$it" } ?: ""),
        )
    }

    private fun <T> safely(block: () -> CompletableFuture<T>?): CompletableFuture<T> = try {
        block() ?: CompletableFuture.failedFuture(IllegalStateException("port returned null"))
    } catch (e: Throwable) {
        CompletableFuture.failedFuture(e)
    }

    private fun rootMessage(error: Throwable): String {
        var e = error
        while ((e is java.util.concurrent.CompletionException || e is java.util.concurrent.ExecutionException) && e.cause != null) e = e.cause!!
        return "${e.javaClass.simpleName}: ${e.message}"
    }

    companion object {
        /** 同一位成員兩次傳送之間的退避:2、4、8、16 秒,之後每 30 秒一次。 */
        fun backoffMs(attempt: Int): Long = minOf(30_000L, 2_000L shl (attempt - 1).coerceIn(0, 4))

        /** 背包還沒還完就先不送人的次數上限;超過就先送走(journal 留著等登入)。 */
        const val RESTORE_BEFORE_EVACUATE_ATTEMPTS = 5

        /** 第幾次開始改送保底目的地(重生點/第一個非副本世界),見 HanaTokiCore 的 evacuate。 */
        const val FALLBACK_DESTINATION_FROM_ATTEMPT = 3

        const val STAGE_END_TIMEOUT_MS = 30_000L
        const val MAX_ROLLBACK_ATTEMPTS = 3
        const val ESCALATION_INTERVAL_MS = 60_000L
    }
}
