package com.tinyyana.hanatoki.instance

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.api.DungeonEntryOutcome
import com.tinyyana.hanatoki.api.DungeonEntryStatus
import com.tinyyana.hanatoki.folia.PlayerOp
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * 一次進場的**完整交易**(ARCH §5.2 規則 3「必須處理失敗分支」)。
 *
 * ## 為什麼要有這個類別
 *
 * 舊的進場路徑是「分 slot → `teleportAsync` → 回傳 true」,傳送的結果被丟掉。傳送失敗時
 * session 已經建好、slot 已經被佔、stage 已經在跑,玩家卻還站在主世界——`PresenceBridge`
 * 從此對他回 true,獎勵和廣播都把他算成在場。這個缺陷在引擎自己的 `docs/API.md` 就寫著。
 *
 * 這裡把進場寫成一個有明確成功/失敗與**完整回滾**的交易:失敗時 session 成員資格、slot 佔用、
 * stage 狀態、bossbar/排程、返回點登記、局內背包 journal 全部回到進場前。
 *
 * ## 順序,以及每一步為什麼在那個位置
 *
 * ```
 * 1  記返回點            ← 在玩家自己的 region 讀他的座標(ARCH §5.1④)
 * 2  分配 session/slot   ← 純記憶體查表,失敗就是「客滿」,什麼都還沒動
 * 3  啟動 stage          ← ⚠ 必須在傳送之前(見下方)
 * 4  局內背包 prepare    ← 持久化 journal(PREPARED),背包還沒動
 * 5  teleportAsync       ← 只有這一步真的成功,才算進場
 * 6  局內背包 activate   ← 拍快照 → 清空 → 發局內裝
 * 7  ACTIVE
 * ```
 *
 * ⚠ **步驟 3 刻意在傳送之前**,雖然「真正傳送成功前不算 ACTIVE Run」。理由是物理性的:
 * 專屬副本世界是虛空,場地是 entry stage 的 behavior 用程式碼蓋出來的——傳送先發生的話,
 * 玩家會落進一個還沒有地板的座標直接往下掉。所以蓋場地在前、**但失敗時要連它一起拆掉**
 * ([rollback] 會呼叫 `stageEngine.endFor`),而不是把「已經蓋好場地」當成「已經進場」。
 */
class DungeonEntry(private val core: HanaTokiCore) {

    /**
     * 把一組玩家送進副本,回傳交易結果。
     *
     * 多人進場(刀塚的雙人局)的語意是**全有全無**:任何一位傳送失敗或背包交易失敗,整局回滾,
     * 兩個人都留在原地。分別處理會產生「一個人在裡面打、一個人在外面看」的狀態,那比失敗更糟。
     */
    fun enter(players: List<Player>, dungeonId: String): CompletableFuture<DungeonEntryOutcome> {
        if (players.isEmpty()) return done(fail(DungeonEntryStatus.PLAYER_OFFLINE, "沒有可進場的玩家"))
        val def = core.registry.definitions[dungeonId]
            ?: return done(fail(DungeonEntryStatus.NO_DUNGEON, "沒有 id=$dungeonId 的副本定義"))

        return rememberReturnPoints(players).thenCompose { returnPoints ->
            val session: Session
            val anchor: Location
            val statusOnSuccess: String
            when (val result = core.enter(dungeonId, players)) {
                is EnterResult.NoSlot ->
                    return@thenCompose done(fail(DungeonEntryStatus.NO_SLOT, "${def.display} 目前沒有空的場地"))
                is EnterResult.Entered -> {
                    session = result.session; anchor = result.anchor; statusOnSuccess = DungeonEntryStatus.ENTERED
                }
                is EnterResult.Joined -> {
                    session = result.session; anchor = result.anchor; statusOnSuccess = DungeonEntryStatus.JOINED
                }
            }

            prepareInventories(players, session, returnPoints).thenCompose { prepared ->
                if (prepared == null) {
                    return@thenCompose rollback(session, players, emptyMap())
                        .thenApply { fail(DungeonEntryStatus.INVENTORY_FAILED, "局內背包 journal 寫入失敗,已回滾") }
                }
                val destination = core.entryLocationFor(def, anchor)
                teleportAll(players, destination).thenCompose { teleported ->
                    if (!teleported) {
                        return@thenCompose rollback(session, players, prepared)
                            .thenApply { fail(DungeonEntryStatus.TELEPORT_FAILED, "傳送未成功,已回滾") }
                    }
                    activateInventories(session, prepared, def.instanceInventory).thenCompose { ok ->
                        if (!ok) {
                            rollback(session, players, prepared)
                                .thenApply { fail(DungeonEntryStatus.INVENTORY_FAILED, "局內背包切換失敗,已回滾") }
                        } else {
                            // 交易整個做完才通知 behavior:局內背包已經換好,起始物品放進去不會被清掉。
                            players.forEach { core.stageEngine.notifyMemberReady(session.sessionId, it.uniqueId) }
                            done(
                                Outcome(
                                    status = statusOnSuccess,
                                    succeeded = true,
                                    sessionId = session.sessionId.toString(),
                                    instanceId = prepared[players.first().uniqueId]?.toString(),
                                    failureReason = null,
                                    rolledBack = false,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 各步驟 -------------------------------------------------------------

    /**
     * 在每位玩家自己的 region 上讀他現在的座標(ARCH §5.1④),同時寫進記憶體登記表與交給
     * journal 持久化。讀不到(已登出)就回 null,那位玩家的返回點走 fallback。
     */
    private fun rememberReturnPoints(players: List<Player>): CompletableFuture<Map<UUID, Location?>> {
        val collected = java.util.concurrent.ConcurrentHashMap<UUID, Location>()
        val ops = players.map { player ->
            PlayerOp.dispatch(core.plugin, player) { p ->
                core.returnPoints.remember(p)
                val here = p.location
                if (!core.isDungeonWorld(here.world.name)) collected[p.uniqueId] = here.clone()
            }
        }
        return CompletableFuture.allOf(*ops.toTypedArray())
            .thenApply { players.associate { it.uniqueId to collected[it.uniqueId] } }
    }

    /**
     * 這座副本沒開局內背包的話整段跳過(既有副本行為完全不變),回傳空 map。
     * 任何一位寫入失敗就回 null = 整筆交易失敗。
     */
    private fun prepareInventories(
        players: List<Player>,
        session: Session,
        returnPoints: Map<UUID, Location?>,
    ): CompletableFuture<Map<UUID, UUID>?> {
        core.registry.definitions[session.dungeonId]?.instanceInventory
            ?: return CompletableFuture.completedFuture(emptyMap())
        val prepared = java.util.concurrent.ConcurrentHashMap<UUID, UUID>()
        val ops = players.map { player ->
            core.instanceInventory.prepare(
                player.uniqueId,
                session.dungeonId,
                session.slotId,
                returnPoints[player.uniqueId],
            ).thenAccept { instanceId -> instanceId?.let { prepared[player.uniqueId] = it } }
        }
        return CompletableFuture.allOf(*ops.toTypedArray()).thenApply {
            if (prepared.size == players.size) prepared.toMap() else null
        }
    }

    /** 全部成功才算成功。任一失敗/exceptional 都回 false(ARCH §5.2 規則 3)。 */
    private fun teleportAll(players: List<Player>, destination: Location): CompletableFuture<Boolean> {
        val results = java.util.concurrent.ConcurrentHashMap<UUID, Boolean>()
        val ops = players.map { player ->
            player.teleportAsync(destination)
                .handle { ok, error ->
                    results[player.uniqueId] = (error == null && ok == true)
                    null
                }
        }
        return CompletableFuture.allOf(*ops.toTypedArray())
            .thenApply { players.all { results[it.uniqueId] == true } }
    }

    private fun activateInventories(
        session: Session,
        prepared: Map<UUID, UUID>,
        def: com.tinyyana.hanatoki.config.InstanceInventoryDef?,
    ): CompletableFuture<Boolean> {
        if (def == null || prepared.isEmpty()) return CompletableFuture.completedFuture(true)
        val ok = java.util.concurrent.ConcurrentHashMap<UUID, Boolean>()
        val ops = prepared.map { (playerId, instanceId) ->
            core.instanceInventory.activate(instanceId, session.sessionId, def)
                .thenAccept { ok[playerId] = it }
        }
        return CompletableFuture.allOf(*ops.toTypedArray())
            .thenApply { prepared.keys.all { ok[it] == true } }
    }

    /**
     * 完整回滾:局內背包交易 → session 成員資格 → stage 狀態/排程/bossbar → slot 佔用 →
     * 返回點登記。
     *
     * 順序有意義:背包先還(那是玩家真的會少東西的部分),最後才放 slot(ARCH §5.1
     * 「回滾未完成前 slot 不得標為空閒」)。
     */
    private fun rollback(
        session: Session,
        players: List<Player>,
        prepared: Map<UUID, UUID>,
    ): CompletableFuture<Void> {
        val invOps = prepared.values.map { core.instanceInventory.restore(it, "entry-rollback") }
        return CompletableFuture.allOf(*invOps.toTypedArray()).thenAccept {
            for (player in players) {
                core.sessionManager.kick(player.uniqueId)
                core.returnPoints.forget(player.uniqueId)
            }
            core.stageEngine.endFor(session.sessionId, EndReason.ABANDONED.name)
            // 常駐副本的 instance 不因為一次進場失敗而收掉(它本來就不歸還 slot);
            // session 型副本則要把 slot 放回池子,否則這次失敗會永久吃掉一個場地。
            if (!session.persistent) {
                core.sessionManager.endSession(session.sessionId, EndReason.ABANDONED)
                core.sessionManager.releaseSlotAfterRollback(session.slotId)
            }
            core.plugin.logger.info("[HanaToki] 進場失敗已回滾:dungeon=${session.dungeonId} slot=${session.slotId}")
        }
    }

    // ---- 結果物件 -----------------------------------------------------------

    private fun done(outcome: DungeonEntryOutcome) = CompletableFuture.completedFuture(outcome)

    private fun fail(status: String, reason: String) =
        Outcome(status, succeeded = false, sessionId = null, instanceId = null, failureReason = reason, rolledBack = true)

    /**
     * [DungeonEntryOutcome] 的實作。刻意是普通 class + 明確 getter,不是 data class:
     * data class 會生成 `copy$default`(簽章帶 `kotlin.jvm.internal.DefaultConstructorMarker`),
     * 而這個型別會出現在跨插件簽章上(ARCH §4.0)。
     */
    private class Outcome(
        private val status: String,
        private val succeeded: Boolean,
        private val sessionId: String?,
        private val instanceId: String?,
        private val failureReason: String?,
        private val rolledBack: Boolean,
    ) : DungeonEntryOutcome {
        override fun status(): String = status
        override fun succeeded(): Boolean = succeeded
        override fun sessionId(): String? = sessionId
        override fun instanceId(): String? = instanceId
        override fun failureReason(): String? = failureReason
        override fun rolledBack(): Boolean = rolledBack
    }
}
