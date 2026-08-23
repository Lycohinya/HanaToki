package com.tinyyana.hanatoki.reward

import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ARCH §4「缺席時：獎勵靜置待補跑,啟動時警告 log(記取 NectarBridge 靜默堆積的教訓)」。
 *
 * `RewardSink` 本身是 fire-and-forget、無回傳值——HanaToki 沒有 ack 機制知道 integration
 * 端是否真的落地了(那是 integration 自己用 `completionId` 做幂等 upsert 的責任,ARCH §4/§7)。
 * 這裡的「補跑」範圍只到「HanaToki 這個 JVM 存活期間,RewardSink 從缺席變成註冊」的情境——
 * 例如 integration 插件比 HanaToki 晚啟動,或熱插拔重載順序問題。真正跨伺服器重啟的補跑
 * 責任在 integration 自己的 completionId 落庫(不是 HanaToki 該做的事,§4 已經寫明)。
 */
class RewardDispatcher(private val plugin: Plugin) {
    private val pending = ConcurrentLinkedQueue<CompletionResult>()

    private fun currentSink(): RewardSink? =
        plugin.server.servicesManager.getRegistration(RewardSink::class.java)?.provider

    fun dispatch(result: CompletionResult) {
        val sink = currentSink()
        if (sink == null) {
            pending += result
            plugin.logger.warning(
                "[HanaToki] 沒有註冊 RewardSink,completionId=${result.completionId} " +
                    "(dungeon=${result.dungeonId} player=${result.playerId}) 已排入待補跑佇列",
            )
            return
        }
        // 先把之前積壓的送一輪(sink 剛上線的常見情境),再送這次的。
        drainPending(sink)
        sink.onCompletion(result)
    }

    /** 有新的 RewardSink 註冊時呼叫(見 HanaTokiPlugin 的 ServiceRegisterEvent handler),或 tick 時定期補跑。 */
    fun drainPending(sink: RewardSink? = currentSink()) {
        if (sink == null) return
        var r = pending.poll()
        while (r != null) {
            sink.onCompletion(r)
            r = pending.poll()
        }
    }

    fun pendingCount(): Int = pending.size
}
