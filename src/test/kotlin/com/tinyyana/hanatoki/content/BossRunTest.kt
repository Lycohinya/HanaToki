package com.tinyyana.hanatoki.content

import java.util.Random
import java.util.UUID
import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * rc.23 BossRun 的流程測試：用假的 [BossHost]（手動時鐘，快照／計時／身體狀態都可控）跑完整時間軸，
 * 不碰 Bukkit。涵蓋階段、護盾與寶石、跪下計時、招式排程與單人單預警、熄燈、召喚、倒下與復活、全滅重來、
 * 倒下身體狀態在各種離開路徑下的還原不變式、boss 被打倒。
 */
class BossRunTest {

    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val cara = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    private val original = BodyState("SURVIVAL", invisible = false, glowing = false, invulnerable = false, speedModifier = 0.0)

    /** 假的世界：手動時鐘、可控快照、記錄所有對外的呼叫。 */
    private class FakeHost(val players: List<UUID>, names: Map<UUID, String>) : BossHost {
        var clock = 0L
        override val now: Long get() = clock

        private class Timer(val due: Long, val seq: Int, val action: Runnable)

        private val timers = ArrayList<Timer>()
        private var seq = 0
        val logs = ArrayList<String>()
        val memberList = players.toMutableList()
        val online = players.toMutableSet()
        private val nameMap = names
        val markers = HashMap<String, Pt>()
        val cuesPlayed = ArrayList<String>()
        val cuesFor = ArrayList<Pair<String, UUID>>()
        val anims = ArrayList<String>()
        val positions = HashMap<UUID, Pt>()
        val airborne = HashSet<UUID>()
        val bodies = HashMap<UUID, DownedBodyTest>()
        val ledger = DownedBodyLedger()
        val damage = ArrayList<Pair<UUID, Double>>()
        val lethalNext = HashSet<UUID>()
        val litLamps = HashSet<String>()
        val burningLamps = HashSet<String>()
        val resetLamps = ArrayList<String>()
        val teleports = ArrayList<Pair<UUID, Pt>>()
        val spawned = ArrayList<Triple<String, String, Pt>>()
        val dissolved = ArrayList<List<String>>()
        val powered = HashSet<UUID>()
        val endPoweredCalls = ArrayList<String>()
        val pumpkinCalls = ArrayList<Pair<List<String>, Boolean>>()
        val bones = ArrayList<Pair<String, Boolean>>()
        val healths = HashMap<UUID, Double>()
        val revivePointCounts = ArrayList<Int>()
        val barFractions = ArrayList<Double>()
        var barHidden = 0
        var defeatedCalls = 0
        val actionBars = ArrayList<Pair<UUID, String>>()
        val musics = ArrayList<String>()
        var despawned = false
        lateinit var run: BossRun

        init {
            for (p in players) {
                bodies[p] = DownedBodyTest(BodyState("SURVIVAL", false, false, false, 0.0))
                positions[p] = Pt(6.0, 0.0, 0.0)
            }
            val named = mapOf(
                "nun_point_a" to Pt(0.0, 0.0, 0.0), "nun_point_b" to Pt(10.0, 0.0, 0.0), "nun_point_c" to Pt(0.0, 0.0, 10.0),
                "front_door" to Pt(5.0, 0.0, -20.0), "revive_1" to Pt(-12.0, 0.0, 0.0), "revive_2" to Pt(12.0, 0.0, 12.0), "revive_3" to Pt(0.0, 0.0, 14.0),
            )
            markers.putAll(named)
            for (i in 1..3) markers["mist_$i"] = Pt(i * 3.0, 0.0, 8.0)
            for (i in 1..4) markers["doll_corner_$i"] = Pt(i * 2.0, 0.0, -8.0)
            for (i in 1..4) markers["boss_lamp_$i"] = Pt(-4.0 * i, 1.0, 4.0)
        }

        override fun schedule(delayTicks: Long, action: Runnable) { timers += Timer(clock + delayTicks.coerceAtLeast(0), seq++, action) }

        fun advance(ticks: Int) {
            repeat(ticks) {
                clock++
                while (true) {
                    val next = timers.filter { it.due <= clock }.minWithOrNull(compareBy<Timer>({ it.due }, { it.seq })) ?: break
                    timers.remove(next)
                    next.action.run()
                }
                if (clock % 2L == 0L) run.tick()
            }
        }

        /** 一直跑到 [predicate] 成立（最多 [maxTicks]）。回傳是否成立。 */
        fun advanceUntil(maxTicks: Int, predicate: () -> Boolean): Boolean {
            var spent = 0
            while (spent < maxTicks) {
                if (predicate()) return true
                advance(2)
                spent += 2
            }
            return predicate()
        }

        fun logged(prefix: String): List<String> = logs.filter { it.startsWith(prefix) }

        override fun log(event: String, detail: String) { logs += if (detail.isEmpty()) event else "$event $detail" }
        override fun members(): List<UUID> = memberList
        override fun isOnline(id: UUID): Boolean = id in online
        override fun nameOf(id: UUID): String = nameMap.getValue(id)
        override fun markerPos(id: String): Pt? = markers[id]
        override fun cue(cueId: String, at: Pt) { cuesPlayed += cueId }
        override fun cueFor(cueId: String, at: Pt, player: UUID) { cuesFor += cueId to player }
        override fun loopCue(cueId: String, at: Pt): Runnable { cuesPlayed += "loop:$cueId"; return Runnable { cuesPlayed += "cancel:$cueId" } }
        override fun followCue(cueId: String, player: UUID, at: Pt): Runnable { cuesPlayed += "follow:$cueId"; return Runnable { cuesPlayed += "cancel:$cueId" } }
        override fun music(cueId: String) { musics += cueId }
        override fun title(titleKey: String, subtitleKey: String?) {}
        override fun titleFor(player: UUID, titleKey: String, subtitleKey: String?) {}
        override fun actionBar(player: UUID, key: String) { actionBars += player to key }
        override fun bossBar(textKey: String, fraction: Double, color: String, overlay: String, params: Map<String, String>) { barFractions += fraction }
        override fun hideBossBar() { barHidden++ }
        override fun spawnBoss(at: Pt) {}
        override fun moveBoss(to: Pt) {}
        override fun faceBoss(toward: Pt) {}
        override fun playAnimation(name: String, loop: Boolean) { anims += name }
        override fun setBone(bone: String, visible: Boolean) { bones += bone to visible }
        override fun despawnBoss() { despawned = true }

        override fun snapshots(callback: Consumer<Map<UUID, DoubleArray>>) {
            val out = LinkedHashMap<UUID, DoubleArray>()
            for (id in memberList) {
                if (id !in online) continue
                val p = positions.getValue(id)
                out[id] = doubleArrayOf(p.x, p.y, p.z, 0.0, 0.0, 20.0, 20.0, if (id in airborne) 0.0 else 1.0, 0.0, 0.0)
            }
            callback.accept(out)
        }

        override fun damageFixed(id: UUID, fraction: Double, push: HarassEffect.Push?, from: Pt?, onLethal: Runnable) {
            damage += id to fraction
            if (lethalNext.remove(id)) onLethal.run()
        }

        override fun applyDowned(id: UUID, spec: DownedSpec, revivePoints: List<Pt>) {
            ledger.apply(id, bodies.getValue(id), spec.bodyState())
            revivePointCounts += revivePoints.size
        }

        override fun restoreDowned(id: UUID, health: Double?) {
            if (ledger.restore(id, bodies.getValue(id)) && health != null) healths[id] = health
        }

        override fun restoreDownedSync(id: UUID) { ledger.restore(id, bodies.getValue(id)) }
        override fun teleportPlayer(id: UUID, to: Pt) { teleports += id to to }
        override fun lampUsed(lamp: String): Boolean = lamp in litLamps || lamp in burningLamps
        override fun lampPos(lamp: String): Pt? = markers[lamp]
        override fun resetLamp(lamp: String) { litLamps.remove(lamp); burningLamps.remove(lamp); resetLamps += lamp }
        override fun setPumpkinsAvailable(objects: List<String>, available: Boolean) { pumpkinCalls += objects to available }
        override fun isPowered(id: UUID): Boolean = id in powered
        override fun endPowered(reason: String) { endPoweredCalls += reason }
        override fun spawnSummon(actorId: String, enemyId: String, at: Pt): Boolean { spawned += Triple(actorId, enemyId, at); return true }
        override fun dissolveSummons(actorIds: Collection<String>) { dissolved += actorIds.toList() }
        override fun onBossDefeated() { defeatedCalls++ }
    }

    private class DownedBodyTest(var state: BodyState, var markerState: BodyState? = null) : BodyPort {
        var applies = 0
        override fun read() = state
        override fun apply(state: BodyState) { this.state = state; applies++ }
        override fun marker() = markerState
        override fun setMarker(state: BodyState?) { markerState = state }
    }

    private val baseSpec: BossSpec by lazy { BossFixtures.parse().bosses.getValue("nun") }

    private fun names() = mapOf(alice to "alice", bob to "bob", cara to "cara")

    private fun setup(
        players: List<UUID> = listOf(alice),
        spec: BossSpec = baseSpec,
        seed: Long = 7,
    ): Pair<FakeHost, BossRun> {
        val host = FakeHost(players, names())
        val run = BossRun(host, spec, Random(seed))
        host.run = run
        return host to run
    }

    /** 出場並等到開打（start-delay 3 秒）。 */
    private fun begin(host: FakeHost, run: BossRun) {
        run.start()
        host.advance(62)
        assertEquals(BossState.IDLE, run.state)
    }

    private fun lightAll(host: FakeHost, run: BossRun) {
        for (i in 1..4) {
            host.litLamps += "boss_lamp_$i"
            run.onLampLit("boss_lamp_$i")
        }
    }

    /** 點亮四盞燈 → 跪下，回傳 kneel 後的 host（已在 KNEELING）。 */
    private fun kneel(host: FakeHost, run: BossRun) {
        lightAll(host, run)
        assertEquals(BossState.KNEELING, run.state)
    }

    private fun only(spec: BossSpec, vararg ids: String, phase1: Boolean = true): BossSpec =
        spec.copy(moves = ids.associateWith { id -> spec.moves.getValue(id).let { if (phase1) it.copy(fromPhase = 1) else it } }, points = listOf("nun_point_a"))

    private fun hits(run: BossRun, who: UUID, n: Int) = repeat(n) { run.onHit(who, Pt(0.0, 0.0, 0.0)) }

    private fun taken(run: BossRun): String = Regex("命中=([0-9.]+)/").find(run.describe().first())!!.groupValues[1]

    // ---- 開場、階段 ----

    @Test
    fun `出場後等 start-delay 才開打，開打才有 boss-phase 1`() {
        val (host, run) = setup()
        run.start()
        assertEquals(BossState.WAITING, run.state)
        host.advance(40)
        assertTrue(host.logged("boss-phase").isEmpty())
        host.advance(40)
        assertEquals(listOf("boss-phase 1"), host.logged("boss-phase"))
        assertEquals(BossState.IDLE, run.state)
        assertTrue("boss.p1" in host.musics, "階段的 on-enter 音樂")
        assertEquals(listOf("boss-start nun hits=40 players=1"), host.logged("boss-start"))
        assertEquals(1.0, host.barFractions.last())
        // 南瓜燈在第三階段前是下架的
        assertEquals(listOf(listOf("pumpkin_boss_1", "pumpkin_boss_2") to false), host.pumpkinCalls)
    }

    @Test
    fun `人數倍率決定總命中數`() {
        val (host, run) = setup(players = listOf(alice, bob, cara))
        run.start()
        assertEquals("boss-start nun hits=88 players=3", host.logged("boss-start").single())
    }

    @Test
    fun `護盾亮著近戰打不動並濺火花；powered 的人穿過護盾算 powered-hit-weight`() {
        val spec = baseSpec.copy(poweredHitWeight = 2.0)
        val (host, run) = setup(players = listOf(alice, bob), spec = spec)
        begin(host, run)
        run.onHit(alice, Pt(1.0, 1.0, 1.0))
        assertEquals("0", taken(run))
        assertTrue("shield_spark" in host.cuesPlayed)
        host.powered += bob
        run.onHit(bob, Pt(1.0, 1.0, 1.0))
        assertEquals("2", taken(run))
        assertEquals(1.0 - 2.0 / 64.0, host.barFractions.last(), 1.0E-9) // 2 人：40 × 1.6 = 64 命中
    }

    @Test
    fun `火花有冷卻，不會每一下都播`() {
        val (host, run) = setup()
        begin(host, run)
        val spec = baseSpec.shield.sparkCooldownTicks
        assertTrue(spec > 0)
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        assertEquals(1, host.cuesPlayed.count { it == "shield_spark" })
    }

    @Test
    fun `玩家的有效近戰有個別冷卻`() {
        val spec = baseSpec.copy(hitCooldownTicks = 12)
        val (host, run) = setup(spec = spec)
        begin(host, run)
        kneel(host, run)
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        assertEquals("1", taken(run), "同一 tick 的第二下被冷卻擋掉")
        host.advance(12)
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        assertEquals("2", taken(run))
    }

    // ---- 護盾、寶石、跪下 ----

    @Test
    fun `四盞場邊燈全亮護盾才碎，跪下 10 秒後起身，護盾恢復且燈重置`() {
        val (host, run) = setup()
        begin(host, run)
        for (i in 1..3) { host.litLamps += "boss_lamp_$i"; run.onLampLit("boss_lamp_$i") }
        assertNotEquals(BossState.KNEELING, run.state)
        assertTrue(host.logged("boss-shield-broken").isEmpty())
        assertTrue("beam" in host.cuesPlayed, "每點亮一盞射光束")
        assertTrue("gem_1" to false in host.bones)
        host.litLamps += "boss_lamp_4"
        run.onLampLit("boss_lamp_4")
        assertEquals(BossState.KNEELING, run.state)
        assertEquals(1, host.logged("boss-shield-broken").size)
        assertEquals(listOf("boss-kneel seconds=10"), host.logged("boss-kneel"))
        assertTrue("halo" to false in host.bones, "光環隱藏")
        assertTrue("kneel_cue" in host.cuesPlayed)
        // 跪下時任何人都能輸出
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        assertEquals("1", taken(run))
        // 10 秒內還在跪
        host.advance(190)
        assertEquals(BossState.KNEELING, run.state)
        host.advance(20)
        assertEquals(BossState.RISING, run.state)
        assertEquals(listOf("boss_lamp_1", "boss_lamp_2", "boss_lamp_3", "boss_lamp_4"), host.resetLamps, "起身後場邊燈重置")
        assertTrue("halo" to true in host.bones, "光環恢復")
        assertTrue("gem_3" to true in host.bones, "寶石恢復")
        // 護盾恢復：近戰又打不動
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        assertEquals("1", taken(run))
        host.advance(40)
        assertEquals(BossState.IDLE, run.state)
        assertTrue(run.describe().first().contains("寶石=0/4"))
    }

    @Test
    fun `lamps-reset never：起身後燈不重置`() {
        val spec = baseSpec.copy(kneel = baseSpec.kneel.copy(lampsReset = LampReset.NEVER))
        val (host, run) = setup(spec = spec)
        begin(host, run)
        kneel(host, run)
        host.advance(210)
        assertTrue(host.resetLamps.isEmpty())
    }

    @Test
    fun `跪下期間跨過階段門檻，起身後才換階段；三階段依序進入並上架南瓜燈`() {
        val (host, run) = setup()
        begin(host, run)
        kneel(host, run)
        hits(run, alice, 18) // 剩 22/40 = 0.55
        assertEquals(listOf("boss-phase 1"), host.logged("boss-phase"), "跪下時不換階段")
        host.advance(205)
        assertEquals(listOf("boss-phase 1", "boss-phase 2"), host.logged("boss-phase"))
        assertTrue("phase_2" in host.cuesPlayed)
        host.advance(40)
        assertEquals(BossState.IDLE, run.state)
        // 第二次跪下：再打 12 下進第三階段
        kneel(host, run)
        hits(run, alice, 12)
        host.advance(205)
        assertEquals(listOf("boss-phase 1", "boss-phase 2", "boss-phase 3"), host.logged("boss-phase"))
        assertEquals(listOf("pumpkin_boss_1", "pumpkin_boss_2") to true, host.pumpkinCalls.last(), "第三階段南瓜燈上架")
        assertEquals(2, host.pumpkinCalls.size)
    }

    @Test
    fun `powered 穿過護盾把血打到門檻：不跪下也會立刻換階段`() {
        val (host, run) = setup()
        begin(host, run)
        host.powered += alice
        hits(run, alice, 18)
        assertEquals(listOf("boss-phase 1", "boss-phase 2"), host.logged("boss-phase"))
        assertEquals(BossState.PHASE_SHIFT, run.state)
        assertTrue("phase_up" in host.anims)
    }

    // ---- 招式排程 ----

    @Test
    fun `第一階段不會抽到第二、三階段的招式`() {
        val (host, run) = setup(players = listOf(alice), spec = baseSpec.copy(gapSeconds = 0.5))
        begin(host, run)
        host.advance(6000)
        val ended = host.logged("boss-move-end").map { it.removePrefix("boss-move-end ") }.toSet()
        assertTrue(ended.isNotEmpty())
        assertTrue(ended.none { it == "cross" || it == "dolls" || it == "spin" }, ended.toString())
    }

    @Test
    fun `地面預警：同一位玩家同時最多一個、一次只有一招在進行`() {
        val spec = only(baseSpec, "sweep", "strike").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice, bob, cara), spec = spec)
        begin(host, run)
        host.advance(4000)
        val active = HashSet<String>()
        var telegraphs = 0
        var overlap = false
        for (line in host.logs) {
            when {
                line.startsWith("boss-telegraph ") -> {
                    val target = line.substringAfter("target=")
                    if (!active.add(target)) overlap = true
                    telegraphs++
                }
                line.startsWith("boss-move-end") || line.startsWith("boss-move-abort") || line.startsWith("boss-move-cancel") -> active.clear()
            }
        }
        assertTrue(telegraphs >= 6, "telegraphs=$telegraphs")
        assertFalse(overlap, "同一位玩家在預警結束前又被排了一個預警")
    }

    @Test
    fun `落雷：圈內的人受傷、離開圈的人沒事，傷害是固定比例`() {
        val spec = only(baseSpec, "strike").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice, bob), spec = spec)
        host.positions[alice] = Pt(0.0, 0.0, 6.0)
        host.positions[bob] = Pt(20.0, 0.0, 6.0)
        begin(host, run)
        assertTrue(host.advanceUntil(600) { host.logs.any { it == "boss-telegraph strike target=bob" } })
        host.positions[bob] = Pt(50.0, 0.0, 50.0) // 預警期間走出圈
        assertTrue(host.advanceUntil(300) { host.logged("boss-move-end").isNotEmpty() })
        assertEquals(listOf(alice to 0.3), host.damage.take(1).map { it.first to it.second })
        assertTrue(host.damage.none { it.first == bob })
        assertEquals(listOf("boss-hit strike player=alice"), host.logged("boss-hit"))
    }

    @Test
    fun `落雷：人數倍率調整招式傷害`() {
        val spec = only(baseSpec, "strike").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice, bob, cara), spec = spec)
        begin(host, run)
        assertTrue(host.advanceUntil(600) { host.damage.isNotEmpty() })
        assertEquals(0.3 * 0.8, host.damage.first().second, 1.0E-9)
    }

    @Test
    fun `掃地：地上的人被掃到，跳起來的躲過並收到 dodge cue`() {
        val spec = only(baseSpec, "sweep").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice, bob), spec = spec)
        host.positions[alice] = Pt(4.0, 0.0, 0.0)
        host.positions[bob] = Pt(0.0, 0.0, 4.0)
        host.airborne += bob
        begin(host, run)
        assertTrue(host.advanceUntil(900) { host.logged("boss-move-end").isNotEmpty() })
        assertEquals(listOf(alice), host.damage.map { it.first })
        assertEquals(0.25, host.damage.single().second)
        assertEquals(listOf("boss-dodge sweep player=bob"), host.logged("boss-dodge"))
        assertEquals(listOf("dodge" to bob), host.cuesFor)
        assertEquals(setOf("boss-telegraph sweep target=alice", "boss-telegraph sweep target=bob"), host.logged("boss-telegraph").toSet())
    }

    @Test
    fun `十字：線上的人受傷，楔形空格裡安全`() {
        val spec = only(baseSpec, "cross").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice, bob), spec = spec)
        host.positions[alice] = Pt(5.0, 0.0, 5.0)
        host.positions[bob] = Pt(5.0, 0.0, 0.0)
        begin(host, run)
        assertTrue(host.advanceUntil(900) { host.logged("boss-move-end").isNotEmpty() })
        assertEquals(listOf(alice), host.damage.map { it.first })
    }

    @Test
    fun `迴旋：缺口轉走後站著不動的人會被光束打到，打完才接下一招`() {
        val spec = only(baseSpec, "spin").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice), spec = spec)
        host.positions[alice] = Pt(6.0, 0.0, 0.0)
        begin(host, run)
        assertTrue(host.advanceUntil(900) { host.logged("boss-telegraph").isNotEmpty() })
        assertTrue(host.advanceUntil(1200) { host.logged("boss-move-end").isNotEmpty() })
        assertTrue(host.damage.size >= 2, "站著不動缺口轉走後被打到：${host.damage.size}")
        assertTrue(host.logged("boss-move-end").single().endsWith("spin"))
    }

    @Test
    fun `迴旋：一直待在缺口裡的人沒事`() {
        val spec = only(baseSpec, "spin").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice), spec = spec)
        host.positions[alice] = Pt(6.0, 0.0, 0.0)
        begin(host, run)
        assertTrue(host.advanceUntil(900) { host.logged("boss-telegraph").isNotEmpty() })
        // 預警 2 秒（40 tick）後缺口才開始轉，每秒轉 40 度；玩家那之後跟著繞圈（半徑 6）。每 2 tick 更新一次位置
        val spinStart = host.clock + 40
        while (host.logged("boss-move-end").isEmpty() && host.clock - spinStart < 1400) {
            val moved = 40.0 * (host.clock - spinStart).coerceAtLeast(0) / 20.0
            val a = Math.toRadians(moved)
            host.positions[alice] = Pt(6.0 * Math.cos(a), 0.0, 6.0 * Math.sin(a))
            host.advance(2)
        }
        assertTrue(host.logged("boss-move-end").isNotEmpty())
        // 缺口的起始角度是預警開始時離她最近的人的角度；取樣與位置更新之間最多差 2 tick（4 度），遠小於缺口半寬 40 度
        assertTrue(host.damage.size <= 1, "跟著缺口跑不該被連續打到：${host.damage.size}")
    }

    @Test
    fun `喚敵：場上同種敵人達上限就不再喚，有人消失才補`() {
        val spec = only(baseSpec, "summon").copy(gapSeconds = 0.5)
        val (host, run) = setup(spec = spec)
        begin(host, run)
        assertTrue(host.advanceUntil(600) { host.spawned.size >= 3 })
        host.advance(900)
        assertEquals(3, host.spawned.size, "上限 3，不再喚")
        assertEquals(setOf("mist_1", "mist_2", "mist_3").map { host.markers.getValue(it) }.toSet(), host.spawned.map { it.third }.toSet())
        run.onSummonGone(host.spawned.first().first)
        assertTrue(host.advanceUntil(600) { host.spawned.size >= 4 })
        assertEquals(4, host.spawned.size)
    }

    // ---- 熄燈 ----

    private fun snuffSetup(): Pair<FakeHost, BossRun> {
        val base = only(baseSpec, "snuff").copy(gapSeconds = 0.5)
        // 冷卻拉長，一局測試內只熄一次
        val spec = base.copy(moves = base.moves.mapValues { it.value.copy(cooldownSeconds = 300.0) })
        val (host, run) = setup(players = listOf(alice, bob), spec = spec)
        begin(host, run)
        for (i in 1..2) { host.litLamps += "boss_lamp_$i"; run.onLampLit("boss_lamp_$i") }
        assertTrue(host.advanceUntil(900) { host.logged("boss-snuff-warn").isNotEmpty() })
        return host to run
    }

    @Test
    fun `熄燈：閃爍期間對那盞燈閃光就保住，燈不熄、寶石不恢復`() {
        val (host, run) = snuffSetup()
        val target = run.snuffTarget()
        assertTrue(target == "boss_lamp_1" || target == "boss_lamp_2")
        host.advance(20)
        run.onSnuffFlash(bob)
        assertEquals(listOf("boss-snuff-saved $target by=bob"), host.logged("boss-snuff-saved"))
        assertNull(run.snuffTarget())
        host.advance(200)
        assertTrue(host.resetLamps.isEmpty(), "保住了就不熄")
        assertTrue(host.logged("boss-snuff ").isEmpty())
        assertTrue(run.describe().first().contains("寶石=2/4"))
        assertTrue(host.logged("boss-move-end").isNotEmpty(), "保住後這招提早結束")
    }

    @Test
    fun `熄燈：沒保住時 3 秒後燈熄、她得意小哼、寶石恢復`() {
        val (host, run) = snuffSetup()
        val target = run.snuffTarget()!!
        host.advance(55)
        assertTrue(host.resetLamps.isEmpty())
        host.advance(10)
        assertEquals(listOf(target), host.resetLamps)
        assertEquals(listOf("boss-snuff $target"), host.logged("boss-snuff "))
        assertTrue("nun_hum" in host.cuesPlayed)
        assertTrue(run.describe().first().contains("寶石=1/4"))
        assertNull(run.snuffTarget())
    }

    @Test
    fun `熄燈需要至少一盞亮著的場邊燈，沒有就不會抽到`() {
        val spec = only(baseSpec, "snuff", "strike").copy(gapSeconds = 0.5)
        val (host, run) = setup(spec = spec)
        begin(host, run)
        host.advance(3000)
        assertTrue(host.logged("boss-snuff-warn").isEmpty())
    }

    @Test
    fun `熄燈閃爍中四盞燈都亮了：護盾碎，熄燈作廢`() {
        val spec = only(baseSpec, "snuff").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice, bob), spec = spec)
        begin(host, run)
        for (i in 1..3) { host.litLamps += "boss_lamp_$i"; run.onLampLit("boss_lamp_$i") }
        assertTrue(host.advanceUntil(900) { host.logged("boss-snuff-warn").isNotEmpty() })
        host.litLamps += "boss_lamp_4"
        run.onLampLit("boss_lamp_4")
        assertEquals(BossState.KNEELING, run.state)
        assertNull(run.snuffTarget(), "跪下時熄燈作廢")
        host.advance(100)
        assertTrue(host.logged("boss-snuff ").isEmpty())
        assertEquals(1, host.logged("boss-move-cancel").size)
    }

    // ---- 倒下與復活 ----

    @Test
    fun `致死傷害：進入倒下，身體狀態改變，移到復活點，不能再打`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        run.downMember(alice)
        assertTrue(run.isDowned(alice))
        assertEquals(listOf("member-downed alice"), host.logged("member-downed"))
        val body = host.bodies.getValue(alice)
        assertEquals(BodyState("ADVENTURE", invisible = false, glowing = false, invulnerable = true, speedModifier = -0.8), body.state)
        assertEquals(original, body.markerState)
        assertEquals(listOf(3), host.revivePointCounts, "傳入三個復活點讓實作挑最近的")
        assertTrue(host.actionBars.any { it.first == alice })
    }

    @Test
    fun `倒下的人打不動，隊友照常輸出`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        kneel(host, run)
        run.downMember(alice) // 跪下期間倒下
        val before = taken(run)
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        assertEquals(before, taken(run))
        run.onHit(bob, Pt(0.0, 0.0, 0.0))
        assertNotEquals(before, taken(run))
    }

    @Test
    fun `她跪下時所有倒下的人復活，身體整組還原、血量設成 revive-health`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        run.downMember(alice)
        kneel(host, run)
        assertFalse(run.isDowned(alice))
        assertEquals(original, host.bodies.getValue(alice).state)
        assertNull(host.bodies.getValue(alice).markerState)
        assertEquals(10.0, host.healths.getValue(alice))
        assertEquals(listOf("member-revived alice reason=kneel"), host.logged("member-revived"))
        assertFalse(host.ledger.isApplied(alice))
    }

    @Test
    fun `倒下的人可以在復活後再次倒下`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        run.downMember(alice)
        kneel(host, run)
        host.advance(260)
        run.downMember(alice)
        assertTrue(run.isDowned(alice))
        assertEquals("ADVENTURE", host.bodies.getValue(alice).state.gameMode)
    }

    @Test
    fun `全員倒下 = 全滅：回檢查點、boss 回滿血 P1、燈重置、召喚清掉；前面的燈不動`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        // 打一些：兩盞燈亮著、powered、有召喚
        for (i in 1..2) { host.litLamps += "boss_lamp_$i"; run.onLampLit("boss_lamp_$i") }
        host.powered += alice
        hits(run, alice, 29) // 2 人共 64 命中；剩 35/64 = 0.547 進第二階段
        assertEquals(2, host.logged("boss-phase").size)
        run.downMember(alice)
        assertTrue(host.logged("boss-wipe-restart").isEmpty(), "bob 還站著")
        run.downMember(bob)
        assertEquals(listOf("boss-wipe-restart checkpoint=front_door"), host.logged("boss-wipe-restart"))
        assertEquals(BossState.WIPED, run.state)
        // 身體還原、傳回檢查點
        assertEquals(original, host.bodies.getValue(alice).state)
        assertEquals(original, host.bodies.getValue(bob).state)
        val door = host.markers.getValue("front_door")
        assertEquals(setOf(alice to door, bob to door), host.teleports.toSet())
        // boss 重置
        assertTrue(run.describe().first().contains("命中=0/"), run.describe().first())
        assertEquals(setOf("boss_lamp_1", "boss_lamp_2"), host.resetLamps.toSet())
        assertEquals(listOf("boss-wipe"), host.endPoweredCalls)
        assertEquals(1, host.logs.count { it == "member-restored alice reason=wipe" })
        // 重來的延遲（5 秒）過後重新進入第一階段
        host.advance(90)
        assertEquals(BossState.WIPED, run.state)
        host.advance(20)
        assertEquals(BossState.IDLE, run.state)
        assertEquals("boss-phase 1", host.logged("boss-phase").last())
        // 第一階段門檻：血量回滿，所以南瓜燈再次下架
        assertEquals(listOf("pumpkin_boss_1", "pumpkin_boss_2") to false, host.pumpkinCalls.last())
    }

    @Test
    fun `全滅時燒到一半的場邊燈也要重置`() {
        val (host, run) = setup(players = listOf(alice))
        begin(host, run)
        host.burningLamps += "boss_lamp_3" // 燒到一半
        host.litLamps += "boss_lamp_1"
        run.onLampLit("boss_lamp_1")
        run.downMember(alice)
        assertEquals(setOf("boss_lamp_1", "boss_lamp_3"), host.resetLamps.toSet())
    }

    @Test
    fun `起身時跪下期間才點燃、燒到一半的場邊燈也要重置`() {
        val (host, run) = setup(players = listOf(alice))
        begin(host, run)
        kneel(host, run)
        host.resetLamps.clear()
        host.burningLamps += "boss_lamp_2" // 跪下期間有人又點燈，燒到一半
        host.advance(210)
        assertEquals(BossState.RISING, run.state)
        assertTrue("boss_lamp_2" in host.resetLamps, host.resetLamps.toString())
    }

    @Test
    fun `全滅時同時有召喚的敵人：一起打散`() {
        val spec = only(baseSpec, "summon").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice), spec = spec)
        begin(host, run)
        assertTrue(host.advanceUntil(600) { host.spawned.size >= 3 })
        val ids = host.spawned.map { it.first }
        run.downMember(alice)
        assertEquals(ids, host.dissolved.last())
    }

    @Test
    fun `全滅策略 keep：血量與階段保留`() {
        val spec = baseSpec.copy(wipe = baseSpec.wipe.copy(health = WipeHealth.KEEP, phase = WipePhase.KEEP))
        val (host, run) = setup(players = listOf(alice), spec = spec)
        begin(host, run)
        host.powered += alice
        hits(run, alice, 18)
        run.downMember(alice)
        assertEquals(BossState.WIPED, run.state)
        assertTrue(run.describe().first().contains("phase=2"))
        assertTrue(run.describe().first().contains("命中=18/"))
        host.advance(120)
        assertEquals("boss-phase 2", host.logged("boss-phase").last())
    }

    @Test
    fun `離線的人不算存活也不擋全滅`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        host.online.remove(bob) // 斷線寬限期內
        run.downMember(alice)
        assertEquals(1, host.logged("boss-wipe-restart").size)
    }

    @Test
    fun `一個人倒下、另一個人離場：剩下的人全倒就全滅`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        run.downMember(alice)
        host.memberList.remove(bob)
        run.onMemberLeft(bob)
        assertEquals(1, host.logged("boss-wipe-restart").size)
    }

    // ---- 倒下身體狀態的還原不變式 ----

    /** 每條離開路徑都要把 gamemode／可見性／發光／無敵／速度整組還原成倒下前的值，而且只還原一次。 */
    private fun assertRestoredOnce(host: FakeHost, id: UUID) {
        val body = host.bodies.getValue(id)
        assertEquals(original, body.state, "身體狀態沒有還原成原本的值")
        assertNull(body.markerState, "記號沒有清掉")
        assertFalse(host.ledger.isApplied(id))
        assertEquals(2, body.applies, "應該只有套用一次、還原一次")
    }

    private fun downed(players: List<UUID> = listOf(alice, bob)): Triple<FakeHost, BossRun, DownedBodyTest> {
        val (host, run) = setup(players = players)
        begin(host, run)
        run.downMember(alice)
        val body = host.bodies.getValue(alice)
        assertNotEquals(original, body.state)
        return Triple(host, run, body)
    }

    @Test
    fun `不變式：倒下時斷線，在事件執行緒同步還原；重連寬限期內回來仍是倒下並重新套用`() {
        val (host, run, body) = downed()
        host.online.remove(alice)
        run.onMemberQuitSync(alice) // 玩家自己的執行緒上、資料存檔前
        assertEquals(original, body.state, "斷線事件當下就還原，玩家資料不會帶著倒下狀態存檔")
        assertNull(body.markerState)
        run.onMemberQuit(alice)
        assertTrue(run.isDowned(alice), "邏輯上仍倒下")
        assertEquals(2, body.applies)
        // 重連
        host.online += alice
        run.onMemberReconnected(alice)
        host.advance(BossRun.RECONNECT_DELAY_TICKS.toInt() + 2)
        assertEquals("ADVENTURE", body.state.gameMode, "重新套用倒下狀態")
        assertEquals(original, body.markerState, "原本狀態仍是最初那一份")
        // 之後跪下復活，整組還原
        kneel(host, run)
        assertEquals(original, body.state)
        assertNull(body.markerState)
        assertEquals(4, body.applies)
    }

    @Test
    fun `不變式：斷線期間她跪下，回來的人是活的、直接回檢查點，身體不再動`() {
        val (host, run, body) = downed()
        host.online.remove(alice)
        run.onMemberQuitSync(alice)
        run.onMemberQuit(alice)
        kneel(host, run) // 邏輯復活（人不在，身體早已還原）
        assertFalse(run.isDowned(alice))
        host.online += alice
        run.onMemberReconnected(alice)
        host.advance(BossRun.RECONNECT_DELAY_TICKS.toInt() + 2)
        assertEquals(original, body.state)
        assertEquals(2, body.applies, "沒有再套用倒下狀態")
        assertEquals(listOf(alice to host.markers.getValue("front_door")), host.teleports)
    }

    @Test
    fun `不變式：倒下時離場（kick、離開、grace 逾時）`() {
        val (host, run, _) = downed()
        host.memberList.remove(alice)
        run.onMemberLeft(alice)
        assertRestoredOnce(host, alice)
        assertEquals(listOf("member-restored alice reason=left"), host.logged("member-restored"))
    }

    @Test
    fun `不變式：倒下時局結束（session-end 不依賴成員名單）`() {
        val (host, run, _) = downed()
        host.memberList.clear() // 局結束時名單可能已經清空
        run.shutdown("session-end", despawn = false)
        assertRestoredOnce(host, alice)
        assertFalse(host.despawned)
        assertEquals(1, host.barHidden)
        assertEquals(BossState.STOPPED, run.state)
        // 第二次關閉不會再動玩家
        run.shutdown("session-end", despawn = false)
        assertEquals(2, host.bodies.getValue(alice).applies)
    }

    @Test
    fun `不變式：倒下時 stage 轉換（載體一併移除、召喚打散）`() {
        val spec = only(baseSpec, "summon").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice, bob), spec = spec)
        begin(host, run)
        assertTrue(host.advanceUntil(600) { host.spawned.size >= 3 })
        run.downMember(alice)
        run.shutdown("stage", despawn = true)
        assertRestoredOnce(host, alice)
        assertTrue(host.despawned)
        assertEquals(host.spawned.map { it.first }, host.dissolved.last())
    }

    @Test
    fun `不變式：倒下時全滅重來`() {
        val (host, run, _) = downed()
        run.downMember(bob)
        assertEquals(1, host.logged("boss-wipe-restart").size)
        assertRestoredOnce(host, alice)
        assertRestoredOnce(host, bob)
    }

    @Test
    fun `不變式：倒下時 boss 被打倒，所有人復活`() {
        val (host, run, body) = downed()
        kneel(host, run)
        // 跪下時已復活一次；再讓她倒下一次後打倒 boss
        run.downMember(alice)
        assertEquals("ADVENTURE", body.state.gameMode)
        hits(run, bob, 64) // 2 人：40 × 1.6
        assertEquals(BossState.DEFEATED, run.state)
        assertEquals(original, body.state)
        assertNull(body.markerState)
        assertEquals(1, host.logs.count { it == "member-revived alice reason=defeat" })
    }

    @Test
    fun `不變式：倒下後 boss 戰沒有進行時不再接受新的倒下`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        run.shutdown("stage", despawn = true)
        run.downMember(alice)
        assertEquals(original, host.bodies.getValue(alice).state)
        assertFalse(run.isDowned(alice))
    }

    @Test
    fun `重連時仍倒下的人回到復活點（傳入復活點交給實作挑最近的）`() {
        val (host, run, _) = downed()
        host.online.remove(alice)
        run.onMemberQuitSync(alice)
        run.onMemberQuit(alice)
        host.online += alice
        run.onMemberReconnected(alice)
        host.advance(20)
        assertEquals(listOf(3, 3), host.revivePointCounts)
    }

    // ---- 打倒 ----

    @Test
    fun `血量歸零：boss-defeated、通知 stage、血量條收掉、不再接受任何事`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        kneel(host, run)
        hits(run, alice, 64) // 2 人：40 × 1.6
        assertEquals(BossState.DEFEATED, run.state)
        assertEquals(listOf("boss-defeated nun"), host.logged("boss-defeated"))
        assertEquals(1, host.defeatedCalls)
        assertTrue(run.defeated)
        assertFalse(run.fightActive)
        assertEquals(1, host.barHidden)
        assertTrue("defeat" in host.anims)
        host.advance(100)
        assertTrue("sit_end" in host.anims, "倒下動畫後接坐下收尾")
        // 打倒之後的任何打擊都無效
        run.onHit(alice, Pt(0.0, 0.0, 0.0))
        assertEquals(1, host.defeatedCalls)
        assertEquals(1, host.logged("boss-defeated").size)
    }

    @Test
    fun `打倒時進行中的招式作廢，不再有預警與傷害`() {
        val spec = only(baseSpec, "strike").copy(gapSeconds = 0.5)
        val (host, run) = setup(players = listOf(alice), spec = spec)
        begin(host, run)
        assertTrue(host.advanceUntil(600) { host.logged("boss-telegraph").isNotEmpty() })
        host.powered += alice
        hits(run, alice, 40)
        assertEquals(BossState.DEFEATED, run.state)
        host.advance(400)
        assertTrue(host.damage.isEmpty(), "打倒後預警作廢")
    }

    @Test
    fun `describe 列出狀態，給 admin entities 看`() {
        val (host, run) = setup(players = listOf(alice, bob))
        begin(host, run)
        run.downMember(alice)
        val line = run.describe().first()
        assertTrue(line.startsWith("boss nun: state="), line)
        assertTrue("倒下=1" in line, line)
        assertEquals(1, run.downedCount())
    }
}
