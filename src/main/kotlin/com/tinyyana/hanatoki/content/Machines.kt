package com.tinyyana.hanatoki.content

/**
 * 燈籠狀態機(純邏輯,不碰 Bukkit)。不變式:
 * - 狀態只往前走 UNLIT → BURNING → LIT,**不倒退**;
 * - LIT 只會達成一次([Outcome.COMPLETED] 整個生命週期最多回一次);
 * - 無人在 stay-radius 內時進度暫停(不倒扣),有人回來繼續。
 */
class LanternMachine(private val burnTicks: Double) {
    enum class State { UNLIT, BURNING, LIT }
    enum class Outcome { NONE, PAUSED, RESUMED, COMPLETED }

    init { require(burnTicks > 0.0) { "burnTicks 必須 > 0" } }

    var state: State = State.UNLIT
        private set
    private var progress = 0.0
    var paused: Boolean = false
        private set

    /** 0.0–1.0 */
    val fraction: Double get() = if (state == State.LIT) 1.0 else (progress / burnTicks).coerceIn(0.0, 1.0)

    /** 只有 UNLIT 點得起來;已在燒或已亮回 false(不重置進度)。 */
    fun ignite(): Boolean {
        if (state != State.UNLIT) return false
        state = State.BURNING
        paused = false
        return true
    }

    /**
     * 往前走 [dtTicks]。[multiplier] = 範圍內人數對應的速度倍率(0 = 沒人在範圍內 → 暫停)。
     */
    fun advance(dtTicks: Int, multiplier: Double): Outcome {
        if (state != State.BURNING) return Outcome.NONE
        if (multiplier <= 0.0) {
            if (paused) return Outcome.NONE
            paused = true
            return Outcome.PAUSED
        }
        var outcome = Outcome.NONE
        if (paused) {
            paused = false
            outcome = Outcome.RESUMED
        }
        progress += dtTicks * multiplier
        if (progress >= burnTicks) {
            progress = burnTicks
            state = State.LIT
            paused = false
            return Outcome.COMPLETED
        }
        return outcome
    }
}

/** 柵門:closed → open,只能開一次。 */
class GateMachine {
    var open: Boolean = false
        private set

    fun open(): Boolean {
        if (open) return false
        open = true
        return true
    }
}

/**
 * 小幽靈等敵人的生命週期(純邏輯)。ALIVE ⇄ STUNNED → DISSOLVING → GONE,DISSOLVING/GONE 不回頭,
 * 打散只會發生一次。[stunEpoch] 讓「舊的僵直結束計時」在被重新照到之後失效。
 */
class EnemyMachine {
    enum class State { ALIVE, STUNNED, DISSOLVING, GONE }

    @Volatile
    var state: State = State.ALIVE
        private set
    var stunEpoch: Int = 0
        private set

    val stunned: Boolean get() = state == State.STUNNED

    /** 成功(含重新照到刷新僵直)回 true;已在打散/消失回 false。 */
    fun stun(): Boolean {
        if (state != State.ALIVE && state != State.STUNNED) return false
        state = State.STUNNED
        stunEpoch++
        return true
    }

    /** 僵直時間到。[epoch] 必須是最近一次 stun 的版本,否則是被刷新過的舊計時。 */
    fun recover(epoch: Int): Boolean {
        if (state != State.STUNNED || epoch != stunEpoch) return false
        state = State.ALIVE
        return true
    }

    /** ALIVE/STUNNED → DISSOLVING。只有第一次回 true。 */
    fun dissolve(): Boolean {
        if (state != State.ALIVE && state != State.STUNNED) return false
        state = State.DISSOLVING
        return true
    }

    fun gone(): Boolean {
        if (state == State.GONE) return false
        state = State.GONE
        return true
    }
}

/**
 * 以 tick 計的簡單計時佇列。**只在 session 的 anchor region 執行緒上使用**,由 session 的
 * 週期任務推進。刻意不用 `StageContext.submitLater`:`transition()` 會取消那條路的全部排程,
 * 柵門滑開、打散延遲移除會被半途砍掉。這裡的計時隨 session 存活,跨 stage 轉換不受影響。
 */
class TimerQueue {
    private class Entry(val due: Long, val seq: Long, val action: Runnable)

    private val entries = ArrayList<Entry>()
    private var seq = 0L

    val size: Int get() = entries.size

    fun schedule(now: Long, delayTicks: Long, action: Runnable) {
        entries += Entry(now + delayTicks.coerceAtLeast(0), seq++, action)
    }

    /** 執行所有到期的(依到期時間、排入順序);執行中新排入的若也到期,本輪一併執行。 */
    fun runDue(now: Long, onError: (Throwable) -> Unit) {
        // 單次最多處理 MAX_PER_RUN 筆:執行中又排入「立刻到期」的任務不能把週期任務卡死。
        var budget = MAX_PER_RUN
        while (budget-- > 0) {
            val next = entries.filter { it.due <= now }.minWithOrNull(compareBy<Entry>({ it.due }, { it.seq })) ?: return
            entries.remove(next)
            try { next.action.run() } catch (t: Throwable) { onError(t) }
        }
    }

    fun clear() = entries.clear()

    private companion object { const val MAX_PER_RUN = 2000 }
}

/** 「人數 → 值」表的查法:n 人用 ≤ n 的最大 key,比最小 key 還小用 [default]。 */
fun <T> pickByPlayers(table: Map<Int, T>, players: Int, default: T): T {
    var best: Int? = null
    for (key in table.keys) if (key <= players && (best == null || key > best)) best = key
    return best?.let { table.getValue(it) } ?: default
}

/** 燒燈速度倍率:沒人 = 0(暫停),有人查表(沒寫 = 1.0)。 */
fun lanternMultiplier(speedByPlayers: Map<Int, Double>, peopleInRadius: Int): Double =
    if (peopleInRadius <= 0) 0.0 else pickByPlayers(speedByPlayers, peopleInRadius, 1.0)
