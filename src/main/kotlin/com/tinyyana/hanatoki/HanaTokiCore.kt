package com.tinyyana.hanatoki

import com.tinyyana.hanatoki.api.DungeonInfo
import com.tinyyana.hanatoki.api.MusicCue
import com.tinyyana.hanatoki.api.PresenceBridge
import com.tinyyana.hanatoki.check.CheckResolver
import com.tinyyana.hanatoki.config.DungeonRegistry
import com.tinyyana.hanatoki.folia.InstanceDispatch
import com.tinyyana.hanatoki.folia.WorldOp
import com.tinyyana.hanatoki.instance.EndReason
import com.tinyyana.hanatoki.instance.EnterResult
import com.tinyyana.hanatoki.instance.SessionManager
import com.tinyyana.hanatoki.instance.SlotPool
import com.tinyyana.hanatoki.reward.CompletionResult
import com.tinyyana.hanatoki.reward.RewardDispatcher
import com.tinyyana.hanatoki.stage.InstanceState
import com.tinyyana.hanatoki.stage.StageEngine
import com.tinyyana.hanatoki.text.Texts
import com.tinyyana.hanatoki.world.WorldDiffRecorder
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 instance/world/folia/config/stage/check/reward 幾個模組串起來的執行期核心,HanaTokiPlugin
 * 只做 Bukkit lifecycle 接線,狀態機邏輯都在這裡(方便 Phase 2+ 擴充,不塞進 plugin 主類別)。
 */
class HanaTokiCore(val plugin: Plugin) : PresenceBridge {
    val slotPool = SlotPool<Location>()
    val sessionManager = SessionManager<Location>(slotPool)
    val registry = DungeonRegistry(plugin, plugin.logger)
    val texts = Texts()
    val rewardDispatcher = RewardDispatcher(plugin)
    val stageEngine = StageEngine(this)

    // 每個 slot 一份 diff recorder(Phase 1 場地重置的最小單位是 slot,不是整個 instance)。
    private val diffRecorders = ConcurrentHashMap<String, WorldDiffRecorder>()

    fun diffRecorderFor(slotId: String): WorldDiffRecorder =
        diffRecorders.computeIfAbsent(slotId) { WorldDiffRecorder(plugin) }

    /** [com.tinyyana.hanatoki.stage.StageContext.mutate] 的實作面:mutation 前先記錄 before-state 再 apply。 */
    fun mutateSlot(slotId: String, location: Location, action: (org.bukkit.block.Block) -> Unit): CompletableFuture<Void> {
        val recorder = diffRecorderFor(slotId)
        return WorldOp.dispatch(plugin, location) { block ->
            recorder.record(location, block.blockData)
            action(block)
        }
    }

    /** ARCH §9:CheckResolver 缺席時回傳 `"unavailable"`,由 behavior 的 fail-safe 分支接手(§9 末段)。 */
    fun requestCheck(playerId: UUID, checkId: String): CompletableFuture<String> {
        val resolver = plugin.server.servicesManager.getRegistration(CheckResolver::class.java)?.provider
            ?: return CompletableFuture.completedFuture("unavailable")
        return resolver.resolve(playerId, checkId)
    }

    /** ARCH §4「MusicCue」:integration 缺席時無聲降級。 */
    fun musicCue(playerId: UUID, cueId: String) {
        val cue = plugin.server.servicesManager.getRegistration(MusicCue::class.java)?.provider ?: return
        cue.cue(playerId, cueId)
    }

    fun enter(dungeonId: String, players: List<Player>): EnterResult<Location> {
        val def = registry.definitions[dungeonId]
            ?: return EnterResult.NoSlot
        val now = System.currentTimeMillis()
        val result = sessionManager.enter(
            dungeonId,
            players.map { it.uniqueId },
            now,
            def.sessionTimeLimitSeconds * 1000,
            def.reconnectGraceSeconds * 1000,
        )
        if (result is EnterResult.Entered) {
            stageEngine.startFor(result.session.sessionId, dungeonId, result.session.slotId, result.anchor, now)
        }
        return result
    }

    /** GlobalRegionScheduler.runAtFixedRate 驅動的 tick 訊號(ARCH §5.2 規則 5)。*/
    fun tick() {
        val now = System.currentTimeMillis()
        val ended = sessionManager.tick(now)
        for (e in ended) {
            stageEngine.endFor(e.sessionId)
            handleSessionEnded(e.slotId, e.dungeonId, e.reason)
        }
        stageEngine.tick(now)
    }

    fun kick(playerId: UUID) {
        val ended = sessionManager.kick(playerId) ?: return
        stageEngine.endFor(ended.sessionId)
        handleSessionEnded(ended.slotId, ended.dungeonId, ended.reason)
    }

    /** `/hanatoki admin reset <slotId>`:強制結束該 slot 上的 session(不論人是否還在)。*/
    fun adminReset(slotId: String) {
        val session = sessionManager.sessionBySlot(slotId) ?: run {
            // 沒有 session 掛著,但 slot 可能因為某次異常留在 occupied——直接清空。
            slotPool.free(slotId)
            return
        }
        session.memberIds().forEach { sessionManager.kick(it) }
        stageEngine.endFor(session.sessionId)
        handleSessionEnded(slotId, session.dungeonId, EndReason.ADMIN_RESET)
    }

    /**
     * ARCH §2「Resolution」:[com.tinyyana.hanatoki.stage.StageContext.resolve] 的實作面。
     * 對每位仍在場的成員各自產生一筆帶唯一 completionId 的 [CompletionResult](ARCH §4「每次
     * Resolution 唯一」——這裡的解讀是每位玩家各自一筆,理由見 HANDOFF/PR 說明:
     * `CompletionResult.playerId` 是單數,一筆一人才對得上幂等語意),交給 [rewardDispatcher]
     * 派送,再走既有的 session 結束/世界回滾流程(跟 timeout/admin reset 共用同一條路)。
     */
    fun resolveSession(sessionId: UUID, resultKey: String, state: InstanceState) {
        val session = sessionManager.sessionById(sessionId) ?: return
        val durationMs = System.currentTimeMillis() - session.startedAtMs
        val members = session.activeMembers()
        for (playerId in members) {
            rewardDispatcher.dispatch(
                CompletionResult(
                    completionId = UUID.randomUUID(),
                    playerId = playerId,
                    dungeonId = session.dungeonId,
                    resultKey = resultKey,
                    durationMs = durationMs,
                    stats = state.stats.toMap(),
                ),
            )
        }
        val ended = sessionManager.endSession(sessionId, EndReason.RESOLVED) ?: return
        stageEngine.endFor(sessionId)
        handleSessionEnded(ended.slotId, ended.dungeonId, ended.reason)
    }

    private fun handleSessionEnded(slotId: String, dungeonId: String, reason: EndReason) {
        val world = registry.definitions[dungeonId]?.let { plugin.server.getWorld(it.worldName) }
        val anchor = slotPool.anchorOf(slotId)
        // recorder 只在真的發生過 mutation 時才會被建立(diffRecorderFor 是 lazy)——沒有 recorder
        // 就代表這個 slot 這局根本沒有 diff 要回滾,不是「找不到世界/anchor」的異常情況,
        // 不該混在同一個警告訊息裡(2026-08-23 L3 測試抓到:offline-grace 結束的 session 沒碰過
        // 任何方塊,recorder 是 null,卻被原本的邏輯誤判成「找不到世界或 anchor」)。
        val recorder = diffRecorders[slotId]
        when {
            world != null && anchor != null && recorder != null -> {
                InstanceDispatch.submit(plugin, anchor) {
                    recorder.rollback(world).whenComplete { _, _ ->
                        sessionManager.releaseSlotAfterRollback(slotId)
                    }
                }
            }
            world != null && anchor != null -> {
                // 没有 recorder = 這局沒有任何 mutation,沒有東西要回滾,直接釋放。
                sessionManager.releaseSlotAfterRollback(slotId)
            }
            else -> {
                // 真的找不到世界或 anchor(例如世界已卸載)——不阻塞收斂,直接釋放並記警告。
                plugin.logger.warning("[HanaToki] slot=$slotId 結束時找不到世界或 anchor,略過回滾直接釋放")
                sessionManager.releaseSlotAfterRollback(slotId)
            }
        }
    }

    /** onDisable 收斂:凍結新進場已由呼叫端(HanaTokiPlugin)控制;這裡把所有 session 結為 abandoned。*/
    fun shutdownAll() {
        val ended = sessionManager.endAll(EndReason.ABANDONED)
        for (e in ended) {
            stageEngine.endFor(e.sessionId)
            handleSessionEnded(e.slotId, e.dungeonId, e.reason)
        }
    }

    /** ARCH §10 / Phase 2 目標 §7:未來 GUI 的資料來源,現在只有 `/hanatoki admin debug` 消費。 */
    fun listDungeonInfo(): List<DungeonInfo> = registry.definitions.values.map { def ->
        DungeonInfo(
            id = def.id,
            displayName = def.display,
            description = def.description,
            expectedMinutes = def.expectedMinutes,
            tags = def.tags,
            freeSlots = slotPool.freeCount(def.id),
            totalSlots = slotPool.totalCount(def.id),
            activeSessionCount = sessionManager.snapshot().count { it.dungeonId == def.id },
        )
    }

    // ---- PresenceBridge(ARCH §4,HanaToki 是 provider)----
    override fun isInside(playerId: UUID): Boolean = sessionManager.sessionOf(playerId) != null
    override fun dungeonIdOf(playerId: UUID): String? = sessionManager.sessionOf(playerId)?.dungeonId

    /**
     * Phase 1 沒有 stage/interaction 系統會真的觸發方塊 mutation——world/diff 回滾機制本身
     * 要單獨驗證(L3/L4 要求「500 方塊 mutation 後 rollback 還原」),所以留這個測試專用 hook,
     * 比照 Phase 0 spike 的 `/spike03` 精神:只是把已經寫好的 [WorldOp]/[WorldDiffRecorder]
     * 接一個可從指令觸發的路徑,不是新的玩法功能。Phase 2 接 Stage/Interaction 後,真正的
     * mutation 呼叫點會換成 stage 邏輯,這個 hook 屆時可以移除。
     */
    fun testMutateSlot(slotId: String, count: Int): java.util.concurrent.CompletableFuture<Int> {
        val future = java.util.concurrent.CompletableFuture<Int>()
        val anchor = slotPool.anchorOf(slotId) ?: run { future.complete(0); return future }
        val world = anchor.world ?: run { future.complete(0); return future }
        val recorder = diffRecorderFor(slotId)
        InstanceDispatch.submit(plugin, anchor) {
            var done = 0
            val baseX = anchor.blockX
            val baseY = anchor.blockY
            val baseZ = anchor.blockZ
            val side = Math.ceil(Math.cbrt(count.toDouble())).toInt().coerceAtLeast(1)
            outer@ for (dx in 0 until side) {
                for (dy in 0 until side) {
                    for (dz in 0 until side) {
                        if (done >= count) break@outer
                        val loc = Location(world, (baseX + dx).toDouble(), (baseY + dy).toDouble(), (baseZ + dz).toDouble())
                        val block = loc.block
                        recorder.record(loc, block.blockData)
                        block.setType(org.bukkit.Material.GOLD_BLOCK, false)
                        done++
                    }
                }
            }
            future.complete(done)
        }
        return future
    }

    /** 觸發該 slot 的回滾,回傳 future 供測試等待完成後讀回方塊驗證。*/
    fun testRollbackSlot(slotId: String): java.util.concurrent.CompletableFuture<Void> {
        val anchor = slotPool.anchorOf(slotId) ?: return java.util.concurrent.CompletableFuture.completedFuture(null)
        val world = anchor.world ?: return java.util.concurrent.CompletableFuture.completedFuture(null)
        val recorder = diffRecorderFor(slotId)
        val future = java.util.concurrent.CompletableFuture<Void>()
        InstanceDispatch.submit(plugin, anchor) {
            recorder.rollback(world).whenComplete { _, _ -> future.complete(null) }
        }
        return future
    }
}
