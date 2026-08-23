package com.tinyyana.hanatoki.stage

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.config.InteractionKind
import com.tinyyana.hanatoki.encounter.EncounterController
import com.tinyyana.hanatoki.folia.InstanceDispatch
import com.tinyyana.hanatoki.reward.CompletionResult
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.event.block.Action
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

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
        encounters.despawnAllForSession(sessionId)
    }

    fun stateOf(sessionId: UUID): InstanceState? = states[sessionId]

    /** HanaTokiListener 的 PlayerInteractEvent handler 轉呼叫這裡。已在事發玩家 region 觸發。 */
    fun handleInteraction(playerId: UUID, location: Location, action: Action) {
        val (slotId, interactionId) = core.registry.findInteraction(location) ?: return
        val session = core.sessionManager.sessionBySlot(slotId) ?: return
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

    override fun mutate(location: Location, action: (org.bukkit.block.Block) -> Unit) =
        core.mutateSlot(slotId, location, action)

    override fun interactionLocation(interactionId: String): Location? =
        core.registry.interactionLocations[slotId]?.get(interactionId)

    override fun encounterLocation(encounterId: String): Location? =
        core.registry.encounterLocations[slotId]?.get(encounterId)

    override fun message(playerId: UUID, key: String, vararg params: Pair<String, String>) {
        val player = core.plugin.server.getPlayer(playerId) ?: return
        player.sendMessage(core.texts.format(key, *params))
    }

    override fun transition(stageId: String) {
        val behavior = DungeonBehaviorRegistry.get(dungeonId)
        val fromStageId = state.currentStageId
        behavior?.onStageExit(this, fromStageId)
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

    override fun spawnEncounter(encounterId: String, onEntityDeath: (StageContext) -> Unit): CompletableFuture<Unit> {
        val def = core.registry.definitions[dungeonId]?.encounters?.get(encounterId)
            ?: return CompletableFuture.completedFuture(Unit)
        val center = encounterLocation(encounterId) ?: return CompletableFuture.completedFuture(Unit)
        val type = try {
            EntityType.valueOf(def.entityType)
        } catch (e: IllegalArgumentException) {
            core.plugin.logger.warning("[HanaToki] encounter $encounterId 的 entity=${def.entityType} 不是已知的 EntityType")
            return CompletableFuture.completedFuture(Unit)
        }
        return engine.encounters.spawn(sessionId, encounterId, center, type, def.count, def.radius) {
            InstanceDispatch.submit(core.plugin, anchor) {
                val freshState = engine.stateOf(sessionId) ?: return@submit
                onEntityDeath(StageContextImpl(core, engine, sessionId, dungeonId, slotId, anchor, freshState))
            }
        }
    }

    override fun despawnEncounter(encounterId: String) {
        engine.encounters.despawn(sessionId, encounterId)
    }

    override fun log(message: String) {
        core.plugin.logger.info("[HanaToki] [$dungeonId/$slotId] $message")
    }

    override fun submit(action: () -> Unit) {
        InstanceDispatch.submit(core.plugin, anchor, action)
    }
}
