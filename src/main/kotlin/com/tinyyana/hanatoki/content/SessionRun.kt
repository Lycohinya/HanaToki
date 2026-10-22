package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.api.port.CuePort
import com.tinyyana.hanatoki.api.port.CueRun
import com.tinyyana.hanatoki.api.port.DialogPort
import com.tinyyana.hanatoki.api.port.StageWorldMutator
import com.tinyyana.hanatoki.actor.ActorSpec
import com.tinyyana.hanatoki.map.MapAssetId
import com.tinyyana.hanatoki.map.MapLayout
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MapPos
import com.tinyyana.hanatoki.map.MarkerKind
import com.tinyyana.hanatoki.map.PlacedMap
import com.tinyyana.hanatoki.map.PlacedPiece
import com.tinyyana.hanatoki.presentation.BossModelHandle
import com.tinyyana.hanatoki.stage.StageContext
import com.destroystokyo.paper.entity.ai.GoalType
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.structure.StructureRotation
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.function.IntConsumer

/**
 * 一局的資料驅動內容執行期。**所有可變狀態只在這個 session 的 anchor region 執行緒上讀寫**
 * (外部事件一律先 `ctx.submit`),唯二的例外是給事件執行緒同步判斷用的 volatile:
 * [EnemyMachine.state] 與 [EnemyRt.model]。
 *
 * 世界與實體操作一律經 [StageContext]/ActorHandle(它們各自派工到擁有者 region/EntityScheduler),
 * callback 內不直接 setBlock/spawnEntity。計時走自己的 [TimerQueue]:`StageContext.transition()` 會取消
 * `submitLater/submitRepeating` 全部排程,柵門滑開、打散延遲移除不能被轉場砍掉。
 */
class SessionRun(
    private val core: HanaTokiCore,
    private val runtime: DataContentRuntime,
    private val content: LoadedContent,
    initialCtx: StageContext,
) {
    /** 事件執行緒也會讀(只拿來 `submit`);StageContext 實作是無狀態包裝,同一局的 state 物件不變。 */
    @Volatile
    var ctx: StageContext = initialCtx

    private val spec: ContentSpec = content.spec
    val sessionId: UUID = initialCtx.sessionId
    private val dungeonId: String = initialCtx.dungeonId
    private val slotId: String = initialCtx.slotId
    private val world: World = initialCtx.anchor.world

    @Volatile
    var closed = false
        private set

    private var now = 0L
    private val timers = TimerQueue()
    private var markers: Map<String, MapMarker> = emptyMap()
    private var spawnGroups: Map<String, List<MapMarker>> = emptyMap()

    private val lanterns = LinkedHashMap<String, LanternRt>()
    private val gates = LinkedHashMap<String, GateRt>()
    private val encounters = LinkedHashMap<String, EncounterRt>()
    private val enemies = ConcurrentHashMap<String, EnemyRt>()

    private var currentStage: StageSpec? = null
    private val completedStages = HashSet<String>()
    private var evaluating = false
    private val reached = HashSet<String>()
    private var lastProgress = 0L

    private val charges = HashMap<UUID, MutableMap<String, Int>>()
    private val lastFlash = HashMap<UUID, Long>()

    private val firedHints = HashSet<String>()
    private val hintFiredAt = HashMap<String, Long>()
    private var whiffSeen = false
    private var reachProbePending = false
    private val nearProbePending = HashSet<String>()

    private val cueRuns = ConcurrentLinkedDeque<CueRun>()
    private val portNotes = HashSet<String>()
    private var barPercent = -1

    // 引導與燈火回補(都是內容資料明確啟用才動,舊副本這些欄位全是空的)
    private val meters = HashMap<UUID, MutableMap<String, ChargeMeter>>()
    private var lastRegenAt = 0L
    private var presencePending = false
    private var presenceAt = 0L
    private val lanternHintRuns = HashMap<String, LanternHintRun>()
    private var objectiveKey: String? = null
    private var objectiveDirty = false
    private var objectiveNextAt = 0L
    private val toolSlots: Set<Int> = spec.tools.values.map { it.slot }.toSet()

    /** 需要每 10 tick 抓一次成員快照(位置、主手格)的功能是否有任何一個開著。 */
    private val presenceNeeded: Boolean = spec.tools.values.any { it.passiveRegenSeconds != null || it.zoneRegen != null } ||
        spec.objects.values.any { it is LanternSpec && it.hint != null } ||
        spec.hints.values.any { it.trigger is HintTrigger.UnequippedNearEnemy }

    // 敵人規則 seeks-object／harasses-players:兩者共用同一輪有界掃描。內容沒有任何敵人用到時 scanPeriod = 0,整套不動。
    private val touch = TouchCooldowns()
    private val scanPeriod: Long = spec.enemies.values
        .flatMap { listOfNotNull(it.seeksObject?.repathTicks, it.harassesPlayers?.scanTicks) }.minOrNull()?.toLong() ?: 0L
    private var scanPending = false
    private var scanAt = 0L
    private var lastScanAt = -1000L

    private val mutator = StageWorldMutator { location, data ->
        if (!closed) ctx.mutate(location) { block -> block.blockData = data }
    }

    // ---- 工具 ----

    private class LanternRt(val spec: LanternSpec, val machine: LanternMachine, val loc: Location) {
        var people = -1
        var probing = false
        /** 這次探測開始的 tick;回呼遺失(成員的 region 正在換手等)時超過 [PROBE_STALE_TICKS] 就重探,不讓 probing 永遠卡住 */
        var probeAt = 0L
        var hold: CueRun? = null
        var nextRepeat = 0L
        var milestonesFired = 0
        /** 被啃:各組啃燈速率總和(burn-second / 秒),由掃描發布;超過 [DRAIN_STALE_TICKS] 沒更新視為 0。 */
        var drainRate = 0.0
        var drainers = 0
        var drainAt = -1000L
        var nextContestCue = 0L
        var nextContestLog = 0L
        /** 連續被啃的起點 tick;沒被啃 = -1。lantern-contested 提示用。 */
        var contestedSince = -1L
    }

    /** 某位玩家看到的燈籠世界標示:[level] 1 = 遠處提示,2 = 已在可點燃範圍。 */
    private class LanternHintRun(val level: Int, val run: CueRun?)

    private class GateRt(val spec: GateSpec, val machine: GateMachine, val marker: MapMarker, val loc: Location)

    private class EncounterRt(val spec: EncounterSpec) {
        enum class Phase { PENDING, WAITING, SPAWNING, DONE, CANCELLED }
        var phase = Phase.PENDING
        var target = 0
        var spawned = 0
        val alive = HashSet<String>()
    }

    class EnemyRt(val actorId: String, val spec: EnemySpec, val encounterId: String?) {
        val machine = EnemyMachine()
        @Volatile var model: BossModelHandle? = null
        var lastLoc: Location? = null
        var lastWhiff = -1000L
        /** 跟著敵人的狀態標示 cue(活著 / 僵直中),換狀態時換掉。 */
        var markRun: CueRun? = null
        /** seeks-object:上次下尋路指令的 tick;[seekLogged] 讓 enemy-seek log 每隻只打一次。 */
        var lastRepath = -1000L
        @Volatile var seekLogged = false
    }

    private fun log(event: String, detail: String) {
        core.plugin.logger.info("[HanaToki/$dungeonId] $event $detail slot=$slotId")
    }

    private fun warn(message: String) {
        core.plugin.logger.warning("[HanaToki/$dungeonId] $message slot=$slotId")
    }

    private fun nameOf(playerId: UUID): String = Bukkit.getPlayer(playerId)?.name ?: playerId.toString().take(8)

    private fun members(): List<UUID> = ctx.activeMembers()

    private fun audience(): List<Player> = members().mapNotNull { Bukkit.getPlayer(it) }

    private fun marker(id: String): MapMarker = markers[id] ?: error("marker $id 不存在(註冊時應已驗證)")

    private fun stageActive(stage: String?): Boolean = stage == null || stage == currentStage?.id

    private fun markProgress() { lastProgress = now }

    private fun entryLocation(): Location = marker(spec.scene.entry).location(world)

    private fun noteNoPort(port: String) {
        if (portNotes.add(port)) log("port-missing", "$port 沒有實作者(LycoHanaToki/LycoLib 未啟用?),這局相關演出略過")
    }

    /** 播 cue。沒有 CuePort 實作者 = no-op(每局 INFO 一次);cue 失敗不得影響狀態機。 */
    private fun cue(id: String?, origin: Location, follow: Entity? = null, only: Collection<Player>? = null): CueRun? {
        if (id == null || closed) return null
        val port: CuePort = runtime.cuePort() ?: run { noteNoPort("CuePort"); return null }
        return try {
            val run = port.play(spec.namespace, id, origin, only ?: audience(), follow, mutator)
            cueRuns.add(run)
            run
        } catch (t: Throwable) {
            warn("cue $id 播放失敗:${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun cues(ids: List<String>, origin: Location, follow: Entity? = null, only: Collection<Player>? = null) {
        ids.forEach { cue(it, origin, follow, only) }
    }

    private fun applyBlocks(base: MapMarker, blocks: List<BlockEdit>) {
        for (edit in blocks) {
            val data = try { Bukkit.createBlockData(edit.block) } catch (t: Throwable) { warn("方塊「${edit.block}」無法解析,略過"); continue }
            val at = Location(world, (base.x + edit.dx).toDouble(), (base.y + edit.dy).toDouble(), (base.z + edit.dz).toDouble())
            ctx.mutate(at) { block -> block.blockData = data }
        }
    }

    // ---- 場景 ----

    /** prepareStage:把 scene.yml 的 piece 放進這一局的世界。失敗 = 這一局進不了(整筆回滾),不半載入。 */
    fun prepare(): CompletableFuture<Void> {
        val anchor = ctx.anchor
        val pieces = spec.scene.pieces.values.map { piece ->
            val template = content.templates[piece.id] ?: error("piece ${piece.id} 沒有載入資產")
            PlacedPiece(
                template,
                MapPos(anchor.blockX + piece.ox, anchor.blockY + piece.oy, anchor.blockZ + piece.oz),
                rotationOf(piece.rotationDegrees),
            )
        }
        val layout = MapLayout(pieces)
        return ctx.maps().place(layout).thenAccept { placed -> onPlaced(placed) }
    }

    private fun onPlaced(placed: PlacedMap) {
        val byId = LinkedHashMap<String, MapMarker>()
        for (m in placed.markers) m.id?.let { byId[it] = m }
        markers = byId
        spawnGroups = placed.markers.filter { it.kind == MarkerKind.MOB_SPAWN }
            .groupBy { it.properties["group"] ?: "" }
            .mapValues { (_, list) -> list.sortedBy { it.id ?: "" } }
        for (obj in spec.objects.values) {
            val m = marker(obj.markerId)
            when (obj) {
                is LanternSpec -> lanterns[obj.id] = LanternRt(obj, LanternMachine(obj.burnSeconds * 20.0), m.location(world))
                is GateSpec -> gates[obj.id] = GateRt(obj, GateMachine(), m, m.location(world))
            }
        }
        for (enc in spec.encounters.values) encounters[enc.id] = EncounterRt(enc)
        // 初始狀態(unlit/closed)的方塊外觀
        for (rt in lanterns.values) rt.spec.states[ObjectStates.LANTERN_UNLIT]?.let { applyBlocks(marker(rt.spec.markerId), it.blocks) }
        for (rt in gates.values) rt.spec.states[ObjectStates.GATE_CLOSED]?.let { applyBlocks(rt.marker, it.blocks) }
        log("scene-placed", "pieces=${placed.layout.pieces.size} markers=${placed.markers.size} written=${placed.written}")
    }

    private fun rotationOf(degrees: Int) = when (degrees) {
        90 -> StructureRotation.CLOCKWISE_90
        180 -> StructureRotation.CLOCKWISE_180
        270 -> StructureRotation.COUNTERCLOCKWISE_90
        else -> StructureRotation.NONE
    }

    // ---- stage ----

    fun startStage(newCtx: StageContext, stageId: String) {
        ctx = newCtx
        if (closed) return
        val stage = spec.stage(stageId) ?: run { warn("stage $stageId 不在 stages.yml"); return }
        currentStage = stage
        markProgress()
        objectiveKey = null
        objectiveDirty = false
        log("stage-start", stageId)
        playEffects(stage.onStart, stage, "on-start")
        checkTriggers()
        // transition() 會取消所有 submitRepeating,每個 stage 進場都要重新掛一次週期任務。
        ctx.submitRepeating(1L, TICK_PERIOD) { tick() }
        evaluateStage()
    }

    private fun playEffects(effects: StageEffects, stage: StageSpec, what: String) {
        cues(effects.cues, entryLocation())
        effects.music?.let { ctx.musicCue(it) }
        if (effects.titleKey != null) {
            val params = mapOf(
                "stage_index" to (spec.stages.indexOf(stage) + 1).toString(),
                "stage_total" to spec.stages.size.toString(),
            )
            ctx.titleAll(effects.titleKey, effects.subtitleKey ?: "", params)
        }
        val dialogId = effects.dialog ?: return
        val port: DialogPort = runtime.dialogPort() ?: run { noteNoPort("DialogPort"); return }
        for (playerId in members()) {
            ctx.runOnMember(playerId) { player ->
                try {
                    port.open(player, spec.namespace, dialogId, IntConsumer { choice ->
                        log("dialog", "$dialogId ($what/${stage.id}) player=${nameOf(playerId)} choice=$choice")
                    })
                } catch (t: Throwable) {
                    warn("dialog $dialogId 開啟失敗:${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun conditionMet(c: Condition): Boolean = when (c) {
        is Condition.EncounterCleared -> encounters[c.encounter]?.phase == EncounterRt.Phase.DONE
        is Condition.ObjectState -> objectReached(c.objectId, c.state)
        is Condition.Reach -> c.marker in reached
    }

    private fun objectReached(objectId: String, state: String): Boolean {
        lanterns[objectId]?.let { rt ->
            val target = ObjectStates.LANTERN.indexOf(state)
            return target >= 0 && rt.machine.state.ordinal >= target
        }
        gates[objectId]?.let { rt ->
            return when (state) {
                ObjectStates.GATE_CLOSED -> true
                ObjectStates.GATE_OPEN -> rt.machine.open
                else -> false
            }
        }
        return false
    }

    private fun evaluateStage() {
        if (evaluating || closed) return
        val stage = currentStage ?: return
        if (stage.id in completedStages || stage.complete.isEmpty()) return
        if (!stage.complete.all(::conditionMet)) return
        evaluating = true
        try { completeStage(stage) } finally { evaluating = false }
    }

    private fun completeStage(stage: StageSpec) {
        if (!completedStages.add(stage.id)) return
        markProgress()
        log("stage-complete", stage.id)
        playEffects(stage.onComplete, stage, "on-complete")
        val next = stage.next
        if (next != null) {
            ctx.transition(next)
        } else {
            // 第一段切片到此為止:不結算、不發獎。
            log("slice end", stage.id)
        }
    }

    // ---- 週期任務 ----

    private fun tick() {
        if (closed) return
        now += TICK_PERIOD
        timers.runDue(now) { warn("計時任務丟出例外:${it.javaClass.simpleName}: ${it.message}") }
        tickLanterns()
        tickEnemyRules()
        if (now % 10L == 0L) probeMarkers()
        if (presenceNeeded && now % 10L == 0L) probePresence()
        tickIdleHints()
        tickObjective()
        evaluateStage()
    }

    // ---- 燈籠 ----

    private fun tickLanterns() {
        for (rt in lanterns.values) {
            if (!stageActive(rt.spec.stage) || rt.machine.state != LanternMachine.State.BURNING) continue
            val spec = rt.spec
            val people = if (rt.people < 0) 1 else rt.people
            // 被啃:先扣再前進(暫停中也扣;見 LanternMachine.drain)。啃燈速率由掃描發布,過期視為 0
            if (rt.drainRate > 0.0 && now - rt.drainAt <= DRAIN_STALE_TICKS) {
                rt.machine.drain(drainTicks(rt.drainRate, TICK_PERIOD.toInt()))
            }
            when (rt.machine.advance(TICK_PERIOD.toInt(), lanternMultiplier(spec.speedByPlayers, people))) {
                LanternMachine.Outcome.PAUSED -> {
                    log("object", "${spec.id} burning paused")
                    rt.hold?.cancel(); rt.hold = null
                    cues(spec.pauseCues, rt.loc)
                }
                LanternMachine.Outcome.RESUMED -> {
                    log("object", "${spec.id} burning resumed")
                    startHold(rt)
                    cues(spec.resumeCues, rt.loc)
                }
                LanternMachine.Outcome.COMPLETED -> { lanternLit(rt); continue }
                LanternMachine.Outcome.NONE -> {}
            }
            if (!rt.machine.paused) {
                spec.repeatCue?.let { r ->
                    if (now >= rt.nextRepeat) {
                        cue(r.cue, rt.loc)
                        rt.nextRepeat = now + (r.everySeconds * 20).toLong()
                    }
                }
            }
            while (rt.milestonesFired < spec.milestones.size && rt.machine.fraction >= spec.milestones[rt.milestonesFired].at) {
                val m = spec.milestones[rt.milestonesFired++]
                log("object", "${spec.id} milestone ${m.at}")
                cues(m.effect.cues, rt.loc)
                applyBlocks(marker(spec.markerId), m.effect.blocks)
            }
            updateBar(rt)
            if (now % 4L == 0L && (!rt.probing || now - rt.probeAt > PROBE_STALE_TICKS)) probeLantern(rt)
        }
    }

    private fun probeLantern(rt: LanternRt) {
        rt.probing = true
        rt.probeAt = now
        val startedAt = now
        ctx.membersWithin(rt.loc, rt.spec.stayRadius).whenComplete { list, error ->
            ctx.submit {
                // 過期的探測(已被較新的取代)不覆蓋結果
                if (rt.probeAt != startedAt) return@submit
                rt.probing = false
                val people = if (error != null) rt.people else (list?.size ?: 0)
                if (people != rt.people) log("object", "${rt.spec.id} people=$people")
                rt.people = people
            }
        }
    }

    private fun updateBar(rt: LanternRt) {
        val bar = rt.spec.progressBar ?: return
        val percent = (rt.machine.fraction * 100).toInt()
        if (percent == barPercent) return
        barPercent = percent
        val raw = core.texts.rawOrNull(bar.textKey) ?: return
        ctx.bossBar(raw.replace("{percent}", percent.toString()), rt.machine.fraction, bar.color)
    }

    private fun startHold(rt: LanternRt) {
        val id = rt.spec.holdCue ?: return
        rt.hold?.cancel()
        rt.hold = cue(id, rt.loc)
    }

    private fun igniteLantern(rt: LanternRt): Boolean {
        if (!rt.machine.ignite()) return false
        log("object", "${rt.spec.id} unlit->burning")
        markProgress()
        clearLanternHints(rt.spec.id)
        rt.nextRepeat = now
        val effect = rt.spec.states[ObjectStates.LANTERN_BURNING]
        effect?.let {
            cues(it.cues, rt.loc)
            applyBlocks(marker(rt.spec.markerId), it.blocks)
        }
        startHold(rt)
        checkTriggers()
        evaluateStage()
        return true
    }

    private fun lanternLit(rt: LanternRt) {
        val spec = rt.spec
        log("object", "${spec.id} burning->lit")
        markProgress()
        rt.drainRate = 0.0; rt.drainers = 0
        rt.hold?.cancel(); rt.hold = null
        if (spec.progressBar != null) { ctx.hideBossBar(); barPercent = -1 }
        spec.states[ObjectStates.LANTERN_LIT]?.let {
            cues(it.cues, rt.loc)
            applyBlocks(marker(spec.markerId), it.blocks)
        }
        val lit = spec.onLit
        cues(lit.cues, rt.loc)
        placeLight(rt.loc, lit)
        if (lit.clearsEnemies) clearEnemiesAround(rt.loc, lit.lightRadius)
        lit.opens?.let { gates[it]?.let(::openGate) }
        checkTriggers()
        evaluateStage()
    }

    private fun placeLight(center: Location, lit: LanternLit) {
        if (lit.lightRadius <= 0.0) return
        val data = try { Bukkit.createBlockData("minecraft:light[level=${lit.lightLevel}]") } catch (t: Throwable) { warn("光源方塊無法建立"); return }
        val r = lit.lightRadius
        val step = lit.lightSpacing
        val span = (r / step).toInt() * step
        var placed = 0
        var dx = -span
        while (dx <= span) {
            var dy = -span
            while (dy <= span) {
                var dz = -span
                while (dz <= span) {
                    if (dx * dx + dy * dy + dz * dz <= r * r) {
                        val at = Location(world, center.blockX + dx.toDouble(), center.blockY + 1.0 + dy, center.blockZ + dz.toDouble())
                        ctx.mutate(at) { block -> if (block.type.isAir) block.blockData = data }
                        placed++
                    }
                    dz += step
                }
                dy += step
            }
            dx += step
        }
        log("light", "placed<=$placed level=${lit.lightLevel} radius=$r")
    }

    private fun clearEnemiesAround(center: Location, radius: Double) {
        // 這一個 stage 還沒生完的波次不再生:光圈亮起之後只剩現有的幽靈被清掉。
        // 還沒開始的波次直接取消(CANCELLED 不算「清空」,不會連鎖觸發後面的波次);已經在生的,剩下的不生,
        // 場上的打散後正常清空。
        val stageId = currentStage?.id
        for (enc in encounters.values) {
            if (enc.spec.stage != stageId) continue
            when (enc.phase) {
                EncounterRt.Phase.PENDING, EncounterRt.Phase.WAITING -> {
                    enc.phase = EncounterRt.Phase.CANCELLED
                    log("wave-cancel", enc.spec.id)
                }
                EncounterRt.Phase.SPAWNING -> { enc.target = enc.spawned; maybeClear(enc) }
                else -> {}
            }
        }
        val victims = enemies.values.filter { it.machine.state == EnemyMachine.State.ALIVE || it.machine.stunned }
        val futures = victims.map { rt -> ctx.actors().locationOf(rt.actorId).handle { loc, _ -> rt to loc } }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            ctx.submit {
                var cleared = 0
                for (f in futures) {
                    val (rt, loc) = f.getNow(null) ?: continue
                    if (loc == null || loc.world != center.world || loc.distanceSquared(center) > radius * radius) continue
                    if (dissolve(rt, null, loc)) cleared++
                }
                log("enemies-cleared", "radius=$radius count=$cleared")
            }
        }
    }

    // ---- 柵門 ----

    private fun openGate(rt: GateRt) {
        if (!rt.machine.open()) return
        val spec = rt.spec
        log("object", "${spec.id} closed->open")
        markProgress()
        log("gate-open", spec.id)
        spec.states[ObjectStates.GATE_OPEN]?.let {
            cues(it.cues, rt.loc)
            applyBlocks(rt.marker, it.blocks)
        }
        val w = rt.marker.properties["w"]!!.toInt()
        val h = rt.marker.properties["h"]!!.toInt()
        val facing = rt.marker.facing!!
        // 站在門前(facing 那一側)看過去,marker 在左下角,寬往右延伸:右手邊 = facing 逆時針轉 90 度。
        val right = when (facing) {
            BlockFace.NORTH -> BlockFace.WEST
            BlockFace.WEST -> BlockFace.SOUTH
            BlockFace.SOUTH -> BlockFace.EAST
            else -> BlockFace.NORTH
        }
        for (i in 0 until w) {
            val column = if (spec.slideFrom == "left") i else w - 1 - i
            timers.schedule(now, i.toLong() * spec.columnTicks) {
                for (j in 0 until h) {
                    val at = Location(
                        world,
                        (rt.marker.x + right.modX * column).toDouble(), (rt.marker.y + j).toDouble(), (rt.marker.z + right.modZ * column).toDouble(),
                    )
                    ctx.mutate(at) { block -> block.setType(Material.AIR, false) }
                }
            }
        }
    }

    // ---- 遭遇 ----

    private fun checkTriggers() {
        val stage = currentStage ?: return
        for (enc in encounters.values) {
            if (enc.phase != EncounterRt.Phase.PENDING || enc.spec.stage != stage.id) continue
            val met = when (val t = enc.spec.trigger) {
                Trigger.StageStart -> true
                is Trigger.ObjectState -> objectReached(t.objectId, t.state)
                is Trigger.EncounterCleared -> encounters[t.encounter]?.phase == EncounterRt.Phase.DONE
            }
            if (!met) continue
            enc.phase = EncounterRt.Phase.WAITING
            timers.schedule(now, enc.spec.delayTicks.toLong()) { startEncounter(enc) }
        }
    }

    private fun startEncounter(enc: EncounterRt) {
        if (closed || enc.phase != EncounterRt.Phase.WAITING) return
        val group = spawnGroups[enc.spec.markerGroup].orEmpty()
        val players = members().size.coerceAtLeast(1)
        enc.phase = EncounterRt.Phase.SPAWNING
        enc.target = pickByPlayers(enc.spec.counts, players, enc.spec.counts.getValue(1))
        log("wave-start", "${enc.spec.id} count=${enc.target} players=$players offset=${enc.spec.markerOffset}")
        markProgress()
        group.firstOrNull()?.let { cues(enc.spec.startCues, it.location(world)) }
        for (i in 0 until enc.target) {
            timers.schedule(now, i.toLong() * enc.spec.intervalTicks) { spawnEnemy(enc, group, i) }
        }
        if (enc.target == 0) maybeClear(enc)
    }

    private fun spawnEnemy(enc: EncounterRt, group: List<MapMarker>, index: Int) {
        if (closed || enc.phase != EncounterRt.Phase.SPAWNING || enc.spawned >= enc.target || group.isEmpty()) return
        enc.spawned++
        val enemySpec = spec.enemies.getValue(enc.spec.enemy)
        val actorId = "${enc.spec.id}-${index + 1}"
        val rt = EnemyRt(actorId, enemySpec, enc.spec.id)
        enemies[actorId] = rt
        enc.alive += actorId
        val at = group[(enc.spec.markerOffset + index) % group.size].location(world)
        val players = members().size.coerceAtLeast(1)
        val m = pickByPlayers(enemySpec.byPlayers, players, EnemyMultipliers())
        val actorSpec = ActorSpec().apply {
            entityType = enemySpec.baseEntity
            ai = true
            collidable = true
            invisible = enemySpec.model != null
            removeWhenFarAway = false
            immovable = false
            invulnerable = false
            gravity = true
            silent = true
            fireResistant = true
            randomizeData = false
            maxHealth = enemySpec.health * m.health
            scale = enemySpec.scale
            movementSpeed = enemySpec.speed?.let { it * m.speed }
            attackDamage = enemySpec.damage?.let { it * m.damage }
        }
        ctx.actors().spawn(actorId, at, actorSpec).whenComplete { _, error ->
            ctx.submit { afterSpawn(rt, at, error) }
        }
    }

    private fun afterSpawn(rt: EnemyRt, at: Location, error: Throwable?) {
        if (closed) return
        if (error != null || ctx.actors().entityIdOf(rt.actorId) == null) {
            warn("敵人 ${rt.actorId}(${rt.spec.id}) 生成失敗${error?.let { ":${it.message}" } ?: ""},視為已消失")
            enemyGone(rt, "spawn-failed")
            return
        }
        rt.lastLoc = at
        log("enemy-spawn", "${rt.actorId} enemy=${rt.spec.id} encounter=${rt.encounterId}")
        cues(rt.spec.spawnCues, at, core.actorController.entityOf(sessionId, rt.actorId))
        setMark(rt, rt.spec.needsFlash?.aliveCue)
        if (rt.spec.seeksObject?.ignoresPlayers == true) {
            // 啃燈的不理玩家:拿掉原版的鎖定目標與移動目標(近戰、閒晃),之後只聽掃描給的路徑。導航本身仍然會走
            ctx.actors().runOnEntity(rt.actorId) { e -> (e as? Mob)?.let { stripPlayerAi(rt, it) } }
        }
        val modelId = rt.spec.model ?: return
        ctx.actors().bindModel(rt.actorId, modelId).whenComplete { handle, _ ->
            ctx.submit {
                if (closed) return@submit
                rt.model = handle
                ctx.actors().runOnEntity(rt.actorId) { e ->
                    if (handle != null) {
                        handle.setBase(rt.spec.animations.idle)
                    } else {
                        (e as? LivingEntity)?.isInvisible = false
                    }
                }
                if (handle == null) warn("敵人 ${rt.actorId} 的模型 $modelId 綁定失敗(BetterModel 缺席或模型不存在),載體保持可見")
            }
        }
    }

    /** 把敵人身上的狀態標示換成 [cueId](null = 拿掉)。標示是只放給成員看的 loop 光效,跟著敵人實體。 */
    private fun setMark(rt: EnemyRt, cueId: String?) {
        rt.markRun?.cancel()
        rt.markRun = null
        if (cueId == null || closed) return
        val at = rt.lastLoc ?: return
        rt.markRun = cue(cueId, at, core.actorController.entityOf(sessionId, rt.actorId))
    }

    private fun enemyGone(rt: EnemyRt, reason: String) {
        if (!rt.machine.gone()) return
        setMark(rt, null)
        enemies.remove(rt.actorId)
        val enc = rt.encounterId?.let { encounters[it] } ?: return
        enc.alive.remove(rt.actorId)
        log("enemy-gone", "${rt.actorId} reason=$reason")
        maybeClear(enc)
    }

    private fun maybeClear(enc: EncounterRt) {
        if (enc.phase != EncounterRt.Phase.SPAWNING || enc.spawned < enc.target || enc.alive.isNotEmpty()) return
        enc.phase = EncounterRt.Phase.DONE
        markProgress()
        log("wave-clear", enc.spec.id)
        checkTriggers()
        evaluateStage()
    }

    // ---- 敵人規則:needs-flash ----

    fun enemy(actorId: String): EnemyRt? = enemies[actorId]

    /** 事件執行緒呼叫:玩家打到一隻 needs-flash 的敵人(傷害已被取消)。[loc] 在受擊實體自己的執行緒上讀好。 */
    fun onEnemyAttacked(rt: EnemyRt, playerId: UUID, loc: Location) {
        when (rt.machine.state) {
            EnemyMachine.State.STUNNED -> ctx.submit { dissolve(rt, playerId, loc) }
            EnemyMachine.State.ALIVE -> ctx.submit { whiff(rt, playerId, loc) }
            else -> {}
        }
    }

    private fun whiff(rt: EnemyRt, playerId: UUID, loc: Location) {
        val nf = rt.spec.needsFlash ?: return
        if (rt.machine.state != EnemyMachine.State.ALIVE || now - rt.lastWhiff < nf.whiffCooldownTicks) return
        rt.lastWhiff = now
        rt.lastLoc = loc
        log("whiff", "${rt.actorId} enemy=${rt.spec.id} player=${nameOf(playerId)}")
        cue(nf.whiffCue, loc, core.actorController.entityOf(sessionId, rt.actorId))
        if (!whiffSeen) {
            whiffSeen = true
            fireHints { it.trigger is HintTrigger.FirstWhiff }
        }
    }

    private fun stunEnemy(rt: EnemyRt, tool: ToolSpec, loc: Location) {
        val nf = rt.spec.needsFlash ?: return
        val wasStunned = rt.machine.stunned
        if (!rt.machine.stun()) return
        val epoch = rt.machine.stunEpoch
        rt.lastLoc = loc
        log("enemy-stun", "${rt.actorId} enemy=${rt.spec.id}${if (wasStunned) " refreshed" else ""}")
        if (!wasStunned) {
            cue(nf.stunCue, loc, core.actorController.entityOf(sessionId, rt.actorId))
            setMark(rt, nf.stunnedCue)
            ctx.actors().runOnEntity(rt.actorId) { e ->
                (e as? LivingEntity)?.setAI(false)
                e.isGlowing = nf.glowOnStun
                rt.model?.play(rt.spec.animations.stunned, true, null)
            }
        }
        timers.schedule(now, (tool.stunSeconds * 20).toLong().coerceAtLeast(1)) { recoverEnemy(rt, epoch) }
    }

    private fun recoverEnemy(rt: EnemyRt, epoch: Int) {
        if (closed || !rt.machine.recover(epoch)) return
        log("enemy-recover", rt.actorId)
        setMark(rt, rt.spec.needsFlash?.aliveCue)
        ctx.actors().runOnEntity(rt.actorId) { e ->
            (e as? LivingEntity)?.setAI(true)
            e.isGlowing = false
            rt.model?.play(rt.spec.animations.idle, true, null)
        }
    }

    /** 打散。[killer] 非 null 才補燈火(光圈清場不補)。回傳是否真的打散(只會發生一次)。 */
    private fun dissolve(rt: EnemyRt, killer: UUID?, loc: Location?): Boolean {
        val nf = rt.spec.needsFlash
        if (!rt.machine.dissolve()) return false
        val at = loc ?: rt.lastLoc
        log("enemy-dissolve", "${rt.actorId} enemy=${rt.spec.id} by=${killer?.let(::nameOf) ?: "lantern"}")
        markProgress()
        setMark(rt, null)
        if (at != null) {
            rt.lastLoc = at
            cue(nf?.dissolveCue, at, core.actorController.entityOf(sessionId, rt.actorId))
        }
        ctx.actors().runOnEntity(rt.actorId) { e ->
            (e as? LivingEntity)?.setAI(false)
            e.isGlowing = false
            rt.model?.play(rt.spec.animations.dissolve, false, null)
        }
        if (killer != null) refill(killer, nf?.refillCue, at)
        timers.schedule(now, (nf?.dissolveTicks ?: 12).toLong()) { removeEnemy(rt) }
        return true
    }

    private fun removeEnemy(rt: EnemyRt) {
        if (closed) return
        ctx.actors().despawn(rt.actorId)
        enemyGone(rt, "dissolved")
    }

    /** 事件層:實體沒走打散流程就死了(掉進虛空、指令清除…)。 */
    fun onActorDeath(actorId: String) {
        val rt = enemies[actorId] ?: return
        if (rt.machine.state == EnemyMachine.State.GONE) return
        enemyGone(rt, "death")
    }

    // ---- 敵人規則:seeks-object / harasses-players ----

    private fun stripPlayerAi(rt: EnemyRt, mob: Mob) {
        try {
            val goals = Bukkit.getMobGoals()
            goals.removeAllGoals(mob, GoalType.TARGET)
            goals.removeAllGoals(mob, GoalType.MOVE)
            mob.target = null
        } catch (t: Throwable) {
            warn("敵人 ${rt.actorId} 拿掉原版 AI 失敗,退回每輪清掉目標:${t.javaClass.simpleName}: ${t.message}")
            runCatching { mob.target = null }
        }
    }

    /** 每 [scanPeriod] tick 一輪:取還活著的規則敵人位置(各自的 EntityScheduler)與成員快照,回 anchor 執行緒一次處理完。單輪最多 [MAX_SCAN_ENEMIES] 隻。 */
    private fun tickEnemyRules() {
        if (scanPeriod <= 0L || closed || now - lastScanAt < scanPeriod) return
        if (scanPending && now - scanAt <= PROBE_STALE_TICKS) return
        lastScanAt = now
        val candidates = enemies.values
            .filter { (it.spec.seeksObject != null || it.spec.harassesPlayers != null) && it.machine.state == EnemyMachine.State.ALIVE }
            .take(MAX_SCAN_ENEMIES)
        if (candidates.isEmpty()) { publishDrain(emptyMap()); return }
        scanPending = true
        scanAt = now
        val startedAt = now
        val locations = candidates.map { rt -> ctx.actors().locationOf(rt.actorId).handle { loc, _ -> rt to loc } }
        val snapshots: CompletableFuture<Map<UUID, DoubleArray>> =
            if (candidates.any { it.spec.harassesPlayers != null }) ctx.memberSnapshots().handle { m, e -> if (e != null) emptyMap() else m ?: emptyMap() }
            else CompletableFuture.completedFuture(emptyMap())
        CompletableFuture.allOf(snapshots, *locations.toTypedArray()).whenComplete { _, _ ->
            ctx.submit {
                if (scanAt != startedAt) return@submit // 較新的一輪已取代(這輪回得太晚)
                scanPending = false
                if (closed) return@submit
                applyEnemyScan(locations.mapNotNull { it.getNow(null) }, snapshots.getNow(emptyMap()))
            }
        }
    }

    private fun applyEnemyScan(found: List<Pair<EnemyRt, Location?>>, snap: Map<UUID, DoubleArray>) {
        // (燈籠 id, 敵人定義 id) -> 這輪在啃的隻數
        val contest = LinkedHashMap<Pair<String, String>, Int>()
        for ((rt, loc) in found) {
            if (loc == null || loc.world != world || rt.machine.state != EnemyMachine.State.ALIVE) continue
            rt.lastLoc = loc
            rt.spec.seeksObject?.let { seekStep(rt, loc, it, contest) }
            rt.spec.harassesPlayers?.let { harassStep(rt, loc, it, snap) }
        }
        publishDrain(contest)
    }

    private fun seekStep(rt: EnemyRt, loc: Location, seek: SeeksObjectSpec, contest: MutableMap<Pair<String, String>, Int>) {
        val lantern = lanterns[seek.objectId] ?: return
        if (!stageActive(lantern.spec.stage) || lantern.machine.state.name.lowercase() !in seek.states) return
        val near = withinContact(loc.x - lantern.loc.x, loc.y - lantern.loc.y, loc.z - lantern.loc.z, seek.contestRadius, CONTEST_HEIGHT)
        if (near) {
            contest.merge(lantern.spec.id to rt.spec.id, 1, Int::plus)
            return
        }
        if (now - rt.lastRepath < seek.repathTicks) return
        rt.lastRepath = now
        val target = lantern.loc.clone()
        ctx.actors().runOnEntity(rt.actorId) { e ->
            val mob = e as? Mob ?: return@runOnEntity
            if (seek.ignoresPlayers) mob.target = null
            val ok = mob.pathfinder.moveTo(target, seek.speed)
            if (!rt.seekLogged) {
                rt.seekLogged = true
                log("enemy-seek", "${rt.actorId} enemy=${rt.spec.id} target=${seek.objectId} path=${if (ok) "ok" else "none"}")
            }
        }
    }

    /** 把這輪的啃燈結果發布到各燈籠(掃描沒看到的燈籠歸零),並節流播 cue 與 log。 */
    private fun publishDrain(contest: Map<Pair<String, String>, Int>) {
        for (rt in lanterns.values) {
            val groups = ArrayList<DrainGroup>()
            var contestCues: List<String> = emptyList()
            var cueEvery = 1.0
            for ((key, count) in contest) {
                if (key.first != rt.spec.id) continue
                val seek = spec.enemies[key.second]?.seeksObject ?: continue
                groups += DrainGroup(count, seek.maxDrainers, seek.drainPerSecond)
                if (seek.contestCues.isNotEmpty()) { contestCues = seek.contestCues; cueEvery = seek.contestCueEverySeconds }
            }
            val before = rt.drainers
            rt.drainRate = drainRate(groups)
            rt.drainers = groups.sumOf { it.inRange.coerceIn(0, it.max) }
            rt.drainAt = now
            if (rt.drainers > 0) {
                if (rt.contestedSince < 0) rt.contestedSince = now
                val contestedSeconds = (now - rt.contestedSince) / 20.0
                for (h in spec.hints.values) {
                    val t = h.trigger as? HintTrigger.LanternContested ?: continue
                    if (stageActive(h.stage) && contestedSeconds >= t.seconds) fireHint(h)
                }
                if (contestCues.isNotEmpty() && now >= rt.nextContestCue) {
                    rt.nextContestCue = now + (cueEvery * 20).toLong()
                    cues(contestCues, rt.loc)
                }
                if (now >= rt.nextContestLog || rt.drainers != before) {
                    rt.nextContestLog = now + 20
                    log("enemy-contest", "${rt.spec.id} ghosts=${rt.drainers} progress=${fmt2(rt.machine.fraction)}")
                }
            } else if (before > 0) {
                rt.contestedSince = -1
                rt.nextContestLog = 0
                log("enemy-contest", "${rt.spec.id} ghosts=0 progress=${fmt2(rt.machine.fraction)}")
            }
        }
    }

    private fun fmt2(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)

    private fun harassStep(rt: EnemyRt, loc: Location, h: HarassSpec, snap: Map<UUID, DoubleArray>) {
        if (snap.isEmpty()) return
        val current = members().toSet()
        for ((playerId, pos) in snap) {
            if (playerId !in current) continue
            if (!withinContact(pos[0] - loc.x, pos[1] - loc.y, pos[2] - loc.z, h.contactRadius)) continue
            if (!touch.tryTouch(playerId, now, h.cooldownTicks)) continue
            applyHarass(rt, loc, h, playerId, pos)
        }
    }

    private fun applyHarass(rt: EnemyRt, loc: Location, h: HarassSpec, playerId: UUID, pos: DoubleArray) {
        val applied = ArrayList<String>()
        for (effect in h.effects) when (effect) {
            is HarassEffect.Push -> {
                val v = pushAway(loc.x, loc.z, pos[0], pos[2], effect.strength)
                ctx.pushMember(playerId, v[0], effect.lift, v[1])
                applied += "push"
            }
            is HarassEffect.ToolCharges -> {
                val tool = spec.tools[effect.tool] ?: continue
                val before = chargesOf(playerId, tool)
                val after = (before + effect.delta).coerceIn(0, tool.maxCharges)
                if (after != before) {
                    setCharges(playerId, tool, after)
                    applied += "charges${if (after > before) "+" else ""}${after - before}"
                }
            }
            is HarassEffect.Potion -> {
                ctx.applyEffectToMember(playerId, effect.effect, effect.durationTicks, effect.amplifier)
                applied += "potion"
            }
            is HarassEffect.Damage -> {
                val amount = minOf(effect.amount, pos[HEALTH] - effect.minHealth)
                if (amount > 0.0) {
                    ctx.damageMember(playerId, amount, "")
                    applied += "damage"
                }
            }
        }
        log("enemy-touch", "${rt.actorId} player=${nameOf(playerId)} effect=${if (applied.isEmpty()) "none" else applied.joinToString("+")}")
        cues(h.cues, loc, core.actorController.entityOf(sessionId, rt.actorId))
    }

    // ---- 局內道具 ----

    private fun toolSpecs(): Collection<ToolSpec> = spec.tools.values

    private fun chargesOf(playerId: UUID, tool: ToolSpec): Int = charges.getOrPut(playerId) { HashMap() }.getOrPut(tool.id) { tool.startCharges }

    private fun setCharges(playerId: UUID, tool: ToolSpec, value: Int) {
        val v = value.coerceIn(0, tool.maxCharges)
        charges.getOrPut(playerId) { HashMap() }[tool.id] = v
        objectiveDirty = true
        ctx.runOnMember(playerId) { player -> runtime.toolItems.syncDurability(player, tool, v) }
        tool.hudTextKey?.let { ctx.actionBar(playerId, it, mapOf("charges" to v.toString(), "max" to tool.maxCharges.toString())) }
    }

    private fun refill(playerId: UUID, refillCue: String?, at: Location?) {
        var any = false
        for (tool in toolSpecs()) {
            if (tool.refillOnDissolve <= 0) continue
            val before = chargesOf(playerId, tool)
            if (before >= tool.maxCharges) continue
            setCharges(playerId, tool, before + tool.refillOnDissolve)
            any = true
            log("refill", "player=${nameOf(playerId)} tool=${tool.id} charges=${before + tool.refillOnDissolve}")
        }
        if (any && at != null) Bukkit.getPlayer(playerId)?.let { cue(refillCue, at, null, listOf(it)) }
    }

    /** 給進場成員發 tool。必須等 onMemberReady(進場交易完成、局內背包換好)。 */
    fun onMemberReady(playerId: UUID) {
        if (closed) return
        objectiveDirty = true
        val items = core.instanceInventory.items
        val instanceId = items.activeInstanceIdOf(playerId)
        for (tool in toolSpecs()) {
            val count = chargesOf(playerId, tool)
            ctx.runOnMember(playerId) { player ->
                val stack = runtime.toolItems.mint(tool, count, items, instanceId)
                if (stack == null) { warn("tool ${tool.id} 的材質「${tool.material}」無法鑄造"); return@runOnMember }
                player.inventory.setItem(tool.slot, stack)
            }
            log("tool-give", "${tool.id} player=${nameOf(playerId)} charges=$count")
        }
    }

    fun onMemberLeft(playerId: UUID) {
        touch.forget(playerId)
        charges.remove(playerId)
        meters.remove(playerId)
        lastFlash.remove(playerId)
        val suffix = "/$playerId"
        val gone = lanternHintRuns.keys.filter { it.endsWith(suffix) }
        gone.forEach { lanternHintRuns.remove(it)?.run?.cancel() }
    }

    /** 事件執行緒呼叫:玩家右鍵 tool。[eye]/[dir]/[feet] 是事件當下持有者的快照([feet] = `player.location`,點燃半徑與世界標示用同一套量法)。 */
    fun onFlash(playerId: UUID, toolId: String, eye: Location, dir: Vector, feet: Location) {
        ctx.submit { handleFlash(playerId, toolId, eye, dir, feet) }
    }

    private fun handleFlash(playerId: UUID, toolId: String, eye: Location, dir: Vector, feet: Location) {
        if (closed) return
        val tool = spec.tools[toolId] ?: return
        if (playerId !in members()) return
        val last = lastFlash[playerId]
        if (last != null && now - last < tool.cooldownTicks) return
        lastFlash[playerId] = now
        val player = Bukkit.getPlayer(playerId)
        val current = chargesOf(playerId, tool)
        if (current <= 0) {
            log("flash-empty", "player=${nameOf(playerId)} tool=${tool.id}")
            cues(tool.emptyCues, eye, null, listOfNotNull(player))
            hintToolEmpty(playerId)
            return
        }
        setCharges(playerId, tool, current - 1)
        cues(tool.flashCues, eye)
        if (current - 1 == 0) {
            log("tool-drained", "player=${nameOf(playerId)} tool=${tool.id}")
            cues(tool.drainedCues, eye, null, listOfNotNull(player))
            hintToolEmpty(playerId)
        }
        val direction = dir.clone().normalize()
        val half = Math.toRadians(tool.coneAngle / 2.0)
        var lanternHits = 0
        val misses = ArrayList<String>()
        for (rt in lanterns.values) {
            if (!stageActive(rt.spec.stage) || rt.machine.state != LanternMachine.State.UNLIT) continue
            val base = rt.loc
            val coneHit = inCone(eye, direction, base.clone().add(0.0, 0.5, 0.0), half, tool.range) ||
                inCone(eye, direction, base.clone().add(0.0, 2.5, 0.0), half, tool.range)
            val center = base.clone().add(0.0, 1.0, 0.0)
            // 點燃半徑量「腳底到燈籠 marker」:跟守燈範圍(membersWithin)、世界標示亮圈(成員快照)是同一套量法,蹲下也不變
            val close: Double = if (feet.world == base.world) feet.distance(base) else Double.MAX_VALUE
            val radiusHit = rt.spec.igniteRadius > 0.0 && close <= rt.spec.igniteRadius
            if ((coneHit || radiusHit) && igniteLantern(rt)) {
                lanternHits++
            } else if (!coneHit && !radiusHit) {
                // 實測證據:沒點到時記下距離與視線偏角,下次調射程與判定半徑有數據可看
                val to = center.toVector().subtract(eye.toVector())
                val angle = if (to.lengthSquared() < 1.0E-6) 0.0 else Math.toDegrees(Math.acos(to.normalize().dot(direction).coerceIn(-1.0, 1.0)))
                misses += String.format(java.util.Locale.ROOT, "%s:dist=%.1f,angle=%.0f", rt.spec.id, if (close == Double.MAX_VALUE) -1.0 else close, angle)
            }
        }
        if (lanternHits == 0 && misses.isNotEmpty()) {
            log("flash-miss", "player=${nameOf(playerId)} range=${tool.range} cone=${tool.coneAngle} ${misses.joinToString(" ")}")
        }
        val candidates = enemies.values.filter { it.spec.needsFlash != null && (it.machine.state == EnemyMachine.State.ALIVE || it.machine.stunned) }
        log("flash", "player=${nameOf(playerId)} tool=${tool.id} charges=${current - 1} lanterns=$lanternHits candidates=${candidates.size}")
        if (lanternHits > 0) markProgress()
        if (candidates.isEmpty()) return
        val futures = candidates.map { rt -> ctx.actors().locationOf(rt.actorId).handle { loc, _ -> rt to loc } }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            ctx.submit {
                var stunned = 0
                for (f in futures) {
                    val (rt, loc) = f.getNow(null) ?: continue
                    if (loc == null || loc.world != eye.world) continue
                    if (!inCone(eye, direction, loc.clone().add(0.0, 0.6, 0.0), half, tool.range)) continue
                    stunEnemy(rt, tool, loc)
                    stunned++
                }
                if (stunned > 0) markProgress()
                log("flash-hit", "player=${nameOf(playerId)} enemies=$stunned")
            }
        }
    }

    // ---- 提示 ----

    private fun hintOrigin(h: HintSpec): Location =
        (h.trigger as? HintTrigger.NearMarker)?.let { marker(it.marker).location(world) } ?: entryLocation()

    private fun fireHints(filter: (HintSpec) -> Boolean) {
        for (h in spec.hints.values) {
            if (!filter(h) || !stageActive(h.stage)) continue
            fireHint(h)
        }
    }

    /** [only] 非 null = 個人提示(只播給這位玩家,once／repeat 也各算各的)。 */
    private fun fireHint(h: HintSpec, only: Player? = null) {
        val key = if (only != null) "${h.id}/${only.uniqueId}" else h.id
        if (h.once && key in firedHints) return
        val last = hintFiredAt[key]
        if (!h.once && last != null && now - last < (h.repeatSeconds * 20).toLong()) return
        firedHints += key
        hintFiredAt[key] = now
        log("hint", if (only != null) "${h.id} player=${only.name}" else h.id)
        cues(h.cues, hintOrigin(h), null, only?.let { listOf(it) })
    }

    private fun hintToolEmpty(playerId: UUID) {
        val player = Bukkit.getPlayer(playerId) ?: return
        for (h in spec.hints.values) if (h.trigger is HintTrigger.ToolEmpty && stageActive(h.stage)) fireHint(h, player)
    }

    private fun tickIdleHints() {
        if (now % 10L != 0L) return
        val idleSeconds = (now - lastProgress) / 20.0
        for (h in spec.hints.values) {
            val t = h.trigger as? HintTrigger.NoProgress ?: continue
            if (!stageActive(h.stage) || idleSeconds < t.seconds) continue
            fireHint(h)
        }
    }

    // ---- marker 探測(reach 條件、near-marker 提示)----

    private fun probeMarkers() {
        val stage = currentStage ?: return
        if (!reachProbePending) {
            for (c in stage.complete) if (c is Condition.Reach && c.marker !in reached) {
                reachProbePending = true
                ctx.membersWithin(marker(c.marker).location(world), c.radius).whenComplete { list, _ ->
                    ctx.submit {
                        reachProbePending = false
                        if (!list.isNullOrEmpty() && reached.add(c.marker)) {
                            log("reach", c.marker)
                            markProgress()
                            evaluateStage()
                        }
                    }
                }
                break
            }
        }
        for (h in spec.hints.values) {
            val t = h.trigger as? HintTrigger.NearMarker ?: continue
            if (!stageActive(h.stage) || (h.once && h.id in firedHints) || h.id in nearProbePending) continue
            nearProbePending += h.id
            ctx.membersWithin(marker(t.marker).location(world), t.radius).whenComplete { list, _ ->
                ctx.submit {
                    nearProbePending -= h.id
                    if (!list.isNullOrEmpty()) fireHint(h)
                }
            }
        }
    }

    // ---- 目標(ActionBar 一行字)----

    /** 依 stages.yml 的 objective:進 stage、步驟達成換下一步、燈火變了、到定時間隔就重畫。 */
    private fun tickObjective() {
        val stage = currentStage ?: return
        if (stage.objective.isEmpty()) return
        val key = pickObjective(stage.objective, ::conditionMet)?.textKey ?: stage.objectiveDoneKey
        val every = stage.objectiveEverySeconds
        val due = key != objectiveKey || objectiveDirty || (every > 0.0 && now >= objectiveNextAt)
        if (!due) return
        objectiveKey = key
        objectiveDirty = false
        objectiveNextAt = now + (every * 20).toLong()
        if (key != null) showObjective(key)
    }

    private fun showObjective(key: String) {
        val tool = spec.tools.values.firstOrNull()
        for (playerId in members()) {
            val params = if (tool == null) emptyMap() else {
                val c = chargesOf(playerId, tool)
                mapOf("charges" to c.toString(), "max" to tool.maxCharges.toString(), "pips" to chargePips(c, tool.maxCharges))
            }
            ctx.actionBar(playerId, key, params)
        }
    }

    // ---- 成員快照:燈火回補、燈籠世界標示、「沒拿道具靠近敵人」提示 ----

    private fun probePresence() {
        if (presencePending && now - presenceAt <= PROBE_STALE_TICKS) return
        presencePending = true
        presenceAt = now
        val startedAt = now
        ctx.memberSnapshots().whenComplete { snap, error ->
            ctx.submit {
                if (presenceAt != startedAt) return@submit
                presencePending = false
                if (!closed) processPresence(if (error != null) emptyMap() else snap ?: emptyMap())
            }
        }
    }

    private fun processPresence(snap: Map<UUID, DoubleArray>) {
        val dtSeconds = ((now - lastRegenAt) / 20.0).coerceAtMost(MAX_REGEN_DT_SECONDS)
        lastRegenAt = now
        regenCharges(snap, dtSeconds)
        updateLanternHints(snap)
        probeUnequipped(snap)
    }

    private fun distance(pos: DoubleArray, loc: Location): Double {
        val dx = pos[0] - loc.x
        val dy = pos[1] - loc.y
        val dz = pos[2] - loc.z
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun inRegenZone(tool: ToolSpec, pos: DoubleArray): Boolean {
        val zone = tool.zoneRegen ?: return false
        for (rt in lanterns.values) {
            if (!stageActive(rt.spec.stage)) continue
            if (rt.machine.state.name.lowercase() in zone.states && distance(pos, rt.loc) <= zone.radius) return true
        }
        return zone.markers.any { id -> markers[id]?.let { distance(pos, it.location(world)) <= zone.radius } == true }
    }

    /** 被動／光源區回補。有被動回補的道具,燈火 0 最久 passive-regen-seconds 秒內一定回到 >= 1(不靠敵人)。 */
    private fun regenCharges(snap: Map<UUID, DoubleArray>, dtSeconds: Double) {
        if (dtSeconds <= 0.0) return
        for (playerId in members()) for (tool in toolSpecs()) {
            if (tool.passiveRegenSeconds == null && tool.zoneRegen == null) continue
            val before = chargesOf(playerId, tool)
            val meter = meters.getOrPut(playerId) { HashMap() }
                .getOrPut(tool.id) { ChargeMeter(tool.passiveRegenSeconds, tool.zoneRegen?.seconds) }
            val pos = snap[playerId]
            val inZone = pos != null && inRegenZone(tool, pos)
            val gained = meter.advance(dtSeconds, before, tool.maxCharges, inZone)
            if (gained <= 0) continue
            setCharges(playerId, tool, before + gained)
            log("regen", "player=${nameOf(playerId)} tool=${tool.id} charges=${(before + gained).coerceAtMost(tool.maxCharges)} zone=$inZone")
            val player = Bukkit.getPlayer(playerId) ?: continue
            // origin 帶眼睛位置與朝向(光效 orient: aim 要用);快照沒有時退回入口
            val at = if (pos != null) Location(world, pos[0], pos[1] + 1.62, pos[2], pos[3].toFloat(), pos[4].toFloat()) else entryLocation()
            cues(tool.gainCues, at, null, listOf(player))
        }
    }

    /** 未點亮燈籠的世界標示:手持道具的玩家走近才出現,只放給他自己;進入可點燃範圍換成較亮的一個。 */
    private fun updateLanternHints(snap: Map<UUID, DoubleArray>) {
        for (rt in lanterns.values) {
            val hint = rt.spec.hint ?: continue
            val active = stageActive(rt.spec.stage) && rt.machine.state == LanternMachine.State.UNLIT
            for (playerId in members()) {
                val key = "${rt.spec.id}/$playerId"
                val current = lanternHintRuns[key]
                val pos = snap[playerId]
                var level = 0
                if (active && pos != null) {
                    val holding = hint.requiresTool == null || spec.tools[hint.requiresTool]?.slot == pos[HELD_SLOT].toInt()
                    if (holding) {
                        level = lanternHintLevel(distance(pos, rt.loc), current?.level ?: 0, hint.radius, hint.readyRadius, hint.readyCue != null, HINT_SLACK)
                    }
                }
                if (level == (current?.level ?: 0)) continue
                current?.run?.cancel()
                if (level == 0) { lanternHintRuns.remove(key); continue }
                val player = Bukkit.getPlayer(playerId)
                val cueId = if (level == 2) hint.readyCue else hint.cue
                val run = if (player != null) cue(cueId, rt.loc, null, listOf(player)) else null
                lanternHintRuns[key] = LanternHintRun(level, run)
            }
        }
    }

    private fun clearLanternHints(lanternId: String) {
        val prefix = "$lanternId/"
        val gone = lanternHintRuns.keys.filter { it.startsWith(prefix) }
        gone.forEach { lanternHintRuns.remove(it)?.run?.cancel() }
    }

    /** 主手沒拿局內道具、卻靠近還沒僵直的敵人:個人提示。 */
    private fun probeUnequipped(snap: Map<UUID, DoubleArray>) {
        val hints = spec.hints.values.filter { it.trigger is HintTrigger.UnequippedNearEnemy && stageActive(it.stage) }
        if (hints.isEmpty() || snap.isEmpty() || toolSlots.isEmpty()) return
        val alive = enemies.values.filter { it.spec.needsFlash != null && it.machine.state == EnemyMachine.State.ALIVE }
        if (alive.isEmpty()) return
        val futures = alive.map { rt -> ctx.actors().locationOf(rt.actorId).handle { loc, _ -> loc } }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            ctx.submit {
                if (closed) return@submit
                val locs = futures.mapNotNull { it.getNow(null) }.filter { it.world == world }
                if (locs.isEmpty()) return@submit
                val current = members().toSet()
                for (h in hints) {
                    val radius = (h.trigger as HintTrigger.UnequippedNearEnemy).radius
                    for ((playerId, pos) in snap) {
                        if (playerId !in current || pos[HELD_SLOT].toInt() in toolSlots) continue
                        if (locs.none { distance(pos, it) <= radius }) continue
                        Bukkit.getPlayer(playerId)?.let { fireHint(h, it) }
                    }
                }
            }
        }
    }

    // ---- 收尾 ----

    /** 局結束:取消所有 CueRun 與排程、停掉 mutator。場地回滾由引擎在這之後做。 */
    fun close() {
        if (closed) return
        closed = true
        timers.clear()
        touch.clear()
        lanterns.values.forEach { it.hold?.cancel(); it.hold = null }
        lanternHintRuns.clear()
        while (true) {
            val run = cueRuns.pollFirst() ?: break
            try { run.cancel() } catch (t: Throwable) { warn("CueRun.cancel 失敗:${t.message}") }
        }
        enemies.clear()
        log("session-end", "cancelledCues=done")
    }

    private companion object {
        const val TICK_PERIOD = 2L
        const val PROBE_STALE_TICKS = 40L
        /** [StageContext.MEMBER_SNAPSHOT_FIELDS] 的 heldSlot 欄位。 */
        const val HELD_SLOT = 9
        /** [StageContext.MEMBER_SNAPSHOT_FIELDS] 的 health 欄位。 */
        const val HEALTH = 5
        /** 敵人規則掃描單輪上限,以及啃燈判定允許的高度差、啃燈速率過期時間。 */
        const val MAX_SCAN_ENEMIES = 24
        const val CONTEST_HEIGHT = 3.0
        const val DRAIN_STALE_TICKS = 60L
        const val HINT_SLACK = 1.0
        /** 快照回來太晚時,一次最多補算這麼多秒的回補,不讓卡頓變成一次補滿。 */
        const val MAX_REGEN_DT_SECONDS = 5.0

        fun inCone(eye: Location, direction: Vector, target: Location, halfAngle: Double, range: Double): Boolean {
            if (target.world != eye.world) return false
            val v = target.toVector().subtract(eye.toVector())
            val distance = v.length()
            if (distance > range) return false
            if (distance < 0.6) return true
            return v.normalize().dot(direction) >= Math.cos(halfAngle)
        }
    }
}
