package com.tinyyana.hanatoki.content

import java.util.Random
import java.util.UUID
import java.util.function.Consumer

/**
 * [BossRun] 對外界的全部需求:世界、實體、玩家、計時、演出都經過這個介面,所以 Boss 的階段、時間軸、護盾、跪下、倒下與
 * 全滅重來可以脫離 Bukkit 單元測試(測試用假的 host)。實作在 SessionRun(所有方法都在 session 的 anchor region 執行緒呼叫,
 * 除了標示 [sync] 的)。
 */
interface BossHost {
    val now: Long

    /** 排進 session 的 TimerQueue(跨 stage 轉換不被砍,是否有效由 BossRun 的 epoch 判斷)。 */
    fun schedule(delayTicks: Long, action: Runnable)

    fun log(event: String, detail: String)

    /** 目前的成員(含離線寬限期內的)。 */
    fun members(): List<UUID>

    /** 成員此刻是否在線(斷線寬限期內 = 離線)。 */
    fun isOnline(id: UUID): Boolean

    fun nameOf(id: UUID): String

    fun markerPos(id: String): Pt?

    // ---- 演出 ----
    fun cue(cueId: String, at: Pt)

    /** 只放給一位玩家看的 cue。 */
    fun cueFor(cueId: String, at: Pt, player: UUID)

    /**
     * 取樣用的短促 cue(光環迴旋的光束與缺口標示,每幾 tick 一大把)。實作端不要無上限保留它們的 CueRun
     * (SessionRun 只留最近一小段,局結束時取消);預設等同 [cue]。cue 必須是短促、非 loop 的。
     */
    fun sampleCue(cueId: String, at: Pt) { cue(cueId, at) }

    /** 持續型 cue,回傳取消用的 Runnable。 */
    fun loopCue(cueId: String, at: Pt): Runnable

    /** 持續型 cue,跟著玩家(倒下標示)。 */
    fun followCue(cueId: String, player: UUID, at: Pt): Runnable

    fun music(cueId: String)
    fun title(titleKey: String, subtitleKey: String?)
    fun titleFor(player: UUID, titleKey: String, subtitleKey: String?)
    fun actionBar(player: UUID, key: String)
    fun bossBar(textKey: String, fraction: Double, color: String, overlay: String, params: Map<String, String>)
    fun hideBossBar()

    // ---- boss 實體(載體 + 模型)----
    fun spawnBoss(at: Pt)
    fun moveBoss(to: Pt)
    fun faceBoss(toward: Pt)
    fun playAnimation(name: String, loop: Boolean)
    fun setBone(bone: String, visible: Boolean)
    fun despawnBoss()

    // ---- 玩家 ----
    /** 成員快照(欄位順序同 StageContext.MEMBER_SNAPSHOT_FIELDS);回呼在 anchor 執行緒。取不到時給空 map。 */
    fun snapshots(callback: Consumer<Map<UUID, DoubleArray>>)

    /**
     * 固定比例傷害(最大血量的 [fraction],不看護甲、附魔與抗性)。會致死就把血量留在 1 並呼叫 [onLethal](在 anchor 執行緒)。
     * [push] 非 null 時從 [from] 往外推。
     */
    fun damageFixed(id: UUID, fraction: Double, push: HarassEffect.Push?, from: Pt?, onLethal: Runnable)

    /** 套用倒下身體狀態並(依 [DownedSpec.move])移到最近的 [revivePoints]。派工到玩家自己的執行緒。 */
    fun applyDowned(id: UUID, spec: DownedSpec, revivePoints: List<Pt>)

    /** 還原倒下前的身體狀態;[health] 非 null 時一併設定血量。派工到玩家自己的執行緒,不依賴成員名單(離場、局結束後也要能還原)。 */
    fun restoreDowned(id: UUID, health: Double?)

    /** 斷線事件當下在玩家自己的執行緒上同步還原([sync]):玩家資料還沒存檔前,不能把倒下的身體狀態寫進去。 */
    fun restoreDownedSync(id: UUID)

    fun teleportPlayer(id: UUID, to: Pt)

    // ---- 場邊燈、南瓜燈、powered ----
    /** 場邊燈目前不是未點亮（燒到一半或已亮）。重置時所有這種燈都要回到未點亮。 */
    fun lampUsed(lamp: String): Boolean
    fun lampPos(lamp: String): Pt?

    /** 把場邊燈重置成未點亮(狀態機、方塊外觀、持續 cue、世界標示)。 */
    fun resetLamp(lamp: String)

    /** 南瓜燈上架或下架([available] = false:拿走狀態且不重生)。 */
    fun setPumpkinsAvailable(objects: List<String>, available: Boolean)

    fun isPowered(id: UUID): Boolean
    fun endPowered(reason: String)

    // ---- 召喚的敵人 ----
    /** 生一隻 [enemyId](enemies.yml)在 [at];回傳是否送出生成。 */
    fun spawnSummon(actorId: String, enemyId: String, at: Pt): Boolean

    fun dissolveSummons(actorIds: Collection<String>)

    // ---- stage ----
    fun onBossDefeated()
}

/** 出場演出的進度(rc.24)。 */
private enum class IntroPhase { NONE, HOLD, DESCEND, DONE }

/** BossRun 的狀態。 */
enum class BossState { WAITING, IDLE, CASTING, KNEELING, RISING, PHASE_SHIFT, WIPED, DEFEATED, STOPPED }

/**
 * 一個 boss 的一局執行期(rc.23)。所有可變狀態只在 anchor region 執行緒讀寫;給事件執行緒同步讀的只有
 * [state]（volatile）與 [isDowned]（並行集合）。
 *
 * 時間軸:WAITING(出場等待)→ IDLE(滑行 + 排招)⇄ CASTING(預警 → 判定)→ 四盞場邊燈全亮:KNEELING(跪下輸出)
 * → RISING(起身、護盾恢復、場邊燈重置)→ IDLE …;血量歸零 DEFEATED;全員倒下 WIPED(回檢查點重來)。
 * 每個計時任務都帶 epoch：跪下、全滅、倒下、局結束時 epoch 加一，舊任務自己失效，不需要取消。
 */
class BossRun(private val host: BossHost, val spec: BossSpec, private val random: Random = Random()) {

    @Volatile
    var state: BossState = BossState.WAITING
        private set

    /** 事件執行緒用:致死傷害攔截只看這個。 */
    val fightActive: Boolean get() = state != BossState.DEFEATED && state != BossState.STOPPED

    val defeated: Boolean get() = state == BossState.DEFEATED

    private var epoch = 0
    private var health = BossHealth(spec.health, spec.phases.map { it.at })
    private var phase = 1
    private var shieldUp = true
    private val gems = GemTracker(spec.lampIds)
    private val ledger = TelegraphLedger()
    private val downed = DownedTracker()
    private val positions = HashMap<UUID, Pt>()
    private val lastHitAt = HashMap<UUID, Long>()
    private var lastSparkAt = -1000L
    private var pos: Pt = Pt(0.0, 0.0, 0.0)

    private var targetIdx = 0
    private var dwellUntil = 0L
    private var gliding = false
    private var anim: String? = null

    private var startAt = 0L
    private var restartAt = 0L
    private var kneelEnd = 0L
    private var riseEnd = 0L
    private var shiftEnd = 0L
    private var nextMoveAt = 0L
    private var nextHud = 0L
    private var nextPositions = 0L
    private var pendingPhase = false
    private var pumpkinsAvailable: Boolean? = null

    private var introPhase = IntroPhase.NONE
    private var introHoldEnd = 0L
    private var descendStart = 0L
    private var descendEnd = 0L
    private var descendFrom: Pt = Pt(0.0, 0.0, 0.0)

    private val lastUsed = HashMap<String, Long>()
    private var lastMove: String? = null
    private var cast: Cast? = null
    private var castSeq = 0

    private var snuffLamp: String? = null
    private var snuffSaved = false
    private var snuffLoop: Runnable? = null

    private val markRuns = HashMap<UUID, Runnable>()
    private val summons = LinkedHashMap<String, String>()
    private var summonSeq = 0
    private val spinHitAt = HashMap<UUID, Long>()

    private class Cast(val move: MoveSpec, val id: String, val epoch: Int) {
        var done = false
        val hit = HashSet<UUID>()
        val dodged = HashSet<UUID>()
    }

    // ---- 小工具 ----

    private fun ticks(seconds: Double): Long = Math.round(seconds * 20.0)

    private fun name(id: UUID) = host.nameOf(id)

    private fun later(delayTicks: Long, action: () -> Unit) {
        val e = epoch
        host.schedule(delayTicks.coerceAtLeast(0), Runnable { if (e == epoch && state != BossState.STOPPED) action() })
    }

    private fun cues(ids: List<String>, at: Pt) = ids.forEach { host.cue(it, at) }

    private fun playAnim(name: String?, loop: Boolean) {
        if (name == null) return
        anim = name
        host.playAnimation(name, loop)
    }

    /** 回到基底動畫(閒置或滑行)。 */
    private fun resumeAnim() {
        gliding = false
        playAnim(spec.animations.idle, true)
    }

    private fun alive(c: Cast): Boolean = !c.done && c.epoch == epoch && state == BossState.CASTING

    private fun onlineMembers(): List<UUID> = host.members().filter { host.isOnline(it) }

    private fun updatePositions(snap: Map<UUID, DoubleArray>) {
        for ((id, s) in snap) positions[id] = Pt(s[SX], s[SY], s[SZ])
    }

    /** 能當目標的成員:在線、沒倒下。 */
    private fun usable(snap: Map<UUID, DoubleArray>): Map<UUID, DoubleArray> {
        val members = host.members().toSet()
        return snap.filter { (id, _) -> id in members && !downed.contains(id) && host.isOnline(id) }
    }

    private fun scaled(): Double = scaledMaxHits(spec.health, spec.byPlayers, host.members().size)

    // ---- 生命週期 ----

    /** stage 開始:boss 出場,等 start-delay 後開打。 */
    fun start() {
        val first = host.markerPos(spec.points.first()) ?: Pt(0.0, 0.0, 0.0)
        val intro = spec.intro
        // 有出場演出:先在 intro marker(例如屋頂)現身,停一會兒再落到 then-glide-to;沒有就同舊版直接在第一個點
        val spawnAt = intro?.let { host.markerPos(it.marker) } ?: first
        pos = spawnAt
        host.spawnBoss(spawnAt)
        cues(spec.spawnCues, spawnAt)
        introPhase = IntroPhase.NONE
        if (intro != null) beginIntro(intro, spawnAt)
        state = BossState.WAITING
        startAt = host.now + ticks(spec.startDelaySeconds)
        targetIdx = if (spec.points.size > 1) 1 else 0
        health = BossHealth(scaled(), spec.phases.map { it.at })
        phase = 1
        shieldUp = true
        pumpkinsAvailable = null
        applyPumpkins()
        updateBar()
        host.log("boss-start", "${spec.id} hits=${fmt(health.maxHits)} players=${host.members().size}")
    }

    // ---- 出場演出 ----

    private fun introLanding(intro: BossIntroSpec): Pt =
        host.markerPos(intro.glideTo ?: spec.points.first()) ?: host.markerPos(spec.points.first()) ?: pos

    private fun beginIntro(intro: BossIntroSpec, at: Pt) {
        introPhase = IntroPhase.HOLD
        introHoldEnd = host.now + ticks(intro.holdSeconds)
        host.log("boss-intro", "${spec.id} marker=${intro.marker}")
        cues(intro.cues, at)
        // 載體剛生成、模型還在綁定:晚一點再播單次動畫才不會掉
        val animation = intro.animation ?: return
        later(INTRO_ANIM_DELAY_TICKS) {
            if (introPhase == IntroPhase.HOLD) {
                playAnim(animation, false)
                later(ticks(intro.animationSeconds)) { if (introPhase == IntroPhase.HOLD) resumeAnim() }
            }
        }
    }

    private fun tickIntro() {
        val intro = spec.intro ?: return
        val now = host.now
        if (introPhase == IntroPhase.HOLD && now >= introHoldEnd) {
            if (intro.glideSeconds <= 0.0) { landIntro(intro); return }
            introPhase = IntroPhase.DESCEND
            descendFrom = pos
            descendStart = now
            descendEnd = now + ticks(intro.glideSeconds).coerceAtLeast(1)
            playAnim(spec.animations.glide, true)
        }
        if (introPhase == IntroPhase.DESCEND) {
            if (now >= descendEnd) { landIntro(intro); return }
            val to = introLanding(intro)
            val f = ((now - descendStart).toDouble() / (descendEnd - descendStart)).coerceIn(0.0, 1.0)
            pos = Pt(descendFrom.x + (to.x - descendFrom.x) * f, descendFrom.y + (to.y - descendFrom.y) * f, descendFrom.z + (to.z - descendFrom.z) * f)
            host.moveBoss(pos)
        }
    }

    private fun landIntro(intro: BossIntroSpec) {
        val to = introLanding(intro)
        pos = to
        host.moveBoss(to)
        introPhase = IntroPhase.DONE
        resumeAnim()
        host.log("boss-intro-done", "${spec.id} point=${intro.glideTo ?: spec.points.first()}")
    }

    private fun begin() {
        spec.intro?.let { if (introPhase == IntroPhase.HOLD || introPhase == IntroPhase.DESCEND) landIntro(it) }
        if (health.taken <= 0.0) health = BossHealth(scaled(), spec.phases.map { it.at })
        resumeAnim()
        enterPhase(phase)
        state = BossState.IDLE
        nextMoveAt = host.now + ticks(spec.gapSeconds)
        checkShieldBreak()
    }

    /** 每個 TICK_PERIOD 呼叫一次(SessionRun.tick)。 */
    fun tick() {
        val now = host.now
        when (state) {
            BossState.STOPPED, BossState.DEFEATED -> return
            BossState.WAITING -> {
                tickIntro()
                if (now >= startAt) begin()
            }
            BossState.WIPED -> if (now >= restartAt) restartFight()
            BossState.IDLE -> {
                glide()
                if (now >= nextMoveAt) tryStartMove()
            }
            BossState.KNEELING -> if (now >= kneelEnd) rise()
            BossState.RISING -> if (now >= riseEnd) {
                state = BossState.IDLE
                resumeAnim()
                nextMoveAt = now + ticks(spec.gapSeconds)
                checkShieldBreak()
            }
            BossState.PHASE_SHIFT -> if (now >= shiftEnd) {
                state = BossState.IDLE
                resumeAnim()
                nextMoveAt = now + ticks(spec.gapSeconds)
            }
            BossState.CASTING -> {}
        }
        if (!fightActive) return
        if (downed.size > 0 && now >= nextHud) {
            nextHud = now + ticks(spec.downed.hudEverySeconds)
            spec.downed.hudTextKey?.let { key -> downed.ids().forEach { if (host.isOnline(it)) host.actionBar(it, key) } }
        }
        if (now >= nextPositions) {
            nextPositions = now + POSITION_REFRESH_TICKS
            host.snapshots(Consumer { snap -> if (fightActive) updatePositions(snap) })
        }
    }

    // ---- 滑行 ----

    private fun glide() {
        if (spec.points.size < 2) return
        val now = host.now
        if (now < dwellUntil) return
        val target = host.markerPos(spec.points[targetIdx]) ?: return
        val step = spec.glide.speed * TICK_PERIOD / 20.0
        val (next, arrived) = glideStep(pos, target, step)
        if (!gliding) {
            gliding = true
            playAnim(spec.animations.glide, true)
        }
        pos = next
        host.moveBoss(next)
        if (arrived) {
            gliding = false
            playAnim(spec.animations.idle, true)
            dwellUntil = now + ticks(spec.glide.dwellSeconds)
            targetIdx = nextPointIndex()
        }
    }

    private fun nextPointIndex(): Int {
        val n = spec.points.size
        if (n < 2) return 0
        return when (spec.glide.order) {
            GlideOrder.CYCLE -> (targetIdx + 1) % n
            GlideOrder.RANDOM -> {
                var next = random.nextInt(n - 1)
                if (next >= targetIdx) next++
                next
            }
        }
    }

    // ---- 階段、血量條、南瓜燈 ----

    private fun enterPhase(n: Int) {
        phase = n
        host.log("boss-phase", "$n")
        val effects = spec.phases[n - 1].onEnter
        cues(effects.cues, pos)
        effects.music?.let(host::music)
        effects.titleKey?.let { host.title(it, effects.subtitleKey) }
        applyPumpkins()
        updateBar()
    }

    private fun advancePhase(target: Int) {
        var changed = false
        while (phase < target && phase < spec.phases.size) {
            enterPhase(phase + 1)
            changed = true
        }
        if (changed && state == BossState.IDLE) {
            state = BossState.PHASE_SHIFT
            shiftEnd = host.now + ticks(spec.phaseUpSeconds)
            playAnim(spec.animations.phaseUp, false)
        }
    }

    private fun applyPumpkins() {
        val p = spec.pumpkins ?: return
        val available = phase >= p.fromPhase
        if (pumpkinsAvailable == available) return
        pumpkinsAvailable = available
        host.setPumpkinsAvailable(p.objects, available)
    }

    private fun updateBar() {
        val bar = spec.bar ?: return
        val percent = Math.round(health.fraction * 100.0).toInt()
        host.bossBar(bar.textKey, health.fraction, bar.color, bar.overlay, mapOf("percent" to percent.toString(), "phase" to phase.toString()))
    }

    private fun fmt(v: Double): String = if (v == Math.rint(v)) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

    // ---- 被打 ----

    /**
     * 玩家一下有效近戰（事件已在事發執行緒做完 cooldown／蓄力的篩選，這裡處理護盾與計數）。
     * 護盾亮著且攻擊者沒有 powered：濺火花、不扣血；跪下時任何人有效；powered 穿過護盾用 powered-hit-weight。
     */
    fun onHit(attacker: UUID, at: Pt) {
        if (!fightActive || downed.contains(attacker)) return
        val now = host.now
        val powered = host.isPowered(attacker)
        val canDamage = state != BossState.WAITING && state != BossState.WIPED
        if (shieldUp && (!powered || !canDamage)) {
            if (now - lastSparkAt >= spec.shield.sparkCooldownTicks) {
                lastSparkAt = now
                cues(spec.shield.sparkCues, at)
                if (state == BossState.IDLE) shortAnim(spec.animations.shieldSpark)
            }
            return
        }
        if (!canDamage) return
        val last = lastHitAt[attacker]
        if (last != null && now - last < spec.hitCooldownTicks) return
        lastHitAt[attacker] = now
        health.hit(if (shieldUp) spec.poweredHitWeight else spec.hitWeight)
        cues(spec.hitCues, at)
        updateBar()
        if (state == BossState.IDLE) shortAnim(spec.animations.hit)
        if (health.defeated) { defeat(); return }
        val target = health.phaseByHealth()
        if (target > phase) {
            if (state == BossState.KNEELING) pendingPhase = true else advancePhase(target)
        }
    }

    /** 閒置時的短動畫（受擊、火花），播完回基底。 */
    private fun shortAnim(name: String) {
        playAnim(name, false)
        later(SHORT_ANIM_TICKS) { if (state == BossState.IDLE) { if (gliding) playAnim(spec.animations.glide, true) else playAnim(spec.animations.idle, true) } }
    }

    // ---- 場邊燈與護盾 ----

    fun onLampLit(lamp: String) {
        if (lamp !in spec.lampIds || state == BossState.STOPPED || state == BossState.DEFEATED) return
        if (!gems.setLit(lamp, true)) return
        val gem = spec.gems.first { it.lamp == lamp }
        host.lampPos(lamp)?.let { cues(gem.beamCues, it) }
        cues(gem.breakCues, pos)
        gem.bone?.let { host.setBone(it, false) }
        checkShieldBreak()
    }

    private fun restoreGem(lamp: String) {
        if (!gems.setLit(lamp, false)) return
        val gem = spec.gems.first { it.lamp == lamp }
        cues(gem.restoreCues, pos)
        gem.bone?.let { host.setBone(it, true) }
    }

    private fun checkShieldBreak() {
        if (!gems.allLit || !shieldUp) return
        if (state != BossState.IDLE && state != BossState.CASTING && state != BossState.PHASE_SHIFT) return
        breakShield()
    }

    private fun breakShield() {
        cancelCast("shield-broken")
        epoch++
        shieldUp = false
        state = BossState.KNEELING
        kneelEnd = host.now + ticks(spec.kneel.seconds)
        gliding = false
        host.log("boss-shield-broken", "")
        cues(spec.shield.breakCues, pos)
        spec.shield.haloBone?.let { host.setBone(it, false) }
        host.log("boss-kneel", "seconds=${fmt(spec.kneel.seconds)}")
        cues(spec.kneel.cues, pos)
        playAnim(spec.animations.kneel, true)
        reviveAll("kneel")
    }

    private fun rise() {
        state = BossState.RISING
        riseEnd = host.now + ticks(spec.kneel.riseSeconds)
        shieldUp = true
        playAnim(spec.animations.rise, false)
        cues(spec.kneel.riseCues, pos)
        cues(spec.shield.upCues, pos)
        spec.shield.haloBone?.let { host.setBone(it, true) }
        if (spec.kneel.lampsReset == LampReset.ON_RISE) {
            for (gem in spec.gems) {
                if (gems.isLit(gem.lamp) || host.lampUsed(gem.lamp)) host.resetLamp(gem.lamp)
                restoreGem(gem.lamp)
            }
        }
        if (pendingPhase) {
            pendingPhase = false
            val target = health.phaseByHealth()
            while (phase < target && phase < spec.phases.size) enterPhase(phase + 1)
        }
    }

    // ---- 排招與施放 ----

    private fun available(moveId: String): Boolean = when (val shape = spec.moves.getValue(moveId).shape) {
        is MoveShape.Snuff -> gems.litLamps().any { it != snuffLamp }
        is MoveShape.Summon -> summons.values.count { it == shape.enemy } < shape.maxAlive
        else -> true
    }

    private fun tryStartMove() {
        val now = host.now
        val candidates = spec.moves.values.map { MoveCandidate(it.id, it.weight, ticks(it.cooldownSeconds), it.fromPhase) }
        val picked = MovePicker.pick(candidates, phase, now, lastUsed, lastMove, ::available, random)
        if (picked == null) {
            nextMoveAt = now + RETRY_TICKS
            return
        }
        val move = spec.moves.getValue(picked)
        state = BossState.CASTING
        lastUsed[picked] = now
        lastMove = picked
        val e = epoch
        host.snapshots(Consumer { snap ->
            if (e != epoch || state != BossState.CASTING || cast != null) return@Consumer
            updatePositions(snap)
            beginCast(move, usable(snap))
        })
        // 快照一直沒回來也不能卡在 CASTING
        later(WATCHDOG_TICKS) {
            if (state == BossState.CASTING && cast == null) {
                state = BossState.IDLE
                nextMoveAt = host.now + RETRY_TICKS
            }
        }
    }

    private fun originOf(move: MoveSpec): Pt = move.origin.marker?.let { host.markerPos(it) } ?: pos

    private fun nearest(from: Pt, snap: Map<UUID, DoubleArray>): Pair<UUID, Pt>? =
        snap.map { (id, s) -> id to Pt(s[SX], s[SY], s[SZ]) }.minByOrNull { it.second.horizontalDistance(from) }

    private fun abortCast(c: Cast, reason: String) {
        c.done = true
        ledger.end(c.id)
        cast = null
        host.log("boss-move-abort", "${c.move.id} reason=$reason")
        if (state == BossState.CASTING) {
            state = BossState.IDLE
            nextMoveAt = host.now + RETRY_TICKS
        }
        resumeAnim()
    }

    private fun beginCast(move: MoveSpec, snap: Map<UUID, DoubleArray>) {
        val shape = move.shape
        val needsPlayers = shape !is MoveShape.Summon && shape !is MoveShape.Snuff
        if (needsPlayers && snap.isEmpty()) {
            state = BossState.IDLE
            nextMoveAt = host.now + RETRY_TICKS
            return
        }
        val c = Cast(move, "cast-${++castSeq}", epoch)
        cast = c
        val origin = originOf(move)
        nearest(origin, snap)?.let { host.faceBoss(it.second) }
        val warn = ticks(move.warnSeconds)
        when (shape) {
            is MoveShape.Ring -> {
                val members = snap.keys.toList()
                if (!ledger.tryBeginAll(members, c.id)) { abortCast(c, "telegraph-busy"); return }
                announce(c, members, origin, loop = false)
                later(warn) { ringExpand(c, shape, origin) }
            }
            is MoveShape.Circle -> {
                val pool = snap.keys.toList().shuffled(random)
                val chosen = if (shape.maxTargets > 0) pool.take(shape.maxTargets) else pool
                val begun = chosen.filter { ledger.tryBegin(it, c.id) }
                if (begun.isEmpty()) { abortCast(c, "telegraph-busy"); return }
                val centers = begun.associateWith { Pt(snap.getValue(it)[SX], snap.getValue(it)[SY], snap.getValue(it)[SZ]) }
                begun.forEach { host.log("boss-telegraph", "${move.id} target=${name(it)}") }
                playAnim(move.animation, false)
                centers.values.forEach { cues(move.warnCues, it) }
                later(warn) { circleResolve(c, shape, centers.values.toList()) }
            }
            is MoveShape.Cross -> {
                val members = snap.keys.toList()
                if (!ledger.tryBeginAll(members, c.id)) { abortCast(c, "telegraph-busy"); return }
                announce(c, members, origin, loop = false)
                later(warn) { crossResolve(c, shape, origin) }
            }
            is MoveShape.Spin -> {
                val members = snap.keys.toList()
                if (!ledger.tryBeginAll(members, c.id)) { abortCast(c, "telegraph-busy"); return }
                announce(c, members, origin, loop = true)
                val start = nearest(origin, snap)?.let { angleDegrees(it.second.x - origin.x, it.second.z - origin.z) } ?: 0.0
                // 缺口方向從預警就畫出來(停在起始方位),迴旋開始後跟著轉
                if (shape.hasVisual) spinVisual(c, shape, origin, start, -warn)
                later(warn) { spinStep(c, shape, origin, start, 0L) }
            }
            is MoveShape.Summon -> {
                playAnim(move.animation, false)
                cues(move.warnCues, pos)
                later(warn) { summon(c, shape) }
            }
            is MoveShape.Snuff -> startSnuff(c, shape, warn)
        }
    }

    private fun announce(c: Cast, members: List<UUID>, origin: Pt, loop: Boolean) {
        members.forEach { host.log("boss-telegraph", "${c.move.id} target=${name(it)}") }
        playAnim(c.move.animation, loop)
        cues(c.move.warnCues, origin)
    }

    private fun endCast(c: Cast) {
        if (c.done) return
        c.done = true
        ledger.end(c.id)
        if (cast === c) cast = null
        host.log("boss-move-end", c.move.id)
        if (state == BossState.CASTING && c.epoch == epoch) {
            state = BossState.IDLE
            nextMoveAt = host.now + ticks(c.move.recoverySeconds + spec.gapSeconds)
            resumeAnim()
        }
    }

    /** 跪下、全滅、倒下、局結束:進行中的招式整個作廢(預警登記、熄燈閃爍、迴旋全部釋放)。 */
    private fun cancelCast(reason: String) {
        val c = cast
        if (c != null) {
            c.done = true
            host.log("boss-move-cancel", "${c.move.id} reason=$reason")
        }
        cast = null
        ledger.clear()
        snuffLoop?.run()
        snuffLoop = null
        snuffLamp = null
        snuffSaved = false
        spinHitAt.clear()
    }

    private fun hitPlayer(c: Cast, id: UUID, from: Pt?) {
        val mult = damageMultiplier(spec.byPlayers, host.members().size)
        host.damageFixed(id, (c.move.damagePercent * mult).coerceIn(0.0, 1.0), c.move.push, from, Runnable { downMember(id) })
        host.log("boss-hit", "${c.move.id} player=${name(id)}")
        cues(c.move.hitCues, positions[id] ?: pos)
    }

    // 掃地:環形波
    private fun ringExpand(c: Cast, s: MoveShape.Ring, center: Pt) {
        if (!alive(c)) return
        val total = ticks(s.expandSeconds).coerceAtLeast(SAMPLE_TICKS)
        fun step(k: Long) {
            val t = (k * SAMPLE_TICKS).coerceAtMost(total)
            val front = s.maxRadius * t / total
            val prev = s.maxRadius * ((k - 1) * SAMPLE_TICKS).coerceAtLeast(0) / total
            host.snapshots(Consumer { snap ->
                if (!alive(c)) return@Consumer
                updatePositions(snap)
                for ((id, p) in usable(snap)) {
                    val dx = p[SX] - center.x
                    val dz = p[SZ] - center.z
                    if (Math.abs(p[SY] - center.y) > BOSS_HEIGHT || !inRingSweep(Math.hypot(dx, dz), prev, front, s.bandWidth)) continue
                    if (p[SGROUND] < 0.5) {
                        if (c.dodged.add(id) && id !in c.hit) {
                            s.dodgeCues.forEach { host.cueFor(it, Pt(p[SX], p[SY], p[SZ]), id) }
                            host.log("boss-dodge", "${c.move.id} player=${name(id)}")
                        }
                    } else if (c.hit.add(id)) {
                        hitPlayer(c, id, center)
                    }
                }
            })
            if (t < total) later(SAMPLE_TICKS) { step(k + 1) } else later(SAMPLE_TICKS * 2) { endCast(c) }
        }
        cues(c.move.hitCues, center)
        step(1)
    }

    // 落雷:每位目標腳下一個圈
    private fun circleResolve(c: Cast, s: MoveShape.Circle, centers: List<Pt>) {
        if (!alive(c)) return
        host.snapshots(Consumer { snap ->
            if (!alive(c)) return@Consumer
            updatePositions(snap)
            for ((id, p) in usable(snap)) {
                val inside = centers.any { inCircle(p[SX] - it.x, p[SY] - it.y, p[SZ] - it.z, s.radius) }
                if (inside && c.hit.add(id)) hitPlayer(c, id, centers.minByOrNull { Math.hypot(p[SX] - it.x, p[SZ] - it.z) })
            }
            centers.forEach { cues(c.move.hitCues, it) }
            later(RESOLVE_PAD_TICKS) { endCast(c) }
        })
    }

    // 雙杖十字
    private fun crossResolve(c: Cast, s: MoveShape.Cross, center: Pt) {
        if (!alive(c)) return
        host.snapshots(Consumer { snap ->
            if (!alive(c)) return@Consumer
            updatePositions(snap)
            cues(c.move.hitCues, center)
            for ((id, p) in usable(snap)) {
                if (inCross(p[SX] - center.x, p[SY] - center.y, p[SZ] - center.z, s.length, s.halfWidth, s.angleOffset) && c.hit.add(id)) {
                    hitPlayer(c, id, center)
                }
            }
            later(RESOLVE_PAD_TICKS) { endCast(c) }
        })
    }

    // 光環迴旋:光束環留缺口,缺口持續轉
    private fun spinStep(c: Cast, s: MoveShape.Spin, center: Pt, startAngle: Double, elapsed: Long) {
        if (!alive(c)) return
        val total = ticks(s.durationSeconds)
        val gapCenter = startAngle + s.degreesPerSecond * elapsed / 20.0
        host.snapshots(Consumer { snap ->
            if (!alive(c)) return@Consumer
            updatePositions(snap)
            val now = host.now
            for ((id, p) in usable(snap)) {
                if (!inSpinBeam(p[SX] - center.x, p[SY] - center.y, p[SZ] - center.z, s.innerRadius, s.radius, gapCenter, s.gapDegrees)) continue
                val last = spinHitAt[id]
                if (last != null && now - last < s.hitIntervalTicks) continue
                spinHitAt[id] = now
                hitPlayer(c, id, center)
            }
        })
        if (elapsed < total) later(SPIN_SAMPLE_TICKS) { spinStep(c, s, center, startAngle, elapsed + SPIN_SAMPLE_TICKS) }
        else endCast(c)
    }

    /**
     * 光環迴旋的視覺取樣(rc.24):每 [MoveShape.Spin.sampleTicks] tick 依目前缺口方位算出光束線與缺口邊緣上的取樣點,
     * 對每點播資料指定的 cue。[elapsed] 是距迴旋開始的 tick(預警期間為負,缺口不動)。每輪 cue 數有上限
     * ([MoveShape.Spin.MAX_SAMPLE_CUES],載入時檢查),招式被作廢時自己停。
     */
    private fun spinVisual(c: Cast, s: MoveShape.Spin, center: Pt, startAngle: Double, elapsed: Long) {
        if (!alive(c)) return
        val gap = spinGapCenter(startAngle, s.degreesPerSecond, elapsed)
        val samples = spinSamplePoints(center, s.innerRadius, s.radius, gap, s.gapDegrees, if (s.beamCues.isEmpty()) 0 else s.beams, s.pointsPerLine, withGap = s.gapCues.isNotEmpty())
        for (pt in samples.gapEdge) for (id in s.gapCues) host.sampleCue(id, pt)
        for (pt in samples.beam) for (id in s.beamCues) host.sampleCue(id, pt)
        val next = elapsed + s.sampleTicks
        if (next <= ticks(s.durationSeconds)) later(s.sampleTicks.toLong()) { spinVisual(c, s, center, startAngle, next) }
    }

    // 喚敵
    private fun summon(c: Cast, s: MoveShape.Summon) {
        if (!alive(c)) return
        val room = s.maxAlive - summons.values.count { it == s.enemy }
        val markers = s.markers.mapNotNull { host.markerPos(it) }
        val wanted = if (s.countByPlayers.isEmpty()) markers.size else pickByPlayers(s.countByPlayers, host.members().size.coerceAtLeast(1), markers.size)
        val count = minOf(wanted, markers.size, room.coerceAtLeast(0))
        // 起點輪替,不同次召喚不固定用同幾個點
        val offset = if (markers.isEmpty()) 0 else (summonSeq % markers.size)
        var spawned = 0
        for (i in 0 until count) {
            val at = markers[(offset + i) % markers.size]
            val actorId = "${spec.id}-${c.move.id}-${++summonSeq}"
            if (host.spawnSummon(actorId, s.enemy, at)) {
                summons[actorId] = s.enemy
                spawned++
            }
        }
        host.log("boss-summon", "${c.move.id} enemy=${s.enemy} count=$spawned")
        cues(c.move.hitCues, pos)
        later(RESOLVE_PAD_TICKS) { endCast(c) }
    }

    /** 召喚的敵人消失(打散、死亡、生成失敗)。 */
    fun onSummonGone(actorId: String) {
        summons.remove(actorId)
    }

    // 熄燈
    private fun startSnuff(c: Cast, s: MoveShape.Snuff, warn: Long) {
        val pool = gems.litLamps().filter { it != snuffLamp }
        if (pool.isEmpty()) { abortCast(c, "no-lit-lamp"); return }
        val lamp = pool[random.nextInt(pool.size)]
        val at = host.lampPos(lamp) ?: pos
        snuffLamp = lamp
        snuffSaved = false
        playAnim(c.move.animation, false)
        cues(c.move.warnCues, pos)
        snuffLoop = s.flashCues.firstOrNull()?.let { host.loopCue(it, at) }
        s.flashCues.drop(1).forEach { host.cue(it, at) }
        host.log("boss-snuff-warn", lamp)
        later(warn) { snuffResolve(c, s, lamp) }
    }

    /** 目前被點名要熄的場邊燈（還沒被保住）;SessionRun 的閃光判定用。 */
    fun snuffTarget(): String? = if (snuffSaved) null else snuffLamp

    /** 有人對閃爍中的場邊燈閃光:保住。 */
    fun onSnuffFlash(player: UUID) {
        val lamp = snuffLamp ?: return
        val c = cast ?: return
        val shape = c.move.shape as? MoveShape.Snuff ?: return
        if (snuffSaved || !alive(c) || downed.contains(player)) return
        snuffSaved = true
        snuffLoop?.run()
        snuffLoop = null
        host.log("boss-snuff-saved", "$lamp by=${name(player)}")
        host.lampPos(lamp)?.let { cues(shape.savedCues, it) }
        endCast(c)
        snuffLamp = null
    }

    private fun snuffResolve(c: Cast, s: MoveShape.Snuff, lamp: String) {
        if (!alive(c)) return
        snuffLoop?.run()
        snuffLoop = null
        snuffLamp = null
        host.resetLamp(lamp)
        restoreGem(lamp)
        host.log("boss-snuff", lamp)
        cues(s.snuffCues, pos)
        playAnim(spec.animations.snuff, false)
        endCast(c)
    }

    // ---- 倒下、復活、全滅 ----

    fun isDowned(id: UUID): Boolean = downed.contains(id)

    fun downedCount(): Int = downed.size

    /** 致死傷害（或招式的固定傷害致死）：進入倒下。 */
    fun downMember(id: UUID) {
        if (!fightActive || id !in host.members() || !downed.down(id)) return
        host.log("member-downed", name(id))
        val d = spec.downed
        val revivePoints = if (d.move == DownedMove.NEAREST_REVIVE) d.revivePoints.mapNotNull { host.markerPos(it) } else emptyList()
        ledger.endPlayer(id)
        host.applyDowned(id, d, revivePoints)
        positions[id]?.let { cues(d.cues, it) }
        d.markCue?.let { markRuns[id] = host.followCue(it, id, positions[id] ?: pos) }
        d.titleKey?.let { host.titleFor(id, it, d.subtitleKey) }
        d.hudTextKey?.let { host.actionBar(id, it) }
        // 全滅時不必再提示「等她跪下」;否則自己與隊友各一則(隊友 = 在線、沒倒下的其他成員)
        if (checkWipe()) return
        d.selfHintCues.forEach { host.cueFor(it, positions[id] ?: pos, id) }
        for (ally in onlineMembers()) {
            if (ally == id || downed.contains(ally)) continue
            d.allyHintCues.forEach { host.cueFor(it, positions[ally] ?: pos, ally) }
            d.allyHudTextKey?.let { host.actionBar(ally, it) }
        }
    }

    private fun revive(id: UUID, reason: String, withHealth: Boolean) {
        if (!downed.revive(id)) return
        markRuns.remove(id)?.run()
        host.restoreDowned(id, if (withHealth) spec.downed.reviveHealth else null)
        if (reason == "kneel" || reason == "defeat") {
            host.log("member-revived", "${name(id)} reason=$reason")
            cues(spec.downed.reviveCues, positions[id] ?: pos)
        } else {
            host.log("member-restored", "${name(id)} reason=$reason")
        }
    }

    private fun reviveAll(reason: String) {
        for (id in downed.ids()) revive(id, reason, withHealth = true)
    }

    /** 全員倒下就全滅重來;回傳這次是否觸發了全滅。 */
    private fun checkWipe(): Boolean {
        if (!fightActive || state == BossState.WIPED || state == BossState.WAITING) return false
        if (!isWipe(host.members(), host::isOnline, downed::contains)) return false
        wipe()
        return true
    }

    private fun wipe() {
        val w = spec.wipe
        cancelCast("wipe")
        epoch++
        state = BossState.WIPED
        host.log("boss-wipe-restart", "checkpoint=${w.checkpoint}")
        cues(w.cues, pos)
        w.titleKey?.let { host.title(it, w.subtitleKey) }
        // 先還原身體（遊戲模式、可見性…），再傳回檢查點
        for (id in downed.ids()) revive(id, "wipe", withHealth = true)
        host.dissolveSummons(summons.keys.toList())
        summons.clear()
        host.endPowered("boss-wipe")
        for (gem in spec.gems) {
            if (gems.isLit(gem.lamp) || host.lampUsed(gem.lamp)) host.resetLamp(gem.lamp)
            restoreGem(gem.lamp)
        }
        shieldUp = true
        spec.shield.haloBone?.let { host.setBone(it, true) }
        pendingPhase = false
        if (w.health == WipeHealth.FULL) {
            health = BossHealth(scaled(), spec.phases.map { it.at })
            lastHitAt.clear()
        }
        if (w.phase == WipePhase.FIRST) phase = 1
        val first = host.markerPos(spec.points.first()) ?: pos
        pos = first
        host.moveBoss(first)
        targetIdx = if (spec.points.size > 1) 1 else 0
        dwellUntil = 0L
        gliding = false
        resumeAnim()
        lastUsed.clear()
        lastMove = null
        host.markerPos(w.checkpoint)?.let { checkpoint ->
            for (id in host.members()) if (host.isOnline(id)) host.teleportPlayer(id, checkpoint)
        }
        applyPumpkins()
        updateBar()
        restartAt = host.now + ticks(w.restartDelaySeconds)
    }

    private fun restartFight() {
        // 血量沒重置時（health: keep）保留已扣的命中數；階段依策略
        resumeAnim()
        enterPhase(phase)
        state = BossState.IDLE
        nextMoveAt = host.now + ticks(spec.gapSeconds)
    }

    // ---- 成員事件 ----

    /** 斷線事件當下（玩家自己的執行緒）：同步還原身體狀態，玩家資料存檔前不能留著倒下的狀態。 */
    fun onMemberQuitSync(id: UUID) {
        if (downed.contains(id)) host.restoreDownedSync(id)
    }

    /** 斷線之後（anchor 執行緒）：邏輯上仍倒下（重連寬限期內回來還是倒下）；離線的人不算存活，可能因此全滅。 */
    fun onMemberQuit(id: UUID) {
        markRuns.remove(id)?.run()
        ledger.endPlayer(id)
        checkWipe()
    }

    /**
     * 重連（寬限期內）：還在倒下就重新套用倒下狀態並回到復活點；已經在跪下時復活的人（邏輯上不再倒下）
     * 直接回到檢查點。既有的重連傳送會把資料驅動副本的玩家送回入口，所以這裡稍後再傳一次。
     */
    fun onMemberReconnected(id: UUID) {
        if (!fightActive) return
        // 不用帶 epoch 的 later:這一刻剛好跪下／全滅也要把剛重連的人送到對的位置
        host.schedule(RECONNECT_DELAY_TICKS, Runnable {
            if (state == BossState.STOPPED) return@Runnable
            if (downed.contains(id)) {
                val d = spec.downed
                val points = if (d.move == DownedMove.NEAREST_REVIVE) d.revivePoints.mapNotNull { host.markerPos(it) } else emptyList()
                host.applyDowned(id, d, points)
                d.markCue?.let { markRuns.remove(id)?.run(); markRuns[id] = host.followCue(it, id, positions[id] ?: pos) }
                if (points.isEmpty()) host.markerPos(spec.wipe.checkpoint)?.let { host.teleportPlayer(id, it) }
            } else if (fightActive) {
                host.markerPos(spec.wipe.checkpoint)?.let { host.teleportPlayer(id, it) }
            }
        })
    }

    /** 成員離場（kick、離開、grace 逾時）：邏輯與身體都清掉，剩下的人全倒就全滅。 */
    fun onMemberLeft(id: UUID) {
        if (downed.revive(id)) {
            markRuns.remove(id)?.run()
            host.restoreDowned(id, null)
            host.log("member-restored", "${name(id)} reason=left")
        }
        ledger.endPlayer(id)
        lastHitAt.remove(id)
        spinHitAt.remove(id)
        positions.remove(id)
        checkWipe()
    }

    // ---- 打倒、結束 ----

    private fun defeat() {
        cancelCast("defeated")
        epoch++
        state = BossState.DEFEATED
        shieldUp = false
        host.log("boss-defeated", spec.id)
        playAnim(spec.animations.defeat, false)
        cues(spec.defeat.cues, pos)
        reviveAll("defeat")
        host.dissolveSummons(summons.keys.toList())
        summons.clear()
        host.hideBossBar()
        // epoch 已加一，這個計時用新的 epoch 排
        later(ticks(spec.defeat.seconds)) { playAnim(spec.animations.sitEnd, true) }
        host.onBossDefeated()
    }

    /**
     * stage 轉換或局結束:所有倒下的人還原，招式與計時作廢，血量條收掉。[despawn] = stage 轉換時連載體一起移除
     * （局結束由引擎的 actor 清理負責）。
     */
    fun shutdown(reason: String, despawn: Boolean) {
        if (state == BossState.STOPPED) return
        cancelCast("stop")
        epoch++
        val wasDefeated = state == BossState.DEFEATED
        state = BossState.STOPPED
        for (id in downed.clear()) {
            markRuns.remove(id)?.run()
            host.restoreDowned(id, null)
            host.log("member-restored", "${name(id)} reason=$reason")
        }
        markRuns.values.forEach { it.run() }
        markRuns.clear()
        if (summons.isNotEmpty()) host.dissolveSummons(summons.keys.toList())
        summons.clear()
        if (!wasDefeated) host.hideBossBar()
        host.log("boss-stop", "${spec.id} reason=$reason")
        if (despawn) host.despawnBoss()
    }

    // ---- 給 debug 指令 ----

    fun describe(): List<String> {
        val lines = ArrayList<String>()
        lines += "boss ${spec.id}: state=${state.name.lowercase()} phase=$phase 命中=${fmt(health.taken)}/${fmt(health.maxHits)} " +
            "護盾=${if (shieldUp) "亮" else "碎"} 寶石=${gems.litCount}/${gems.total} 倒下=${downed.size} 預警=${ledger.size} 召喚=${summons.size}"
        cast?.let { lines += "  招式 ${it.move.id}(${it.id})${snuffLamp?.let { l -> " 熄燈目標=$l" } ?: ""}" }
        return lines
    }

    companion object {
        const val TICK_PERIOD = 2L
        const val SAMPLE_TICKS = 2L
        const val SPIN_SAMPLE_TICKS = 4L
        const val RESOLVE_PAD_TICKS = 6L
        const val RETRY_TICKS = 20L
        const val WATCHDOG_TICKS = 60L
        const val SHORT_ANIM_TICKS = 12L
        const val POSITION_REFRESH_TICKS = 10L
        const val RECONNECT_DELAY_TICKS = 10L
        const val INTRO_ANIM_DELAY_TICKS = 20L

        /** [com.tinyyana.hanatoki.stage.StageContext.MEMBER_SNAPSHOT_FIELDS] 的欄位。 */
        const val SX = 0
        const val SY = 1
        const val SZ = 2
        const val SGROUND = 7
    }
}
