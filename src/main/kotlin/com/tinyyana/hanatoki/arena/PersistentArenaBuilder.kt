package com.tinyyana.hanatoki.arena

import com.tinyyana.hanatoki.folia.ChunkWorkReport
import com.tinyyana.hanatoki.stage.StageContext
import org.bukkit.block.data.type.Stairs
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class ArenaBuildReport(
    val checked: Int,
    val written: Int,
    val planMillis: Long,
    val planCached: Boolean,
    val chunks: ChunkWorkReport,
) {
    override fun toString(): String =
        "checked=$checked written=$written plan=${planMillis}ms${if (planCached) "(快取)" else ""} $chunks"
}

/** Content owns this cache instance and clears it when its definitions reload or disable. */
class PersistentArenaBuilder<K : Any> {
    private val plans = ConcurrentHashMap<K, CompletableFuture<ArenaPlan>>()

    fun clear() { plans.clear() }

    fun ensureBuilt(ctx: StageContext, key: K, force: Boolean, factory: Supplier<ArenaPlan>): CompletableFuture<ArenaBuildReport> {
        val anchor = ctx.anchor
        val world = anchor.world ?: return CompletableFuture.failedFuture(IllegalStateException("anchor 沒有世界"))
        val ax = anchor.blockX
        val ay = anchor.blockY
        val az = anchor.blockZ
        val started = System.nanoTime()
        var cached = true
        val future = plans.compute(key) { _, existing ->
            if (!force && existing != null && !existing.isCompletedExceptionally) existing
            else {
                cached = false
                CompletableFuture.supplyAsync(factory)
            }
        }!!
        return future.thenCompose { plan ->
            val planMillis = (System.nanoTime() - started) / 1_000_000
            val checked = AtomicInteger()
            val written = AtomicInteger()
            ctx.mutatePersistentChunks(world, plan.chunksOf(ax, az)) { chunk ->
                val snapshot = if (force) null else chunk.getChunkSnapshot(false, false, false)
                val result = plan.reconcileChunk(ax, ay, az, chunk.x, chunk.z, force,
                    { x, y, z -> snapshot!!.getBlockType(x and 15, y, z and 15) },
                    { x, y, z -> (snapshot!!.getBlockData(x and 15, y, z and 15) as? Stairs)?.facing },
                    { x, y, z, planned ->
                        val block = world.getBlockAt(x, y, z)
                        val facing = planned.stairAscent
                        if (facing == null) block.setType(planned.material, false)
                        else {
                            val data = planned.material.createBlockData()
                            if (data is Stairs) data.facing = facing
                            block.setBlockData(data, false)
                        }
                    })
                checked.addAndGet(result.checked)
                written.addAndGet(result.written)
            }.thenApply { chunks -> ArenaBuildReport(checked.get(), written.get(), planMillis, cached, chunks) }
        }
    }
}
