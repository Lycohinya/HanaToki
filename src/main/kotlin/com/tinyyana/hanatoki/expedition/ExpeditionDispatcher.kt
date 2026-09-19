package com.tinyyana.hanatoki.expedition

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** 一筆待送的見證(engine 內部用,不跨插件邊界——跨邊界一律走 [ExpeditionSink.onResolved] 的展開參數)。 */
data class ExpeditionEvidence(
    val kitId: UUID,
    val deskId: UUID,
    val packRevision: Long,
    val ability: String,
    /** 從物品的 `lycoitems:custom_item`(或舊命名空間 fallback)讀到的物品 id,例如 `preparation_lining`。 */
    val product: String,
    val playerId: UUID,
    val dungeonId: String,
    val encounterId: String,
    val deployed: Boolean,
    val runId: UUID?,
    val packedAtMs: Long,
    val resolvedAtMs: Long,
)

/**
 * 見證出口的 dispatcher,照 [com.tinyyana.hanatoki.reward.RewardDispatcher] 同一套形狀:
 * 每次呼叫都**重新解析** provider(熱插拔安全),缺席時先放進本 JVM 記憶體佇列,
 * 下次有 sink 上線時先補送舊的、再送新的。
 *
 * ## 跨重啟持久化(2026-09 稽核問題 2 的修法)
 *
 * `RewardDispatcher` 刻意不做跨重啟持久化(ARCH §4:那是 integration 端 `completionId` 冪等
 * upsert 的責任)。見證出口不能照抄那份限制——產品規則明文禁止只靠記憶體佇列保住進度,
 * 而且這裡跟 [com.tinyyana.hanatoki.inventory.InstanceInventoryService] 的關服見證缺陷疊加後
 * 完全無法察覺(見該類別 `shutdownFlush` 的 KDoc)。所以待送見證額外落地到
 * [PendingEvidenceStore](temp 檔 → fsync → 原子改名,跟 `InstanceJournal` 同一套手法),
 * 記憶體佇列只是它的快取:
 *
 * - 進佇列(`sink` 缺席,或送出時丟例外)一定連帶落地一份,派工到 AsyncScheduler 寫,
 *   不佔用呼叫端所在的執行緒(呼叫端可能是玩家自己的 EntityScheduler,見
 *   `InstanceInventoryService.startRestore` 的呼叫點)。
 * - 送成功([ExpeditionSink.onResolved] 沒丟例外)才刪對應的落地檔——`onResolved` 是
 *   fire-and-forget,沒有 ack,所以「沒丟例外」是這裡唯一能拿到的成功訊號。
 * - 檔名是 kitId,所以同一個 kit 最多只有一份待送檔案,天生去重。
 */
class ExpeditionDispatcher(private val plugin: Plugin) {
    private val pending = ConcurrentLinkedQueue<ExpeditionEvidence>()
    private val store = PendingEvidenceStore(java.io.File(plugin.dataFolder, "expedition-pending"), plugin.logger)

    init {
        // 讀回上次沒送出去的見證。這裡跟 `InstanceInventoryService.recoverAll()` 對 `InstanceJournal`
        // 的讀法同一個時機假設:建構這個物件發生在 onEnable 附近,還沒有任何 region 在 tick,
        // 這條執行緒此刻做同步磁碟 I/O 不違反「tick thread 不碰同步 I/O」的限制。
        store.cleanupTempFiles()
        pending += store.loadAll()
    }

    private fun currentSink(): ExpeditionSink? =
        plugin.server.servicesManager.getRegistration(ExpeditionSink::class.java)?.provider

    fun dispatch(evidence: ExpeditionEvidence) {
        val sink = currentSink()
        if (sink == null) {
            enqueue(evidence)
            plugin.logger.warning(
                "[HanaToki] 沒有註冊 ExpeditionSink,kitId=${evidence.kitId} " +
                    "(dungeon=${evidence.dungeonId} player=${evidence.playerId}) 已排入待補送佇列",
            )
            return
        }
        drainPending(sink)
        send(sink, evidence)
    }

    fun drainPending(sink: ExpeditionSink? = currentSink()) {
        if (sink == null) return
        var e = pending.poll()
        while (e != null) {
            send(sink, e)
            e = pending.poll()
        }
    }

    private fun send(sink: ExpeditionSink, e: ExpeditionEvidence) {
        try {
            sink.onResolved(
                e.kitId, e.deskId, e.packRevision, e.ability, e.product,
                e.playerId, e.dungeonId, e.encounterId, e.deployed, e.runId, e.packedAtMs, e.resolvedAtMs,
            )
            // 送達之後才刪落地檔——刪晚了(甚至沒刪成)只會讓下次啟動多讀一筆重送,而重送對
            // ExpeditionSink 的呼叫端是安全的(kitId 天生去重 + Lophinya 端本來就有重複判定)。
            deleteAsync(e.kitId)
        } catch (ex: Throwable) {
            plugin.logger.severe("[HanaToki] ExpeditionSink.onResolved 丟例外 kitId=${e.kitId}:${ex.message}")
            // 丟例外代表沒送達,原本這裡會讓 evidence 直接消失——改成跟「sink 缺席」同一條路:
            // 排回記憶體佇列 + 落地,等下一次 dispatch/drainPending 再試。
            enqueue(e)
        }
    }

    /** 進記憶體佇列,並非同步落地一份(見類別 KDoc)。 */
    private fun enqueue(e: ExpeditionEvidence) {
        pending += e
        persistAsync(e)
    }

    private fun persistAsync(e: ExpeditionEvidence) {
        val scheduled = try {
            Bukkit.getAsyncScheduler().runNow(plugin) { _ -> store.writeSync(e) }
        } catch (ex: Exception) {
            null
        }
        // 排不進 AsyncScheduler(插件正在停用,見 InstanceInventoryService.runAsync 的同一個情境):
        // 寧可阻塞一下同步寫完,也不要讓待送見證的落地靜靜消失。
        if (scheduled == null) store.writeSync(e)
    }

    private fun deleteAsync(kitId: UUID) {
        val scheduled = try {
            Bukkit.getAsyncScheduler().runNow(plugin) { _ -> store.delete(kitId) }
        } catch (ex: Exception) {
            null
        }
        if (scheduled == null) store.delete(kitId)
    }

    /** 記憶體佇列大小,沿用既有呼叫端(admin 指令等)。 */
    fun pendingCount(): Int = pending.size

    /** 磁碟上實際還沒送出去的筆數——讓待送佇列不是黑箱(稽核問題 2 明文要求)。 */
    fun onDiskPendingCount(): Int = store.onDiskCount()
}
