package com.tinyyana.hanatoki.world

import com.tinyyana.hanatoki.folia.WorldOp
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

/**
 * 方塊 diff 快照回滾(ARCH §6「場地重置 = 方塊 diff 快照回滾」、§5.1③ 派工模式、
 * Phase 0-3 實測:500 方塊逐塊/整批派工皆遠低於 50ms/tick 預算)。
 *
 * [record] 要在已經派工到該座標所屬 region 的 task 內呼叫(通常就是 mutation 動作本身的
 * callback 裡順手記錄)——這裡不再重複派工,只是把 before-state 存進 [DiffLog]。
 *
 * [rollback] 依 ARCH §5.1 的 completion barrier:每筆逆序 mutation 各自 [WorldOp.dispatch]
 * (未依 region 分組——Phase 0 已驗證逐塊派工的效能足夠,分組合併留作未來優化,見架構文件
 * §5.1 註記),回傳的 future 全部完成後才視為回滾結束;呼叫端必須等這個 future 完成才能把
 * slot 標為空閒(`SessionManager.releaseSlotAfterRollback`)。
 */
class WorldDiffRecorder(private val plugin: Plugin) {
    private data class LocKey(val worldName: String, val x: Int, val y: Int, val z: Int)

    private val log = DiffLog<LocKey, BlockData>()

    fun record(location: Location, before: BlockData) {
        val world = location.world ?: return
        log.record(LocKey(world.name, location.blockX, location.blockY, location.blockZ), before.clone())
    }

    fun pendingCount(): Int = log.size()

    /** 立即丟棄未回滾的 diff(不 apply)——用於「反正整個世界要重建」的情境,Phase 1 不使用。*/
    fun discard() = log.clear()

    /** 回滾;回傳的 future 全部 world-op 完成才 complete。空 log 直接回已完成的 future。*/
    fun rollback(world: World): CompletableFuture<Void> {
        val entries = log.reverseEntries()
        log.clear()
        if (entries.isEmpty()) return CompletableFuture.completedFuture(null)
        val futures = entries.map { entry ->
            val loc = Location(world, entry.key.x.toDouble(), entry.key.y.toDouble(), entry.key.z.toDouble())
            WorldOp.dispatch(plugin, loc) { block -> block.blockData = entry.before }
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }
}
