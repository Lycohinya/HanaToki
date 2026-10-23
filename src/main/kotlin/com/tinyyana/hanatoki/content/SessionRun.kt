package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.api.port.CuePort
import com.tinyyana.hanatoki.api.port.CueRun
import com.tinyyana.hanatoki.api.port.DialogPort
import com.tinyyana.hanatoki.api.port.StageWorldMutator
import com.tinyyana.hanatoki.actor.ActorSpec
import com.tinyyana.hanatoki.reward.RunStats
import com.tinyyana.hanatoki.map.MapAssetId
import com.tinyyana.hanatoki.map.MapLayout
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MapPos
import com.tinyyana.hanatoki.map.MarkerKind
import com.tinyyana.hanatoki.map.PlacedMap
import com.tinyyana.hanatoki.map.PlacedPiece
import com.tinyyana.hanatoki.folia.PlayerOp
import com.tinyyana.hanatoki.hud.ActionBars
import com.tinyyana.hanatoki.presentation.BossModelBones
import com.tinyyana.hanatoki.presentation.BossModelHandle
import com.tinyyana.hanatoki.stage.StageContext
import com.destroystokyo.paper.entity.ai.GoalType
import net.kyori.adventure.text.Component
import org.bukkit.attribute.Attribute
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
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
import java.util.function.Consumer
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

    @Volatile
    private var now = 0L
    private val timers = TimerQueue()
    private var markers: Map<String, MapMarker> = emptyMap()
    private var spawnGroups: Map<String, List<MapMarker>> = emptyMap()

    private val lanterns = LinkedHashMap<String, LanternRt>()
    private val gates = LinkedHashMap<String, GateRt>()
    private val encounters = LinkedHashMap<String, EncounterRt>()
    private val enemies = ConcurrentHashMap<String, EnemyRt>()
    private val powerUps = LinkedHashMap<String, PowerUpRt>()

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
    /** rc.26:這個 stage 的 `arrive` 是否已觸發(沒寫 arrive 的 stage 一開始就是 true);換 stage 重設。 */
    private var arrived = true
    private var arriveProbePending = false
    /** rc.26:stage 有 arrive 時，這個 stage 的 boss 等抵達才出場。 */
    private var arrivalBoss: BossSpec? = null
    private var arriveDialogRun: ArriveDialogRun? = null
    private val nearProbePending = HashSet<String>()

    private val cueRuns = ConcurrentLinkedDeque<CueRun>()

    /** 取樣用的短促 cue(boss 迴旋的光束標示)只留最近 [MAX_TRANSIENT_CUE_RUNS] 個,局結束一併取消;只在 anchor region 執行緒使用。 */
    private val transientRuns = java.util.ArrayDeque<CueRun>()
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
    /** rc.30:打散提示([DispersedTip])送出後到這個 tick 之前不重畫目標列,免得下一個 tick 就把短句蓋掉。 */
    private var objectiveHoldUntil = 0L
    private val toolSlots: Set<Int> = spec.tools.values.map { it.slot }.toSet()

    /** 需要每 10 tick 抓一次成員快照(位置、主手格)的功能是否有任何一個開著。 */
    private val presenceNeeded: Boolean = spec.tools.values.any { it.passiveRegenSeconds != null || it.zoneRegen != null } ||
        spec.objects.values.any { it is LanternSpec && it.hint != null } ||
        spec.hints.values.any { it.trigger is HintTrigger.UnequippedNearEnemy } ||
        spec.stages.any { st -> st.toolOverrides.values.any { it.passiveRegenSeconds != null || it.zoneRegenSeconds != null } } ||
        spec.stages.any { st -> st.objective.any { step -> core.texts.rawOrNull(step.textKey)?.contains("{nearest:") == true } }

    /** rc.27:最近一輪成員快照(presence 探測)，目標文字 `{nearest:power-up}` 算方向用。 */
    private var lastSnap: Map<UUID, DoubleArray> = emptyMap()

    // 敵人規則 seeks-object／harasses-players:兩者共用同一輪有界掃描。內容沒有任何敵人用到時 scanPeriod = 0,整套不動。
    private val touch = TouchCooldowns()
    /** chases-players 內嵌的接觸效果自己一份冷卻,不和 harasses-players 共用。 */
    private val chaseTouch = TouchCooldowns()
    private val scanPeriod: Long = spec.enemies.values
        .flatMap {
            listOfNotNull(
                it.seeksObject?.repathTicks, it.contact?.scanTicks, it.frozenWhenWatched?.scanTicks,
                it.chasesPlayers?.repathTicks, it.fleesWhenPowered?.repathTicks,
            )
        }.minOrNull()?.toLong() ?: 0L
    private val random = java.util.Random()
    private var raycastFailureLogged = false

    // powered(power-up 物件觸發的全隊狀態)與最後一段尾聲
    private val powered = PoweredTracker()
    /** rc.25:這個 stage 開始後有人吃過 power-up／某種敵人被打散或碰散過(objective 條件 powered-used、enemy-dispersed;換 stage 清掉)。 */
    private var poweredUsed = false
    private val dispersedEnemies = HashSet<String>()
    /** rc.25:收到過目標列(objective)的成員;局結束、離場、換到沒有目標的 stage 時要把動作列清掉,不讓最後一則停留。 */
    private val objectiveHud = ObjectiveHudTracker()
    private var poweredSource: PowerUpSpec? = null
    private val poweredCueRuns = ArrayList<CueRun>()
    /** 給指令執行緒讀的 powered 快照:成員 -> 結束的 tick。 */
    @Volatile
    private var poweredView: Map<UUID, Long> = emptyMap()
    private var poweredOrigin: Location? = null
    private var epilogueRun: EpilogueRun? = null
    private var scanPending = false
    private var scanAt = 0L
    private var lastScanAt = -1000L
    /** 敵人掃描的輪替游標:上一輪視窗最後一隻的 actorId(超過單輪上限時，下一輪從它之後接著掃)。 */
    private var scanCursor: String? = null

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
        /** rc.30:遠距光束照著這盞燈的成員 -> 光束續到哪個 tick。 */
        val beamers = HashMap<UUID, Long>()
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

    private class PowerUpRt(val spec: PowerUpSpec, val machine: PowerUpMachine, val marker: MapMarker, val loc: Location) {
        var probing = false
        var probeAt = 0L
        /** rc.25:在架上期間持續播的標示 cue(beacon-cue)。 */
        var beacon: CueRun? = null
        /** rc.27:浮空實體 prop：已要求生成／生成完成可以擺姿勢／下一次擺姿勢的 tick／第幾個半週期。 */
        var propOn = false
        var propReady = false
        var propNextPose = 0L
        var propPhase = 0
        var propWarned = false
    }

    /** 最後一段結算前的尾聲:[token] 每換一步加一,舊步驟的計時與對話回呼靠它失效。 */
    private class EpilogueRun(val stage: StageSpec, val resolve: ResolveSpec) {
        val sequence = EpilogueSequence(resolve.epilogue)
        var token = 0
        var scheduledToken = -1
        var wait: DialogWait? = null
    }

    /** rc.26:stage `on-arrive.dialogs` 的播放進度。[token] 每換一段加一，舊段的計時與對話回呼靠它失效。 */
    private class ArriveDialogRun(val stageId: String, val dialogs: List<String>, val timeoutSeconds: Double) {
        var index = 0
        var token = 0
        var wait: DialogWait? = null
    }

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
        /** rc.26:出生(載體生成完成)的 tick;chases-players.start-delay-seconds 用。 */
        var bornAt = -1L
        /** rc.28:人偶一出生就先凍結(第一輪掃描前不先走兩步)；第一次判定時補記 doll-frozen／doll-pose log。 */
        var initialFreeze = false
        var lastWhiff = -1000L
        /** 跟著敵人的狀態標示 cue(活著 / 僵直中),換狀態時換掉。 */
        var markRun: CueRun? = null
        /** seeks-object:上次下尋路指令的 tick;[seekLogged] 讓 enemy-seek log 每隻只打一次。 */
        var lastRepath = -1000L
        @Volatile var seekLogged = false
        /** frozen-when-watched:凍結旗標、姿勢記憶、聲音冷卻。 */
        val freeze = FreezeState()
        /** chases-players:目前鎖定的成員與上次下追擊路徑的 tick。 */
        var chaseTarget: UUID? = null
        var lastChase = -1000L
        /** rc.31 snuff-lamps:正在去吹的燈、到燈邊的 tick 與最後一次在燈邊被掃到的 tick、下次可擲骰決定的 tick、吹完後的冷卻到期 tick。 */
        var snuffLamp: String? = null
        var snuffArrivedAt = -1L
        var snuffSeenAt = -1L
        var snuffNextDecideAt = 0L
        var snuffCooldownUntil = 0L
        /** rc.31 retreat:退開到哪個 tick 為止、要遠離的點(x,z)與上次下退開路徑的 tick。 */
        var retreatUntil = -1L
        var retreatX = 0.0
        var retreatZ = 0.0
        var lastRetreat = -1000L
        /** rc.31 pressure:上次播逼近 cue 的 tick,與是否已記過 enemy-pressure log(退到範圍外才重置)。 */
        var pressureAt = -1000L
        var pressured = false
        /** rc.31 rush:人偶正在加速(靠近成員、沒被看著)與上次播加速喀喀聲的 tick。 */
        var rushing = false
        var rushCueAt = -1000L
        /** flees-when-powered:正在逃(換了外觀)與上次下逃跑路徑的 tick。 */
        var fleeing = false
        var lastFlee = -1000L
        /** frozen-when-watched:判定的流水號(較舊的回呼不覆蓋較新的)。 */
        var scanSeq = 0
        var verdictSeq = 0
    }

    private fun log(event: String, detail: String) {
        core.plugin.logger.info("[HanaToki/$dungeonId] $event${if (detail.isEmpty()) "" else " $detail"} slot=$slotId")
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
    private fun cue(id: String?, origin: Location, follow: Entity? = null, only: Collection<Player>? = null, track: Boolean = true): CueRun? {
        if (id == null || closed) return null
        val port: CuePort = runtime.cuePort() ?: run { noteNoPort("CuePort"); return null }
        chatFor(id, only)
        return try {
            val run = port.play(spec.namespace, id, origin, only ?: audience(), follow, mutator)
            if (track) {
                cueRuns.add(run)
            } else {
                transientRuns.addLast(run)
                while (transientRuns.size > MAX_TRANSIENT_CUE_RUNS) transientRuns.pollFirst()
            }
            run
        } catch (t: Throwable) {
            warn("cue $id 播放失敗:${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** rc.26 chat.yml:這個 cue 有對應的聊天室台詞就送給聽眾([only] 或全體成員);delay-seconds 之後才送。 */
    private fun chatFor(cueId: String, only: Collection<Player>?) {
        val line = spec.chat[cueId] ?: return
        val targets: List<UUID> = only?.map { it.uniqueId } ?: members().toList()
        val send = Runnable { if (!closed) for (id in targets) ctx.message(id, line.textKey) }
        val delay = (line.delaySeconds * 20).toLong()
        if (delay <= 0L) send.run() else timers.schedule(now, delay) { send.run() }
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
                is PowerUpSpec -> powerUps[obj.id] = PowerUpRt(
                    obj,
                    PowerUpMachine(obj.consume, (obj.respawnSeconds * 20).toLong(), (obj.seconds * 20).toLong()),
                    m, m.location(world),
                )
            }
        }
        for (enc in spec.encounters.values) encounters[enc.id] = EncounterRt(enc)
        // 初始狀態(unlit/closed)的方塊外觀
        for (rt in lanterns.values) rt.spec.states[ObjectStates.LANTERN_UNLIT]?.let { applyBlocks(marker(rt.spec.markerId), it.blocks) }
        for (rt in gates.values) rt.spec.states[ObjectStates.GATE_CLOSED]?.let { applyBlocks(rt.marker, it.blocks) }
        for (rt in powerUps.values) rt.spec.states[ObjectStates.POWER_UP_READY]?.let { applyBlocks(rt.marker, it.blocks) }
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
        clearTransient("stage")
        poweredUsed = false
        dispersedEnemies.clear()
        if (stage.objective.isEmpty()) clearObjectiveHud(objectiveHud.drain())
        // boss 屬於它的 stage:離開時倒下的人還原、載體移除;進入有 boss 的 stage 時出場
        stopBoss("stage", despawn = true)
        arriveDialogRun = null
        arrived = stage.arrive == null
        arriveProbePending = false
        arrivalBoss = null
        applyToolOverrides(stage)
        spec.bosses.values.firstOrNull { it.stage == stageId }?.let {
            if (it.id !in defeatedBosses) { if (arrived) startBoss(it, held = false) else arrivalBoss = it }
        }
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

    /** rc.26:抵達區觸發。每個 stage 一次：記 log、重置無進展計時、播 on-arrive、開對話序列、讓 boss 出場，最後檢查等抵達的波次。 */
    private fun onArrive(stage: StageSpec) {
        if (closed || arrived || currentStage?.id != stage.id) return
        arrived = true
        log("stage-arrive", stage.id)
        markProgress()
        objectiveDirty = true
        playEffects(stage.onArrive, stage, "on-arrive")
        val sequence = stage.onArrive.dialogs.isNotEmpty() && startArriveDialogs(stage)
        arrivalBoss?.let { startBoss(it, held = sequence && it.intro?.awaitArriveDialogs == true) }
        arrivalBoss = null
        checkTriggers()
        evaluateStage()
    }

    // ---- stage 抵達後的對話序列(rc.26 on-arrive.dialogs)----

    private fun startArriveDialogs(stage: StageSpec): Boolean {
        if (runtime.dialogPort() == null) { noteNoPort("DialogPort"); return false }
        val run = ArriveDialogRun(stage.id, stage.onArrive.dialogs, stage.onArrive.dialogTimeoutSeconds)
        arriveDialogRun = run
        log("arrive-dialogs-start", "stage=${stage.id} count=${run.dialogs.size}")
        runArriveDialog(run)
        return arriveDialogRun === run
    }

    private fun runArriveDialog(run: ArriveDialogRun) {
        if (closed || arriveDialogRun !== run) return
        if (run.index >= run.dialogs.size) { finishArriveDialogs(run); return }
        val dialogId = run.dialogs[run.index]
        val token = ++run.token
        val port: DialogPort = runtime.dialogPort() ?: run { finishArriveDialogs(run); return }
        val ids = members()
        if (ids.isEmpty()) { finishArriveDialogs(run); return }
        run.wait = DialogWait(ids)
        for (playerId in ids) {
            ctx.runOnMember(playerId) { player ->
                try {
                    port.open(player, spec.namespace, dialogId, IntConsumer { choice ->
                        log("dialog", "$dialogId (on-arrive/${run.stageId}) player=${nameOf(playerId)} choice=$choice")
                        ctx.submit { onArriveDialogDone(run, token, playerId) }
                    })
                } catch (t: Throwable) {
                    warn("on-arrive dialog $dialogId 開啟失敗:${t.javaClass.simpleName}: ${t.message}")
                    ctx.submit { onArriveDialogDone(run, token, playerId) }
                }
            }
        }
        // 有人一直不按(斷線、關掉對話)也不能卡住後面的 boss 出場
        timers.schedule(now, (run.timeoutSeconds * 20).toLong()) {
            if (!closed && arriveDialogRun === run && run.token == token && run.wait != null) {
                log("arrive-dialog-timeout", "stage=${run.stageId} dialog=$dialogId waiting=${run.wait?.size ?: 0}")
                run.wait = null
                run.index++
                runArriveDialog(run)
            }
        }
    }

    private fun onArriveDialogDone(run: ArriveDialogRun, token: Int, playerId: UUID) {
        if (closed || arriveDialogRun !== run || run.token != token) return
        run.wait?.done(playerId)
        checkArriveDialogDone(run)
    }

    private fun checkArriveDialogDone(run: ArriveDialogRun) {
        val wait = run.wait ?: return
        if (!wait.complete) return
        run.wait = null
        run.index++
        runArriveDialog(run)
    }

    private fun finishArriveDialogs(run: ArriveDialogRun) {
        if (arriveDialogRun !== run) return
        arriveDialogRun = null
        markProgress()
        log("arrive-dialogs-done", "stage=${run.stageId}")
        boss?.releaseIntro()
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
        val openDialog = Runnable {
            if (!closed && currentStage?.id == stage.id) for (playerId in members()) {
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
        // rc.28 dialog-delay-seconds:先讓敵人現身，過幾秒使魔才開口
        val delayTicks = (effects.dialogDelaySeconds * 20).toLong()
        if (delayTicks > 0L) timers.schedule(now, delayTicks) { openDialog.run() } else openDialog.run()
    }

    private fun conditionMet(c: Condition): Boolean = when (c) {
        is Condition.EncounterCleared -> encounters[c.encounter]?.phase == EncounterRt.Phase.DONE
        is Condition.ObjectState -> objectReached(c.objectId, c.state)
        is Condition.Reach -> c.marker in reached
        is Condition.BossDefeated -> c.boss in defeatedBosses
        Condition.Powered -> powered.poweredIds(now).isNotEmpty()
        is Condition.BossKneel -> boss?.let { it.spec.id == c.boss && it.state == BossState.KNEELING } == true
        Condition.PoweredUsed -> poweredUsed
        is Condition.EnemyDispersed -> c.enemy in dispersedEnemies
        Condition.Arrived -> arrived
        Condition.LanternBurning -> lanterns.values.any { it.spec.stage == currentStage?.id && it.machine.state == LanternMachine.State.BURNING }
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
        powerUps[objectId]?.let { rt ->
            return when (state) {
                ObjectStates.POWER_UP_READY -> true
                ObjectStates.POWER_UP_TAKEN -> rt.machine.state == PowerUpMachine.State.TAKEN
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
        // rc.24:stage 完成直接開門(gates 與 timers 跨 stage 存在,滑門不會被轉場砍掉)
        for (gate in stage.onComplete.opens) gates[gate]?.let(::openGate)
        if (stage.clearsEnemies) dissolveAllEnemies()
        val next = stage.next
        if (next != null) {
            ctx.transition(next)
        } else if (stage.resolve != null) {
            startEpilogue(stage, stage.resolve)
        } else {
            // 沒有 resolve 開關:切片到此為止,不結算、不發獎(舊行為)。
            log("slice end", stage.id)
        }
    }

    // ---- 週期任務 ----

    private fun tick() {
        if (closed) return
        now += TICK_PERIOD
        timers.runDue(now) { warn("計時任務丟出例外:${it.javaClass.simpleName}: ${it.message}") }
        tickLanterns()
        tickPowerUps()
        tickPowered()
        boss?.tick()
        tickEnemyRules()
        if (now % 10L == 0L) probeMarkers()
        // rc.28:boss 模型補送。BetterModel 偶爾丟掉整個 tick 的封包(生成封包剛好被丟 = 該玩家看不到模型)，每秒檢查一次
        if (now % 20L == 0L) bossModel?.ensureVisibleTo(members())
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
            // rc.30:遠距光束續著的每位成員各加 beam.speed 倍；倒下的、過期的不算
            var beamBonus = 0.0
            val beam = spec.beam
            if (beam != null && rt.beamers.isNotEmpty()) {
                rt.beamers.entries.removeIf { it.value <= now || isDowned(it.key) || it.key !in members() }
                beamBonus = rt.beamers.size * beam.speed
            }
            when (rt.machine.advance(TICK_PERIOD.toInt(), lanternMultiplier(spec.speedByPlayers, people) + beamBonus)) {
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
                val people = if (error != null) rt.people else (list?.count { !isDowned(it) } ?: 0)
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
        log("lantern-lit", spec.id)
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
        boss?.onLampLit(spec.id)
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
                Trigger.StageArrive -> arrived
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
        val group: List<Location> = enc.spec.spawnMarker?.let { id ->
            val o = enc.spec.spawnOffset
            listOf(marker(id).location(world).add(o.getOrElse(0) { 0.0 }, o.getOrElse(1) { 0.0 }, o.getOrElse(2) { 0.0 }))
        } ?: spawnGroups[enc.spec.markerGroup].orEmpty().map { it.location(world) }
        val players = members().size.coerceAtLeast(1)
        enc.phase = EncounterRt.Phase.SPAWNING
        enc.target = pickByPlayers(enc.spec.counts, players, enc.spec.counts.getValue(1))
        log("wave-start", "${enc.spec.id} count=${enc.target} players=$players offset=${enc.spec.markerOffset}")
        markProgress()
        group.firstOrNull()?.let { cues(enc.spec.startCues, it) }
        for (i in 0 until enc.target) {
            timers.schedule(now, i.toLong() * enc.spec.intervalTicks) { spawnEnemy(enc, group, i) }
        }
        if (enc.target == 0) maybeClear(enc)
    }

    private fun spawnEnemy(enc: EncounterRt, group: List<Location>, index: Int) {
        if (closed || enc.phase != EncounterRt.Phase.SPAWNING || enc.spawned >= enc.target || group.isEmpty()) return
        enc.spawned++
        val enemySpec = spec.enemies.getValue(enc.spec.enemy)
        val actorId = "${enc.spec.id}-${index + 1}"
        enc.alive += actorId
        val at = group[(enc.spec.markerOffset + index) % group.size].clone()
        spawnEnemyAt(actorId, enemySpec, at, enc.spec.id)
    }

    /** 生一隻敵人。[encounterId] = null 是 boss 召喚的(不屬於任何波次)。 */
    private fun spawnEnemyAt(actorId: String, enemySpec: EnemySpec, at: Location, encounterId: String?) {
        val rt = EnemyRt(actorId, enemySpec, encounterId)
        enemies[actorId] = rt
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
        rt.bornAt = now
        log("enemy-spawn", "${rt.actorId} enemy=${rt.spec.id} encounter=${rt.encounterId ?: "boss"}")
        cues(rt.spec.spawnCues, at, core.actorController.entityOf(sessionId, rt.actorId))
        setMark(rt, rt.spec.needsFlash?.aliveCue)
        if (rt.spec.frameworkMoves) {
            // 啃燈的、人偶、追擊者的移動完全由框架下的路徑指令決定:拿掉原版的鎖定目標與移動目標(近戰、閒晃),
            // 之後只聽掃描給的路徑。導航本身仍然會走
            ctx.actors().runOnEntity(rt.actorId) { e -> (e as? Mob)?.let { stripPlayerAi(rt, it) } }
        }
        // rc.28:人偶一生出來就是凍結的姿勢(原地不動)，第一輪掃描才決定要不要放開；不先走兩步才凍住
        rt.spec.frozenWhenWatched?.let { f ->
            rt.freeze.update(true)
            rt.freeze.pickPose(f.poses.size, random)
            rt.initialFreeze = true
            ctx.actors().runOnEntity(rt.actorId) { e -> (e as? LivingEntity)?.setAI(false) }
        }
        if (powered.active && rt.spec.fleesWhenPowered != null) setFleeing(rt, true)
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
                // 模型綁好前已經凍結或在逃跑(powered 期間生成)就補上對應動畫
                if (handle != null && (rt.freeze.frozen || rt.fleeing)) syncBody(rt)
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
        boss?.onSummonGone(rt.actorId)
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
                (e as? Mob)?.pathfinder?.stopPathfinding()
                e.isGlowing = nf.glowOnStun
                rt.model?.play(rt.spec.animations.stunned, true, null)
            }
        }
        timers.schedule(now, (tool.stunSeconds * 20).toLong().coerceAtLeast(1)) { recoverEnemy(rt, epoch) }
    }

    private fun recoverEnemy(rt: EnemyRt, epoch: Int) {
        if (closed || !rt.machine.recover(epoch)) return
        log("enemy-recover", rt.actorId)
        setMark(rt, if (rt.fleeing) rt.spec.fleesWhenPowered?.poweredCue else rt.spec.needsFlash?.aliveCue)
        // 還被看著(凍結)就留在凍結,不能因為僵直結束就動起來
        syncBody(rt)
        ctx.actors().runOnEntity(rt.actorId) { e -> e.isGlowing = false }
    }

    /**
     * 敵人的 AI 與模型動畫一律由狀態決定:AI 只在 ALIVE 且沒凍結時開;動畫依序是僵直、凍結姿勢、逃跑、移動、預設。
     * 僵直結束、逃跑結束、stage 轉換後都走這裡,避免各處各自 setAI(true) 把凍結中的敵人放出來。
     */
    private fun syncBody(rt: EnemyRt) {
        val frozenSpec = rt.spec.frozenWhenWatched
        val plan = bodyPlan(rt.machine.state, rt.freeze.frozen, rt.fleeing, frozenSpec != null) ?: return
        val animations = rt.spec.animations
        val animation = when (plan.anim) {
            BodyAnim.STUNNED -> animations.stunned
            BodyAnim.POSE -> frozenSpec?.poses?.getOrNull(rt.freeze.poseIndex)?.animation ?: animations.idle
            BodyAnim.FLEEING -> rt.spec.fleesWhenPowered?.animation ?: animations.idle
            BodyAnim.MOVING -> frozenSpec?.movingAnimation ?: animations.idle
            BodyAnim.IDLE -> animations.idle
        }
        val aiOn = plan.aiOn
        ctx.actors().runOnEntity(rt.actorId) { e ->
            (e as? LivingEntity)?.setAI(aiOn)
            if (!aiOn) (e as? Mob)?.pathfinder?.stopPathfinding()
            rt.model?.play(animation, true, null)
        }
    }

    /** 打散。[killer] 非 null 才補燈火(光圈清場不補)。回傳是否真的打散(只會發生一次)。 */
    private fun dissolve(rt: EnemyRt, killer: UUID?, loc: Location?, reversal: Boolean = false): Boolean {
        val nf = rt.spec.needsFlash
        val flee = rt.spec.fleesWhenPowered
        if (!rt.machine.dissolve()) return false
        val at = loc ?: rt.lastLoc
        log("enemy-dissolve", "${rt.actorId} enemy=${rt.spec.id} by=${killer?.let(::nameOf) ?: "lantern"}")
        if (rt.spec.frozenWhenWatched != null && killer != null) log("doll-dispersed", "${rt.actorId} by=${nameOf(killer)}")
        // 玩家打散或 powered 碰散才算「打散過」;光圈／stage 清場(killer 為 null 且不是 reversal)不算
        if (killer != null || reversal) {
            dispersedEnemies += rt.spec.id
            if (killer != null) recordDispersed(killer)
            fireHints { (it.trigger as? HintTrigger.EnemyDispersed)?.enemy == rt.spec.id }
        }
        markProgress()
        setMark(rt, null)
        rt.fleeing = false
        if (at != null) {
            rt.lastLoc = at
            cue(if (reversal) flee?.dissolveCue ?: nf?.dissolveCue else nf?.dissolveCue ?: flee?.dissolveCue, at, core.actorController.entityOf(sessionId, rt.actorId))
        }
        ctx.actors().runOnEntity(rt.actorId) { e ->
            (e as? LivingEntity)?.setAI(false)
            e.isGlowing = false
            rt.model?.play(rt.spec.animations.dissolve, false, null)
        }
        // 閃光打散補燈火;powered 時被碰散不補
        if (killer != null && !reversal) refill(killer, nf?.refillCue, at)
        if (killer != null) showDispersedTip(killer, rt)
        val ticks = if (reversal) flee?.dissolveTicks ?: nf?.dissolveTicks else nf?.dissolveTicks ?: flee?.dissolveTicks
        timers.schedule(now, (ticks ?: 12).toLong()) { removeEnemy(rt) }
        return true
    }

    /**
     * rc.30:統計「每人打散幾隻」給結算用(`RunStats.DISPERSED` 整局合計與逐人鍵)。
     * 只有 [killer] 非 null 的打散會進來(光圈與 stage 清場、Boss 召喚物收場都不算);用 [InstanceState.stats],在 [StageContext.submit] 的執行線上寫。
     */
    private fun recordDispersed(killer: UUID) {
        RunStats.record(ctx.state.stats, RunStats.DISPERSED, killer)
    }

    /**
     * 依敵人的 `dispersed-tip` 對這位玩家送 ActionBar 預告(累計第 every 隻、2×every 隻…)。
     * 必須在補燈火([refill] → 工具 HUD)**之後**送:兩者共用同一格 ActionBar,最新一則贏。
     */
    private fun showDispersedTip(killer: UUID, rt: EnemyRt) {
        val tip = rt.spec.dispersedTip ?: return
        val mine = ctx.state.stats[RunStats.playerKey(RunStats.DISPERSED, killer)] ?: return
        if (!tip.due(mine)) return
        ctx.actionBar(killer, tip.textKey, mapOf("count" to tip.count.toString()))
        objectiveHoldUntil = maxOf(objectiveHoldUntil, now + (tip.holdSeconds * 20).toLong())
        log("dispersed-tip", "player=${nameOf(killer)} total=$mine text=${tip.textKey}")
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
        // 超過單輪上限時輪替(round-robin):從上一輪視窗最後一隻之後接著掃，每隻都會輪到
        val eligible = enemies.values.filter { it.spec.scanned && it.machine.state == EnemyMachine.State.ALIVE }
        val candidates = rotatingWindow(eligible, scanCursor, MAX_SCAN_ENEMIES) { it.actorId }
        val nextCursor: String? = if (eligible.size > MAX_SCAN_ENEMIES) candidates.lastOrNull()?.actorId else null
        if (candidates.isEmpty()) { scanCursor = null; publishDrain(emptyMap()); return }
        scanPending = true
        scanAt = now
        val startedAt = now
        val locations = candidates.map { rt -> ctx.actors().locationOf(rt.actorId).handle { loc, _ -> rt to loc } }
        val snapshots: CompletableFuture<Map<UUID, DoubleArray>> =
            if (candidates.any { it.spec.needsSnapshots }) ctx.memberSnapshots().handle { m, e -> if (e != null) emptyMap() else m ?: emptyMap() }
            else CompletableFuture.completedFuture(emptyMap())
        CompletableFuture.allOf(snapshots, *locations.toTypedArray()).whenComplete { _, _ ->
            ctx.submit {
                if (scanAt != startedAt) return@submit // 較新的一輪已取代(這輪回得太晚)
                scanPending = false
                if (closed) return@submit
                // 這一輪真的套用了才推進游標（太晚回來被丟掉的一輪，下一輪重掃同一批）
                scanCursor = nextCursor
                applyEnemyScan(locations.mapNotNull { it.getNow(null) }, withoutDowned(snapshots.getNow(emptyMap())))
            }
        }
    }

    private fun applyEnemyScan(found: List<Pair<EnemyRt, Location?>>, snap: Map<UUID, DoubleArray>) {
        // (燈籠 id, 敵人定義 id) -> 這輪在啃的隻數
        val contest = LinkedHashMap<Pair<String, String>, Int>()
        val poweredNow = powered.poweredIds(now)
        for ((rt, loc) in found) {
            if (loc == null || loc.world != world || rt.machine.state != EnemyMachine.State.ALIVE) continue
            rt.lastLoc = loc
            rt.spec.seeksObject?.let { seekStep(rt, loc, it, contest) }
            // powered 期間:會逃的敵人改逃離 powered 成員(碰到就被打散),不追、不碰人
            val flee = rt.spec.fleesWhenPowered
            if (flee != null && poweredNow.isNotEmpty()) { fleeStep(rt, loc, flee, snap, poweredNow); continue }
            if (rt.fleeing) setFleeing(rt, false)
            // rc.26:出生後的緩衝期原地不動(不追、不碰人)，讓開場先播完
            val chase = rt.spec.chasesPlayers
            if (chase != null && rt.bornAt >= 0 && now - rt.bornAt < (chase.startDelaySeconds * 20).toLong()) continue
            rt.spec.chasesPlayers?.let { chaseStep(rt, loc, it, snap) }
            rt.spec.frozenWhenWatched?.let { frozenStep(rt, loc, it, snap) }
            // rc.28 harmless-while-frozen:被看著凍結的人偶不推人、不扣燈火
            val harmless = rt.freeze.frozen && rt.spec.frozenWhenWatched?.harmlessWhileFrozen == true
            if (!harmless) rt.spec.contact?.let {
                val hit = harassStep(rt, loc, it, snap, if (rt.spec.harassesPlayers != null) touch else chaseTouch)
                // rc.31 retreat:碰到人之後退開一段(chases-players 才有)
                val retreat = rt.spec.chasesPlayers?.retreat
                if (hit != null && retreat != null) {
                    rt.retreatUntil = now + (retreat.seconds * 20).toLong()
                    rt.retreatX = hit[0]
                    rt.retreatZ = hit[2]
                    rt.lastRetreat = -1000L
                    log("enemy-retreat", "${rt.actorId} seconds=${fmtSeconds(retreat.seconds)}")
                }
            }
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

    /** 回最後一位被碰到的成員快照位置(沒人被碰到 = null);rc.31 retreat 用它決定往哪退。 */
    private fun harassStep(rt: EnemyRt, loc: Location, h: HarassSpec, snap: Map<UUID, DoubleArray>, cooldowns: TouchCooldowns): DoubleArray? {
        if (snap.isEmpty()) return null
        val current = members().toSet()
        var hit: DoubleArray? = null
        for ((playerId, pos) in snap) {
            if (playerId !in current) continue
            if (!withinContact(pos[0] - loc.x, pos[1] - loc.y, pos[2] - loc.z, h.contactRadius)) continue
            if (!cooldowns.tryTouch(playerId, now, h.cooldownTicks)) continue
            applyHarass(rt, loc, h, playerId, pos)
            hit = pos
        }
        return hit
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
                val tool = effectiveTools[effect.tool] ?: continue
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
            is HarassEffect.DamagePercent -> {
                // 固定比例、不看護甲與抗性:直接改血量，最多扣到剩 minHealth(不致死)；血量在成員快照裡讀，沒血可扣就不記
                if (pos[HEALTH] - effect.minHealth > 0.0) {
                    ctx.runOnMember(playerId) { p ->
                        if (p.isDead) return@runOnMember
                        val max = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                        val amount = minOf(max * effect.percent, p.health - effect.minHealth)
                        if (amount > 0.0) {
                            p.health = p.health - amount
                            runCatching { p.playHurtAnimation(0f) }
                        }
                    }
                    applied += "damage%${fmt2(effect.percent)}"
                }
            }
        }
        log("enemy-touch", "${rt.actorId} player=${nameOf(playerId)} effect=${if (applied.isEmpty()) "none" else applied.joinToString("+")}")
        cues(h.cues, loc, core.actorController.entityOf(sessionId, rt.actorId))
    }

    /** 把燈重置成未點亮(狀態機、方塊外觀、持續 cue、世界標示)。boss 的場邊燈與 rc.31 的 snuff-lamps 都走這裡。 */
    private fun resetLantern(lamp: String) {
        val rt = lanterns[lamp] ?: return
        rt.machine.reset()
        rt.hold?.cancel(); rt.hold = null
        rt.people = -1
        rt.probing = false
        rt.milestonesFired = 0
        rt.beamers.clear()
        rt.drainRate = 0.0; rt.drainers = 0; rt.contestedSince = -1
        clearLanternHints(lamp)
        rt.spec.states[ObjectStates.LANTERN_UNLIT]?.let { applyBlocks(marker(rt.spec.markerId), it.blocks) }
        log("object", "$lamp reset->unlit")
    }

    // ---- 敵人規則:frozen-when-watched / chases-players / flees-when-powered ----

    /** 視錐內的成員:眼睛位置與到敵人的距離。 */
    private class Watcher(val id: UUID, val x: Double, val eyeY: Double, val z: Double, val distance: Double)

    /**
     * 人偶:有人看著就凍結,沒人看就朝最近的成員走。視錐在 anchor 執行緒用成員快照算(純幾何);
     * 遮擋(raytrace 方塊)只對已在視錐與距離內的候選、在敵人自己的 region 上算,結果與 AI 開關在同一個回呼裡套用。
     */
    private fun frozenStep(rt: EnemyRt, loc: Location, f: FrozenWhenWatchedSpec, snap: Map<UUID, DoubleArray>) {
        // 成員快照沒回來(失敗或全員不在副本世界)= 不知道有沒有人在看,維持現狀,不能趁這時放開人偶
        if (snap.isEmpty()) return
        val current = members().toSet()
        val cx = loc.x
        val cy = loc.y + f.bodyHeight
        val cz = loc.z
        val watchers = ArrayList<Watcher>()
        var nearest: DoubleArray? = null
        var nearestDistance = Double.MAX_VALUE
        for ((id, pos) in snap) {
            if (id !in current) continue
            val d = distance(pos, loc)
            if (d < nearestDistance) { nearestDistance = d; nearest = pos }
            val eyeY = pos[1] + eyeHeight(pos[SNEAKING] > 0.5)
            if (inViewCone(pos[0], eyeY, pos[2], pos[3], pos[4], cx, cy, cz, f.halfAngle, f.maxDistance)) {
                watchers += Watcher(id, pos[0], eyeY, pos[2], d)
            }
        }
        watchers.sortBy { it.distance }
        val destination = nearest?.let { Location(world, it[0], it[1], it[2]) }
        val scanId = ++rt.scanSeq
        // rc.31 rush:最近的成員在距離內 = 沒人看著時加速(被看著仍凍結)
        val nearDistance: Double? = if (nearest != null) nearestDistance else null
        val moveSpeed = rushSpeed(f.speed, f.rush, nearDistance)
        val rushing = f.rush != null && nearDistance != null && nearDistance <= f.rush.distance
        ctx.actors().runOnEntity(rt.actorId) { e ->
            var watcher: UUID? = null
            var failed = false
            if (watchers.isNotEmpty()) {
                if (!f.occlusion) {
                    watcher = watchers.first().id
                } else {
                    try {
                        watcher = firstClearWatcher(e, watchers, f.bodyHeight)
                    } catch (t: Throwable) {
                        // 射線跨出這個 region 或讀取失敗:寧可當作被看到(凍結),偏向玩家,並留一行 WARN
                        failed = true
                        watcher = watchers.first().id
                    }
                }
            }
            if (rt.machine.state == EnemyMachine.State.ALIVE) {
                val mob = e as? Mob
                if (watcher != null) {
                    (e as? LivingEntity)?.setAI(false)
                    mob?.pathfinder?.stopPathfinding()
                } else {
                    (e as? LivingEntity)?.setAI(true)
                    if (destination != null) mob?.pathfinder?.moveTo(destination, moveSpeed) else mob?.pathfinder?.stopPathfinding()
                }
            }
            val verdict = watcher
            val raycastFailed = failed
            ctx.submit { onWatchVerdict(rt, scanId, verdict, raycastFailed, f, if (rushing) nearDistance else null) }
        }
    }

    /** 在敵人自己的執行緒上:第一個「視線沒有被方塊擋住」的候選(依距離由近到遠)。跨出本 region 的射線丟例外,由呼叫端當作被看到。 */
    private fun firstClearWatcher(e: Entity, watchers: List<Watcher>, bodyHeight: Double): UUID? {
        val here = e.location
        val w = e.world
        val tx = here.x
        val ty = here.y + bodyHeight
        val tz = here.z
        for (cand in watchers) {
            val dx = tx - cand.x
            val dy = ty - cand.eyeY
            val dz = tz - cand.z
            val length = Math.sqrt(dx * dx + dy * dy + dz * dz)
            if (length < 0.8) return cand.id
            val minX = Math.floor(minOf(cand.x, tx)).toInt() shr 4
            val maxX = Math.floor(maxOf(cand.x, tx)).toInt() shr 4
            val minZ = Math.floor(minOf(cand.z, tz)).toInt() shr 4
            val maxZ = Math.floor(maxOf(cand.z, tz)).toInt() shr 4
            // 簽章 isOwnedByCurrentRegion(World, minChunkX, minChunkZ, maxChunkX, maxChunkZ)——區塊座標、min 的 X、Z 在前，max 的 X、Z 在後:
            // Lecithin 原始碼 paper-api/src/main/java/org/bukkit/Bukkit.java:2973 與 Server.java:2755(參數名 minChunkX/minChunkZ/maxChunkX/maxChunkZ，
            // 語意是 x∈[minChunkX,maxChunkX]、z∈[minChunkZ,maxChunkZ] 的每個區塊都屬於目前 ticking 的 region)。這裡的 shr 4 就是方塊座標換區塊座標。
            check(Bukkit.isOwnedByCurrentRegion(w, minX, minZ, maxX, maxZ)) { "射線跨出這個 region" }
            val hit = w.rayTraceBlocks(
                Location(w, cand.x, cand.eyeY, cand.z), Vector(dx / length, dy / length, dz / length), length - 0.3,
                FluidCollisionMode.NEVER, true,
            )
            if (hit == null) return cand.id
        }
        return null
    }

    /** anchor 執行緒:套用人偶這一輪的判定結果,狀態轉換才打 log／播 cue／換姿勢。 */
    private fun onWatchVerdict(rt: EnemyRt, scanId: Int, watcher: UUID?, raycastFailed: Boolean, f: FrozenWhenWatchedSpec, rushDistance: Double? = null) {
        if (closed || scanId <= rt.verdictSeq || rt.machine.state != EnemyMachine.State.ALIVE) return
        rt.verdictSeq = scanId
        if (raycastFailed && !raycastFailureLogged) {
            raycastFailureLogged = true
            warn("人偶 ${rt.actorId} 的遮擋檢查失敗(射線跨出敵人所在的 region 或讀取失敗),這種情況一律當作被看到")
        }
        val entity = core.actorController.entityOf(sessionId, rt.actorId)
        val at = rt.lastLoc
        val initial = rt.initialFreeze
        rt.initialFreeze = false
        val change = rt.freeze.update(watcher != null)
        when (change) {
            FreezeState.Change.FROZE -> {
                val index = rt.freeze.pickPose(f.poses.size, random)
                log("doll-frozen", "${rt.actorId} watcher=${nameOf(watcher!!)}")
                log("doll-pose", "${rt.actorId} pose=${f.poses.getOrNull(index)?.id ?: "none"}")
                if (at != null) cues(f.freezeCues, at, entity)
                syncBody(rt)
            }
            FreezeState.Change.MOVED -> {
                // 喀喀聲播在人偶身上(follow 實體),才聽得出從哪個方向過來
                if (at != null && rt.freeze.moveCueDue(now, f.cueCooldownTicks)) cues(f.moveCues, at, entity)
                syncBody(rt)
            }
            FreezeState.Change.NONE -> {
                // 出生時就先凍結、第一次判定也被看著:補記「從移動轉凍結」該有的 log 與 cue(bot 以 doll-frozen 為準)
                if (initial && watcher != null) {
                    val index = rt.freeze.poseIndex
                    log("doll-frozen", "${rt.actorId} watcher=${nameOf(watcher)}")
                    log("doll-pose", "${rt.actorId} pose=${f.poses.getOrNull(index)?.id ?: "none"}")
                    if (at != null) cues(f.freezeCues, at, entity)
                }
            }
        }
        // rc.31 rush:移動中、最近成員在距離內 = 加速；進入時記 log，期間每隔 cueIntervalTicks 重播喀喀聲(有方位)
        val rush = f.rush
        if (rush != null && rushDistance != null && watcher == null && !rt.freeze.frozen) {
            if (!rt.rushing) {
                rt.rushing = true
                log("doll-rush", "${rt.actorId} distance=${fmt2(rushDistance)}")
            }
            if (change != FreezeState.Change.MOVED && at != null && now - rt.rushCueAt >= rush.cueIntervalTicks) {
                rt.rushCueAt = now
                cues(rush.cues, at, entity)
            }
        } else {
            rt.rushing = false
        }
    }

    /** 大幽靈:挑目標、朝目標走;沒有目標(超出索敵與放棄距離)就停下。 */
    private fun chaseStep(rt: EnemyRt, loc: Location, c: ChasesPlayersSpec, snap: Map<UUID, DoubleArray>) {
        // rc.31 retreat:碰到人之後退開一段時間,期間不追、不吹燈、不播逼近 cue
        c.retreat?.let { if (retreatStep(rt, loc, c, it)) return }
        c.pressure?.let { pressureStep(rt, loc, it, snap) }
        // rc.31 snuff-lamps:這輪改去吹燈(或守在燈邊)就不追人;接觸效果照常(applyEnemyScan 之後還會跑 harassStep)
        c.snuffLamps?.let { if (snuffStep(rt, loc, c, it)) return }
        if (now - rt.lastChase < c.repathTicks) return
        rt.lastChase = now
        val current = members().toSet()
        val candidates = snap.filter { it.key in current }.map { (id, pos) -> ChaseCandidate(id, distance(pos, loc), pos[HEALTH]) }
        val target = pickChaseTarget(candidates, c.target, c.sightRange, c.giveUpRange, rt.chaseTarget)
        if (target != rt.chaseTarget) {
            rt.chaseTarget = target
            log("enemy-chase", "${rt.actorId} target=${target?.let(::nameOf) ?: "none"}")
        }
        val pos = target?.let { snap[it] }
        val destination = pos?.let { Location(world, it[0], it[1], it[2]) }
        ctx.actors().runOnEntity(rt.actorId) { e ->
            val mob = e as? Mob ?: return@runOnEntity
            mob.target = null
            if (destination != null) mob.pathfinder.moveTo(destination, c.speed) else mob.pathfinder.stopPathfinding()
        }
    }

    /** rc.31:退開中(回 true,這輪不追):朝遠離碰到的那位成員的方向走 [RetreatSpec.distance] 格,被牆擋住依序偏轉角度。 */
    private fun retreatStep(rt: EnemyRt, loc: Location, c: ChasesPlayersSpec, r: RetreatSpec): Boolean {
        if (now >= rt.retreatUntil) return false
        if (now - rt.lastRetreat >= c.repathTicks) {
            rt.lastRetreat = now
            val speed = r.speed ?: c.speed
            val fromX = rt.retreatX
            val fromZ = rt.retreatZ
            ctx.actors().runOnEntity(rt.actorId) { e ->
                val mob = e as? Mob ?: return@runOnEntity
                mob.target = null
                for (angle in FLEE_ANGLES) {
                    val point = fleePoint(loc.x, loc.z, fromX, fromZ, r.distance, angle)
                    if (mob.pathfinder.moveTo(Location(world, point[0], loc.y, point[1]), speed)) break
                }
            }
        }
        return true
    }

    /** rc.31:逼近感。最近成員在 [PressureSpec.distance] 內,按距離決定的間隔在她身上(follow 實體)播 cue。 */
    private fun pressureStep(rt: EnemyRt, loc: Location, p: PressureSpec, snap: Map<UUID, DoubleArray>) {
        val current = members().toSet()
        var nearest = Double.MAX_VALUE
        for ((id, pos) in snap) if (id in current) nearest = minOf(nearest, distance(pos, loc))
        if (nearest > p.distance) { rt.pressured = false; return }
        if (!rt.pressured) {
            rt.pressured = true
            log("enemy-pressure", "${rt.actorId} distance=${fmt2(nearest)}")
        }
        if (now - rt.pressureAt < p.intervalAt(nearest)) return
        rt.pressureAt = now
        cues(p.cues, loc, core.actorController.entityOf(sessionId, rt.actorId))
    }

    /**
     * rc.31:霧鬼吹燈。回 true = 這輪這隻在去燈的路上或守在燈邊(不追人)；false = 照常追人。
     * 每 [SnuffLampsSpec.decideSeconds] 秒擲一次 [SnuffLampsSpec.preference]，中了就鎖定最近的合格燈直到吹完或燈不合格；
     * 飄到 [SnuffLampsSpec.reachRadius] 內停 [SnuffLampsSpec.dwellSeconds] 秒才吹(僵直的敵人不在掃描裡，所以被閃光照住就中斷)。
     */
    private fun snuffStep(rt: EnemyRt, loc: Location, c: ChasesPlayersSpec, s: SnuffLampsSpec): Boolean {
        if (now < rt.snuffCooldownUntil) return false
        val candidates = s.objects.mapNotNull { id ->
            val lantern = lanterns[id] ?: return@mapNotNull null
            if (!stageActive(lantern.spec.stage) || lantern.machine.state.name.lowercase() !in s.states) return@mapNotNull null
            if (boss?.lampSnuffable(id) == false) return@mapNotNull null
            val dx = lantern.loc.x - loc.x
            val dy = lantern.loc.y - loc.y
            val dz = lantern.loc.z - loc.z
            SnuffCandidate(id, Math.sqrt(dx * dx + dy * dy + dz * dz))
        }
        val committed = rt.snuffLamp
        val valid = committed != null && candidates.any { it.id == committed }
        if (committed != null && !valid) {
            rt.snuffLamp = null
            rt.snuffArrivedAt = -1L
            log("enemy-snuff-abort", "${rt.actorId} lamp=$committed")
        }
        if (!valid) {
            if (candidates.isEmpty() || now < rt.snuffNextDecideAt) return false
            rt.snuffNextDecideAt = now + (s.decideSeconds * 20).toLong()
        }
        val picked = pickSnuffLamp(candidates, if (valid) committed else null, s.preference, random.nextDouble()) ?: return false
        val lantern = lanterns[picked] ?: return false
        if (!valid) {
            rt.snuffLamp = picked
            rt.snuffArrivedAt = -1L
            log("enemy-snuff-seek", "${rt.actorId} lamp=$picked")
        }
        val near = withinContact(loc.x - lantern.loc.x, loc.y - lantern.loc.y, loc.z - lantern.loc.z, s.reachRadius, CONTEST_HEIGHT)
        if (near) {
            // 僵直期間不在掃描裡:隔太久才又掃到 = 被打斷過，重新計停留
            if (rt.snuffArrivedAt < 0L || now - rt.snuffSeenAt > maxOf(scanPeriod * 3L, 20L)) rt.snuffArrivedAt = now
            rt.snuffSeenAt = now
            if (now - rt.snuffArrivedAt >= (s.dwellSeconds * 20).toLong()) snuffLantern(rt, lantern, s)
            return true
        }
        rt.snuffArrivedAt = -1L
        if (now - rt.lastChase >= c.repathTicks) {
            rt.lastChase = now
            val target = lantern.loc.clone()
            val speed = s.speed ?: c.speed
            ctx.actors().runOnEntity(rt.actorId) { e ->
                val mob = e as? Mob ?: return@runOnEntity
                mob.target = null
                mob.pathfinder.moveTo(target, speed)
            }
        }
        return true
    }

    private fun snuffLantern(rt: EnemyRt, lantern: LanternRt, s: SnuffLampsSpec) {
        val id = lantern.spec.id
        val was = lantern.machine.state.name.lowercase()
        rt.snuffLamp = null
        rt.snuffArrivedAt = -1L
        rt.snuffCooldownUntil = now + (s.cooldownSeconds * 20).toLong()
        resetLantern(id)
        boss?.onLampSnuffedByEnemy(id)
        log("enemy-snuff", "${rt.actorId} lamp=$id was=$was")
        cues(s.cues, lantern.loc)
    }

    /** powered 期間的逃跑:碰到 powered 成員就被打散(先判定,優先於任何傷害效果),否則朝遠離最近 powered 成員的方向跑。 */
    private fun fleeStep(rt: EnemyRt, loc: Location, flee: FleesWhenPoweredSpec, snap: Map<UUID, DoubleArray>, poweredIds: Set<UUID>) {
        val current = members().toSet()
        var threat: DoubleArray? = null
        var best = Double.MAX_VALUE
        for ((id, pos) in snap) {
            if (id !in current || id !in poweredIds) continue
            if (withinContact(pos[0] - loc.x, pos[1] - loc.y, pos[2] - loc.z, flee.touchRadius)) {
                log("powered-reversal", "${rt.actorId} by=${nameOf(id)}")
                dissolve(rt, id, loc, reversal = true)
                return
            }
            val d = distance(pos, loc)
            if (d < best) { best = d; threat = pos }
        }
        if (!rt.fleeing) setFleeing(rt, true)
        val away = threat ?: return
        if (now - rt.lastFlee < flee.repathTicks) return
        rt.lastFlee = now
        ctx.actors().runOnEntity(rt.actorId) { e ->
            val mob = e as? Mob ?: return@runOnEntity
            mob.target = null
            for (angle in FLEE_ANGLES) {
                val point = fleePoint(loc.x, loc.z, away[0], away[2], flee.fleeDistance, angle)
                if (mob.pathfinder.moveTo(Location(world, point[0], loc.y, point[1]), flee.speed)) break
            }
        }
    }

    /** 進出逃跑外觀(模型動畫與持續 cue)。僵直中只記旗標,恢復時由 [recoverEnemy] 套回去。 */
    private fun setFleeing(rt: EnemyRt, on: Boolean) {
        val flee = rt.spec.fleesWhenPowered ?: return
        if (rt.fleeing == on) return
        rt.fleeing = on
        rt.lastFlee = -1000L
        val state = rt.machine.state
        if (state == EnemyMachine.State.ALIVE) setMark(rt, if (on) flee.poweredCue else rt.spec.needsFlash?.aliveCue)
        if (state == EnemyMachine.State.ALIVE || state == EnemyMachine.State.STUNNED) syncBody(rt)
    }

    // ---- power-up 物件與 powered ----

    /** rc.25:南瓜燈在架上(ready、所屬 stage 進行中)才有標示 cue;被拿走、換 stage、結束時取消。 */
    private fun syncBeacon(rt: PowerUpRt) {
        val cueId = rt.spec.beaconCue ?: return
        val want = !closed && stageActive(rt.spec.stage) && rt.machine.state == PowerUpMachine.State.READY
        if (want && rt.beacon == null) rt.beacon = cue(cueId, rt.loc)
        else if (!want && rt.beacon != null) { rt.beacon?.cancel(); rt.beacon = null }
    }

    /**
     * rc.27:power-up 的浮空實體。在架上(ready、所屬 stage 進行中)才有:生成 item display(part)，之後每半個週期擺一次姿勢
     * (客戶端補間成緩慢上下浮動與慢轉，伺服器只送少量封包)；被拿走、換 stage 就移除。生成完成前不移除(避免競態)。
     */
    private fun syncProp(rt: PowerUpRt) {
        val prop = rt.spec.prop ?: return
        val want = !closed && stageActive(rt.spec.stage) && rt.machine.state == PowerUpMachine.State.READY
        val propId = "powerup-" + rt.spec.id
        if (want) {
            if (!rt.propOn) {
                val material = Material.matchMaterial(prop.item)
                if (material == null) {
                    if (!rt.propWarned) { rt.propWarned = true; warn("power-up ${rt.spec.id} 的 prop.item「${prop.item}」不是 Material，不生成浮空實體") }
                    return
                }
                rt.propOn = true
                rt.propReady = false
                rt.propPhase = 0
                val at = rt.loc.clone().add(0.0, prop.height, 0.0)
                ctx.props().spawnPart(propId, at, org.bukkit.inventory.ItemStack(material), 0).whenComplete { _, error ->
                    ctx.submit {
                        if (error != null) warn("power-up ${rt.spec.id} 浮空實體生成失敗:${error.message}")
                        else { rt.propReady = true; rt.propNextPose = now }
                    }
                }
            } else if (rt.propReady && now >= rt.propNextPose) {
                val half = Math.max(1, Math.round(prop.bobSeconds * 10.0).toInt())
                val up = rt.propPhase % 2 == 0
                val ty = (if (up) prop.bob / 2.0 else -prop.bob / 2.0).toFloat()
                val yaw = (prop.spinDegrees * (rt.propPhase + 1)).toFloat()
                ctx.props().pose(propId, 0f, ty, 0f, 0f, yaw, 0f, prop.scale.toFloat(), half)
                rt.propPhase++
                rt.propNextPose = now + half
            }
        } else if (rt.propOn && rt.propReady) {
            rt.propOn = false
            rt.propReady = false
            ctx.props().despawn(propId)
        }
    }

    private fun tickPowerUps() {
        if (powerUps.isEmpty()) return
        for (rt in powerUps.values) syncBeacon(rt)
        for (rt in powerUps.values) syncProp(rt)
        for (rt in powerUps.values) {
            if (rt.machine.tryRespawn(now)) {
                log("power-up", "${rt.spec.id} taken->ready")
                rt.spec.states[ObjectStates.POWER_UP_READY]?.let {
                    cues(it.cues, rt.loc)
                    applyBlocks(rt.marker, it.blocks)
                }
            }
            if (now % 4L != 0L || !stageActive(rt.spec.stage) || !rt.machine.canTake(now)) continue
            if (rt.probing && now - rt.probeAt <= PROBE_STALE_TICKS) continue
            rt.probing = true
            rt.probeAt = now
            val startedAt = now
            ctx.membersWithin(rt.loc, rt.spec.radius).whenComplete { list, _ ->
                ctx.submit {
                    if (rt.probeAt != startedAt) return@submit
                    rt.probing = false
                    if (closed || list.isNullOrEmpty() || !stageActive(rt.spec.stage)) return@submit
                    // 倒下的人撿不起來
                    val picker = list.firstOrNull { !isDowned(it) } ?: return@submit
                    if (rt.machine.take(now)) pickUp(rt, picker)
                }
            }
        }
    }

    private fun pickUp(rt: PowerUpRt, picker: UUID) {
        val spec = rt.spec
        log("power-up-taken", "${spec.id} player=${nameOf(picker)}")
        markProgress()
        if (spec.consume) {
            spec.states[ObjectStates.POWER_UP_TAKEN]?.let {
                cues(it.cues, rt.loc)
                applyBlocks(rt.marker, it.blocks)
            }
        }
        grantPower(spec, rt.loc)
    }

    /** 全隊 powered。再吃一次把結束時間刷新成較晚的那個(不疊加)、持續 cue 收掉重播。 */
    private fun grantPower(spec: PowerUpSpec, origin: Location) {
        val count = powered.grant(members(), now, (spec.seconds * 20).toLong())
        if (count == 0) return
        poweredUsed = true
        poweredSource = spec
        poweredOrigin = origin
        cancelPoweredCues()
        for (id in spec.startCues) cue(id, origin)?.let { poweredCueRuns += it }
        publishPowered()
        log("powered-start", "players=$count seconds=${fmtSeconds(spec.seconds)}")
        for (rt in enemies.values) if (rt.spec.fleesWhenPowered != null) setFleeing(rt, true)
    }

    private fun fmtSeconds(seconds: Double): String =
        if (seconds == Math.rint(seconds)) seconds.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", seconds)

    private fun cancelPoweredCues() {
        poweredCueRuns.forEach { run -> try { run.cancel() } catch (t: Throwable) { warn("powered cue 取消失敗:${t.message}") } }
        poweredCueRuns.clear()
    }

    private fun publishPowered() {
        poweredView = powered.poweredIds(now).associateWith { id -> now + powered.remainingTicks(id, now) }
    }

    private fun tickPowered() {
        if (!powered.active) return
        if (powered.expire(now).isEmpty()) return
        publishPowered()
        if (!powered.active) endPowered("expired")
    }

    /** powered 全部結束(或被清掉):收持續 cue、敵人恢復追擊外觀;只有自然到期才播結束 cue。 */
    private fun endPowered(reason: String) {
        cancelPoweredCues()
        val source = poweredSource
        val origin = poweredOrigin
        poweredSource = null
        poweredOrigin = null
        powered.clear()
        poweredView = emptyMap()
        for (rt in enemies.values) if (rt.fleeing) setFleeing(rt, false)
        log("powered-end", "reason=$reason")
        if (reason == "expired" && source != null && origin != null) cues(source.endCues, origin)
    }

    /** stage 轉換:powered、凍結旗標、追擊目標、逃跑外觀全部清掉(敵人本身不動,下一輪掃描重新判定)。 */
    private fun clearTransient(reason: String) {
        if (powered.active) endPowered(reason)
        for (rt in enemies.values) {
            val wasFrozen = rt.freeze.frozen
            rt.freeze.reset()
            rt.chaseTarget = null
            rt.lastChase = -1000L
            rt.snuffLamp = null
            rt.snuffArrivedAt = -1L
            rt.retreatUntil = -1L
            rt.pressured = false
            rt.rushing = false
            rt.lastFlee = -1000L
            if (rt.fleeing) setFleeing(rt, false) else if (wasFrozen) syncBody(rt)
        }
    }

    /** stage 設了 clears-enemies:完成時場上剩下的敵人全部打散(不補燈火)。 */
    private fun dissolveAllEnemies() = dissolveEnemies("stage-complete") { true }

    /** 打散符合 [filter] 的敵人(不補燈火)。[tag] 寫進 log。 */
    private fun dissolveEnemies(tag: String, filter: (EnemyRt) -> Boolean) {
        val victims = enemies.values.filter { (it.machine.state == EnemyMachine.State.ALIVE || it.machine.stunned) && filter(it) }
        if (victims.isEmpty()) return
        val futures = victims.map { rt -> ctx.actors().locationOf(rt.actorId).handle { loc, _ -> rt to loc } }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            ctx.submit {
                var cleared = 0
                for (f in futures) {
                    val (rt, loc) = f.getNow(null) ?: continue
                    if (dissolve(rt, null, loc)) cleared++
                }
                log("enemies-cleared", "$tag count=$cleared")
            }
        }
    }

    // ---- 最後一段:尾聲 → resolve ----

    private fun startEpilogue(stage: StageSpec, resolve: ResolveSpec) {
        if (epilogueRun != null) return
        val ep = EpilogueRun(stage, resolve)
        epilogueRun = ep
        log("epilogue-start", "stage=${stage.id} steps=${resolve.epilogue.size}")
        runEpilogueStep(ep)
    }

    private fun runEpilogueStep(ep: EpilogueRun) {
        if (closed || epilogueRun !== ep) return
        val step = ep.sequence.current()
        if (step == null) { finishEpilogue(ep); return }
        val token = ++ep.token
        ep.wait = null
        step.cue?.let { id -> cue(id, step.marker?.let { marker(it).location(world) } ?: entryLocation()) }
        val waitTicks = (step.waitSeconds * 20).toLong()
        val dialogId = step.dialog
        if (dialogId == null) { scheduleEpilogueAdvance(ep, token, waitTicks); return }
        val port: DialogPort = runtime.dialogPort() ?: run { noteNoPort("DialogPort"); scheduleEpilogueAdvance(ep, token, waitTicks); return }
        val ids = members()
        if (ids.isEmpty()) { scheduleEpilogueAdvance(ep, token, waitTicks); return }
        ep.wait = DialogWait(ids)
        for (playerId in ids) {
            ctx.runOnMember(playerId) { player ->
                try {
                    port.open(player, spec.namespace, dialogId, IntConsumer { choice ->
                        log("dialog", "$dialogId (epilogue/${ep.stage.id}) player=${nameOf(playerId)} choice=$choice")
                        ctx.submit { onEpilogueDialogDone(ep, token, playerId) }
                    })
                } catch (t: Throwable) {
                    warn("尾聲 dialog $dialogId 開啟失敗:${t.javaClass.simpleName}: ${t.message}")
                    ctx.submit { onEpilogueDialogDone(ep, token, playerId) }
                }
            }
        }
        // 有人一直不按(斷線、關掉對話)也不能卡住結算
        timers.schedule(now, (ep.resolve.dialogTimeoutSeconds * 20).toLong()) {
            if (!closed && epilogueRun === ep && ep.token == token && ep.wait != null) {
                log("epilogue-dialog-timeout", "stage=${ep.stage.id} dialog=$dialogId waiting=${ep.wait?.size ?: 0}")
                ep.wait = null
                scheduleEpilogueAdvance(ep, token, waitTicks)
            }
        }
    }

    private fun onEpilogueDialogDone(ep: EpilogueRun, token: Int, playerId: UUID) {
        if (closed || epilogueRun !== ep || ep.token != token) return
        ep.wait?.done(playerId)
        checkDialogDone(ep)
    }

    /** 對話步驟:名單清空(全選完或離場)就等 wait-seconds 後進下一步。 */
    private fun checkDialogDone(ep: EpilogueRun) {
        val wait = ep.wait ?: return
        if (!wait.complete) return
        ep.wait = null
        scheduleEpilogueAdvance(ep, ep.token, ((ep.sequence.current()?.waitSeconds ?: 0.0) * 20).toLong())
    }

    private fun scheduleEpilogueAdvance(ep: EpilogueRun, token: Int, delayTicks: Long) {
        if (ep.scheduledToken == token) return
        ep.scheduledToken = token
        timers.schedule(now, delayTicks) {
            if (!closed && epilogueRun === ep && ep.token == token) {
                ep.sequence.advance()
                runEpilogueStep(ep)
            }
        }
    }

    private fun finishEpilogue(ep: EpilogueRun) {
        epilogueRun = null
        markProgress()
        log("epilogue-done", "stage=${ep.stage.id}")
        val key = ep.resolve.result
        log("resolve", "stage=${ep.stage.id} result=$key")
        ctx.resolve(key)
    }

    // ---- 給指令用的現況(debug)----

    /** 在 anchor 執行緒組好這一局的內容執行期現況再交給 [consumer];指令執行緒不直接讀可變狀態。 */
    fun describe(consumer: java.util.function.Consumer<List<String>>) {
        ctx.submit { consumer.accept(describeNow()) }
    }

    private fun describeNow(): List<String> {
        val lines = ArrayList<String>()
        val all = enemies.values.toList()
        fun count(state: EnemyMachine.State) = all.count { it.machine.state == state }
        lines += "stage=${currentStage?.id ?: "none"} tick=$now closed=$closed"
        lines += "敵人 ${all.size}(alive=${count(EnemyMachine.State.ALIVE)} stunned=${count(EnemyMachine.State.STUNNED)} " +
            "dissolving=${count(EnemyMachine.State.DISSOLVING)}) 凍結=${all.count { it.freeze.frozen }} " +
            "追擊中=${all.count { it.chaseTarget != null }} 逃跑中=${all.count { it.fleeing }} 模型=${all.count { it.model?.isActive == true }}"
        val byKind = all.groupingBy { it.spec.id }.eachCount()
        if (byKind.isNotEmpty()) lines += "  " + byKind.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val poweredNow = powered.poweredIds(now)
        lines += if (poweredNow.isEmpty()) "powered: 無" else
            "powered: " + poweredNow.joinToString(", ") { "${nameOf(it)}(${String.format(java.util.Locale.ROOT, "%.1f", powered.remainingTicks(it, now) / 20.0)}s)" }
        if (powerUps.isNotEmpty()) lines += "power-up: " + powerUps.values.joinToString(", ") { "${it.spec.id}=${it.machine.state.name.lowercase()}" }
        lines += "arrive: ${if (currentStage?.arrive == null) "無" else if (arrived) "已抵達" else "等候中"}" + (arriveDialogRun?.let { " 對話 ${it.index + 1}/${it.dialogs.size}" } ?: "")
        epilogueRun?.let { lines += "尾聲: 第 ${it.sequence.position + 1}/${it.resolve.epilogue.size} 步" }
        boss?.let { lines += it.describe() }
        lines += "倒下狀態玩家數=${boss?.downedCount() ?: 0}"
        lines += "cueRuns=${cueRuns.size} timers=${timers.size}"
        return lines
    }

    // ---- 局內道具 ----

    /** rc.28:目前 stage 生效的道具數值(tool.yml 加上 stage 的 tool-overrides);換 stage 時重算。 */
    private var effectiveTools: Map<String, ToolSpec> = spec.tools

    private fun toolSpecs(): Collection<ToolSpec> = effectiveTools.values

    private fun effectiveToolsFor(stage: StageSpec): Map<String, ToolSpec> =
        if (stage.toolOverrides.isEmpty()) spec.tools else spec.tools.mapValues { (id, base) ->
            val o = stage.toolOverrides[id] ?: return@mapValues base
            base.copy(
                maxCharges = o.charges ?: base.maxCharges,
                passiveRegenSeconds = o.passiveRegenSeconds ?: base.passiveRegenSeconds,
                zoneRegen = base.zoneRegen?.let { z -> o.zoneRegenSeconds?.let { z.copy(seconds = it) } ?: z },
                refillOnDissolve = o.refillOnDissolve ?: base.refillOnDissolve,
            )
        }

    /**
     * rc.28:進 stage 時套用 tool-overrides:重算生效數值、回補計時重開、耐久條上限跟著換(`fill-on-start` 的補滿，其餘維持目前格數)。
     * 沒有覆寫、前一個 stage 也沒有覆寫就什麼都不做。
     */
    private fun applyToolOverrides(stage: StageSpec) {
        val before = effectiveTools
        val after = effectiveToolsFor(stage)
        if (before === after) return
        effectiveTools = after
        meters.clear()
        for (tool in after.values) {
            val o = stage.toolOverrides[tool.id]
            for (playerId in members()) {
                val current = chargesOf(playerId, tool)
                val target = if (o?.fillOnStart == true) tool.maxCharges else current
                setCharges(playerId, tool, target)
            }
            log("tool-override", "stage=${stage.id} tool=${tool.id} max=${tool.maxCharges} passive=${tool.passiveRegenSeconds ?: 0.0} zone=${tool.zoneRegen?.seconds ?: 0.0} refill=${tool.refillOnDissolve}")
        }
    }

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
        quitMembers.remove(playerId)
        if (objectiveHud.forget(playerId)) clearObjectiveHud(listOf(playerId))
        // 離場的人已不在成員名單，身體狀態還原走 PlayerOp(不依賴名單)，見 BossHostImpl.restoreDowned
        boss?.onMemberLeft(playerId)
        touch.forget(playerId)
        chaseTouch.forget(playerId)
        for (rt in enemies.values) if (rt.chaseTarget == playerId) rt.chaseTarget = null
        if (powered.remove(playerId)) {
            publishPowered()
            if (!powered.active) endPowered("members-left")
        }
        epilogueRun?.let { run -> run.wait?.remove(playerId); checkDialogDone(run) }
        arriveDialogRun?.let { run -> run.wait?.remove(playerId); checkArriveDialogDone(run) }
        charges.remove(playerId)
        meters.remove(playerId)
        lastFlash.remove(playerId)
        val suffix = "/$playerId"
        val gone = lanternHintRuns.keys.filter { it.endsWith(suffix) }
        gone.forEach { lanternHintRuns.remove(it)?.run?.cancel() }
    }

    /** 事件執行緒呼叫:玩家右鍵 tool。[eye]/[dir]/[feet] 是事件當下持有者的快照([feet] = `player.location`,點燃半徑與世界標示用同一套量法)。 */
    fun onFlash(playerId: UUID, toolId: String, eye: Location, dir: Vector, feet: Location) {
        ctx.submit { handleFlash(playerId, toolId, eye, dir, feet, Double.MAX_VALUE) }
    }

    /** rc.30:同上，[blockDistance] = 事件執行緒沿視線射線打到方塊的距離(沒打到 = [Double.MAX_VALUE])，遠距光束判視線用。 */
    fun onFlash(playerId: UUID, toolId: String, eye: Location, dir: Vector, feet: Location, blockDistance: Double) {
        ctx.submit { handleFlash(playerId, toolId, eye, dir, feet, blockDistance) }
    }

    private enum class BeamResult { NONE, HIT, BLOCKED }

    /**
     * rc.30:這次閃光的光束有沒有照到這盞燈。[BeamResult.BLOCKED] = 瞄準了、但方塊([blockDistance])或 boss 本體擋住視線。
     * 燈的瞄準點是 marker 上方 2.5 格(燈在 marker 上方 2 格)。
     */
    private fun beamResult(rt: LanternRt, eye: Location, direction: Vector, blockDistance: Double): BeamResult {
        val beam = rt.spec.beam ?: return BeamResult.NONE
        if (!stageActive(rt.spec.stage) || eye.world != rt.loc.world) return BeamResult.NONE
        val ax = rt.loc.x
        val ay = rt.loc.y + 2.5
        val az = rt.loc.z
        if (!beamAimed(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, ax, ay, az, beam.range, beam.angle)) return BeamResult.NONE
        if (!beam.requiresSight) return BeamResult.HIT
        val dist = Math.sqrt((ax - eye.x) * (ax - eye.x) + (ay - eye.y) * (ay - eye.y) + (az - eye.z) * (az - eye.z))
        if (blockDistance < dist - 1.0) return BeamResult.BLOCKED
        val p = bossPos
        if (p != null && bodyBlocksSegment(eye.x, eye.y, eye.z, ax, ay, az, p.x, p.y, p.z, beam.bossBlockRadius, BOSS_BODY_HEIGHT)) return BeamResult.BLOCKED
        return BeamResult.HIT
    }

    private fun handleFlash(playerId: UUID, toolId: String, eye: Location, dir: Vector, feet: Location, blockDistance: Double) {
        if (closed) return
        val tool = effectiveTools[toolId] ?: return
        if (playerId !in members()) return
        // 倒下的人不能點燈、不能閃光
        if (isDowned(playerId)) return
        val last = lastFlash[playerId]
        if (last != null && now - last < tool.cooldownTicks) return
        lastFlash[playerId] = now
        val player = Bukkit.getPlayer(playerId)
        val current = chargesOf(playerId, tool)
        val direction = dir.clone().normalize()
        val half = Math.toRadians(tool.coneAngle / 2.0)
        // rc.28 free-on-burning-lamp:閃光碰到正在燒的燈、又沒點到任何未點燃的燈 = 「繼續照住燈」，不吃燈火(實玩 31 次閃光有 20 次沒點到燈，
        // 多半是對燒著或太遠的燈連按)；這次閃光仍照常判定敵人
        val keepLighting = tool.freeOnBurningLamp &&
            lanterns.values.any { stageActive(it.spec.stage) && it.machine.state == LanternMachine.State.BURNING && flashTouches(it, eye, direction, half, tool, feet, blockDistance) } &&
            lanterns.values.none { stageActive(it.spec.stage) && it.machine.state == LanternMachine.State.UNLIT && flashTouches(it, eye, direction, half, tool, feet, blockDistance) }
        if (current <= 0 && !keepLighting) {
            log("flash-empty", "player=${nameOf(playerId)} tool=${tool.id}")
            cues(tool.emptyCues, eye, null, listOfNotNull(player))
            hintToolEmpty(playerId)
            return
        }
        val after = if (keepLighting) current else current - 1
        if (!keepLighting) setCharges(playerId, tool, after) else log("flash-keep", "player=${nameOf(playerId)} tool=${tool.id} charges=$current")
        cues(tool.flashCues, eye)
        if (!keepLighting && after == 0) {
            log("tool-drained", "player=${nameOf(playerId)} tool=${tool.id}")
            cues(tool.drainedCues, eye, null, listOfNotNull(player))
            hintToolEmpty(playerId)
        }
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
            val beamHit = beamResult(rt, eye, direction, blockDistance) == BeamResult.HIT
            if ((coneHit || radiusHit || beamHit) && igniteLantern(rt)) {
                lanternHits++
            } else if (!coneHit && !radiusHit && !beamHit) {
                // 實測證據:沒點到時記下距離與視線偏角,下次調射程與判定半徑有數據可看
                val to = center.toVector().subtract(eye.toVector())
                val angle = if (to.lengthSquared() < 1.0E-6) 0.0 else Math.toDegrees(Math.acos(to.normalize().dot(direction).coerceIn(-1.0, 1.0)))
                misses += String.format(java.util.Locale.ROOT, "%s:dist=%.1f,angle=%.0f", rt.spec.id, if (close == Double.MAX_VALUE) -1.0 else close, angle)
            }
        }
        // rc.30 遠距光束:照到正在燒的燈就續著燒(離守燈範圍遠的才算光束)；被方塊或 boss 擋住給擋住回饋
        for (rt in lanterns.values) {
            val beam = rt.spec.beam ?: continue
            if (!stageActive(rt.spec.stage) || rt.machine.state != LanternMachine.State.BURNING) continue
            when (beamResult(rt, eye, direction, blockDistance)) {
                BeamResult.HIT -> {
                    val close: Double = if (feet.world == rt.loc.world) feet.distance(rt.loc) else Double.MAX_VALUE
                    if (close > rt.spec.stayRadius) {
                        val fresh = (rt.beamers[playerId] ?: 0L) <= now
                        rt.beamers[playerId] = now + (beam.sustainSeconds * 20).toLong()
                        if (fresh) log("beam-hold", "lamp=${rt.spec.id} player=${nameOf(playerId)}")
                    }
                    cues(beam.hitCues, rt.loc.clone().add(0.0, 2.0, 0.0), null, listOfNotNull(player))
                    markProgress()
                }
                BeamResult.BLOCKED -> {
                    log("beam-blocked", "lamp=${rt.spec.id} player=${nameOf(playerId)}")
                    cues(beam.blockedCues, rt.loc.clone().add(0.0, 2.0, 0.0), null, listOfNotNull(player))
                }
                BeamResult.NONE -> {}
            }
        }
        // boss 熄燈招式:閃光碰到被點名的場邊燈 = 保住
        boss?.snuffTarget()?.let { lampId ->
            val lamp = lanterns[lampId]
            if (lamp != null && flashTouches(lamp, eye, direction, half, tool, feet, blockDistance)) boss?.onSnuffFlash(playerId)
        }
        if (lanternHits == 0 && misses.isNotEmpty()) {
            log("flash-miss", "player=${nameOf(playerId)} range=${tool.range} cone=${tool.coneAngle} ${misses.joinToString(" ")}")
        }
        val candidates = enemies.values.filter { it.spec.needsFlash != null && (it.machine.state == EnemyMachine.State.ALIVE || it.machine.stunned) }
        log("flash", "player=${nameOf(playerId)} tool=${tool.id} charges=$after lanterns=$lanternHits candidates=${candidates.size}")
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
            if (h.requiresArrival && !arrived) continue
            fireHint(h)
        }
    }

    // ---- marker 探測(reach 條件、near-marker 提示)----

    private fun probeMarkers() {
        val stage = currentStage ?: return
        stage.arrive?.let { a ->
            if (!arrived && !arriveProbePending) {
                arriveProbePending = true
                val o = a.offset
                val at = marker(a.marker).location(world).add(o[0], o[1], o[2])
                ctx.membersWithin(at, a.radius).whenComplete { list, _ ->
                    ctx.submit {
                        if (closed || currentStage?.id != stage.id) return@submit
                        arriveProbePending = false
                        if (!list.isNullOrEmpty()) onArrive(stage)
                    }
                }
            }
        }
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
        if (now < objectiveHoldUntil) return
        val every = stage.objectiveEverySeconds
        val due = key != objectiveKey || objectiveDirty || (every > 0.0 && now >= objectiveNextAt)
        if (!due) return
        objectiveKey = key
        objectiveDirty = false
        objectiveNextAt = now + (every * 20).toLong()
        if (key != null) showObjective(key)
    }

    /** rc.26:目標文字的 `{burn}`:這個 stage 進度最高的燃燒中燈籠，五格圓點(`●●●○○`)；沒有燈在燒 = 空字串。 */
    private fun burnPips(): String {
        val stageId = currentStage?.id ?: return ""
        val best = lanterns.values
            .filter { it.spec.stage == stageId && it.machine.state == LanternMachine.State.BURNING }
            .maxOfOrNull { it.machine.fraction } ?: return ""
        return chargePips(Math.round(best * BURN_PIPS).toInt(), BURN_PIPS)
    }

    /**
     * rc.27:目標文字 `{nearest:power-up}`:這位成員面向看，最近一顆 ready 的 power-up(所屬 stage 進行中)在哪個方位、多遠，
     * 例如「左前方 12 格」。方位八向以玩家 yaw 為準；方位字與格式取自內容 texts `<dungeonId>.direction.<front|front-right|right|back-right|back|back-left|left|front-left>`
     * 與 `<dungeonId>.direction.format`(`{dir}` `{distance}`)，缺字時只回距離數字。沒有成員快照或沒有 ready 的 power-up = 空字串。
     */
    private fun nearestPowerUpText(playerId: UUID): String {
        val pos = lastSnap[playerId] ?: return ""
        var best: PowerUpRt? = null
        var bestSq = Double.MAX_VALUE
        for (rt in powerUps.values) {
            if (!stageActive(rt.spec.stage) || rt.machine.state != PowerUpMachine.State.READY) continue
            val dx = rt.loc.x - pos[0]
            val dz = rt.loc.z - pos[2]
            val sq = dx * dx + dz * dz
            if (sq < bestSq) { bestSq = sq; best = rt }
        }
        val target = best ?: return ""
        val dx = target.loc.x - pos[0]
        val dz = target.loc.z - pos[2]
        val distance = Math.round(Math.sqrt(bestSq)).toInt()
        val octant = directionOctant(pos[3], dx, dz)
        val prefix = spec.dungeonId + ".direction."
        val dir = core.texts.rawOrNull(prefix + octant) ?: return distance.toString()
        val format = core.texts.rawOrNull(prefix + "format") ?: return "$dir $distance"
        return format.replace("{dir}", dir).replace("{distance}", distance.toString())
    }

    private fun showObjective(key: String) {
        val tool = effectiveTools.values.firstOrNull()
        val burn = burnPips()
        for (playerId in members()) {
            val base = if (tool == null) mapOf("burn" to burn) else {
                val c = chargesOf(playerId, tool)
                mapOf("charges" to c.toString(), "max" to tool.maxCharges.toString(), "pips" to chargePips(c, tool.maxCharges), "burn" to burn)
            }
            val params = base + mapOf("nearest:power-up" to nearestPowerUpText(playerId))
            ctx.actionBar(playerId, key, params)
            objectiveHud.shown(playerId)
        }
    }

    /** rc.25:把動作列清成空白(同一個 group 取代掉最後一則目標，不等它自己過期)。走玩家自己的執行緒，不依賴成員名單。 */
    private fun clearObjectiveHud(ids: Collection<UUID>) {
        for (id in ids) PlayerOp.dispatch(core.plugin, id) { p -> ActionBars.show(p, Component.empty()) }
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
        lastSnap = snap
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

    // ---- Boss(rc.23)----

    private var boss: BossRun? = null
    /** 事件執行緒讀的 boss 參照(只讀 [BossRun.fightActive]／[BossRun.isDowned]，兩者都是執行緒安全的)。 */
    @Volatile
    private var bossView: BossRun? = null
    @Volatile
    private var bossActor: String? = null
    @Volatile
    private var bossModel: BossModelHandle? = null
    private var bossBase: String? = null
    private var bossPos: Pt? = null
    private var bossYaw = 0f
    private val defeatedBosses = HashSet<String>()
    /** 斷線中的成員(寬限期內)。全滅判定不把他們算成存活。 */
    private val quitMembers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val downedBodies = DownedBodyLedger()

    private fun loc(p: Pt) = Location(world, p.x, p.y, p.z)

    private fun startBoss(bossSpec: BossSpec, held: Boolean = false) {
        val run = BossRun(BossHostImpl(bossSpec), bossSpec, random)
        boss = run
        bossView = run
        run.start(held)
    }

    /** stage 轉換或局結束:倒下的人還原、招式作廢、血量條收掉。[despawn] = 連載體一起移除。 */
    private fun stopBoss(reason: String, despawn: Boolean) {
        val run = boss ?: return
        boss = null
        bossView = null
        run.shutdown(reason, despawn)
        if (despawn) {
            bossActor = null
            bossModel = null
            bossBase = null
            bossPos = null
        }
    }

    fun isBossActor(actorId: String): Boolean = actorId == bossActor

    fun bossMinAttackStrength(): Double = bossView?.spec?.minAttackStrength ?: 0.0

    /** 事件執行緒呼叫:玩家一下近戰打到 boss 載體(事件已取消，傷害由命中數取代)。 */
    fun onBossAttacked(playerId: UUID, at: Location) {
        ctx.submit { boss?.onHit(playerId, Pt(at.x, at.y, at.z)) }
    }

    /** 事件執行緒同步讀:這位成員目前是否倒下。 */
    fun isDowned(id: UUID): Boolean = bossView?.isDowned(id) == true

    /** 事件執行緒同步讀:boss 戰進行中（致死傷害不死亡，進入倒下）。 */
    fun lethalGuardActive(): Boolean = bossView?.fightActive == true

    fun onLethalDamage(id: UUID) {
        ctx.submit { boss?.downMember(id) }
    }

    /** 事件執行緒呼叫（PlayerQuitEvent，玩家自己的執行緒）：同步還原倒下的身體狀態，再回 anchor 執行緒處理邏輯。 */
    fun onMemberQuit(id: UUID) {
        quitMembers += id
        bossView?.onMemberQuitSync(id)
        ctx.submit { boss?.onMemberQuit(id) }
    }

    /** 事件執行緒呼叫（PlayerJoinEvent，重連寬限期內回來）。 */
    fun onMemberReconnected(id: UUID) {
        quitMembers.remove(id)
        ctx.submit { boss?.onMemberReconnected(id) }
    }

    private fun withoutDowned(snap: Map<UUID, DoubleArray>): Map<UUID, DoubleArray> {
        val run = boss ?: return snap
        if (run.downedCount() == 0) return snap
        return snap.filterKeys { !run.isDowned(it) }
    }

    /** 熄燈招式:閃光碰得到被點名的場邊燈就保住(與點燃共用同一套錐形／點燃半徑量法)。 */
    private fun flashTouches(rt: LanternRt, eye: Location, direction: Vector, half: Double, tool: ToolSpec, feet: Location, blockDistance: Double): Boolean {
        if (beamResult(rt, eye, direction, blockDistance) == BeamResult.HIT) return true
        val base = rt.loc
        val cone = inCone(eye, direction, base.clone().add(0.0, 0.5, 0.0), half, tool.range) ||
            inCone(eye, direction, base.clone().add(0.0, 2.5, 0.0), half, tool.range)
        val close: Double = if (feet.world == base.world) feet.distance(base) else Double.MAX_VALUE
        return cone || (rt.spec.igniteRadius > 0.0 && close <= rt.spec.igniteRadius)
    }

    private inner class BossHostImpl(private val bossSpec: BossSpec) : BossHost {
        override val now: Long get() = this@SessionRun.now

        override fun schedule(delayTicks: Long, action: Runnable) { timers.schedule(now, delayTicks, action) }

        override fun log(event: String, detail: String) { this@SessionRun.log(event, detail) }

        override fun members(): List<UUID> = this@SessionRun.members()

        override fun isOnline(id: UUID): Boolean = id !in quitMembers && Bukkit.getPlayer(id) != null

        override fun nameOf(id: UUID): String = this@SessionRun.nameOf(id)

        override fun markerPos(id: String): Pt? = markers[id]?.let { m -> val l = m.location(world); Pt(l.x, l.y, l.z) }

        override fun cue(cueId: String, at: Pt) { this@SessionRun.cue(cueId, loc(at)) }

        override fun cueFor(cueId: String, at: Pt, player: UUID) {
            Bukkit.getPlayer(player)?.let { this@SessionRun.cue(cueId, loc(at), null, listOf(it)) }
        }

        override fun sampleCue(cueId: String, at: Pt) { this@SessionRun.cue(cueId, loc(at), track = false) }

        override fun loopCue(cueId: String, at: Pt): Runnable {
            val run = this@SessionRun.cue(cueId, loc(at))
            return Runnable { run?.cancel() }
        }

        override fun followBossCue(cueId: String): Runnable {
            var run: CueRun? = null
            var cancelled = false
            fun attempt(tries: Int) {
                if (cancelled || closed) return
                val actor = bossActor
                val entity = actor?.let { core.actorController.entityOf(sessionId, it) }
                if (entity == null) {
                    // 載體還在生成:稍後再試(最多 10 次)
                    if (tries < 10) timers.schedule(now, 10L) { attempt(tries + 1) }
                    return
                }
                // 原點用 BossHost 維護的座標（不跨 region 讀實體位置）；實體只當 follow 交給 LycoLib
                val origin = bossPos?.let { loc(it) } ?: return
                run = this@SessionRun.cue(cueId, origin, entity)
            }
            attempt(0)
            return Runnable { cancelled = true; run?.cancel() }
        }

        override fun followCue(cueId: String, player: UUID, at: Pt): Runnable {
            val p = Bukkit.getPlayer(player) ?: return Runnable { }
            val run = this@SessionRun.cue(cueId, loc(at), p)
            return Runnable { run?.cancel() }
        }

        override fun music(cueId: String) { ctx.musicCue(cueId) }

        override fun title(titleKey: String, subtitleKey: String?) { ctx.titleAll(titleKey, subtitleKey ?: "", emptyMap()) }

        override fun titleFor(player: UUID, titleKey: String, subtitleKey: String?) { ctx.title(player, titleKey, subtitleKey ?: "") }

        override fun actionBar(player: UUID, key: String) { ctx.actionBar(player, key) }

        override fun bossBar(textKey: String, fraction: Double, color: String, overlay: String, params: Map<String, String>) {
            var raw = core.texts.rawOrNull(textKey) ?: return
            for ((k, v) in params) raw = raw.replace("{$k}", v)
            ctx.bossBar(raw, fraction.coerceIn(0.0, 1.0), color, overlay)
        }

        override fun hideBossBar() { ctx.hideBossBar(); barPercent = -1 }

        override fun healMembers(ids: List<UUID>) {
            for (id in ids) ctx.runOnMember(id) { p ->
                if (p.isDead) return@runOnMember
                p.health = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
            }
            // rc.28 heal.tools:開打、全滅重來、跪下時把提燈也補滿(戰鬥不用停下來等閃光)
            if (bossSpec.heal.refillTools) {
                for (id in ids) for (tool in toolSpecs()) setCharges(id, tool, tool.maxCharges)
                this@SessionRun.log("boss-refill-tools", "players=${ids.size}")
            }
        }

        override fun spawnBoss(at: Pt) {
            val actorId = "boss-${bossSpec.id}"
            bossActor = actorId
            bossPos = at
            val actorSpec = ActorSpec().apply {
                entityType = bossSpec.baseEntity
                // VELOCITY 模式要開著 AI(沒有 AI 的 Mob 不會被速度推動)，目標與移動目標在生成後全部拿掉；也不讓玩家把她擠走
                ai = bossSpec.moveMode == BossMoveMode.VELOCITY
                collidable = bossSpec.moveMode != BossMoveMode.VELOCITY
                invisible = bossSpec.model != null
                removeWhenFarAway = false
                immovable = false
                invulnerable = false
                gravity = false
                silent = true
                fireResistant = true
                randomizeData = false
                // 傷害一律在事件裡取消，血量用命中數表示；載體血量只是讓它不會被一般手段秒掉
                maxHealth = BOSS_CARRIER_HEALTH
                scale = bossSpec.scale
            }
            ctx.actors().spawn(actorId, loc(at), actorSpec).whenComplete { _, error ->
                ctx.submit { afterBossSpawn(bossSpec, actorId, error) }
            }
        }

        override fun moveBoss(to: Pt) {
            val id = bossActor ?: return
            val prev = bossPos
            bossPos = to
            if (prev != null) {
                val dx = to.x - prev.x
                val dz = to.z - prev.z
                if (Math.abs(dx) > 1.0E-6 || Math.abs(dz) > 1.0E-6) bossYaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
            }
            if (bossSpec.moveMode != BossMoveMode.VELOCITY) {
                ctx.actors().teleport(id, Location(world, to.x, to.y, to.z, bossYaw, 0f))
                return
            }
            // rc.30 速度驅動:不經傳送，原版移動推著走(BetterModel 不會因為載體移除再加入而重送整個模型)；差距太大(落地、全滅重來)才傳送
            val yaw = bossYaw
            ctx.actors().runOnEntity(id) { e ->
                val here = e.location
                val dx = to.x - here.x
                val dy = to.y - here.y
                val dz = to.z - here.z
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > BOSS_VELOCITY_MAX_STEP) {
                    e.teleportAsync(Location(world, to.x, to.y, to.z, yaw, 0f))
                } else {
                    val v = velocityToward(here.x, here.y, here.z, to.x, to.y, to.z, e.isOnGround)
                    e.velocity = Vector(v[0], v[1], v[2])
                    e.setRotation(yaw, 0f)
                }
            }
        }

        override fun faceBoss(toward: Pt) {
            val id = bossActor ?: return
            val from = bossPos ?: return
            val dx = toward.x - from.x
            val dz = toward.z - from.z
            if (Math.abs(dx) < 1.0E-6 && Math.abs(dz) < 1.0E-6) return
            bossYaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
            ctx.actors().setRotation(id, bossYaw, 0f)
        }

        override fun playAnimation(name: String, loop: Boolean) {
            val model = bossModel ?: return
            if (loop) {
                model.setBase(name)
                bossBase = name
            } else {
                // 單次動畫前先收掉基底迴圈，播完由 BossRun 切回基底
                bossBase?.let { model.stop(it) }
                bossBase = null
                model.play(name, false, null)
            }
        }

        override fun setBone(bone: String, visible: Boolean) {
            (bossModel as? BossModelBones)?.setBoneVisible(bone, visible)
        }

        override fun despawnBoss() {
            val id = bossActor ?: return
            ctx.actors().despawn(id)
        }

        override fun snapshots(callback: Consumer<Map<UUID, DoubleArray>>) {
            ctx.memberSnapshots().whenComplete { snap, error ->
                ctx.submit { if (!closed) callback.accept(if (error != null) emptyMap() else snap ?: emptyMap()) }
            }
        }

        override fun damageFixed(id: UUID, fraction: Double, push: HarassEffect.Push?, from: Pt?, onLethal: Runnable) {
            ctx.runOnMember(id) { p ->
                if (p.isDead) return@runOnMember
                val max = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                val amount = max * fraction
                val health = p.health
                // 固定比例、不看護甲、附魔與抗性:直接改血量;致死就留 1 滴血交給倒下機制
                val lethal = health - amount <= 0.0
                p.health = if (lethal) health.coerceAtMost(1.0).coerceAtLeast(0.5) else health - amount
                runCatching { p.playHurtAnimation(0f) }
                if (push != null && from != null) {
                    val here = p.location
                    val v = pushAway(from.x, from.z, here.x, here.z, push.strength)
                    p.velocity = p.velocity.clone().add(Vector(v[0], push.lift, v[1]))
                }
                if (lethal) ctx.submit { onLethal.run() }
            }
        }

        override fun applyDowned(id: UUID, spec: DownedSpec, revivePoints: List<Pt>) {
            PlayerOp.dispatch(core.plugin, id) { p ->
                downedBodies.apply(id, BukkitBody(p, core.plugin), spec.bodyState())
                if (revivePoints.isNotEmpty() && p.world == world) {
                    val here = p.location
                    val best = revivePoints.minByOrNull { Math.hypot(it.x - here.x, it.z - here.z) }
                    if (best != null) p.teleportAsync(Location(world, best.x, best.y, best.z, here.yaw, here.pitch))
                }
            }
        }

        override fun restoreDowned(id: UUID, health: Double?) {
            PlayerOp.dispatch(core.plugin, id) { p ->
                if (!downedBodies.restore(id, BukkitBody(p, core.plugin))) return@dispatch
                if (health != null) {
                    val max = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
                    p.health = health.coerceIn(1.0, max)
                }
                ActionBars.show(p, Component.empty())
            }
        }

        override fun restoreDownedSync(id: UUID) {
            Bukkit.getPlayer(id)?.let { downedBodies.restore(id, BukkitBody(it, core.plugin)) }
        }

        override fun teleportPlayer(id: UUID, to: Pt) {
            ctx.runOnMember(id) { p -> p.teleportAsync(loc(to)) }
        }

        override fun lampUsed(lamp: String): Boolean = lanterns[lamp]?.machine?.state.let { it != null && it != LanternMachine.State.UNLIT }

        override fun lampPos(lamp: String): Pt? = lanterns[lamp]?.loc?.let { Pt(it.x, it.y, it.z) }

        override fun lampFraction(lamp: String): Double = lanterns[lamp]?.machine?.fraction ?: 0.0

        override fun lampBurning(lamp: String): Boolean = lanterns[lamp]?.machine?.state == LanternMachine.State.BURNING

        override fun lampLighters(): Set<UUID> {
            val out = HashSet<UUID>()
            for (lamp in bossSpec.lampIds) {
                val rt = lanterns[lamp] ?: continue
                if (rt.machine.state != LanternMachine.State.BURNING) continue
                rt.beamers.filter { it.value > now }.keys.forEach { out += it }
                for ((id, pos) in lastSnap) if (distance(pos, rt.loc) <= rt.spec.stayRadius) out += id
            }
            return out.filter { !isDowned(it) }.toSet()
        }

        override fun boostLamp(lamp: String, fraction: Double): Boolean {
            val rt = lanterns[lamp] ?: return false
            if (rt.machine.state == LanternMachine.State.LIT) return false
            if (rt.machine.state == LanternMachine.State.UNLIT && !igniteLantern(rt)) return false
            if (rt.machine.boost(rt.spec.burnSeconds * 20.0 * fraction) == LanternMachine.Outcome.COMPLETED) lanternLit(rt)
            return true
        }

        override fun actionBarText(player: UUID, key: String, params: Map<String, String>) { ctx.actionBar(player, key, params) }

        override fun resetLamp(lamp: String) = resetLantern(lamp)

        override fun setPumpkinsAvailable(objects: List<String>, available: Boolean) {
            for (id in objects) {
                val rt = powerUps[id] ?: continue
                if (available) {
                    rt.machine.forceReady()
                    rt.spec.states[ObjectStates.POWER_UP_READY]?.let { applyBlocks(rt.marker, it.blocks) }
                } else {
                    rt.machine.forceTaken()
                    rt.spec.states[ObjectStates.POWER_UP_TAKEN]?.let { applyBlocks(rt.marker, it.blocks) }
                }
                this@SessionRun.log("power-up", "$id ${if (available) "available" else "hidden"}")
            }
        }

        override fun isPowered(id: UUID): Boolean = powered.isPowered(id, now)

        override fun endPowered(reason: String) {
            if (powered.active) this@SessionRun.endPowered(reason)
        }

        override fun spawnSummon(actorId: String, enemyId: String, at: Pt): Boolean {
            val enemySpec = spec.enemies[enemyId] ?: return false
            spawnEnemyAt(actorId, enemySpec, loc(at), null)
            return true
        }

        override fun dissolveSummons(actorIds: Collection<String>) {
            val ids = actorIds.toSet()
            dissolveEnemies("boss") { it.actorId in ids }
        }

        override fun onBossDefeated() {
            defeatedBosses += bossSpec.id
            markProgress()
            evaluateStage()
        }
    }

    private fun afterBossSpawn(bossSpec: BossSpec, actorId: String, error: Throwable?) {
        if (closed || bossActor != actorId) return
        if (error != null || ctx.actors().entityIdOf(actorId) == null) {
            warn("boss ${bossSpec.id} 載體生成失敗${error?.let { ":${it.message}" } ?: ""}")
            return
        }
        if (bossSpec.moveMode == BossMoveMode.VELOCITY) {
            ctx.actors().runOnEntity(actorId) { e ->
                try {
                    (e as? Mob)?.let { Bukkit.getMobGoals().removeAllGoals(it) }
                } catch (t: Throwable) {
                    warn("boss ${bossSpec.id} 拿掉原版 AI 目標失敗:${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        val modelId = bossSpec.model ?: return
        // alwaysVisible:boss 的大模型不吃 BetterModel 的 sight-trace 剔除
        ctx.actors().bindModel(actorId, modelId, true).whenComplete { handle, _ ->
            ctx.submit {
                if (closed || bossActor != actorId) return@submit
                bossModel = handle
                if (handle == null) {
                    warn("boss ${bossSpec.id} 的模型 $modelId 綁定失敗(BetterModel 缺席或模型不存在),載體保持可見")
                    ctx.actors().runOnEntity(actorId) { e -> (e as? LivingEntity)?.isInvisible = false }
                } else {
                    handle.setBase(bossSpec.animations.idle)
                    bossBase = bossSpec.animations.idle
                }
            }
        }
    }

    // ---- 收尾 ----

    /** 局結束:取消所有 CueRun 與排程、停掉 mutator。場地回滾由引擎在這之後做。 */
    fun close(reason: String = "closed") {
        if (closed) return
        // boss 先收（倒下的人還原身體狀態、血量條收掉）；這時 closed 還是 false，host 的演出呼叫才不會被擋
        stopBoss(reason, despawn = false)
        clearObjectiveHud(objectiveHud.drain())
        closed = true
        timers.clear()
        touch.clear()
        chaseTouch.clear()
        powered.clear()
        poweredView = emptyMap()
        poweredSource = null
        poweredCueRuns.clear()
        epilogueRun = null
        arriveDialogRun = null
        lanterns.values.forEach { it.hold?.cancel(); it.hold = null }
        lanternHintRuns.clear()
        while (true) {
            val run = cueRuns.pollFirst() ?: break
            try { run.cancel() } catch (t: Throwable) { warn("CueRun.cancel 失敗:${t.message}") }
        }
        while (true) {
            val run = transientRuns.pollFirst() ?: break
            try { run.cancel() } catch (t: Throwable) { warn("CueRun.cancel 失敗:${t.message}") }
        }
        powerUps.values.forEach { it.beacon = null }
        enemies.clear()
        log("session-end", "$reason cancelledCues=done")
    }

    private companion object {
        const val TICK_PERIOD = 2L
        /** rc.26:目標文字 `{burn}` 的圓點數。 */
        const val BURN_PIPS = 5
        const val PROBE_STALE_TICKS = 40L
        /** 取樣 cue 的 CueRun 保留上限(每輪最多 80 個、每 4 tick 一輪,保留約 1.6 輪,短促 cue 早已自己結束)。 */
        const val MAX_TRANSIENT_CUE_RUNS = 128
        /** [StageContext.MEMBER_SNAPSHOT_FIELDS] 的 heldSlot 欄位。 */
        const val HELD_SLOT = 9
        /** [StageContext.MEMBER_SNAPSHOT_FIELDS] 的 health 欄位。 */
        const val HEALTH = 5
        /** [StageContext.MEMBER_SNAPSHOT_FIELDS] 的 sneaking 欄位。 */
        const val SNEAKING = 8
        /** 敵人規則掃描單輪上限,以及啃燈判定允許的高度差、啃燈速率過期時間。 */
        const val MAX_SCAN_ENEMIES = 24
        /** boss 載體的血量上限:傷害一律在事件取消，只是讓它不被一般手段（指令、環境）秒掉。 */
        const val BOSS_CARRIER_HEALTH = 1024.0
        /** rc.30:boss 本體與光環擋光的圓柱高度(格)。 */
        const val BOSS_BODY_HEIGHT = 4.5
        /** rc.30:速度驅動移動一步最遠走多少格，超過就改傳送。 */
        const val BOSS_VELOCITY_MAX_STEP = 6.0
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
