package com.tinyyana.hanatoki.testcontent

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.folia.ChunkWaveRunner
import com.tinyyana.hanatoki.map.DirectoryMapAssetSource
import com.tinyyana.hanatoki.map.JigsawPlanner
import com.tinyyana.hanatoki.map.MapAssetLibrary
import com.tinyyana.hanatoki.map.MapAssetRef
import com.tinyyana.hanatoki.map.MapLayout
import com.tinyyana.hanatoki.map.MapPos
import com.tinyyana.hanatoki.map.MarkerKind
import com.tinyyana.hanatoki.map.PlacedMap
import com.tinyyana.hanatoki.stage.DungeonBehavior
import com.tinyyana.hanatoki.stage.StageContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.structure.StructureRotation
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Map Asset layer 的 **executable contract**(不是正式內容)。註冊給 `dungeons.yml` 的
 * `test-structure`(test-only,預設不載入)。未來的內容插件照這個形狀接:
 *
 * `prepareStage`:非 tick thread 讀資產 + Jigsaw 排版 → `ctx.maps().place()` → 記下 typed marker
 * → `onStageEnter`:marker ready,用 `trigger` marker 當終點 → 結束後引擎自動回收 generation。
 *
 * 資產放在 `plugins/HanaToki/map-assets/`(第一次用時從 jar 複製),改檔下一局生效,不需要改 Kotlin。
 */
class StructureProbeBehavior(private val plugin: Plugin) : DungeonBehavior {
    private val placed = ConcurrentHashMap<UUID, PlacedMap>()

    override fun prepareStage(ctx: StageContext, stageId: String): CompletableFuture<Void> {
        val anchor = ctx.anchor
        val target = MapPos(anchor.blockX, anchor.blockY, anchor.blockZ)
        val seed = ctx.slotId.hashCode().toLong()
        return CompletableFuture.supplyAsync { StructureProbe.jigsawLayout(plugin, seed).alignMarker(MarkerKind.PLAYER_SPAWN, target) }
            .thenCompose { ctx.maps().place(it) }
            .thenAccept { map ->
                placed[ctx.sessionId] = map
                ctx.log("${StructureProbe.TAG} ready ${StructureProbe.summary(map)}")
            }
    }

    override fun onStageEnter(ctx: StageContext, stageId: String) {
        val map = placed[ctx.sessionId] ?: return
        ctx.messageAll("map-probe.ready", mapOf("pieces" to map.layout.pieces.size.toString(), "markers" to map.markers.size.toString()))
        val goal = map.markers(MarkerKind.TRIGGER).firstOrNull() ?: return
        val world = ctx.anchor.world ?: return
        val radius = goal.properties["radius"]?.toDoubleOrNull() ?: 1.5
        ctx.submitRepeating(20L, 10L) {
            ctx.membersWithin(goal.location(world), radius).thenAccept { hits ->
                if (hits.isNotEmpty()) ctx.submit { ctx.resolve("cleared") }
            }
        }
    }

    override fun onSessionEnd(ctx: StageContext, reason: String) {
        placed.remove(ctx.sessionId)
    }
}

/** `/hanatoki admin mapprobe` 與 [StructureProbeBehavior] 共用的資產與檢查。只在隔離測試環境用。 */
object StructureProbe {
    const val TAG = "HT-MAP-PROBE"
    private const val NS = "hanatoki_probe"
    private val BUNDLED = listOf(
        "structure/entrance.nbt", "structure/corridor_long.nbt", "structure/corridor_short.nbt", "structure/end_room.nbt",
        "worldgen/template_pool/corridors.json", "worldgen/template_pool/ends.json",
    )

    /** 第一次用時把 jar 裡的 probe 資產複製出來;已存在的檔案不覆蓋(那是建築者改過的版本)。 */
    fun library(plugin: Plugin): MapAssetLibrary {
        val root = File(plugin.dataFolder, "map-assets")
        for (path in BUNDLED) {
            if (!File(root, "$NS/$path").isFile) plugin.saveResource("map-assets/$NS/$path", false)
        }
        return MapAssetLibrary(DirectoryMapAssetSource(root.toPath()))
    }

    fun jigsawLayout(plugin: Plugin, seed: Long): MapLayout =
        JigsawPlanner(library(plugin)).plan(MapAssetRef(NS, "entrance"), StructureRotation.NONE, seed, 8)

    fun summary(map: PlacedMap): String {
        val kinds = MarkerKind.entries.joinToString(" ") { "${it.id}=${map.markers(it).size}" }
        val pieces = map.layout.pieces.joinToString(",") { "${it.template.id.path}/${it.rotation}" }
        val sections = map.chunksTouched().map { Math.floorDiv(it.x, 16) to Math.floorDiv(it.z, 16) }.toSet()
        return "pieces=[$pieces] $kinds sections=${sections.size} $map"
    }

    private fun PlacedMap.chunksTouched() = layout.cellsByChunk().keys

    fun run(core: HanaTokiCore, sender: CommandSender, slotId: String, mode: String, seed: Long) {
        val plugin = core.plugin
        val anchor = core.slotPool.anchorOf(slotId)
        val world = anchor?.world
        if (anchor == null || world == null) { sender.sendMessage("§c找不到 slot $slotId"); return }
        if (core.sessionManager.sessionBySlot(slotId) != null) { sender.sendMessage("§cslot $slotId 有 session,先讓它結束"); return }
        val target = MapPos(anchor.blockX, anchor.blockY, anchor.blockZ)
        fun report(line: String) {
            plugin.logger.info("[HanaToki] $TAG mode=$mode slot=$slotId $line")
            sender.sendMessage("§7$TAG $line")
        }
        CompletableFuture.supplyAsync {
            val library = library(plugin)
            when (mode) {
                "fixed", "foreign" -> MapLayout.single(library.template(MapAssetRef(NS, "entrance")), MapPos(0, 0, 0), StructureRotation.CLOCKWISE_90)
                else -> JigsawPlanner(library).plan(MapAssetRef(NS, "entrance"), StructureRotation.NONE, seed, 8)
            }.alignMarker(MarkerKind.PLAYER_SPAWN, target)
        }.thenCompose { layout ->
            // 先量盒子裡原本有幾格非空氣(前一次 foreign 測試刻意留下的陌生修改也算在內),回收後要回到這個數。
            countNonAir(plugin, world, layout).thenApply { layout to it }
        }.thenCompose { (layout, baseline) ->
            val placements = core.mapPlacements
            when (mode) {
                "fixed", "jigsaw" -> placements.place(slotId, null, world, layout).thenCompose { map ->
                    report("placed ${summary(map)}")
                    countMatching(plugin, world, layout).thenCompose { matching ->
                        report("readback planned=$matching")
                        placements.release(slotId, map.generationId)
                    }
                }.thenCompose { cleanup -> verifyCleared(plugin, world, layout, baseline, cleanup.toString(), ::report) }
                "foreign" -> placements.place(slotId, null, world, layout).thenCompose { map ->
                    report("placed ${summary(map)}")
                    tamper(plugin, world, map).thenCompose { cells ->
                        placements.release(slotId, map.generationId).thenCompose { cleanup ->
                            val ok = cleanup.foreignKept == 2
                            verifyCleared(plugin, world, layout, baseline + 3, "$cleanup foreignKeptOk=$ok", ::report)
                        }.thenCompose {
                            // 「別的系統」收回自己的修改,讓下一次 probe 從乾淨的場地開始。
                            CompletableFuture.allOf(*cells.map { c -> writeAt(plugin, world, c[0], c[1], c[2]) { it.setType(Material.AIR, false) } }.toTypedArray())
                        }
                    }
                }
                "fail" -> placements.place(slotId, null, world, layout, 1).handle { _, error ->
                    report("placement failed as injected: ${error?.message}")
                }.thenCompose { verifyCleared(plugin, world, layout, baseline, "after-failure", ::report) }
                "cancel" -> {
                    val placing = placements.place(slotId, null, world, layout)
                    // 讓 placement 真的開始寫之後再取消(下一個 tick 在 anchor region 上)。
                    val cancelled = CompletableFuture<Void>()
                    Bukkit.getRegionScheduler().runDelayed(plugin, anchor, { _ ->
                        placements.cleanupSlot(slotId).whenComplete { _, _ -> cancelled.complete(null) }
                    }, 2L)
                    placing.handle { map, error -> report("placement outcome=${if (error == null) "completed-before-cancel $map" else "cancelled: ${error.message}"}") }
                        .thenCombine(cancelled) { _, _ -> null }
                        .thenCompose { verifyCleared(plugin, world, layout, baseline, "after-cancel", ::report) }
                }
                else -> CompletableFuture.failedFuture(IllegalArgumentException("mode 只接受 fixed|jigsaw|foreign|fail|cancel"))
            }
        }.whenComplete { _, error ->
            if (error != null) report("ERROR ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** 放置後讀回:有幾格現況等於計畫(含空氣格)。 */
    private fun countMatching(plugin: Plugin, world: World, layout: MapLayout): CompletableFuture<Int> {
        val cells = layout.cellsByChunk()
        val keys = cells.values.first().keys
        val data = keys.map { Bukkit.createBlockData(it.state).also { d -> d.rotate(it.rotation) } }
        val matching = AtomicInteger()
        return ChunkWaveRunner(plugin, world, cells.keys.toList(), { chunk ->
            val c = cells.getValue(com.tinyyana.hanatoki.folia.ChunkCoord(chunk.x, chunk.z))
            for (i in 0 until c.size) if (world.getBlockAt(c.x(i), c.y(i), c.z(i)).blockData == data[c.key(i)]) matching.incrementAndGet()
        }).start().thenApply { matching.get() }
    }

    /** 回收後讀回:排版盒子內非空氣格數要回到放置前的 [expectedNonAir](加上刻意保留的陌生修改)。 */
    private fun verifyCleared(plugin: Plugin, world: World, layout: MapLayout, expectedNonAir: Int, label: String, report: (String) -> Unit): CompletableFuture<Void> =
        countNonAir(plugin, world, layout).thenAccept { nonAir ->
            val verdict = if (nonAir == expectedNonAir) "PASS" else "FAIL"
            report("$verdict $label nonAir=$nonAir expected=$expectedNonAir")
        }

    private fun countNonAir(plugin: Plugin, world: World, layout: MapLayout): CompletableFuture<Int> {
        val b = layout.bounds
        val chunks = (Math.floorDiv(b.minX, 16)..Math.floorDiv(b.maxX, 16)).flatMap { cx ->
            (Math.floorDiv(b.minZ, 16)..Math.floorDiv(b.maxZ, 16)).map { cz -> com.tinyyana.hanatoki.folia.ChunkCoord(cx, cz) }
        }
        val nonAir = AtomicInteger()
        return ChunkWaveRunner(plugin, world, chunks, { chunk ->
            for (x in maxOf(b.minX, chunk.x * 16)..minOf(b.maxX, chunk.x * 16 + 15))
                for (z in maxOf(b.minZ, chunk.z * 16)..minOf(b.maxZ, chunk.z * 16 + 15))
                    for (y in b.minY..b.maxY) if (!world.getBlockAt(x, y, z).type.isAir) nonAir.incrementAndGet()
        }).start().thenApply { nonAir.get() }
    }

    /**
     * 模擬「玩家/其他系統在 placement 之後改了東西」:樓梯轉向(同 Material 不同狀態)、牆換成玻璃、
     * 在原本就是空氣、generation 沒碰過的格子放一塊金。cleanup 後這三格都必須留著。
     */
    private fun writeAt(plugin: Plugin, world: World, x: Int, y: Int, z: Int, action: (org.bukkit.block.Block) -> Unit): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        Bukkit.getRegionScheduler().execute(plugin, world, x shr 4, z shr 4) {
            try { action(world.getBlockAt(x, y, z)); done.complete(null) } catch (t: Throwable) { done.completeExceptionally(t) }
        }
        return done
    }

    /** 回傳被改的三格座標。 */
    private fun tamper(plugin: Plugin, world: World, map: PlacedMap): CompletableFuture<List<IntArray>> {
        val b = map.layout.bounds
        val futures = ArrayList<CompletableFuture<Void>>()
        fun at(x: Int, y: Int, z: Int, action: (org.bukkit.block.Block) -> Unit) { futures += writeAt(plugin, world, x, y, z, action) }
        val stairs = ArrayList<IntArray>()
        val walls = ArrayList<IntArray>()
        val chunks = (Math.floorDiv(b.minX, 16)..Math.floorDiv(b.maxX, 16)).flatMap { cx ->
            (Math.floorDiv(b.minZ, 16)..Math.floorDiv(b.maxZ, 16)).map { cz -> com.tinyyana.hanatoki.folia.ChunkCoord(cx, cz) }
        }
        // 先用 chunk 派工讀出目標格,再各自派到擁有該格的 region 改。
        return ChunkWaveRunner(plugin, world, chunks, { chunk ->
                for (x in maxOf(b.minX, chunk.x * 16)..minOf(b.maxX, chunk.x * 16 + 15))
                    for (z in maxOf(b.minZ, chunk.z * 16)..minOf(b.maxZ, chunk.z * 16 + 15))
                        for (y in b.minY..b.maxY) {
                            val type = world.getBlockAt(x, y, z).type
                            synchronized(stairs) {
                                if (type == Material.OAK_STAIRS) stairs += intArrayOf(x, y, z)
                                if (type == Material.STONE_BRICKS && y == b.minY + 1) walls += intArrayOf(x, y, z)
                            }
                        }
        }).start().thenCompose {
            val s = stairs.single()
            at(s[0], s[1], s[2]) { block ->
                val data = block.blockData as Stairs
                data.facing = data.facing.oppositeFace
                block.setBlockData(data, false)
            }
            val w = walls.first()
            at(w[0], w[1], w[2]) { it.setType(Material.GLASS, false) }
            val spawn = map.markers(MarkerKind.PLAYER_SPAWN).single()
            at(spawn.x, spawn.y + 2, spawn.z) { it.setType(Material.GOLD_BLOCK, false) }
            CompletableFuture.allOf(*futures.toTypedArray()).thenApply { listOf(s, w, intArrayOf(spawn.x, spawn.y + 2, spawn.z)) }
        }
    }
}
