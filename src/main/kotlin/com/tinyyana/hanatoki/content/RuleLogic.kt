package com.tinyyana.hanatoki.content

import java.util.UUID

/*
 * rc.22 新規則的純邏輯(frozen-when-watched、chases-players、flees-when-powered、power-up、尾聲),
 * 不碰 Bukkit,全部在 session 的 anchor region 執行緒上使用。SessionRun 只負責把它們接到實體與排程。
 */

/** 玩家站立與蹲下時的眼睛高度(腳底往上)。 */
fun eyeHeight(sneaking: Boolean): Double = if (sneaking) 1.27 else 1.62

/** Minecraft 的 yaw／pitch(度)換成視線單位向量 `[x, y, z]`:yaw 0 = +Z、yaw 90 = -X、pitch -90 = 正上方。 */
fun lookDirection(yawDegrees: Double, pitchDegrees: Double): DoubleArray {
    val yaw = Math.toRadians(yawDegrees)
    val pitch = Math.toRadians(pitchDegrees)
    val horizontal = Math.cos(pitch)
    return doubleArrayOf(-Math.sin(yaw) * horizontal, -Math.sin(pitch), Math.cos(yaw) * horizontal)
}

/** 視線方向與「眼睛 → 目標」方向的夾角(度);目標幾乎貼在眼睛上回 0。 */
fun viewAngleDegrees(
    eyeX: Double, eyeY: Double, eyeZ: Double, yawDegrees: Double, pitchDegrees: Double,
    targetX: Double, targetY: Double, targetZ: Double,
): Double {
    val dx = targetX - eyeX
    val dy = targetY - eyeY
    val dz = targetZ - eyeZ
    val distance = Math.sqrt(dx * dx + dy * dy + dz * dz)
    if (distance < 1.0E-6) return 0.0
    val look = lookDirection(yawDegrees, pitchDegrees)
    val dot = (look[0] * dx + look[1] * dy + look[2] * dz) / distance
    return Math.toDegrees(Math.acos(dot.coerceIn(-1.0, 1.0)))
}

/** 目標在視錐內:距離 <= [maxDistance] 且夾角 <= [halfAngleDegrees]。這只是幾何,遮擋另外查。 */
fun inViewCone(
    eyeX: Double, eyeY: Double, eyeZ: Double, yawDegrees: Double, pitchDegrees: Double,
    targetX: Double, targetY: Double, targetZ: Double, halfAngleDegrees: Double, maxDistance: Double,
): Boolean {
    val dx = targetX - eyeX
    val dy = targetY - eyeY
    val dz = targetZ - eyeZ
    if (dx * dx + dy * dy + dz * dz > maxDistance * maxDistance) return false
    return viewAngleDegrees(eyeX, eyeY, eyeZ, yawDegrees, pitchDegrees, targetX, targetY, targetZ) <= halfAngleDegrees
}

/**
 * frozen-when-watched 的凍結狀態。「移動 → 凍結」才算一次 [Change.FROZE],
 * 「凍結 → 移動」才算一次 [Change.MOVED];狀態沒變 [Change.NONE]。
 */
class FreezeState {
    enum class Change { NONE, FROZE, MOVED }

    var frozen: Boolean = false
        private set
    var poseIndex: Int = -1
        private set
    private var lastMoveCueAt = Long.MIN_VALUE / 2

    fun update(watched: Boolean): Change {
        if (watched == frozen) return Change.NONE
        frozen = watched
        return if (watched) Change.FROZE else Change.MOVED
    }

    /** 隨機挑一個姿勢,有兩種以上時不會連續兩次同一個。回傳新的索引(也存進 [poseIndex])。 */
    fun pickPose(count: Int, random: java.util.Random): Int {
        if (count <= 0) { poseIndex = -1; return -1 }
        var next = random.nextInt(count)
        if (count > 1 && next == poseIndex) next = (next + 1 + random.nextInt(count - 1)) % count
        poseIndex = next
        return next
    }

    /** 「喀喀」聲冷卻:到期回 true 並記下 [now]。 */
    fun moveCueDue(now: Long, cooldownTicks: Int): Boolean {
        if (now - lastMoveCueAt < cooldownTicks) return false
        lastMoveCueAt = now
        return true
    }

    /** stage 轉換等:凍結旗標、姿勢記憶、聲音冷卻全部清掉。 */
    fun reset() {
        frozen = false
        poseIndex = -1
        lastMoveCueAt = Long.MIN_VALUE / 2
    }
}

/** 追擊候選:[distance] 是離這隻敵人的距離,[health] 是目前血量。 */
data class ChaseCandidate(val id: UUID, val distance: Double, val health: Double)

/**
 * 挑追擊目標。[sightRange] 內依 [strategy] 選;沒有人在索敵距離內時,[current](已鎖定的目標)只要還在 [giveUpRange] 內
 * 就繼續追,超過放棄回 null。[giveUpRange] 小於 [sightRange] 時以 [sightRange] 為準。
 */
fun pickChaseTarget(
    candidates: List<ChaseCandidate>, strategy: ChaseTarget, sightRange: Double, giveUpRange: Double, current: UUID?,
): UUID? {
    val inSight = candidates.filter { it.distance <= sightRange }
    if (inSight.isNotEmpty()) {
        val picked = when (strategy) {
            ChaseTarget.NEAREST -> inSight.minWith(compareBy<ChaseCandidate>({ it.distance }, { it.id }))
            ChaseTarget.FARTHEST -> inSight.maxWith(compareBy<ChaseCandidate>({ it.distance }, { it.id }))
            ChaseTarget.LOWEST_HEALTH -> inSight.minWith(compareBy<ChaseCandidate>({ it.health }, { it.distance }, { it.id }))
        }
        return picked.id
    }
    val keep = candidates.firstOrNull { it.id == current } ?: return null
    return if (keep.distance <= maxOf(giveUpRange, sightRange)) keep.id else null
}

/** 逃跑時依序嘗試的偏轉角度(度):先直接遠離,再左右偏,最後幾乎折返。 */
val FLEE_ANGLES: List<Double> = listOf(0.0, 45.0, -45.0, 90.0, -90.0, 135.0, -135.0)

/** 從 (fromX, fromZ) 逃離 (threatX, threatZ):把「遠離方向」偏轉 [rotationDegrees],取 [distance] 格外的點 `[x, z]`;兩點重疊時朝 +X。 */
fun fleePoint(fromX: Double, fromZ: Double, threatX: Double, threatZ: Double, distance: Double, rotationDegrees: Double): DoubleArray {
    var dx = fromX - threatX
    var dz = fromZ - threatZ
    val length = Math.hypot(dx, dz)
    if (length < 1.0E-4) { dx = 1.0; dz = 0.0 } else { dx /= length; dz /= length }
    val r = Math.toRadians(rotationDegrees)
    val rx = dx * Math.cos(r) - dz * Math.sin(r)
    val rz = dx * Math.sin(r) + dz * Math.cos(r)
    return doubleArrayOf(fromX + rx * distance, fromZ + rz * distance)
}

/**
 * 全隊 powered 的計時。每位成員各有到期 tick;再吃一次南瓜燈把到期時間刷新成較晚的那個(不疊加)。
 * [expire] 一次回傳剛到期的成員。
 */
class PoweredTracker {
    private val until = HashMap<UUID, Long>()

    val active: Boolean get() = until.isNotEmpty()

    /** 讓 [members] powered [ticks] 個 tick(從 [now] 起算);回傳這次被授予的人數。 */
    fun grant(members: Collection<UUID>, now: Long, ticks: Long): Int {
        val end = now + ticks.coerceAtLeast(1)
        for (id in members) until[id] = maxOf(until[id] ?: 0L, end)
        return members.size
    }

    fun isPowered(id: UUID, now: Long): Boolean = (until[id] ?: return false) > now

    fun poweredIds(now: Long): Set<UUID> = until.filterValues { it > now }.keys.toSet()

    fun remainingTicks(id: UUID, now: Long): Long = ((until[id] ?: return 0L) - now).coerceAtLeast(0L)

    /** 到期的移出並回傳。 */
    fun expire(now: Long): List<UUID> {
        val gone = until.filterValues { it <= now }.keys.toList()
        gone.forEach { until.remove(it) }
        return gone
    }

    fun remove(id: UUID): Boolean = until.remove(id) != null

    /** 全部清掉,回傳原本還 powered 的人。 */
    fun clear(): List<UUID> {
        val all = until.keys.toList()
        until.clear()
        return all
    }
}

/**
 * power-up 物件的狀態機。consume = true:READY → TAKEN,[respawnTicks] > 0 才會回 READY;
 * consume = false:一直是 READY,但拿過之後要隔 max([respawnTicks], [poweredTicks]) 才能再拿。
 */
class PowerUpMachine(private val consume: Boolean, private val respawnTicks: Long, private val poweredTicks: Long) {
    enum class State { READY, TAKEN }

    var state: State = State.READY
        private set
    private var respawnAt = Long.MAX_VALUE
    private var nextUseAt = 0L

    fun canTake(now: Long): Boolean = state == State.READY && now >= nextUseAt

    /** 拿走。成功回 true(同一個時間點不會被拿兩次)。 */
    fun take(now: Long): Boolean {
        if (!canTake(now)) return false
        if (consume) {
            state = State.TAKEN
            respawnAt = if (respawnTicks > 0) now + respawnTicks else Long.MAX_VALUE
        } else {
            nextUseAt = now + maxOf(respawnTicks, poweredTicks, 1L)
        }
        return true
    }

    /** boss 的南瓜燈暫時下架(階段未到):變 TAKEN、不會自動重生,直到 [forceReady]。 */
    fun forceTaken() {
        state = State.TAKEN
        respawnAt = Long.MAX_VALUE
    }

    /** 上架:變 READY 且立刻可拿。 */
    fun forceReady() {
        state = State.READY
        respawnAt = Long.MAX_VALUE
        nextUseAt = 0L
    }

    /** TAKEN 且到期 → READY,回 true。 */
    fun tryRespawn(now: Long): Boolean {
        if (state != State.TAKEN || now < respawnAt) return false
        state = State.READY
        respawnAt = Long.MAX_VALUE
        return true
    }
}

/** 尾聲序列:`current()` 是目前這一步,`advance()` 換下一步,播完 `finished`。 */
class EpilogueSequence(private val steps: List<EpilogueStep>) {
    private var index = 0

    val finished: Boolean get() = index >= steps.size
    val position: Int get() = index

    fun current(): EpilogueStep? = steps.getOrNull(index)

    fun advance(): EpilogueStep? {
        if (!finished) index++
        return current()
    }
}

/** 對話步驟的等待名單:全部 [done] 或被 [remove](離場)才算完成。 */
class DialogWait(members: Collection<UUID>) {
    private val waiting = HashSet(members)

    val complete: Boolean get() = waiting.isEmpty()
    val size: Int get() = waiting.size

    fun done(id: UUID) { waiting.remove(id) }
    fun remove(id: UUID) { waiting.remove(id) }
}

/** 敵人身體該呈現的動畫類別(對應 [EnemySpec] 的動畫名)。 */
enum class BodyAnim { STUNNED, POSE, FLEEING, MOVING, IDLE }

/** [aiOn] = 原版 AI／導航是否啟用;[anim] = 該播哪一類模型動畫。 */
data class BodyPlan(val aiOn: Boolean, val anim: BodyAnim)

/**
 * 敵人的 AI 與動畫由狀態唯一決定:AI 只在 ALIVE 且沒凍結時開;動畫依序是僵直、凍結姿勢、逃跑、(人偶)移動、預設。
 * 僵直結束、逃跑結束、stage 轉換後都走這個,避免各處各自 setAI(true) 把凍結中的敵人放出來。
 * 打散中或已消失回 null(不再動它)。
 */
fun bodyPlan(state: EnemyMachine.State, frozen: Boolean, fleeing: Boolean, isDoll: Boolean): BodyPlan? = when (state) {
    EnemyMachine.State.DISSOLVING, EnemyMachine.State.GONE -> null
    EnemyMachine.State.STUNNED -> BodyPlan(false, BodyAnim.STUNNED)
    EnemyMachine.State.ALIVE -> when {
        frozen && isDoll -> BodyPlan(false, BodyAnim.POSE)
        fleeing -> BodyPlan(true, BodyAnim.FLEEING)
        isDoll -> BodyPlan(true, BodyAnim.MOVING)
        else -> BodyPlan(true, BodyAnim.IDLE)
    }
}

// ---- rc.31:霧鬼吹燈、人偶靠近加速 ----

/** 可被吹熄的燈與它到敵人的距離。 */
data class SnuffCandidate(val id: String, val distance: Double)

/**
 * 霧鬼這一刻要不要去吹燈。[candidates] 是目前合格的燈(狀態對、stage 進行中…)。
 * 已鎖定的 [committed] 燈還合格就繼續去(不來回改主意);否則 [roll](0 <= roll < 1,呼叫端擲骰)< [preference] 才挑最近的一盞,
 * 擲不中回 null = 這輪繼續追人。[preference] = 1 一定去燈,0 永遠追人。
 */
fun pickSnuffLamp(candidates: List<SnuffCandidate>, committed: String?, preference: Double, roll: Double): String? {
    if (candidates.isEmpty()) return null
    if (committed != null && candidates.any { it.id == committed }) return committed
    if (roll >= preference) return null
    return candidates.minWith(compareBy<SnuffCandidate>({ it.distance }, { it.id })).id
}

/** 人偶沒人看著時的尋路速度倍率:最近成員在 [RushSpec.distance] 內就乘上 [RushSpec.speedMultiplier](上限 [RUSH_SPEED_CAP])。 */
fun rushSpeed(base: Double, rush: RushSpec?, nearestDistance: Double?): Double =
    if (rush != null && nearestDistance != null && nearestDistance <= rush.distance) minOf(base * rush.speedMultiplier, RUSH_SPEED_CAP) else base

const val RUSH_SPEED_CAP = 4.0
