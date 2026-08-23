package com.tinyyana.hanatoki.world

import org.bukkit.generator.ChunkGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * 內容插件註冊自訂地形生成器的地方(`dungeons.yml` 的 `world-generator: <id>` 查這張表)。
 *
 * ## 為什麼引擎需要這個
 *
 * Phase 1–4 的副本場地全部是「void 世界 + 程式化蓋出來的 slot」,生成器寫死成
 * [VoidChunkGenerator] 就夠了。蒼櫻不是——它有一整張自製的交融地形,而且**世界已經存在於
 * 正式服**,不能重建。把那個生成器類別搬進 HanaToki 也不行:它吃的是 Lycohinya 的調色盤/
 * 生態域設定(ARCH §1「引擎不 import 任何 Lyco 插件的具體類別」)。
 *
 * 所以方向反過來:引擎只認一個 id,實際的 [ChunkGenerator] 由內容插件自己送進來。
 * 蒼櫻因此**沿用同一個 `BlendedChunkGenerator` 類別、同一份主題設定、同一個 seed salt**,
 * 地形不可能與遷移前有差異。
 *
 * ## 跨插件簽章
 *
 * 只用 JDK 的 [Supplier] 與 [ChunkGenerator](Bukkit 型別,由伺服器 classloader 提供)——
 * **不要**改成 Kotlin 的 `() -> ChunkGenerator`,那是 `kotlin.jvm.functions.Function0`,
 * 跨插件會丟 `LinkageError`(見 `stage/StageContext` KDoc 記錄的實測)。
 *
 * 每次要用都呼叫一次 [create]:`ChunkGenerator` 實例會被 Bukkit 綁在世界上,不共用。
 */
object WorldGeneratorRegistry {

    private val suppliers = ConcurrentHashMap<String, Supplier<ChunkGenerator>>()

    /** 註冊(或覆蓋)一個生成器 id。內容插件在 onEnable、載入自己的副本定義**之前**呼叫。 */
    fun register(id: String, supplier: Supplier<ChunkGenerator>) {
        suppliers[id] = supplier
    }

    /** 內容插件 onDisable 時收回(PlugMan 熱插拔硬規則:交乾淨)。 */
    fun unregister(id: String) {
        suppliers.remove(id)
    }

    fun isRegistered(id: String): Boolean = suppliers.containsKey(id)

    /** 產生一個新的生成器實例;id 為 null 或沒有註冊時回 null(呼叫端自行決定要不要退回 void)。 */
    fun create(id: String?): ChunkGenerator? {
        val supplier = suppliers[id ?: return null] ?: return null
        return runCatching { supplier.get() }.getOrNull()
    }
}
