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
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.structure.StructureRotation
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
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
    }

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
        if (now % 10L == 0L) probeMarkers()
        tickIdleHints()
        evaluateStage()
    }

    // ---- 燈籠 ----

    private fun tickLanterns() {
        for (rt in lanterns.values) {
            if (!stageActive(rt.spec.stage) || rt.machine.state != LanternMachine.State.BURNING) continue
            val spec = rt.spec
            val people = if (rt.people < 0) 1 else rt.people
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
        log("wave-start", "${enc.spec.id} count=${enc.target} players=$players")
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
        val at = group[index % group.size].location(world)
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

    private fun enemyGone(rt: EnemyRt, reason: String) {
        if (!rt.machine.gone()) return
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

    // ---- 局內道具 ----

    private fun toolSpecs(): Collection<ToolSpec> = spec.tools.values

    private fun chargesOf(playerId: UUID, tool: ToolSpec): Int = charges.getOrPut(playerId) { HashMap() }.getOrPut(tool.id) { tool.startCharges }

    private fun setCharges(playerId: UUID, tool: ToolSpec, value: Int) {
        val v = value.coerceIn(0, tool.maxCharges)
        charges.getOrPut(playerId) { HashMap() }[tool.id] = v
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
        charges.remove(playerId)
        lastFlash.remove(playerId)
    }

    /** 事件執行緒呼叫:玩家右鍵 tool。[eye]/[dir] 是事件當下持有者的快照。 */
    fun onFlash(playerId: UUID, toolId: String, eye: Location, dir: Vector) {
        ctx.submit { handleFlash(playerId, toolId, eye, dir) }
    }

    private fun handleFlash(playerId: UUID, toolId: String, eye: Location, dir: Vector) {
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
            return
        }
        setCharges(playerId, tool, current - 1)
        cues(tool.flashCues, eye)
        val direction = dir.clone().normalize()
        val half = Math.toRadians(tool.coneAngle / 2.0)
        var lanternHits = 0
        for (rt in lanterns.values) {
            if (!stageActive(rt.spec.stage) || rt.machine.state != LanternMachine.State.UNLIT) continue
            val base = rt.loc
            val hit = inCone(eye, direction, base.clone().add(0.0, 0.5, 0.0), half, tool.range) ||
                inCone(eye, direction, base.clone().add(0.0, 2.5, 0.0), half, tool.range)
            if (hit && igniteLantern(rt)) lanternHits++
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

    private fun fireHint(h: HintSpec) {
        if (h.once && h.id in firedHints) return
        val last = hintFiredAt[h.id]
        if (!h.once && last != null && now - last < (h.repeatSeconds * 20).toLong()) return
        firedHints += h.id
        hintFiredAt[h.id] = now
        log("hint", h.id)
        cues(h.cues, hintOrigin(h))
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

    // ---- 收尾 ----

    /** 局結束:取消所有 CueRun 與排程、停掉 mutator。場地回滾由引擎在這之後做。 */
    fun close() {
        if (closed) return
        closed = true
        timers.clear()
        lanterns.values.forEach { it.hold?.cancel(); it.hold = null }
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
