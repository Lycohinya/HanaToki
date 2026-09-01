package com.tinyyana.hanatoki.stage

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.actor.ActorHandle
import com.tinyyana.hanatoki.config.InteractionKind
import com.tinyyana.hanatoki.encounter.DynamicEncounterController
import com.tinyyana.hanatoki.encounter.DynamicEncounterHandle
import com.tinyyana.hanatoki.encounter.EncounterController
import com.tinyyana.hanatoki.folia.InstanceDispatch
import com.tinyyana.hanatoki.folia.PlayerOp
import com.tinyyana.hanatoki.folia.WorldOp
import com.tinyyana.hanatoki.prop.PropHandle
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

    /** 動態 encounter(Roguelike Director 用)。定義驅動的 [encounters] 原樣保留給刀塚/蒼櫻。 */
    val dynamicEncounters = DynamicEncounterController(core.plugin)

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
        sessionMeta[sessionId] = SessionMeta(dungeonId, slotId, anchor)
        // enter() 的呼叫端可能是任意執行緒(指令發出者的 region)——behavior callback 一律要求
        // 已在 anchor 所屬 region 的 task 內執行(ARCH §5.1②),這裡補一次 submit。
        InstanceDispatch.submit(core.plugin, anchor) {
            val ctx = ctxFor(sessionId, dungeonId, slotId, anchor, state)
            behavior.onStageEnter(ctx, state.currentStageId)
        }
    }

    fun endFor(sessionId: UUID, reason: String) {
        val state = states.remove(sessionId)
        cancelScheduled(sessionId)
        encounters.despawnAllForSession(sessionId)
        dynamicEncounters.despawnAllForSession(sessionId)
        // 內容層的收尾回呼。session 登記表這時可能已經被拿掉(resolveSession 先 endSession 再
        // 到這裡),所以 dungeon/slot/anchor 不能再從 sessionManager 查,要從 startFor 記的那份拿。
        val meta = sessionMeta.remove(sessionId)
        if (state != null && meta != null) {
            val behavior = behaviorFor(meta.dungeonId)
            if (behavior != null) {
                InstanceDispatch.submit(core.plugin, meta.anchor) {
                    try {
                        behavior.onSessionEnd(ctxFor(sessionId, meta.dungeonId, meta.slotId, meta.anchor, state), reason)
                    } catch (t: Throwable) {
                        core.plugin.logger.warning("[HanaToki] ${meta.dungeonId} 的 onSessionEnd 丟出例外:${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
        }
        core.actorController.despawnAllForSession(sessionId)
        core.propController.despawnAllForSession(sessionId)
        core.bossBars.clear(sessionId)
    }

    fun stateOf(sessionId: UUID): InstanceState? = states[sessionId]

    /** [endFor] 時 sessionManager 可能已經查不到,所以 dungeon/slot/anchor 在 [startFor] 就記一份。 */
    private class SessionMeta(val dungeonId: String, val slotId: String, val anchor: Location)
    private val sessionMeta = ConcurrentHashMap<UUID, SessionMeta>()

    /**
     * 進場交易整個完成之後(DungeonEntry 呼叫):通知 behavior 這位成員可以收局內物品了。
     * 沒有 stage 圖的副本(Phase 1 test-empty)沒有 behavior,直接略過。
     */
    fun notifyMemberReady(sessionId: UUID, playerId: UUID) {
        val state = states[sessionId] ?: return
        val meta = sessionMeta[sessionId] ?: return
        val behavior = behaviorFor(meta.dungeonId) ?: return
        InstanceDispatch.submit(core.plugin, meta.anchor) {
            if (states[sessionId] !== state) return@submit
            behavior.onMemberReady(ctxFor(sessionId, meta.dungeonId, meta.slotId, meta.anchor, state), playerId)
        }
    }

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

    override fun mutatePersistentBatch(
        locations: List<Location>,
        action: java.util.function.Consumer<org.bukkit.block.Block>,
    ) = core.mutateSlotPersistentBatch(locations) { block -> action.accept(block) }

    override fun readBlock(location: Location, reader: java.util.function.Consumer<org.bukkit.block.Block>) =
        WorldOp.dispatch(core.plugin, location) { block -> reader.accept(block) }

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

    override fun title(playerId: UUID, titleKey: String, subtitleKey: String) =
        title(playerId, titleKey, subtitleKey, emptyMap())

    override fun title(playerId: UUID, titleKey: String, subtitleKey: String, params: Map<String, String>) {
        PlayerOp.title(
            core.plugin,
            playerId,
            core.texts.format(titleKey, params),
            if (subtitleKey.isEmpty()) Component.empty() else core.texts.format(subtitleKey, params),
            300,
            2_000,
            600,
        )
    }

    override fun titleAll(titleKey: String, subtitleKey: String) = titleAll(titleKey, subtitleKey, emptyMap())

    override fun titleAll(titleKey: String, subtitleKey: String, params: Map<String, String>) {
        activeMembers().forEach { title(it, titleKey, subtitleKey, params) }
    }

    override fun actionBar(playerId: UUID, key: String) = actionBar(playerId, key, emptyMap())

    override fun actionBar(playerId: UUID, key: String, params: Map<String, String>) {
        PlayerOp.actionBar(core.plugin, playerId, core.texts.format(key, params))
    }

    override fun actionBarAll(key: String) = actionBarAll(key, emptyMap())

    override fun actionBarAll(key: String, params: Map<String, String>) {
        val text = core.texts.format(key, params)
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

    override fun damageMembersWithin(location: Location, radius: Double, amount: Double, damageTypeKey: String) {
        val type = resolveDamageType(damageTypeKey)
        if (type == null) {
            core.plugin.logger.warning("[HanaToki] 未知的 DamageType「$damageTypeKey」,這一招退回預設傷害管道")
            damageMembersWithin(location, radius, amount)
            return
        }
        val source = org.bukkit.damage.DamageSource.builder(type).withDamageLocation(location).build()
        val radiusSq = radius * radius
        activeMembers().forEach { playerId ->
            PlayerOp.dispatch(core.plugin, playerId) { player ->
                val here = player.location
                if (here.world != location.world) return@dispatch
                if (here.distanceSquared(location) > radiusSq) return@dispatch
                player.damage(amount, source)
            }
        }
    }

    private fun resolveDamageType(key: String): org.bukkit.damage.DamageType? {
        val namespaced = org.bukkit.NamespacedKey.fromString(key) ?: return null
        return runCatching {
            io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.DAMAGE_TYPE)
                .get(namespaced)
        }.getOrNull()
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

    override fun nearestMemberDirection(location: Location): CompletableFuture<DoubleArray> {
        // 每位成員各自在自己的 region 算出「我離這個座標多遠、在哪個方向」,再由這裡挑最近的。
        val found = java.util.concurrent.ConcurrentLinkedQueue<DoubleArray>()
        val probes = activeMembers().map { playerId ->
            PlayerOp.dispatch(core.plugin, playerId) { player ->
                val here = player.location
                if (here.world != location.world) return@dispatch
                val dx = here.x - location.x
                val dz = here.z - location.z
                val dist = Math.hypot(dx, dz)
                if (dist < 1.0E-4) return@dispatch
                found += doubleArrayOf(dx / dist, dz / dist, dist)
            }
        }
        return CompletableFuture.allOf(*probes.toTypedArray())
            .thenApply { found.minByOrNull { it[2] } ?: DoubleArray(0) }
    }

    override fun memberPositions(): CompletableFuture<Map<UUID, DoubleArray>> {
        val found = java.util.concurrent.ConcurrentHashMap<UUID, DoubleArray>()
        val world = anchor.world
        val probes = activeMembers().map { playerId ->
            PlayerOp.dispatch(core.plugin, playerId) { player ->
                val here = player.location
                if (here.world != world) return@dispatch
                found[playerId] = doubleArrayOf(here.x, here.y, here.z)
            }
        }
        return CompletableFuture.allOf(*probes.toTypedArray()).thenApply { found.toMap() }
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

    override fun dynamicEncounters(): DynamicEncounterHandle {
        val limits = core.registry.definitions[dungeonId]?.dynamicEncounters
            ?: com.tinyyana.hanatoki.config.DynamicEncounterLimits()
        return engine.dynamicEncounters.handleFor(sessionId, anchor, limits)
    }

    override fun actors(): ActorHandle = core.actorController.handleFor(sessionId)

    override fun props(): PropHandle = core.propController.handleFor(sessionId)

    override fun bossBar(text: String, progress: Double, colorName: String) {
        core.bossBars.update(sessionId, activeMembers(), text, progress, colorName)
    }

    override fun bossBar(text: String, progress: Double, colorName: String, overlayName: String) {
        core.bossBars.update(sessionId, activeMembers(), text, progress, colorName, overlayName)
    }

    override fun hideBossBar() {
        core.bossBars.clear(sessionId)
    }

    override fun sessionRemainingSeconds(): Long {
        val session = core.sessionManager.sessionById(sessionId) ?: return 0
        val remaining = session.remainingMs(System.currentTimeMillis())
        // NO_TIME_LIMIT(-1)原樣往上傳,不要除以 1000 變成 0——那會讓 Endless Run 的 HUD
        // 顯示成「時間到」。
        if (remaining == com.tinyyana.hanatoki.instance.Session.NO_TIME_LIMIT) return remaining
        return remaining / 1000
    }

    override fun sessionHasTimeLimit(): Boolean =
        core.sessionManager.sessionById(sessionId)?.hasTimeLimit() ?: false

    override fun sessionElapsedSeconds(): Long {
        val session = core.sessionManager.sessionById(sessionId) ?: return 0
        return session.elapsedMs(System.currentTimeMillis()) / 1000
    }

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
