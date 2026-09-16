package com.tinyyana.hanatoki.map

import org.bukkit.block.BlockFace
import org.bukkit.block.structure.StructureRotation
import java.util.Random

/**
 * 薄 connector planner:用資產裡的 jigsaw 方塊與 template pool 把多個 Structure 模組接成一張封閉地圖。
 *
 * 為什麼不直接呼叫原版 Jigsaw:Paper/Lecithin 沒有公開的 jigsaw 組裝 API,原版實作要走 NMS,
 * 而且它是在世界生成執行緒上一口氣放完,沒有辦法接 HanaToki 的 per-chunk 派工與 ownership。
 * 這裡只做「空間排版」,產出 [MapLayout],放置交給同一條 placement 路徑。
 * 資產格式(jigsaw 的 name/target/pool/final_state、template pool JSON)照原版,
 * 之後若有可靠 API 可以換掉這個類別而不動資產。
 *
 * 規則:
 * - 父 connector 的 `target` = 子 connector 的 `name`,子 connector 旋轉後的正面 = 父正面的反方向。
 * - 子模組原點 = 父 connector 前一格 − 旋轉後的子 connector 區域座標。
 * - 模組盒子不得重疊。
 * - **封閉**:每個 `pool != minecraft:empty` 的 connector 都要接上,否則回溯換下一個候選;
 *   [maxPieces] 內組不出來就失敗(不留開口的半張圖)。
 * - 候選順序:pool 依權重不放回抽樣、旋轉洗牌,全部由 [seed] 決定——同 seed + 同資產 revision = 同一張圖。
 */
class JigsawPlanner(private val library: MapAssetLibrary) {

    fun plan(start: MapAssetRef, startRotation: StructureRotation, seed: Long, maxPieces: Int): MapLayout {
        require(maxPieces >= 1) { "maxPieces 至少 1" }
        val search = Search(Random(mix(seed)), maxPieces)
        val first = PlacedPiece(search.template(start), MapPos(0, 0, 0), startRotation)
        search.pieces += first
        check(search.solve(openOf(first, null))) { "$start 無法在 $maxPieces 塊內組出封閉地圖(seed=$seed)" }
        return MapLayout(search.pieces)
    }

    private class Open(val pos: MapPos, val front: BlockFace, val spec: ConnectorSpec)

    private fun openOf(piece: PlacedPiece, used: ConnectorSpec?): List<Open> =
        piece.template.connectors.filter { it !== used && it.expandable }.map {
            Open(piece.origin.plus(MapRotation.rotate(it.pos, piece.rotation)), MapRotation.rotate(it.front, piece.rotation), it)
        }

    private inner class Search(val random: Random, val maxPieces: Int) {
        val pieces = ArrayList<PlacedPiece>()
        private val templates = HashMap<MapAssetRef, StructureTemplate>()
        private val pools = HashMap<MapAssetRef, TemplatePool>()
        private var attempts = 0

        fun template(ref: MapAssetRef): StructureTemplate = templates.getOrPut(ref) { library.template(ref) }

        fun solve(open: List<Open>): Boolean {
            if (open.isEmpty()) return true
            check(++attempts <= MAX_ATTEMPTS) { "jigsaw 搜尋超過 $MAX_ATTEMPTS 次嘗試" }
            if (pieces.size >= maxPieces) return false
            val parent = open.first()
            val rest = open.drop(1)
            val poolRef = MapAssetRef.parse(parent.spec.pool, "minecraft")
            val pool = pools.getOrPut(poolRef) { library.pool(poolRef) }
            val socket = parent.pos.offset(parent.front)
            val wanted = parent.front.oppositeFace
            for (entry in weightedOrder(pool.entries)) {
                val template = template(entry.structure)
                for (rotation in MapRotation.ALL.shuffled(random)) {
                    for (child in template.connectors) {
                        if (child.name != parent.spec.target) continue
                        if (MapRotation.rotate(child.front, rotation) != wanted) continue
                        val origin = socket.minus(MapRotation.rotate(child.pos, rotation))
                        val piece = PlacedPiece(template, origin, rotation)
                        if (pieces.any { it.bounds.intersects(piece.bounds) }) continue
                        pieces += piece
                        if (solve(rest + openOf(piece, child))) return true
                        pieces.removeAt(pieces.size - 1)
                    }
                }
            }
            return false
        }

        private fun weightedOrder(entries: List<PoolEntry>): List<PoolEntry> {
            val remaining = entries.toMutableList()
            val out = ArrayList<PoolEntry>(entries.size)
            while (remaining.isNotEmpty()) {
                var roll = random.nextInt(remaining.sumOf { it.weight })
                val index = remaining.indexOfFirst { roll -= it.weight; roll < 0 }
                out += remaining.removeAt(index)
            }
            return out
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 10_000

        /**
         * splitmix64 finalizer。`java.util.Random` 對相鄰 seed(slot 編號、局數)的第一個輸出高度相關,
         * 不打散的話 seed 0..15 抽權重池全部抽到同一個模組(單元測試實際抓到)。
         */
        private fun mix(seed: Long): Long {
            var z = seed + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
