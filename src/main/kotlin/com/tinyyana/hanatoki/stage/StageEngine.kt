package com.tinyyana.hanatoki.stage

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.actor.ActorHandle
import com.tinyyana.hanatoki.config.InteractionKind
import com.tinyyana.hanatoki.encounter.EncounterController
import com.tinyyana.hanatoki.folia.InstanceDispatch
import com.tinyyana.hanatoki.folia.PlayerOp
import com.tinyyana.hanatoki.folia.WorldOp
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.EntityType
import org.bukkit.event.block.Action
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ARCH §2/§8 落地處:把 [InstanceState] + [DungeonBehavior] + Phase 1 既有的 SessionManager/
 * SlotPool/WorldDiffRecorder 串起來。只有定義了 `stageGraph` 的副本才會經過這裡
 * (`test-empty` 這種 Phase 1 舊定義完全不受影響,行為原樣不變)。
 *
 * 依附 [HanaTokiCore] 而不是反過來被注入一堆 lambda——這個模組本來就是「串接」的角色,
 * 跟 HanaTokiCore 自己的 KDoc 定位一致,直接引用比穿一層抽象介面更直白(careful-coding:
 * 不為了解耦而解耦)。
 */
class StageEngine(private val core: HanaTokiCore) {
    private val states = ConcurrentHashMap<UUID, InstanceState>()
    val encounters = EncounterController(core.plugin)

    /**
     * `ctx.submitLater/submitRepeating` 排出來的演出排程,按 session 記帳,在 stage 離開與
     * session 結束時一次取消。behavior 不需要自己記帳——不記帳的下場是場地已經回滾、session 已
     * 結束之後,招式排程還在對著空場地放粒子/傷害(ARCH §5.2 規則 6 的收斂順序)。
     */
    private val scheduled = ConcurrentHashMap<UUID, CopyOnWriteArrayList<ScheduledTask>>()

    fun startFor(sessionId: UUID, dungeonId: String, slotId: String, anchor: Location, nowMs: Long) {
        val graph = core.registry.definitions[dungeonId]?.stageGraph ?: return
        val behavior = behaviorFor(dungeonId) ?: run {
            // stageGraph 有定義但沒有對應 behavior 註冊——這是設定/接線錯誤,值得警告
            // (跟「這座副本本來就沒有 stage 圖」的 Phase 1 test-empty 情境不同,不能一起靜默略過)。
            core.plugin.logger.warning("[HanaToki] dungeonId=$dungeonId 有 stage 圖但沒有註冊 DungeonBehavior,entry stage 不會執行")
            return
        }
        val state = InstanceState(graph)
        state.enterStage(graph.startStage, nowMs)
        states[sessionId] = state
        // enter() 的呼叫端可能是任意執行緒(指令發出者的 region)——behavior callback 一律要求
        // 已在 anchor 所屬 region 的 task 內執行(ARCH §5.1②),這裡補一次 submit。
        InstanceDispatch.submit(core.plugin, anchor) {
            val ctx = ctxFor(sessionId, dungeonId, slotId, anchor, state)
            behavior.onStageEnter(ctx, state.currentStageId)
        }
    }

    fun endFor(sessionId: UUID) {
        states.remove(sessionId)
        cancelScheduled(sessionId)
        encounters.despawnAllForSession(sessionId)
        core.actorController.despawnAllForSession(sessionId)
    }

    fun stateOf(sessionId: UUID): InstanceState? = states[sessionId]

    /** HanaTokiListener 的 PlayerInteractEvent handler 轉呼叫這裡。已在事發玩家 region 觸發。 */
    fun handleInteraction(playerId: UUID, location: Location, action: Action) {
        val (slotId, interactionId) = core.registry.findInteraction(location) ?: return
        val session = core.sessionManager.sessionBySlot(slotId) ?: return
        // ⚠ 觸發者必須是這個 session 的成員。少了這條,任何走到場地座標上的路人(或另一個
        // session 的玩家、管理員 spectator)按下方塊都會推進別人的謎題狀態——場地是共用世界的
        // 固定座標,不是私有維度,「人在那裡」不等於「他屬於這一局」。
        val playerSession = core.sessionManager.sessionOf(playerId)
        if (playerSession == null || playerSession.sessionId != session.sessionId) return
        val def = core.registry.definitions[session.dungeonId] ?: return
        val interDef = def.interactions[interactionId] ?: return
        val expectedAction = when (interDef.kind) {
            InteractionKind.RIGHT_CLICK -> action == Action.RIGHT_CLICK_BLOCK
            InteractionKind.PHYSICAL -> action == Action.PHYSICAL
        }
        if (!expectedAction) return
        val anchor = core.slotPool.anchorOf(slotId) ?: return
        val behavior = behaviorFor(session.dungeonId) ?: return
        InstanceDispatch.submit(core.plugin, anchor) {
            val state = states[session.sessionId] ?: return@submit
            val ctx = ctxFor(session.sessionId, session.dungeonId, slotId, anchor, state)
            behavior.onInteraction(ctx, interactionId, playerId)
        }
    }

    /** actor 綁定的實體死亡(Boss 型 actor 的勝利判定入口)。 */
    fun handleActorDeath(entityId: UUID) {
        val (sessionId, actorId) = core.actorController.actorIdOf(entityId) ?: return
        val session = core.sessionManager.sessionById(sessionId) ?: return
        val anchor = core.slotPool.anchorOf(session.slotId) ?: return
        val behavior = behaviorFor(session.dungeonId) ?: return
        InstanceDispatch.submit(core.plugin, anchor) {
            val state = states[sessionId] ?: return@submit
            behavior.onActorDeath(ctxFor(sessionId, session.dungeonId, session.slotId, anchor, state), actorId)
        }
    }

    /** GlobalRegionScheduler tick(ARCH §5.2 規則 5)——只做逾時檢查的派工轉發,不直接動狀態。 */
    fun tick(nowMs: Long) {
        for ((sessionId, state) in states) {
            if (!state.isStageTimedOut(nowMs)) continue
            val session = core.sessionManager.sessionById(sessionId) ?: continue
            val anchor = core.slotPool.anchorOf(session.slotId) ?: continue
            val behavior = behaviorFor(session.dungeonId) ?: continue
            val stageIdAtCheck = state.currentStageId
            InstanceDispatch.submit(core.plugin, anchor) {
                // 派工到執行時可能已經因為別的事件轉了 stage——重查一次避免對錯的 stage 觸發逾時。
                if (state.currentStageId != stageIdAtCheck || !state.isStageTimedOut(System.currentTimeMillis())) return@submit
                val ctx = ctxFor(sessionId, session.dungeonId, session.slotId, anchor, state)
                val target = state.stage().timeoutTransition
                // 二選一:定義了 timeout-transition 就直接轉場,否則交給 behavior 的 onStageTimeout
                // (預設實作 = resolve("timeout"))——兩者都做會導致轉場後又立刻被 Resolution 蓋掉。
                if (target != null) ctx.transition(target) else behavior.onStageTimeout(ctx, stageIdAtCheck)
            }
        }
    }

    internal fun trackScheduled(sessionId: UUID, task: ScheduledTask?) {
        if (task == null) return
        scheduled.computeIfAbsent(sessionId) { CopyOnWriteArrayList() } += task
    }

    /** stage 轉換與 session 結束都會呼叫:把上一段演出排的所有任務清掉。 */
    internal fun cancelScheduled(sessionId: UUID) {
        scheduled.remove(sessionId)?.forEach { runCatching { it.cancel() } }
    }

    private fun behaviorFor(dungeonId: String) = DungeonBehaviorRegistry.get(dungeonId)

    private fun ctxFor(sessionId: UUID, dungeonId: String, slotId: String, anchor: Location, state: InstanceState): StageContext =
        StageContextImpl(core, this, sessionId, dungeonId, slotId, anchor, state)
}

private class StageContextImpl(
    private val core: HanaTokiCore,
    private val engine: StageEngine,
    override val sessionId: UUID,
    override val dungeonId: String,
    override val slotId: String,
    override val anchor: Location,
    override val state: InstanceState,
) : StageContext {

    override fun activeMembers(): List<UUID> = core.sessionManager.sessionById(sessionId)?.activeMembers() ?: emptyList()

    override fun mutate(location: Location, action: java.util.function.Consumer<org.bukkit.block.Block>) =
        core.mutateSlot(slotId, location) { block -> action.accept(block) }

    override fun interactionLocation(interactionId: String): Location? =
        core.registry.interactionLocations[slotId]?.get(interactionId)

    override fun encounterLocation(encounterId: String): Location? =
        core.registry.encounterLocations[slotId]?.get(encounterId)

    // ---- 玩家操作:一律經 PlayerOp(該玩家自己的 EntityScheduler),見 StageContext KDoc ----

    override fun message(playerId: UUID, key: String) = message(playerId, key, emptyMap())

    override fun message(playerId: UUID, key: String, params: Map<String, String>) {
        PlayerOp.message(core.plugin, playerId, core.texts.format(key, params))
    }

    override fun messageAll(key: String) = messageAll(key, emptyMap())

    override fun messageAll(key: String, params: Map<String, String>) {
        val text = core.texts.format(key, params)
        activeMembers().forEach { PlayerOp.message(core.plugin, it, text) }
    }

    override fun title(playerId: UUID, titleKey: String, subtitleKey: String) {
        PlayerOp.title(
            core.plugin,
            playerId,
            core.texts.format(titleKey),
            if (subtitleKey.isEmpty()) Component.empty() else core.texts.format(subtitleKey),
            300,
            2_000,
            600,
        )
    }

    override fun titleAll(titleKey: String, subtitleKey: String) {
        activeMembers().forEach { title(it, titleKey, subtitleKey) }
    }

    override fun actionBar(playerId: UUID, key: String) {
        PlayerOp.actionBar(core.plugin, playerId, core.texts.format(key))
    }

    override fun actionBarAll(key: String) {
        val text = core.texts.format(key)
        activeMembers().forEach { PlayerOp.actionBar(core.plugin, it, text) }
    }

    override fun soundAll(location: Location, sound: Sound, volume: Float, pitch: Float) {
        activeMembers().forEach { PlayerOp.soundAt(core.plugin, it, location, sound, volume, pitch) }
    }

    override fun particles(location: Location, particle: Particle, count: Int, spreadX: Double, spreadY: Double, spreadZ: Double, extra: Double) {
        WorldOp.dispatchAt(core.plugin, location) { loc ->
            loc.world?.spawnParticle(particle, loc, count, spreadX, spreadY, spreadZ, extra)
        }
    }

    override fun damageMembersWithin(location: Location, radius: Double, amount: Double) {
        val radiusSq = radius * radius
        // 距離判定在**玩家自己的** EntityScheduler task 內做:讀的是他自己 region 的座標,
        // 比較對象是傳進來的不可變 Location。從 anchor region 讀別人的 location 才是跨 region 讀。
        activeMembers().forEach { playerId ->
            PlayerOp.dispatch(core.plugin, playerId) { player ->
                val here = player.location
                if (here.world != location.world) return@dispatch
                if (here.distanceSquared(location) > radiusSq) return@dispatch
                player.damage(amount)
            }
        }
    }

    override fun membersWithin(location: Location, radius: Double): CompletableFuture<List<UUID>> {
        val radiusSq = radius * radius
        val hits = java.util.concurrent.ConcurrentLinkedQueue<UUID>()
        val probes = activeMembers().map { playerId ->
            PlayerOp.dispatch(core.plugin, playerId) { player ->
                val here = player.location
                if (here.world == location.world && here.distanceSquared(location) <= radiusSq) hits += playerId
            }
        }
        return CompletableFuture.allOf(*probes.toTypedArray()).thenApply { hits.toList() }
    }

    override fun transition(stageId: String) {
        val behavior = DungeonBehaviorRegistry.get(dungeonId)
        val fromStageId = state.currentStageId
        behavior?.onStageExit(this, fromStageId)
        // 上一段 stage 排的演出排程一律取消——轉場後它們指向的狀態已經不存在了。
        engine.cancelScheduled(sessionId)
        state.enterStage(stageId, System.currentTimeMillis())
        behavior?.onStageEnter(this, stageId)
    }

    override fun resolve(resultKey: String) {
        core.resolveSession(sessionId, resultKey, state)
    }

    override fun requestCheck(playerId: UUID, checkId: String): CompletableFuture<String> =
        core.requestCheck(playerId, checkId)

    override fun musicCue(cueId: String) {
        activeMembers().forEach { core.musicCue(it, cueId) }
    }

    override fun spawnEncounter(encounterId: String, onEntityDeath: java.util.function.Consumer<StageContext>): CompletableFuture<Void> {
        val def = core.registry.definitions[dungeonId]?.encounters?.get(encounterId)
            ?: return CompletableFuture.completedFuture(null)
        val center = encounterLocation(encounterId) ?: return CompletableFuture.completedFuture(null)
        val type = try {
            EntityType.valueOf(def.entityType)
        } catch (e: IllegalArgumentException) {
            core.plugin.logger.warning("[HanaToki] encounter $encounterId 的 entity=${def.entityType} 不是已知的 EntityType")
            return CompletableFuture.completedFuture(null)
        }
        return engine.encounters.spawn(sessionId, encounterId, anchor, center, type, def.count, def.radius) {
            // EncounterController 保證這個 callback 已經在 anchor 所屬 region 序列化執行
            // (清空判定本身就在那裡做),這裡只要拿一份最新的 state 重建 ctx。
            val freshState = engine.stateOf(sessionId) ?: return@spawn
            onEntityDeath.accept(StageContextImpl(core, engine, sessionId, dungeonId, slotId, anchor, freshState))
        }
    }

    override fun despawnEncounter(encounterId: String) {
        engine.encounters.despawn(sessionId, encounterId)
    }

    override fun actors(): ActorHandle = core.actorController.handleFor(sessionId)

    override fun log(message: String) {
        core.plugin.logger.info("[HanaToki] [$dungeonId/$slotId] $message")
    }

    override fun submit(action: Runnable) {
        InstanceDispatch.submit(core.plugin, anchor) { action.run() }
    }

    override fun submitLater(delayTicks: Long, action: Runnable) {
        val task = org.bukkit.Bukkit.getRegionScheduler().runDelayed(
            core.plugin,
            anchor,
            { _ -> if (engine.stateOf(sessionId) != null) action.run() },
            delayTicks.coerceAtLeast(1L),
        )
        engine.trackScheduled(sessionId, task)
    }

    override fun submitRepeating(initialDelayTicks: Long, periodTicks: Long, action: Runnable) {
        val task = org.bukkit.Bukkit.getRegionScheduler().runAtFixedRate(
            core.plugin,
            anchor,
            { t -> if (engine.stateOf(sessionId) == null) t.cancel() else action.run() },
            initialDelayTicks.coerceAtLeast(1L),
            periodTicks.coerceAtLeast(1L),
        )
        engine.trackScheduled(sessionId, task)
    }
}
