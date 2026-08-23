package com.tinyyana.hanatoki.testcontent

import com.tinyyana.hanatoki.stage.DungeonBehavior
import com.tinyyana.hanatoki.stage.StageContext
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * ARCH §8「三燈引路」的實際內容實作(architecture probe,不是正式副本內容——正式內容屬
 * integration/內容插件,不放引擎 repo,見 ARCH §11)。註冊給 `dungeons.yml` 的 `test-puzzle`。
 *
 * 正解序 = 「中、左、右」(`lamp_b` → `lamp_a` → `lamp_c`,對照 mural 提示文字)。
 */
class PuzzleTestBehavior : DungeonBehavior {
    private val lampIds = listOf("lamp_a", "lamp_b", "lamp_c")
    private val correctOrder = listOf("lamp_b", "lamp_a", "lamp_c")

    override fun onStageEnter(ctx: StageContext, stageId: String) {
        when (stageId) {
            "entry" -> setupField(ctx)
            "puzzle" -> ctx.state.set("lit", mutableListOf<String>())
            "ambush" -> {
                ctx.messageAll("puzzle.ambush")
                ctx.spawnEncounter("ambush-wave") { deathCtx ->
                    deathCtx.messageAll("puzzle.ambush-cleared")
                    deathCtx.transition("puzzle")
                }
            }
        }
    }

    /** entry stage 進場動作:把場地方塊擺好(diff 記錄由 [StageContext.mutate] 自動處理)。 */
    private fun setupField(ctx: StageContext) {
        ctx.messageAll("puzzle.entry")
        val placements = mapOf(
            "mural" to Material.CHISELED_STONE_BRICKS,
            "shrine" to Material.AMETHYST_BLOCK,
            "door" to Material.IRON_BLOCK,
            "goal-plate" to Material.LIGHT_WEIGHTED_PRESSURE_PLATE,
        ) + lampIds.associateWith { Material.COBBLESTONE }
        val futures = placements.mapNotNull { (id, mat) ->
            ctx.interactionLocation(id)?.let { loc -> ctx.mutate(loc) { it.type = mat } }
        }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            ctx.submit { ctx.transition("puzzle") }
        }
    }

    override fun onInteraction(ctx: StageContext, interactionId: String, playerId: UUID) {
        when (ctx.state.currentStageId) {
            "puzzle" -> handlePuzzleInteraction(ctx, interactionId, playerId)
            "clear_path" -> if (interactionId == "goal-plate") ctx.resolve("solved")
        }
    }

    override fun onStageTimeout(ctx: StageContext, stageId: String) {
        ctx.messageAll("puzzle.timeout")
        ctx.resolve("timeout")
    }

    private fun handlePuzzleInteraction(ctx: StageContext, interactionId: String, playerId: UUID) {
        when (interactionId) {
            "mural" -> if (ctx.state.tryFireRepeatable("mural", 5_000L, System.currentTimeMillis())) {
                ctx.message(playerId, "puzzle.mural-hint")
            }
            "shrine" -> handleShrine(ctx, playerId)
            in lampIds -> handleLamp(ctx, interactionId)
        }
    }

    private fun handleShrine(ctx: StageContext, playerId: UUID) {
        if (!ctx.state.tryFireOnce("shrine")) return
        // 檢定聚合請求本身不阻塞這個 callback——回呼可能在別的執行緒,碰 state 前一律 ctx.submit。
        ctx.requestCheck(playerId, "dungeon").thenAccept { outcome ->
            ctx.submit {
                if (outcome == "success" || outcome == "crit") {
                    ctx.message(playerId, "puzzle.shrine-success")
                } else {
                    ctx.message(playerId, "puzzle.shrine-fail")
                }
            }
        }
    }

    private fun handleLamp(ctx: StageContext, lampId: String) {
        if (!ctx.state.tryFireOnce(lampId)) {
            return // 已點燃,冪等忽略(ARCH §8 案例明文)
        }
        @Suppress("UNCHECKED_CAST")
        val lit = ctx.state.get("lit") as? MutableList<String>
            ?: mutableListOf<String>().also { ctx.state.set("lit", it) }
        lit += lampId
        ctx.interactionLocation(lampId)?.let { loc -> ctx.mutate(loc) { it.type = Material.GLOWSTONE } }
        ctx.messageAll("puzzle.lit", "count" to lit.size.toString())
        when {
            lit.size < 3 -> return
            lit == correctOrder -> solvePuzzle(ctx)
            else -> wrongSolve(ctx)
        }
    }

    private fun solvePuzzle(ctx: StageContext) {
        ctx.messageAll("puzzle.solved")
        val doorLoc = ctx.interactionLocation("door")
        val future = doorLoc?.let { ctx.mutate(it) { block -> block.type = Material.AIR } }
            ?: CompletableFuture.completedFuture(null)
        future.whenComplete { _, _ -> ctx.submit { ctx.transition("clear_path") } }
    }

    private fun wrongSolve(ctx: StageContext) {
        ctx.messageAll("puzzle.wrong")
        val futures = lampIds.mapNotNull { id -> ctx.interactionLocation(id)?.let { loc -> ctx.mutate(loc) { it.type = Material.COBBLESTONE } } }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            ctx.submit { ctx.transition("ambush") }
        }
    }
}
