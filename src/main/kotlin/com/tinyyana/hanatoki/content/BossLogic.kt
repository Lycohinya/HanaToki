package com.tinyyana.hanatoki.content

import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * rc.23 Boss 的純邏輯:幾何判定、招式抽選、單人單預警、血量與階段、寶石、倒下追蹤、倒下身體狀態的套用與還原。
 * 不碰 Bukkit;全部在 session 的 anchor region 執行緒上使用(例外:[DownedTracker] 與 [DownedBodyLedger] 要給事件執行緒同步讀,
 * 用並行集合)。BossRun 只負責把它們接到實體與排程。
 */

/** 世界座標(不依賴 Bukkit)。 */
data class Pt(val x: Double, val y: Double, val z: Double) {
    fun horizontalDistance(other: Pt): Double = Math.hypot(x - other.x, z - other.z)
}

// ---- 幾何 ----

/** 方位角(度,0..360):atan2(dz, dx)。 */
fun angleDegrees(dx: Double, dz: Double): Double {
    val a = Math.toDegrees(Math.atan2(dz, dx))
    return if (a < 0) a + 360.0 else a
}

/** 兩個角度的最小夾角(0..180)。 */
fun angleDiffDegrees(a: Double, b: Double): Double {
    val d = Math.abs(((a - b) % 360.0 + 360.0) % 360.0)
    return if (d > 180.0) 360.0 - d else d
}

/** 水平圓內且高度差不超過 [maxHeight]。 */
fun inCircle(dx: Double, dy: Double, dz: Double, radius: Double, maxHeight: Double = BOSS_HEIGHT): Boolean =
    Math.abs(dy) <= maxHeight && Math.hypot(dx, dz) <= radius

/**
 * 掃地的環形波:波前從 [prevFront] 推進到 [front] 的這一段,中心 [bandWidth] 寬的帶狀範圍內的人被掃到。
 * 用「掃過」的區間而不是單一半徑,取樣間隔再大也不會漏掉波前剛好跨過的人。
 */
fun inRingSweep(distance: Double, prevFront: Double, front: Double, bandWidth: Double): Boolean {
    val half = bandWidth / 2.0
    return distance >= (prevFront - half).coerceAtLeast(0.0) && distance <= front + half
}

/**
 * 雙杖十字:通過原點的兩條垂直的線(第一條與 +X 夾 [angleOffsetDegrees] 度),每條向兩端各延伸 [length]、半寬 [halfWidth]。
 * 在任一條線內就中招;站進四個楔形空格裡安全。
 */
fun inCross(dx: Double, dy: Double, dz: Double, length: Double, halfWidth: Double, angleOffsetDegrees: Double, maxHeight: Double = BOSS_HEIGHT): Boolean {
    if (Math.abs(dy) > maxHeight) return false
    for (k in 0..1) {
        val a = Math.toRadians(angleOffsetDegrees + 90.0 * k)
        val along = dx * Math.cos(a) + dz * Math.sin(a)
        val across = -dx * Math.sin(a) + dz * Math.cos(a)
        if (Math.abs(across) <= halfWidth && Math.abs(along) <= length) return true
    }
    return false
}

/**
 * 光環迴旋:半徑 [innerRadius]..[radius] 的光束環,只在以 [gapCenterDegrees] 為中心、張角 [gapDegrees] 的缺口裡安全。
 */
fun inSpinBeam(dx: Double, dy: Double, dz: Double, innerRadius: Double, radius: Double, gapCenterDegrees: Double, gapDegrees: Double, maxHeight: Double = BOSS_HEIGHT): Boolean {
    if (Math.abs(dy) > maxHeight) return false
    val distance = Math.hypot(dx, dz)
    if (distance < innerRadius || distance > radius) return false
    return angleDiffDegrees(angleDegrees(dx, dz), gapCenterDegrees) > gapDegrees / 2.0
}

/** 判定區的高度容許(腳底差)。 */
const val BOSS_HEIGHT = 3.0

/** 朝 [to] 滑 [distance] 格。回傳新位置與是否到達;一步不會超過目標。 */
fun glideStep(from: Pt, to: Pt, distance: Double): Pair<Pt, Boolean> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val dz = to.z - from.z
    val length = Math.sqrt(dx * dx + dy * dy + dz * dz)
    if (length <= distance || length < 1.0E-6) return to to true
    val f = distance / length
    return Pt(from.x + dx * f, from.y + dy * f, from.z + dz * f) to false
}

// ---- 有界掃描輪替 ----

/**
 * 掃描視窗(round-robin):[items] 依 [key] 排序後,從 [afterKey] 的下一個開始取最多 [limit] 個,尾端接回開頭。
 * 數量不超過 [limit] 時全取。呼叫端把視窗最後一個的 key 記成下一輪的 [afterKey],超過上限時每隻都會輪到。
 * 敵人表是並行 map(迭代順序不穩定),所以用 key 排序而不是位移量。
 */
fun <T> rotatingWindow(items: List<T>, afterKey: String?, limit: Int, key: (T) -> String): List<T> {
    if (limit <= 0 || items.isEmpty()) return emptyList()
    val sorted = items.sortedBy(key)
    if (sorted.size <= limit) return sorted
    val start = if (afterKey == null) 0 else sorted.indexOfFirst { key(it) > afterKey }.let { if (it < 0) 0 else it }
    return List(limit) { sorted[(start + it) % sorted.size] }
}

// ---- 招式抽選 ----

data class MoveCandidate(val id: String, val weight: Int, val cooldownTicks: Long, val fromPhase: Int)

object MovePicker {
    /**
     * 從目前階段開放、不在冷卻、[available] 為真的招式裡加權抽一招;有兩招以上可選時不連續兩次同一招。沒有可選的回 null。
     */
    fun pick(
        moves: List<MoveCandidate>, phase: Int, now: Long, lastUsed: Map<String, Long>, lastMove: String?,
        available: (String) -> Boolean, random: Random,
    ): String? {
        var eligible = moves.filter { m ->
            m.fromPhase <= phase && available(m.id) && (lastUsed[m.id]?.let { now - it >= m.cooldownTicks } ?: true)
        }
        if (eligible.size > 1 && lastMove != null) eligible = eligible.filter { it.id != lastMove }
        if (eligible.isEmpty()) return null
        val total = eligible.sumOf { it.weight.coerceAtLeast(1) }
        var roll = random.nextInt(total)
        for (m in eligible) {
            roll -= m.weight.coerceAtLeast(1)
            if (roll < 0) return m.id
        }
        return eligible.last().id
    }
}

// ---- 單人單預警 ----

/**
 * 地面預警登記:每位玩家同時最多一個進行中的預警。[tryBegin] 失敗代表他已有預警(呼叫端跳過這位目標或延後這一招)。
 */
class TelegraphLedger {
    private val byPlayer = HashMap<UUID, String>()

    fun tryBegin(player: UUID, cast: String): Boolean {
        if (byPlayer.containsKey(player)) return false
        byPlayer[player] = cast
        return true
    }

    /** 一次開始整批;只要有一位已有預警就整批不開始(回 false,什麼都沒登記)。 */
    fun tryBeginAll(players: Collection<UUID>, cast: String): Boolean {
        if (players.any { byPlayer.containsKey(it) }) return false
        players.forEach { byPlayer[it] = cast }
        return true
    }

    fun isActive(player: UUID): Boolean = byPlayer.containsKey(player)

    /** 這一招的預警全部結束,回傳釋放了誰。 */
    fun end(cast: String): List<UUID> {
        val released = byPlayer.filterValues { it == cast }.keys.toList()
        released.forEach { byPlayer.remove(it) }
        return released
    }

    fun endPlayer(player: UUID) { byPlayer.remove(player) }

    val size: Int get() = byPlayer.size

    fun clear() = byPlayer.clear()
}

// ---- 血量(命中數)與階段 ----

/** 依人數倍率換算的總命中數。 */
fun scaledMaxHits(base: Double, byPlayers: Map<Int, BossMultipliers>, players: Int): Double =
    // 四捨五入到千分位:40 × 2.2 這類浮點誤差不該讓總命中數變成 88.00000000000001
    Math.round(base * pickByPlayers(byPlayers, players.coerceAtLeast(1), BossMultipliers()).health * 1000.0) / 1000.0

/** 招式傷害的人數倍率。 */
fun damageMultiplier(byPlayers: Map<Int, BossMultipliers>, players: Int): Double =
    pickByPlayers(byPlayers, players.coerceAtLeast(1), BossMultipliers()).damage

/**
 * Boss 血量:以「命中數」計。[phaseThresholds] 是各階段的進入比例(第一個是 1.0,遞減)。
 */
class BossHealth(val maxHits: Double, private val phaseThresholds: List<Double>) {
    init { require(maxHits > 0.0) { "maxHits 必須 > 0" } }

    var taken: Double = 0.0
        private set

    val remaining: Double get() = (maxHits - taken).coerceAtLeast(0.0)
    val fraction: Double get() = remaining / maxHits
    val defeated: Boolean get() = taken >= maxHits

    /** 扣 [weight] 點命中數,回傳新的血量比例。 */
    fun hit(weight: Double): Double {
        if (weight > 0.0) taken = (taken + weight).coerceAtMost(maxHits)
        return fraction
    }

    /** 以目前血量比例應該在第幾階段(1 起算);永遠不低於 1。 */
    fun phaseByHealth(): Int = phaseThresholds.indexOfLast { fraction <= it + EPS }.let { if (it < 0) 1 else it + 1 }

    fun reset() { taken = 0.0 }

    private companion object { const val EPS = 1.0E-9 }
}

// ---- 寶石(場邊燈) ----

/**
 * 寶石 = 目前亮著的場邊燈。[setLit] 回傳狀態是否真的改變;[allLit] 時光環該碎。
 */
class GemTracker(private val lamps: List<String>) {
    private val lit = LinkedHashSet<String>()

    fun setLit(lamp: String, on: Boolean): Boolean {
        require(lamp in lamps) { "未知的場邊燈 $lamp" }
        return if (on) lit.add(lamp) else lit.remove(lamp)
    }

    fun isLit(lamp: String): Boolean = lamp in lit
    val litCount: Int get() = lit.size
    val total: Int get() = lamps.size
    val allLit: Boolean get() = lit.size == lamps.size
    fun litLamps(): List<String> = lamps.filter { it in lit }
    fun clear() = lit.clear()
}

// ---- 倒下 ----

/**
 * 倒下追蹤。邏輯上的「誰倒下」:要給事件執行緒同步讀(致死傷害攔截、攻擊取消),所以是並行集合。
 */
class DownedTracker {
    private val downed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun down(id: UUID): Boolean = downed.add(id)
    fun revive(id: UUID): Boolean = downed.remove(id)
    fun contains(id: UUID): Boolean = id in downed
    fun ids(): List<UUID> = downed.toList()
    val size: Int get() = downed.size

    /** 全部清掉並回傳原本倒下的人。 */
    fun clear(): List<UUID> {
        val all = downed.toList()
        downed.clear()
        return all
    }
}

/**
 * 全滅判定:在線的成員至少一位,而且全部倒下。離線(斷線寬限期內)的成員不算存活,也不擋住全滅。
 */
fun isWipe(members: Collection<UUID>, online: (UUID) -> Boolean, downed: (UUID) -> Boolean): Boolean {
    val here = members.filter(online)
    return here.isNotEmpty() && here.all(downed)
}

/** 倒下時被改動、還原時要套回的身體狀態。[gameMode] 是 GameMode 常數名。 */
data class BodyState(
    val gameMode: String,
    val invisible: Boolean,
    val glowing: Boolean,
    val invulnerable: Boolean,
    /** 移動速度屬性的乘數修正(0 = 沒有)。 */
    val speedModifier: Double,
)

/** 玩家身體的讀寫面。Bukkit 實作在 SessionRun(玩家自己的執行緒上用),測試用假的。 */
interface BodyPort {
    fun read(): BodyState
    fun apply(state: BodyState)
    /** 玩家資料上留的「倒下前狀態」記號(PDC):重啟或當機後 apply 的回呼不會跑,靠它在下次上線還原。 */
    fun marker(): BodyState?
    fun setMarker(state: BodyState?)
}

/** [DownedSpec] 對應的倒下身體狀態。 */
fun DownedSpec.bodyState(): BodyState = BodyState(gameMode, invisible, glowing, invulnerable, speedModifier)

/**
 * 倒下身體狀態的套用與還原帳本。不變式:
 * - [apply] 之前的「原本狀態」只記一次(重複套用、斷線重連後再套用都不會把倒下狀態當成原本狀態);
 * - [restore] 把原本狀態整組套回並清掉記號,冪等(第二次回 false、不動玩家);
 * - 記憶體帳本遺失(重啟)時,以玩家身上的記號為準。
 * 每位玩家的呼叫都在他自己的執行緒上序列化,不同玩家之間用並行 map。
 */
class DownedBodyLedger {
    private val prior = ConcurrentHashMap<UUID, BodyState>()

    fun apply(id: UUID, body: BodyPort, target: BodyState) {
        val original = prior[id] ?: body.marker() ?: body.read()
        prior[id] = original
        body.setMarker(original)
        body.apply(target)
    }

    /** 還原。回傳是否真的還原了什麼。 */
    fun restore(id: UUID, body: BodyPort): Boolean {
        val original = prior.remove(id) ?: body.marker() ?: return false
        body.apply(original)
        body.setMarker(null)
        return true
    }

    fun isApplied(id: UUID): Boolean = prior.containsKey(id)

    fun clear() = prior.clear()
}
