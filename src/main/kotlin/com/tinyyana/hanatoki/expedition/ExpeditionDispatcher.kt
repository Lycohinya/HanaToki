package com.tinyyana.hanatoki.expedition

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
 * 下次有 sink 上線時先補送舊的、再送新的。不做跨重啟持久化(跟 RewardDispatcher 同一份限制)。
 */
class ExpeditionDispatcher(private val plugin: Plugin) {
    private val pending = ConcurrentLinkedQueue<ExpeditionEvidence>()

    private fun currentSink(): ExpeditionSink? =
        plugin.server.servicesManager.getRegistration(ExpeditionSink::class.java)?.provider

    fun dispatch(evidence: ExpeditionEvidence) {
        val sink = currentSink()
        if (sink == null) {
            pending += evidence
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
        } catch (ex: Throwable) {
            plugin.logger.severe("[HanaToki] ExpeditionSink.onResolved 丟例外 kitId=${e.kitId}:${ex.message}")
        }
    }

    fun pendingCount(): Int = pending.size
}
