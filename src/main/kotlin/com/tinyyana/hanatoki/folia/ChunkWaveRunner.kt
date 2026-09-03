package com.tinyyana.hanatoki.folia

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** 一個 chunk 的座標(chunk 單位,不是方塊)。 */
data class ChunkCoord(val x: Int, val z: Int)

/** [ChunkWaveRunner] 跑完之後的量測:給 log 與 debug 指令用,不是玩家文字。 */
class ChunkWorkReport(
    val chunks: Int,
    val waves: Int,
    val preloadMillis: Long,
    val workMillis: Long,
    val maxChunkMillis: Double,
    val totalChunkMillis: Double,
) {
    override fun toString(): String =
        "chunks=$chunks waves=$waves preload=${preloadMillis}ms work=${workMillis}ms maxChunk=${"%.1f".format(maxChunkMillis)}ms sumChunk=${"%.0f".format(totalChunkMillis)}ms"
}

/**
 * 把「對很多 chunk 各做一件事」分成一波一波派到各 chunk 自己的 region,**每一波的大小依上一波
 * 實際花的時間調整**,讓 region 每 tick 為此多花的時間有上限。
 *
 * ## 為什麼不是一次全派出去
 *
 * 2026-09-03 正式服的深域:每局開場整套場地(六座 91×91 雙層 + 舊版清除範圍,幾百萬格)
 * 是一口氣對六十幾個 chunk 各派一個 task,全部落在同一個 tick——那個 region 停 2~5 秒。
 * 同 region 裡的其他人(隔壁 slot 打到一半的隊伍、同一條 tick thread 上的其他 region)
 * 全部一起停。Folia 的 tick thread pool 只有幾條,一條被綁 3 秒,別的 region 也排不到。
 *
 * ## 派工規則
 *
 * 1. 先把所有 chunk `getChunkAtAsync` 載進來(非同步,不佔 tick)。
 * 2. 第一波派 [initialWave] 個 chunk,每個 chunk 一個 `regionScheduler.execute(world, cx, cz)`
 *    ——Folia 保證那個 task 在擁有該 chunk 的 region 上跑,不管 region 怎麼合併/切分。
 * 3. 一波全部完成後看這一波最慢的 chunk 花了多久:低於 [fastNanos] 就把下一波加倍
 *    (上限 [maxWave]),高於 [slowNanos] 就減半(下限 1)。同一波的 chunk 多半在同一個
 *    region、同一個 tick 內連續執行,所以「一波的總時間」≈ 那個 tick 為此多付的代價。
 * 4. 全部跑完才 complete。任何一個 chunk 的 action 丟例外,整個 future exceptional
 *    (呼叫端要看得到失敗,不能靜靜蓋出半張場地)。
 *
 * [action] 在 chunk 所屬 region 的執行緒上被呼叫,可以直接讀寫那個 chunk 的方塊。
 */
class ChunkWaveRunner(
    private val plugin: Plugin,
    private val world: World,
    private val chunks: List<ChunkCoord>,
    private val action: (Chunk) -> Unit,
    private val initialWave: Int = DEFAULT_INITIAL_WAVE,
    private val maxWave: Int = DEFAULT_MAX_WAVE,
    private val fastNanos: Long = DEFAULT_FAST_NANOS,
    private val slowNanos: Long = DEFAULT_SLOW_NANOS,
) {
    private val result = CompletableFuture<ChunkWorkReport>()
    private val startedAt = System.nanoTime()
    private var preloadDoneAt = startedAt
    private var cursor = 0
    private var waves = 0
    private var waveSize = initialWave.coerceIn(1, maxWave)
    private val maxChunkNanos = AtomicLong(0)
    private val totalChunkNanos = AtomicLong(0)

    fun start(): CompletableFuture<ChunkWorkReport> {
        if (chunks.isEmpty()) {
            result.complete(ChunkWorkReport(0, 0, 0, 0, 0.0, 0.0))
            return result
        }
        val loads = chunks.map { world.getChunkAtAsync(it.x, it.z, true) }
        CompletableFuture.allOf(*loads.toTypedArray()).whenComplete { _, error ->
            if (error != null) {
                result.completeExceptionally(IllegalStateException("chunk 預載失敗:${error.message}", error))
                return@whenComplete
            }
            preloadDoneAt = System.nanoTime()
            nextWave()
        }
        return result
    }

    private fun nextWave() {
        if (result.isDone) return
        if (cursor >= chunks.size) {
            val end = System.nanoTime()
            result.complete(
                ChunkWorkReport(
                    chunks = chunks.size,
                    waves = waves,
                    preloadMillis = (preloadDoneAt - startedAt) / 1_000_000,
                    workMillis = (end - preloadDoneAt) / 1_000_000,
                    maxChunkMillis = maxChunkNanos.get() / 1e6,
                    totalChunkMillis = totalChunkNanos.get() / 1e6,
                ),
            )
            return
        }
        val batch = chunks.subList(cursor, minOf(chunks.size, cursor + waveSize))
        cursor += batch.size
        waves++
        val waveMax = AtomicLong(0)
        val futures = batch.map { coord ->
            val done = CompletableFuture<Void>()
            Bukkit.getRegionScheduler().execute(plugin, world, coord.x, coord.z) {
                val t0 = System.nanoTime()
                try {
                    action(world.getChunkAt(coord.x, coord.z))
                    done.complete(null)
                } catch (t: Throwable) {
                    done.completeExceptionally(t)
                } finally {
                    val spent = System.nanoTime() - t0
                    waveMax.accumulateAndGet(spent, ::maxOf)
                    maxChunkNanos.accumulateAndGet(spent, ::maxOf)
                    totalChunkNanos.addAndGet(spent)
                }
            }
            done
        }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, error ->
            if (error != null) {
                result.completeExceptionally(error)
                return@whenComplete
            }
            val slowest = waveMax.get()
            waveSize = when {
                slowest < fastNanos -> minOf(maxWave, waveSize * 2)
                slowest > slowNanos -> maxOf(1, waveSize / 2)
                else -> waveSize
            }
            nextWave()
        }
    }

    companion object {
        const val DEFAULT_INITIAL_WAVE = 2
        const val DEFAULT_MAX_WAVE = 8
        /** 一個 chunk 低於這個時間就可以放心把下一波加倍(8 個 × 3ms 仍在一個 tick 的預算內)。 */
        const val DEFAULT_FAST_NANOS = 3_000_000L
        /** 一個 chunk 超過這個時間就縮波(第一次蓋、整塊要寫的那種)。 */
        const val DEFAULT_SLOW_NANOS = 10_000_000L

        /** 純函式版的波次調整,給測試對規則本身做斷言。 */
        fun adjustWave(current: Int, slowestNanos: Long, maxWave: Int = DEFAULT_MAX_WAVE, fastNanos: Long = DEFAULT_FAST_NANOS, slowNanos: Long = DEFAULT_SLOW_NANOS): Int = when {
            slowestNanos < fastNanos -> minOf(maxWave, current * 2)
            slowestNanos > slowNanos -> maxOf(1, current / 2)
            else -> current
        }
    }
}
