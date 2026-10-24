package com.tinyyana.hanatoki.folia

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

/**
 * HanaToki 放方塊的唯一寫法:一律 `applyPhysics = false`。
 *
 * `Block.setBlockData(data)` / `Block.type = x` 預設 applyPhysics = true,CraftBlock 會走完整的 onPlace,
 * 雕刻南瓜因此跑 `trySpawnGolem` 去檢查腳下能不能組成雪人／鐵傀儡／銅傀儡;場景還在分批寫入、
 * 鄰近區塊還沒就緒時 `BlockInWorld` 取到 null 就 NPE(rc.37 紙燈別院入口庭 local 12,-1)。
 * `false` 會帶 UPDATE_SKIP_ON_PLACE,跳過 onPlace(生物組成)與鄰格 neighbor update。
 * 必須在該座標所屬 region 執行緒呼叫(照 [WorldOp.dispatch])。
 */
object BlockWrites {
    fun set(block: Block, data: BlockData) = block.setBlockData(data, false)

    fun set(block: Block, material: Material) = block.setType(material, false)
}
