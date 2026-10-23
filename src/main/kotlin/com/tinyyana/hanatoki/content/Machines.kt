package com.tinyyana.hanatoki.content

/**
 * 燈籠狀態機(純邏輯,不碰 Bukkit)。不變式:
 * - 狀態只往前走 UNLIT → BURNING → LIT,**不倒退**;
 * - LIT 只會達成一次([Outcome.COMPLETED] 整個生命週期最多回一次);
 * - 無人在 stay-radius 內時 [advance] 暫停(不因為沒人而倒扣),有人回來繼續;
 * - 被啃([drain],seeks-object 敵人造成):進度可退,夾在 0,**不退回 UNLIT**、不觸發完成;
 *   暫停中照樣生效(離開燈籠不能讓燈免疫,否則「離開」就成了躲壓力的辦法);LIT 之後不再扣。
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

    /** 目前進度(燃燒 tick),測試與 log 用。 */
    val progressTicks: Double get() = progress

    /**
     * 啃掉 [ticks] 燃燒 tick 的進度。只有 BURNING 生效(暫停中也算);結果夾在 0 以上;回傳實際扣掉的量。
     * 不改 [state]、不回傳 Outcome:被啃永遠不會讓燈完成或回到 UNLIT。
     */
    fun drain(ticks: Double): Double {
        if (state != State.BURNING || ticks <= 0.0 || progress <= 0.0) return 0.0
        val taken = minOf(ticks, progress)
        progress -= taken
        return taken
    }

    /**
     * 重置回 UNLIT、進度歸零(rc.23,**只有 boss 的場邊燈會呼叫**:熄燈招式、跪下起身後、全滅重來)。
     * 一般燈籠不倒退的不變式不受影響——沒有任何其他路徑呼叫它。
     */
    fun reset() {
        state = State.UNLIT
        progress = 0.0
        paused = false
    }

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

/**
 * 局內道具燈火的自動回補計量(純邏輯,不碰 Bukkit)。不變式:
 * - 回補後燈火不超過 [advance] 傳入的上限;已滿時進度歸零(不存著等下一次扣燈火就立刻補);
 * - 有被動回補([passiveSeconds])時,燈火 0 最久 [passiveSeconds] 秒內一定回到 >= 1,不依賴場上有沒有可打的敵人;
 * - 光源區內改用 [zoneSeconds](取被動與光源區較快的一個,不相加);沒有任何來源時永遠回 0。
 */
class ChargeMeter(private val passiveSeconds: Double?, private val zoneSeconds: Double?) {
    /** 0 <= progress < 1:離下一格還差多少。 */
    var progress: Double = 0.0
        private set

    init {
        require(passiveSeconds == null || passiveSeconds > 0.0) { "passiveSeconds 必須 > 0" }
        require(zoneSeconds == null || zoneSeconds > 0.0) { "zoneSeconds 必須 > 0" }
    }

    /** 往前走 [dtSeconds]。回傳這段時間補了幾格(0 以上,且 [charges] + 回傳值 <= [max])。 */
    fun advance(dtSeconds: Double, charges: Int, max: Int, inZone: Boolean): Int {
        if (charges >= max) { progress = 0.0; return 0 }
        val passive = passiveSeconds?.let { 1.0 / it } ?: 0.0
        val zone = if (inZone) zoneSeconds?.let { 1.0 / it } ?: 0.0 else 0.0
        val rate = maxOf(passive, zone)
        if (rate <= 0.0 || dtSeconds <= 0.0) return 0
        progress += dtSeconds * rate
        val gained = minOf(progress.toInt(), max - charges)
        progress -= progress.toInt()
        if (charges + gained >= max) progress = 0.0
        return gained
    }

    fun reset() { progress = 0.0 }
}

/**
 * 燈籠世界標示等級:0 = 不顯示,1 = 遠處提示,2 = 已在可點燃範圍。
 * ready 這級**向內遲滯**:進入要 <= readyRadius - slack,離開才 > readyRadius,所以只要等級是 2,
 * 快照當下人一定在 readyRadius 內(把 0.5 秒快照延遲與走動吸收在 slack 裡);far 這級向外遲滯,站在線上不會一直開關。
 */
fun lanternHintLevel(distance: Double, current: Int, radius: Double, readyRadius: Double, hasReady: Boolean, slack: Double): Int {
    if (hasReady && distance <= (if (current == 2) readyRadius else readyRadius - slack)) return 2
    return if (distance <= (if (current > 0) radius + slack else radius)) 1 else 0
}

/** 燈火條:「●●○○○」。[charges] 超出 0..[max] 的部分夾住。 */
/**
 * rc.27:玩家面向([yawDegrees],Minecraft 的 yaw:0 = 南、順時針增加)看，往 ([dx], [dz]) 的方位八向:
 * front／front-right／right／back-right／back／back-left／left／front-left。距離為 0 回 front。
 */
fun directionOctant(yawDegrees: Double, dx: Double, dz: Double): String {
    if (dx * dx + dz * dz < 1.0E-9) return "front"
    val targetYaw = Math.toDegrees(Math.atan2(-dx, dz))
    var rel = (targetYaw - yawDegrees) % 360.0
    if (rel > 180.0) rel -= 360.0
    if (rel <= -180.0) rel += 360.0
    val index = Math.floorMod(Math.round(rel / 45.0).toInt(), 8)
    return DIRECTION_NAMES[index]
}

private val DIRECTION_NAMES = listOf("front", "front-right", "right", "back-right", "back", "back-left", "left", "front-left")

fun chargePips(charges: Int, max: Int): String {
    val full = charges.coerceIn(0, max)
    return "●".repeat(full) + "○".repeat(max - full)
}

/**
 * 目前目標:依序第一個「while 成立(沒寫 = 一律成立)而且 until 還沒達成」的步驟(沒寫 until 的步驟永遠停在那一步);全部不符 = null。
 * while 不成立的步驟被跳過,不算達成,條件回來時又會顯示。
 */
fun pickObjective(steps: List<ObjectiveStep>, met: (Condition) -> Boolean): ObjectiveStep? =
    steps.firstOrNull { step -> (step.during == null || met(step.during)) && (step.until == null || !met(step.until)) }

/** 同一種敵人在同一座燈籠旁的啃燈情況:[inRange] 隻(活著、沒僵直、在 contest-radius 內),最多 [max] 隻有效,每隻每秒 [perSecond] 個 burn-second。 */
data class DrainGroup(val inRange: Int, val max: Int, val perSecond: Double)

/** 這一輪的總啃燈速率(burn-second / 秒)。各組各自套上限,沒有敵人 = 0。 */
fun drainRate(groups: Collection<DrainGroup>): Double =
    groups.sumOf { it.inRange.coerceIn(0, it.max.coerceAtLeast(0)) * it.perSecond }

/** 速率 [ratePerSecond] 在 [dtTicks] 個 tick 內造成的燃燒進度損失(1 個 burn-second 的進度 = 20 燃燒 tick,所以速率與 tick 數直接相乘)。 */
fun drainTicks(ratePerSecond: Double, dtTicks: Int): Double = if (ratePerSecond <= 0.0 || dtTicks <= 0) 0.0 else ratePerSecond * dtTicks

/** 碰到玩家的判定:水平距離 <= [radius] 且高度差不超過 [maxHeight](站在不同層不算碰到)。 */
fun withinContact(dx: Double, dy: Double, dz: Double, radius: Double, maxHeight: Double = 2.0): Boolean =
    Math.abs(dy) <= maxHeight && Math.hypot(dx, dz) <= radius

/**
 * 碰到玩家效果的冷卻(**每位玩家各算**,不分是哪隻敵人):幾隻同時貼上來,同一個人在 [cooldownTicks] 內只吃一次。
 * 只在 session 的 anchor region 執行緒使用。
 */
class TouchCooldowns {
    private val last = HashMap<java.util.UUID, Long>()

    /** 冷卻已過(或第一次)回 true 並記下 [now];否則 false。 */
    fun tryTouch(playerId: java.util.UUID, now: Long, cooldownTicks: Int): Boolean {
        val before = last[playerId]
        if (before != null && now - before < cooldownTicks) return false
        last[playerId] = now
        return true
    }

    fun forget(playerId: java.util.UUID) { last.remove(playerId) }
    fun clear() = last.clear()
}

/** 遠離敵人的水平推力向量 `[vx, vz]`;正好重疊時回 `[0, 0]`(只剩 lift),不挑隨機方向。 */
fun pushAway(fromX: Double, fromZ: Double, toX: Double, toZ: Double, strength: Double): DoubleArray {
    val dx = toX - fromX
    val dz = toZ - fromZ
    val d = Math.hypot(dx, dz)
    return if (d < 1.0E-4) doubleArrayOf(0.0, 0.0) else doubleArrayOf(dx / d * strength, dz / d * strength)
}
