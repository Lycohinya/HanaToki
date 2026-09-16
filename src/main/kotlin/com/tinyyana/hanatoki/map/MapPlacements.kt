package com.tinyyana.hanatoki.map

import com.tinyyana.hanatoki.folia.ChunkCoord
import com.tinyyana.hanatoki.folia.ChunkWaveRunner
import com.tinyyana.hanatoki.folia.ChunkWorkReport
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 放置完成、marker ready 的地圖。交給內容插件的東西只有這個:要在哪生怪、放 loot、開門,
 * 由內容插件看 [markers] 自己決定。
 */
class PlacedMap(
    val generationId: UUID,
    val worldName: String,
    val layout: MapLayout,
    val markers: List<MapMarker>,
    val written: Int,
    val chunks: ChunkWorkReport,
) {
    fun markers(kind: MarkerKind): List<MapMarker> = markers.filter { it.kind == kind }

    override fun toString(): String =
        "generation=$generationId pieces=${layout.pieces.size} markers=${markers.size} written=$written $chunks"
}

class MapCleanupReport(val reverted: Int, val foreignKept: Int, val chunks: ChunkWorkReport) {
    override fun toString(): String = "reverted=$reverted foreignKept=$foreignKept $chunks"
}

/** [com.tinyyana.hanatoki.stage.StageContext.maps] 的操作面。只用 JDK/HanaToki 型別(跨插件介面)。 */
interface MapHandle {
    /**
     * 把 [layout](已平移到世界座標)放進這個 session 的世界。一格一格派到擁有該 chunk 的 region、
     * 分波執行,期間不阻塞任何 region。失敗或 session 結束造成的取消,**回傳 exceptional 之前已經把
     * 這次放下的方塊收乾淨**。session 結束時引擎會自動回收,不需要內容插件記帳。
     */
    fun place(layout: MapLayout): CompletableFuture<PlacedMap>

    /** 提前回收一次 generation(例如換關卡)。只還原仍屬於它的格子。 */
    fun release(generationId: UUID): CompletableFuture<MapCleanupReport>
}

/**
 * 一次 placement。[id] 就是 ownership 的身分:ledger 只記它自己改過的格子。
 */
internal class MapGeneration(
    val id: UUID,
    val slotId: String,
    val sessionId: UUID?,
    val world: World,
    val layout: MapLayout,
) {
    private val cancelled = AtomicBoolean(false)
    private val ledgers = ConcurrentHashMap<ChunkCoord, OwnedCells<BlockData>>()
    private val placementDone = CompletableFuture<Void>()
    private val cleanup = AtomicReference<CompletableFuture<MapCleanupReport>>()

    fun cancel() { cancelled.set(true) }

    /** [failAfterChunks] >= 0 只給 architecture probe 注入失敗用:寫完這麼多個 chunk 之後,下一個寫到一半丟例外。 */
    fun place(plugin: Plugin, failAfterChunks: Int): CompletableFuture<PlacedMap> {
        val result = CompletableFuture<PlacedMap>()
        CompletableFuture.supplyAsync {
            // 先在非 tick thread 把整張圖編成 per-chunk 格子 + BlockData;方塊名錯在這裡就失敗,世界完全沒被碰。
            val cells = layout.cellsByChunk()
            val keys = cells.values.firstOrNull()?.keys ?: emptyList()
            val data = keys.map { key -> Bukkit.createBlockData(key.state).also { it.rotate(key.rotation) } }
            cells to data
        }.thenCompose { (cells, data) ->
            val written = AtomicInteger()
            val started = AtomicInteger()
            ChunkWaveRunner(plugin, world, cells.keys.toList(), { chunk ->
                if (cancelled.get()) return@ChunkWaveRunner
                val coord = ChunkCoord(chunk.x, chunk.z)
                val chunkCells = cells.getValue(coord)
                val ledger = OwnedCells<BlockData>()
                ledgers[coord] = ledger
                val failHere = failAfterChunks >= 0 && started.getAndIncrement() == failAfterChunks
                for (i in 0 until chunkCells.size) {
                    if (failHere && i == chunkCells.size / 2) throw IllegalStateException("probe 注入的 placement 失敗($coord)")
                    val block = world.getBlockAt(chunkCells.x(i), chunkCells.y(i), chunkCells.z(i))
                    val before = block.blockData
                    val target = data[chunkCells.key(i)]
                    if (before == target) continue
                    block.setBlockData(target, false)
                    ledger.record(block.x, block.y, block.z, before, block.blockData)
                    written.incrementAndGet()
                }
            }).start().thenApply { report ->
                if (cancelled.get()) throw CancellationException("generation $id 已取消")
                PlacedMap(id, world.name, layout, layout.markers(), written.get(), report)
            }
        }.whenComplete { placed, error ->
            placementDone.complete(null)
            if (error == null) result.complete(placed) else result.completeExceptionally(unwrap(error))
        }
        return result
    }

    /** 冪等。等 placement 真的停下來(包含寫到一半失敗的那個 chunk)才開始還原,不會跟寫入互搶。 */
    fun cleanup(plugin: Plugin): CompletableFuture<MapCleanupReport> {
        cleanup.get()?.let { return it }
        val fresh = CompletableFuture<MapCleanupReport>()
        if (!cleanup.compareAndSet(null, fresh)) return cleanup.get()
        cancel()
        placementDone.thenCompose {
            val reverted = AtomicInteger()
            val foreign = AtomicInteger()
            ChunkWaveRunner(plugin, world, ledgers.keys.toList(), { chunk ->
                val ledger = ledgers.remove(ChunkCoord(chunk.x, chunk.z)) ?: return@ChunkWaveRunner
                val counts = ledger.revert(
                    { x, y, z -> world.getBlockAt(x, y, z).blockData },
                    { x, y, z, state -> world.getBlockAt(x, y, z).setBlockData(state, false) },
                )
                reverted.addAndGet(counts[0])
                foreign.addAndGet(counts[1])
            }).start().thenApply { MapCleanupReport(reverted.get(), foreign.get(), it) }
        }.whenComplete { report, error ->
            if (error == null) fresh.complete(report) else fresh.completeExceptionally(unwrap(error))
        }
        return fresh
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is java.util.concurrent.CompletionException && error.cause != null) error.cause!! else error
}

/**
 * 所有 placement 的登記處,按 slot 記帳。接在 HanaToki 既有的 session/slot 收斂上:
 * session 結束 → [closeSession](取消進行中的 placement、拒收新的)→ 送人回家、diff 回滾 →
 * [cleanupSlot](逐 generation 還原)→ 才釋放 slot。沒有第二套 lifecycle。
 */
class MapPlacements(private val plugin: Plugin) {
    private val bySlot = ConcurrentHashMap<String, CopyOnWriteArrayList<MapGeneration>>()
    /** slot → 已結束、不再接受 placement 的 session。slot 釋放時一併清掉。 */
    private val closedSessions = ConcurrentHashMap<String, MutableSet<UUID>>()

    fun place(slotId: String, sessionId: UUID?, world: World, layout: MapLayout): CompletableFuture<PlacedMap> =
        place(slotId, sessionId, world, layout, -1)

    internal fun place(slotId: String, sessionId: UUID?, world: World, layout: MapLayout, failAfterChunks: Int): CompletableFuture<PlacedMap> {
        val generation = synchronized(this) {
            if (sessionId != null && closedSessions[slotId]?.contains(sessionId) == true) {
                return CompletableFuture.failedFuture(CancellationException("session $sessionId 已結束,不再接受 placement"))
            }
            MapGeneration(UUID.randomUUID(), slotId, sessionId, world, layout).also {
                bySlot.computeIfAbsent(slotId) { CopyOnWriteArrayList() } += it
            }
        }
        val result = CompletableFuture<PlacedMap>()
        generation.place(plugin, failAfterChunks).whenComplete { placed, error ->
            if (error == null) {
                result.complete(placed)
                return@whenComplete
            }
            generation.cleanup(plugin).whenComplete { report, cleanupError ->
                bySlot[slotId]?.remove(generation)
                if (cleanupError != null) {
                    plugin.logger.severe("[HanaToki] map generation ${generation.id} 失敗後回收也失敗:${cleanupError.message}")
                    error.addSuppressed(cleanupError)
                } else {
                    plugin.logger.warning("[HanaToki] map generation ${generation.id} placement 失敗,已回收:$report 原因:${error.message}")
                }
                result.completeExceptionally(error)
            }
        }
        return result
    }

    fun release(slotId: String, generationId: UUID): CompletableFuture<MapCleanupReport> {
        val generation = bySlot[slotId]?.firstOrNull { it.id == generationId }
            ?: return CompletableFuture.failedFuture(IllegalArgumentException("slot=$slotId 沒有 generation $generationId"))
        return generation.cleanup(plugin).whenComplete { _, _ -> bySlot[slotId]?.remove(generation) }
    }

    /** session 結束的第一時間:取消它進行中的 placement,之後的 place 一律拒收。 */
    fun closeSession(slotId: String, sessionId: UUID) {
        synchronized(this) { closedSessions.computeIfAbsent(slotId) { ConcurrentHashMap.newKeySet() } += sessionId }
        bySlot[slotId]?.filter { it.sessionId == sessionId }?.forEach { it.cancel() }
    }

    /** slot 釋放前:回收這個 slot 上所有 generation(逆放置順序)。 */
    fun cleanupSlot(slotId: String): CompletableFuture<Void> {
        val generations = synchronized(this) {
            closedSessions.remove(slotId)
            bySlot.remove(slotId)?.toList().orEmpty()
        }
        if (generations.isEmpty()) return CompletableFuture.completedFuture(null)
        var chain: CompletableFuture<*> = CompletableFuture.completedFuture(null)
        for (generation in generations.asReversed()) {
            chain = chain.handle { _, _ -> null }.thenCompose {
                generation.cleanup(plugin).whenComplete { report, error ->
                    if (error != null) plugin.logger.severe("[HanaToki] slot=$slotId map generation ${generation.id} 回收失敗:${error.message}")
                    else plugin.logger.info("[HanaToki] slot=$slotId map generation ${generation.id} 已回收:$report")
                }
            }
        }
        return chain.handle { _, _ -> null }
    }

    fun activeCount(slotId: String): Int = bySlot[slotId]?.size ?: 0
}
