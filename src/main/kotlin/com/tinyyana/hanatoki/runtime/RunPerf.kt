package com.tinyyana.hanatoki.runtime

/**
 * 一局的 director 量測:每次 director tick 自己花了多久、兩次 tick 之間實際隔了多久。
 *
 * 2026-09-03 正式服回報「怪物會明顯停頓」——這個類別是把「停頓」拆開來看的第一把尺:
 * - `selfMax` 大 = director 自己這一 tick 做太多事(那是我們的程式碼要改);
 * - `intervalMax` 遠超過 [expectedIntervalNanos] 而 self 很小 = region 本身在卡
 *   (別的 task、別的插件、chunk 載入、或 tick thread 被隔壁 region 綁住),不是 director 的錯。
 * 兩個一起看才分得出「怪在停」是誰造成的。純資料,單元測試直接餵 nanoTime。
 */
class RunPerf(private val expectedIntervalNanos: Long, private val label: String) {
    constructor(expectedIntervalNanos: Long) : this(expectedIntervalNanos, "director")
    var ticks: Long = 0
        private set
    var selfTotalNanos: Long = 0
        private set
    var selfMaxNanos: Long = 0
        private set
    var intervalMaxNanos: Long = 0
        private set
    /** 自己超過 [SLOW_TICK_NANOS] 的 tick 數。 */
    var slowTicks: Int = 0
        private set
    /** 隔了超過 expected × [LATE_FACTOR] 才輪到的 tick 數。 */
    var lateTicks: Int = 0
        private set
    private var lastEndNanos = 0L
    private var warnedAtNanos: Long? = null

    /**
     * 記一次 tick。回傳要印進 log 的警告(超過門檻而且距上次警告 ≥ [WARN_COOLDOWN_NANOS]),
     * 否則 null。
     */
    fun record(startNanos: Long, endNanos: Long): String? {
        ticks++
        val self = endNanos - startNanos
        selfTotalNanos += self
        if (self > selfMaxNanos) selfMaxNanos = self
        var late = false
        var interval = 0L
        if (lastEndNanos != 0L) {
            interval = startNanos - lastEndNanos
            if (interval > intervalMaxNanos) intervalMaxNanos = interval
            if (interval > expectedIntervalNanos * LATE_FACTOR) { lateTicks++; late = true }
        }
        lastEndNanos = endNanos
        val slow = self > SLOW_TICK_NANOS
        if (slow) slowTicks++
        if (!slow && !late) return null
        if (warnedAtNanos != null && endNanos - warnedAtNanos!! < WARN_COOLDOWN_NANOS) return null
        warnedAtNanos = endNanos
        return "$label tick ${if (slow) "慢" else ""}${if (slow && late) "+" else ""}${if (late) "晚" else ""}:self=${ms(self)}ms interval=${ms(interval)}ms(預期 ${ms(expectedIntervalNanos)}ms) $summary"
    }

    val summary: String
        get() = "ticks=$ticks selfAvg=${if (ticks == 0L) "0" else ms(selfTotalNanos / ticks)}ms selfMax=${ms(selfMaxNanos)}ms intervalMax=${ms(intervalMaxNanos)}ms slow=$slowTicks late=$lateTicks"

    private fun ms(nanos: Long): String = "%.1f".format(nanos / 1e6)

    companion object {
        const val SLOW_TICK_NANOS = 25_000_000L
        const val LATE_FACTOR = 1.5
        const val WARN_COOLDOWN_NANOS = 30_000_000_000L
    }
}
