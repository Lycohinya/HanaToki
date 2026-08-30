package com.tinyyana.hanatoki

import com.tinyyana.hanatoki.actor.ActorController
import com.tinyyana.hanatoki.api.DungeonAccess
import com.tinyyana.hanatoki.api.DungeonEntryOutcome
import com.tinyyana.hanatoki.api.DungeonEntryStatus
import com.tinyyana.hanatoki.api.DungeonInfo
import com.tinyyana.hanatoki.api.MusicCue
import com.tinyyana.hanatoki.api.PresenceBridge
import com.tinyyana.hanatoki.check.CheckResolver
import com.tinyyana.hanatoki.config.DungeonDefinition
import com.tinyyana.hanatoki.config.DungeonRegistry
import com.tinyyana.hanatoki.config.ExecutionMode
import com.tinyyana.hanatoki.folia.InstanceDispatch
import com.tinyyana.hanatoki.folia.PlayerOp
import com.tinyyana.hanatoki.folia.WorldOp
import com.tinyyana.hanatoki.hud.SessionBossBars
import com.tinyyana.hanatoki.instance.DungeonEntry
import com.tinyyana.hanatoki.instance.EndReason
import com.tinyyana.hanatoki.instance.EnterResult
import com.tinyyana.hanatoki.instance.SessionManager
import com.tinyyana.hanatoki.instance.SlotPool
import com.tinyyana.hanatoki.inventory.InstanceInventoryService
import com.tinyyana.hanatoki.inventory.InstanceItemGuard
import com.tinyyana.hanatoki.inventory.InstanceJournal
import com.tinyyana.hanatoki.prop.PropController
import com.tinyyana.hanatoki.reward.CompletionResult
import com.tinyyana.hanatoki.reward.RewardDispatcher
import com.tinyyana.hanatoki.stage.InstanceState
import com.tinyyana.hanatoki.stage.StageEngine
import com.tinyyana.hanatoki.text.Texts
import com.tinyyana.hanatoki.world.DungeonWorldProvisioner
import com.tinyyana.hanatoki.world.ReturnPointRegistry
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
class HanaTokiCore(val plugin: Plugin) : PresenceBridge, DungeonAccess {
    val slotPool = SlotPool<Location>()
    val sessionManager = SessionManager<Location>(slotPool)
    val worldProvisioner = DungeonWorldProvisioner(plugin)
    val registry = DungeonRegistry(plugin, plugin.logger, worldProvisioner)
    val returnPoints = ReturnPointRegistry { name -> isDungeonWorld(name) }
    val texts = Texts()
    val rewardDispatcher = RewardDispatcher(plugin)
    val actorController = ActorController(plugin)
    val propController = PropController(plugin)
    val bossBars = SessionBossBars(plugin)
    val stageEngine = StageEngine(this)

    /**
     * 局內背包的持久化 journal 與交易服務(ARCH §5.6)。
     *
     * 檔案放在 `plugins/HanaToki/instances/`——引擎自己的 dataFolder,不碰任何其他插件的
     * 資料層。這是 HanaToki 第一份持久化狀態,理由與範圍見 [InstanceJournal] 的 KDoc。
     */
    val instanceJournal = InstanceJournal(java.io.File(plugin.dataFolder, "instances"), plugin.logger)
    val instanceInventory = InstanceInventoryService(plugin, instanceJournal)
    val instanceItemGuard = InstanceItemGuard(plugin, instanceInventory, texts)
    private val dungeonEntry = DungeonEntry(this)

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

    /**
     * ARCH §4「MusicCue」:integration 缺席時無聲降級。
     *
     * 派工到**該玩家自己的 EntityScheduler**(ARCH §5.2 規則 2):BGM 最終是對這位玩家送封包,
     * 跟 message/title/sound 同一類操作,不該從 instance 的 anchor region 直接打過去。
     * 這同時保證 cue 是「stage 進入的當下就送出」而不是等下一輪輪詢——現況蒼櫻的 BGM 要等
     * MusicService 的下一次輪詢才切,玩家開打了音樂還沒進來,那個問題在 HanaToki 這條路徑
     * 結構上不存在(見 `LycohinyaCore` 的 MusicService 修正)。
     */
    fun musicCue(playerId: UUID, cueId: String) {
        val cue = plugin.server.servicesManager.getRegistration(MusicCue::class.java)?.provider ?: return
        PlayerOp.dispatch(plugin, playerId) { cue.cue(playerId, cueId) }
    }

    fun enter(dungeonId: String, players: List<Player>): EnterResult<Location> {
        val def = registry.definitions[dungeonId]
            ?: return EnterResult.NoSlot
        val now = System.currentTimeMillis()
        val persistent = def.mode == ExecutionMode.PERSISTENT
        val result = sessionManager.enter(
            dungeonId,
            players.map { it.uniqueId },
            now,
            // null = 沒有時限。常駐副本本來就沒有;session 型副本也可以在定義裡寫
            // `session-time-limit-seconds: unlimited`(Endless Run)。以前這裡塞的是
            // `Long.MAX_VALUE`,那個假無限值會讓 `sessionRemainingSeconds()` 回傳 2.9 億年。
            if (persistent) null else def.sessionTimeLimitSeconds?.times(1000),
            def.reconnectGraceSeconds * 1000,
            persistent,
        )
        // ⚠ 只有 Entered(真的開了新 instance)才啟動 stage 狀態機。Joined = 加入一個正在跑的
        //   常駐 instance,重跑 startFor 會把它重置回起始 stage(Boss 打到一半有人進場就消失)。
        if (result is EnterResult.Entered) {
            stageEngine.startFor(result.session.sessionId, dungeonId, result.session.slotId, result.anchor, now)
        }
        return result
    }

    /**
     * 進場落點:slot anchor + 定義裡的 spawn 偏移與朝向(見 [DungeonDefinition.spawnOffsetX])。
     * 預設偏移是 0 = anchor 本身,既有副本行為不變。
     */
    fun entryLocationFor(def: DungeonDefinition, anchor: Location): Location =
        anchor.clone().add(def.spawnOffsetX, def.spawnOffsetY, def.spawnOffsetZ)
            .also { it.yaw = def.spawnYaw }

    // ---- 常駐副本:成員資格跟著「人在不在那個世界」走(MIGRATION_PLAN §5.0 決策 D)----

    /** 這個世界是不是某座常駐副本的世界;是的話回傳它的 dungeonId。 */
    fun persistentDungeonIdForWorld(worldName: String): String? = registry.persistentDungeonIdByWorld[worldName]

    /**
     * 玩家出現在某個常駐副本世界裡 → 把他加進那個 instance 的成員集合(必要時建立 instance)。
     *
     * 蒼櫻現況「沒有隊伍系統,誰站在場地裡就算誰」的直譯:進場、跨世界走進來、在裡面登入、
     * 在裡面死掉重生,全部走同一條路。回傳 true = 現在是(或本來就是)那座副本的成員。
     */
    fun joinPersistentByWorld(playerId: UUID, worldName: String): Boolean {
        val dungeonId = persistentDungeonIdForWorld(worldName) ?: return false
        val current = sessionManager.sessionOf(playerId)
        if (current != null) return current.dungeonId == dungeonId
        val def = registry.definitions[dungeonId] ?: return false
        val now = System.currentTimeMillis()
        val result = sessionManager.enter(
            dungeonId,
            listOf(playerId),
            now,
            null, // 常駐 instance 沒有時限(見 enter() 的說明)
            def.reconnectGraceSeconds * 1000,
            true,
        )
        if (result is EnterResult.Entered) {
            stageEngine.startFor(result.session.sessionId, dungeonId, result.session.slotId, result.anchor, now)
        }
        return result !is EnterResult.NoSlot
    }

    /**
     * 玩家離開了常駐副本世界 → 從成員集合移除。instance 本身不會因此結束
     * ([com.tinyyana.hanatoki.instance.Session.isAllDropped] 對 persistent 恆 false)。
     */
    fun leavePersistent(playerId: UUID) {
        val session = sessionManager.sessionOf(playerId) ?: return
        if (!session.persistent) return
        sessionManager.kick(playerId)
    }

    /** GlobalRegionScheduler.runAtFixedRate 驅動的 tick 訊號(ARCH §5.2 規則 5)。*/
    fun tick() {
        val now = System.currentTimeMillis()
        val ended = sessionManager.tick(now)
        for (e in ended) {
            stageEngine.endFor(e.sessionId, e.reason.name)
            handleSessionEnded(e.slotId, e.dungeonId, e.reason, e.memberIds)
        }
        stageEngine.tick(now)
    }

    fun kick(playerId: UUID) {
        val ended = sessionManager.kick(playerId)
        // 這個人的永久背包一定要還,不論整局有沒有跟著結束。多人局裡其他人還在打時
        // `ended` 是 null,下面那條 handleSessionEnded 不會跑到——少了這一句,單獨離場的
        // 那位就會帶著局內背包走出去,而他的永久背包留在 journal 裡等到下次登入才還。
        //
        // ⚠ 送人回家一定要**等還原完成**才做(同 handleSessionEnded 的說明:跨世界傳送會
        //   讓還原派工撞上 retired,實測會讓玩家拿到空背包)。
        instanceInventory.restoreForPlayer(playerId, "leave").whenComplete { _, _ ->
            // 這個人一定要送出去,不論整局有沒有跟著結束——多人局裡其他人還在打,離開的那位
            // 如果留在副本世界,他會站在一個沒有 session 綁定的場地裡看別人打(而且整局結束的
            // diff 回滾會在他腳下發生)。
            sendHome(playerId)
        }
        if (ended == null) return
        stageEngine.endFor(ended.sessionId, ended.reason.name)
        handleSessionEnded(ended.slotId, ended.dungeonId, ended.reason, ended.memberIds)
    }

    /** 這個世界是不是 HanaToki 自己的副本世界(定義裡 `world-create: true` 的那些)。 */
    fun isDungeonWorld(worldName: String): Boolean = worldName in registry.dungeonWorldNames

    /**
     * 把玩家送回進場前的位置(沒有登記就走重生點/主世界)。人不在線、或人根本不在副本世界裡
     * 就是 no-op——後者很重要:指向生存主世界的副本(`world-create: false`)結束時不該把人瞬移走。
     *
     * 回傳的 future 在**傳送真的完成**之後才 complete,不是「派工完成」——場地回滾要等它
     * (見 [handleSessionEnded])。
     */
    fun sendHome(playerId: UUID): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        PlayerOp.dispatch(plugin, playerId) { player ->
            if (!isDungeonWorld(player.world.name)) {
                returnPoints.forget(playerId)
                done.complete(null)
                return@dispatch
            }
            val destination = returnPoints.destinationFor(player)
            if (destination == null) {
                plugin.logger.warning("[HanaToki] 找不到 ${player.name} 的返回點,也沒有任何非副本世界可送")
                done.complete(null)
                return@dispatch
            }
            player.teleportAsync(destination).whenComplete { _, _ -> done.complete(null) }
        }.whenComplete { _, _ ->
            // 玩家離線時 dispatch 立刻完成而 action 根本沒跑,上面的 done 不會有人 complete。
            if (plugin.server.getPlayer(playerId) == null) done.complete(null)
        }
        return done
    }

    /** `/hanatoki admin reset <slotId>`:強制結束該 slot 上的 session(不論人是否還在)。*/
    fun adminReset(slotId: String) {
        val session = sessionManager.sessionBySlot(slotId) ?: run {
            // 沒有 session 掛著,但 slot 可能因為某次異常留在 occupied——直接清空。
            slotPool.free(slotId)
            return
        }
        val members = session.memberIds()
        members.forEach { sessionManager.kick(it) }
        stageEngine.endFor(session.sessionId, EndReason.ADMIN_RESET.name)
        handleSessionEnded(slotId, session.dungeonId, EndReason.ADMIN_RESET, members)
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
        val now = System.currentTimeMillis()
        // 這一段 stage 只結算一次(見 InstanceState.claimResolution)。對 session 型副本這是
        // 第一道保險(第二道仍然是下面 endSession 的原子認領);對常駐副本這是**唯一**一道
        // ——它不結束 session,拿不到那個認領。
        if (!state.claimResolution()) return
        val members = session.activeMembers()
        if (session.persistent) {
            // MIGRATION_PLAN §5.0 決策 C:常駐 instance 的 Resolution 只結算「這一輪」。
            // 不結束 session、不送人回家、不回滾場地、不歸還 slot——接下來要做什麼(轉回待機、
            // 開始重生冷卻)由 behavior 自己 `transition`,引擎不替它決定。
            // duration 用 **stage 已經過的時間**而不是 instance 壽命:常駐 instance 從開服活到
            // 關服,`now - startedAtMs` 是沒有意義的數字。
            val durationMs = state.stageElapsedMs(now)
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
            return
        }
        val durationMs = now - session.startedAtMs
        // ⚠ 順序:**先原子認領這個 session**(endSession 內部是 `sessions.remove` 的回傳值),
        // 認領成功才發獎。反過來寫的話,同一局被兩條路徑同時結算(behavior 的 resolve 撞上
        // 逾時 tick,或 behavior 自己不小心呼叫兩次 resolve)會產生**兩組不同的 completionId**,
        // integration 端的 completionId 幂等去重完全擋不住——那是「同一局兩次結算」不是「同一筆
        // 送兩次」。2026-08-23 Phase 4 補測時發現的洞。
        val ended = sessionManager.endSession(sessionId, EndReason.RESOLVED) ?: return
        for (playerId in members) {
            rewardDispatcher.dispatch(
                CompletionResult(
                    completionId = UUID.randomUUID(),
                    playerId = playerId,
                    dungeonId = ended.dungeonId,
                    resultKey = resultKey,
                    durationMs = durationMs,
                    stats = state.stats.toMap(),
                ),
            )
        }
        stageEngine.endFor(sessionId, EndReason.RESOLVED.name)
        handleSessionEnded(ended.slotId, ended.dungeonId, ended.reason, ended.memberIds)
    }

    private fun handleSessionEnded(slotId: String, dungeonId: String, reason: EndReason, memberIds: List<UUID>) {
        // ⚠ 送人 → **等傳送真的落地** → 才回滾。
        //   專屬副本世界的回滾終點是虛空(void 生成),人還站在場地上就會直接往下掉。
        //   `sendHome` 的 future 綁的是 `teleportAsync` 的完成,不是「派工完成」,所以這個
        //   barrier 是真的等到人離開了才放行(離線玩家與不在副本世界的人立即完成,不會卡住)。
        // ⚠ 順序是 **還背包 → 送人回家 → 回滾場地**,三段嚴格串起來,不可以並行。
        //
        // 2026-08-29 L4 實測:並行版本會壞。還原派工到玩家自己的 EntityScheduler,而同一時間
        // `sendHome` 正在把他跨世界傳送出去——跨世界會讓舊 region 的 entity retired,還原的
        // task 就走 retired 分支根本沒跑,接著跨世界的局內物品清理把手上的東西清掉,玩家拿到
        // 一個空背包。「先把東西還完再送人走」讓這個競態不存在。
        //
        // 回滾仍然排在最後:專屬副本世界回滾的終點是虛空,人還站在上面就會往下掉。
        val restores = memberIds.map { instanceInventory.restoreForPlayer(it, "session-end-${reason.name.lowercase()}") }
        CompletableFuture.allOf(*restores.toTypedArray()).whenComplete { _, _ ->
            val sends = memberIds.map { sendHome(it) }
            CompletableFuture.allOf(*sends.toTypedArray())
                .whenComplete { _, _ -> rollbackAndRelease(slotId, dungeonId) }
        }
    }

    private fun rollbackAndRelease(slotId: String, dungeonId: String) {
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
            stageEngine.endFor(e.sessionId, e.reason.name)
            handleSessionEnded(e.slotId, e.dungeonId, e.reason, e.memberIds)
        }
        // ⚠ 一定要在最後、而且是同步的:上面那條路徑走的是 AsyncScheduler,插件停用時它會被
        //   取消,不保證跑得完。這一句把所有還沒收斂的 journal 同步標成 RESTORING,
        //   讓下次啟用的 `recoverAll()` 一定接得住(細節見 InstanceInventoryService.shutdownFlush)。
        instanceInventory.shutdownFlush()
    }

    /**
     * onEnable 呼叫:掃未完成的 journal 並恢復(ARCH §5.2 規則 6「onEnable 接在線玩家」)。
     */
    fun recoverInstanceInventories() {
        instanceInventory.recoverAll()
    }

    /**
     * 副本內死亡的收斂(`HanaTokiListener.onPlayerDeath` 轉呼叫)。
     *
     * 定義沒開 `death-resolution` 就是既有行為:死亡 = 退出這一局,不結算。
     * 開了就先走一次 `resultKey=death` 的 Resolution(帶 duration/stats),再由
     * [resolveSession] 內部走完整的結束流程——`InstanceState.claimResolution` 與
     * `SessionManager.endSession` 的兩道原子認領保證它跟同一刻的逾時/手動 resolve
     * 只會有一邊結算成功。
     *
     * 回傳 true = 已經由這裡處理掉了(呼叫端不要再 kick)。
     */
    fun handlePlayerDeath(playerId: UUID): Boolean {
        val session = sessionManager.sessionOf(playerId) ?: return false
        if (session.persistent) return false // 常駐副本的死亡不退出成員集合(決策 D)
        val def = registry.definitions[session.dungeonId] ?: return false
        if (!def.deathResolution) return false
        val state = stageEngine.stateOf(session.sessionId) ?: return false
        resolveSession(session.sessionId, "death", state)
        return true
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

    /**
     * 內容插件用的定義載入入口(ARCH §11:正式副本內容屬 integration,定義應該跟內容同一個 repo)。
     *
     * ⚠ 簽章刻意只有 [java.io.File]:**跨插件簽章不准出現任何 Kotlin 專屬型別**。初版是
     * `DungeonRegistry.loadAdditional(File, SlotPool, (String) -> World?)`,一被 LycoHanaToki
     * 呼叫就丟 `LinkageError: loader constraint violation ... kotlin/jvm/functions/Function1`
     * ——兩個插件各自從 Paper library loader 載入自己那份 kotlin-stdlib,`Function1` 不是同一個
     * Class(2026-08-23 L3 實測,不是理論顧慮)。世界解析與 slotPool 都由這裡自己拿。
     */
    fun loadContentDefinitions(file: java.io.File) {
        // 載入會順帶建立副本專屬世界,而 `createWorld` 在 Folia/Lecithin 上只能在 global region
        // tick thread 呼叫——內容插件的 onEnable 不一定在那條執行緒上(PlugMan 熱插拔就不是)。
        DungeonWorldProvisioner.runOnGlobalRegion(plugin) {
            registry.loadAdditional(file, slotPool)
        }
    }

    /** 這個實體是不是副本生出來的(encounter 小怪或 actor)。死亡掉落物清除、debug 用。 */
    fun isDungeonOwnedEntity(entityId: UUID): Boolean =
        stageEngine.encounters.isTracked(entityId) ||
            stageEngine.dynamicEncounters.isTracked(entityId) ||
            actorController.actorIdOf(entityId) != null ||
            propController.isTracked(entityId)

    // ---- PresenceBridge(ARCH §4,HanaToki 是 provider)----
    override fun isInside(playerId: UUID): Boolean = sessionManager.sessionOf(playerId) != null
    override fun dungeonIdOf(playerId: UUID): String? = sessionManager.sessionOf(playerId)?.dungeonId

    // ---- DungeonAccess(外部 UI 的進出入口,見 api/DungeonAccess)----

    override fun hasDungeon(dungeonId: String): Boolean = registry.definitions.containsKey(dungeonId)

    /**
     * 舊的布林進場入口。**語意沒有改**:回傳 true 仍然只代表「請求受理」,不代表傳送成功。
     *
     * 現在它底下走的是 [DungeonEntry] 的完整交易,所以比以前多了兩件事:①同步就能知道的
     * 失敗(沒這座副本、玩家不在線、客滿)照樣回 false,呼叫端的錯誤訊息路徑不變;
     * ②非同步才會知道的失敗(傳送失敗、背包交易失敗)現在會**完整回滾並主動告訴玩家**,
     * 而不是像以前那樣留下一個「系統認為他在副本裡、人卻站在原地」的幽靈狀態。
     *
     * 要拿到真正的結果,用 [enterDungeonTracked]。
     */
    override fun enterDungeon(playerId: UUID, dungeonId: String): Boolean {
        val player = plugin.server.getPlayer(playerId) ?: return false
        if (!registry.definitions.containsKey(dungeonId)) return false
        // 客滿的同步預檢:留住舊呼叫端「false 就顯示客滿訊息」的路徑。這裡跟真正的配置之間
        // 有理論上的競態(預檢過了、配置時被別人搶走),那條路會走非同步的 NO_SLOT 分支並
        // 通知玩家,不會留下髒狀態。
        val persistent = registry.definitions[dungeonId]?.mode == ExecutionMode.PERSISTENT
        if (!persistent && !slotPool.hasFree(dungeonId) && sessionManager.sessionOf(playerId) == null) return false
        fireAndReport(dungeonEntry.enter(listOf(player), dungeonId), listOf(playerId))
        return true
    }

    override fun enterDungeonDuo(playerId: UUID, partnerId: UUID, dungeonId: String): Boolean {
        val player = plugin.server.getPlayer(playerId) ?: return false
        val partner = plugin.server.getPlayer(partnerId) ?: return false
        if (!registry.definitions.containsKey(dungeonId)) return false
        fireAndReport(dungeonEntry.enter(listOf(player, partner), dungeonId), listOf(playerId, partnerId))
        return true
    }

    /**
     * 同 repo 內部用的多人進場入口(`/hanatoki enter <id> [player2] ...` 的驗收指令走這條)。
     *
     * 不放進 [DungeonAccess]:那支介面是跨插件的穩定面,而目前**沒有第二個消費者**需要
     * 「任意人數進場」——刀塚的 party-cap 是 2,已經有 [enterDungeonDuoTracked]。
     * 有真的案例再擴(ARCH §12「先有案例再抽象」)。
     */
    fun enterParty(players: List<Player>, dungeonId: String): CompletableFuture<DungeonEntryOutcome> =
        dungeonEntry.enter(players, dungeonId)

    override fun enterDungeonTracked(playerId: UUID, dungeonId: String): CompletableFuture<DungeonEntryOutcome> {
        val player = plugin.server.getPlayer(playerId)
            ?: return CompletableFuture.completedFuture(offlineOutcome())
        return dungeonEntry.enter(listOf(player), dungeonId)
    }

    override fun enterDungeonDuoTracked(
        playerId: UUID,
        partnerId: UUID,
        dungeonId: String,
    ): CompletableFuture<DungeonEntryOutcome> {
        val player = plugin.server.getPlayer(playerId)
            ?: return CompletableFuture.completedFuture(offlineOutcome())
        val partner = plugin.server.getPlayer(partnerId)
            ?: return CompletableFuture.completedFuture(offlineOutcome())
        return dungeonEntry.enter(listOf(player, partner), dungeonId)
    }

    /**
     * 布林入口的非同步尾巴:交易失敗時把原因寫進 log 並通知玩家。
     *
     * 沒有這一段的話,`enterDungeon` 回 true 之後傳送失敗,玩家只會看到自己站在原地、
     * 什麼訊息都沒有——那正是這次要修掉的體驗。
     */
    private fun fireAndReport(future: CompletableFuture<DungeonEntryOutcome>, playerIds: List<UUID>) {
        future.whenComplete { outcome, error ->
            if (error != null) {
                plugin.logger.warning("[HanaToki] 進場交易丟出例外:${error.message}")
                playerIds.forEach { PlayerOp.message(plugin, it, texts.format("session.entry-failed")) }
                return@whenComplete
            }
            if (outcome == null || outcome.succeeded()) return@whenComplete
            plugin.logger.warning("[HanaToki] 進場失敗 status=${outcome.status()} reason=${outcome.failureReason()} rolledBack=${outcome.rolledBack()}")
            val key = if (outcome.status() == DungeonEntryStatus.NO_SLOT) "session.no-slot" else "session.entry-failed"
            playerIds.forEach { PlayerOp.message(plugin, it, texts.format(key, mapOf("dungeon" to ""))) }
        }
    }

    private fun offlineOutcome(): DungeonEntryOutcome = object : DungeonEntryOutcome {
        override fun status(): String = DungeonEntryStatus.PLAYER_OFFLINE
        override fun succeeded(): Boolean = false
        override fun sessionId(): String? = null
        override fun instanceId(): String? = null
        override fun failureReason(): String = "玩家不在線"
        override fun rolledBack(): Boolean = true
    }

    override fun leaveDungeon(playerId: UUID): Boolean {
        val session = sessionManager.sessionOf(playerId) ?: return false
        if (!session.persistent) {
            // Session 型副本:離開 = 退出這一局,`kick` 內部已經包含送人回家。
            kick(playerId)
            return true
        }
        // 常駐副本:它的世界刻意不在 `dungeonWorldNames` 裡(決策 E),所以 `sendHome` 對它是
        // no-op——傳送要在這裡自己做,而且要先拿返回點再退出成員集合(順序反了不影響結果,
        // 但這樣讀起來就是「先決定要去哪、再走人」)。
        val destination = returnPoints.destinationFor(plugin.server.getPlayer(playerId) ?: return false)
        sessionManager.kick(playerId)
        if (destination == null) {
            plugin.logger.warning("[HanaToki] 找不到 $playerId 的返回點,也沒有任何非副本世界可送")
            return false
        }
        // 同 kick():先還完背包才傳送,不然跨世界會把還原的派工打掉。
        instanceInventory.restoreForPlayer(playerId, "leave-persistent").whenComplete { _, _ ->
            PlayerOp.dispatch(plugin, playerId) { it.teleportAsync(destination) }
        }
        return true
    }

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
