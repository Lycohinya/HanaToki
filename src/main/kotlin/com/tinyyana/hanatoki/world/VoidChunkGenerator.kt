package com.tinyyana.hanatoki.world

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random

/**
 * 副本世界的地形生成器:**什麼都不生成**。
 *
 * 副本場地是由 [com.tinyyana.hanatoki.stage.StageContext.mutate] 在固定 slot anchor 上程式化
 * 蓋出來的(蓋的同時記 diff,整局結束逐格回滾)。世界本身只需要提供一塊「空的座標空間」,
 * 任何原生地形都是純粹的負擔——會佔磁碟、會生怪、會讓場地邊界外看起來像另一個世界。
 *
 * ⚠ `ChunkGenerator` 的七個 `should*` 預設全是 `true`,只 override `generateNoise` 之類的
 * 方法**不會**讓世界變空:表層/洞穴/裝飾/結構/生怪各自有獨立開關,漏掉一個就會在虛空裡
 * 冒出樹或礦洞。這裡七個一次全關(javap `paper-api-26.2.build.116-stable` 的
 * `org.bukkit.generator.ChunkGenerator` 對照確認共七個無參數多載)。
 *
 * 磁碟行為:未經 mutate 的區塊全是空氣,Anvil 格式對全空 section 不寫任何方塊資料,
 * 因此重複遊玩不會讓世界資料夾持續膨脹——實際量測見 `docs/hanatoki/MIGRATION_PLAN.md`。
 */
class VoidChunkGenerator : ChunkGenerator() {

    override fun shouldGenerateNoise(): Boolean = false
    override fun shouldGenerateSurface(): Boolean = false
    override fun shouldGenerateBedrock(): Boolean = false
    override fun shouldGenerateCaves(): Boolean = false
    override fun shouldGenerateDecorations(): Boolean = false
    override fun shouldGenerateMobs(): Boolean = false
    override fun shouldGenerateStructures(): Boolean = false

    /**
     * 世界出生點。副本玩家一律被傳送到自己那個 slot 的 anchor,不會用到這裡;但世界本身
     * 需要一個合法出生點(重生/管理員 `/tp` 到世界時的落點),而虛空世界的預設出生點會讓人
     * 直接往下掉。[DungeonWorldProvisioner] 會在這個座標下方鋪一塊小平台。
     */
    override fun getFixedSpawnLocation(world: World, random: Random): Location =
        Location(world, SPAWN_X + 0.5, SPAWN_Y.toDouble(), SPAWN_Z + 0.5)

    override fun isParallelCapable(): Boolean = true

    override fun getDefaultPopulators(world: World): MutableList<org.bukkit.generator.BlockPopulator> =
        mutableListOf()

    override fun canSpawn(world: World, x: Int, z: Int): Boolean = true

    override fun getBaseHeight(worldInfo: WorldInfo, random: Random, x: Int, z: Int, heightMap: org.bukkit.HeightMap): Int =
        worldInfo.minHeight

    companion object {
        /** 世界出生平台的座標。刻意遠離所有 slot anchor(slot 從 anchor-offset 起沿 X 軸展開)。 */
        const val SPAWN_X = 0
        const val SPAWN_Y = 64
        const val SPAWN_Z = -64
    }
}
