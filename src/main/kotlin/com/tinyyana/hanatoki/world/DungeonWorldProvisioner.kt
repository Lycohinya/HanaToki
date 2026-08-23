package com.tinyyana.hanatoki.world

import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.SpawnCategory
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * 副本專屬世界的建立與生命週期(ARCH §6「場地」的世界層)。
 *
 * ## 為什麼副本一定要有自己的世界
 *
 * Phase 4 的「刀塚」把場地蓋在 `world`(玩家的生存主世界)裡,真人驗收直接判不合格:場地
 * 佔用主世界的固定座標、玩家可以挖掉場景、路人會走進別人的決鬥、整局結束的 diff 回滾會在
 * 主世界產生一段「方塊突然變回去」的可見時間窗。舊的 `LycohinyaCore.DungeonService` 本來就
 * 會為每座副本 `WorldCreator(...).createWorld()`,這個能力在搬到 HanaToki 時漏掉了,這裡補回來。
 *
 * ## 方案:一種副本 = 一個輕量專屬世界,世界內多 slot
 *
 * 不是「每局複製一個世界」(建立/刪除世界的成本與磁碟碎片都不可接受,而且 Folia 的 runtime
 * world unload 不是穩定路徑),也不是「所有副本擠同一個世界」(anchor 偏移能隔開,但世界層
 * 的設定——時間、天氣、難度、邊界——就沒辦法各副本各自決定)。
 *
 * 每個世界的成本被壓到最低:
 * - 地形生成器是 [VoidChunkGenerator],七個 `should*` 全關,沒有任何原生地形。
 * - 場地由 `StageContext.mutate` 在固定 slot anchor 上蓋出來,整局結束逐格回滾成空氣。
 *   **重複遊玩碰到的是同一批 chunk**,不會隨局數增加。
 * - 預設 `world-auto-save: false`:場地 100% 程式化且會回滾,磁碟上沒有任何值得保留的狀態,
 *   關掉之後這個世界的資料夾大小基本固定在建立當下(量測見 MIGRATION_PLAN)。常駐型副本
 *   (場地是手工蓋的、要跨重開機保留)把它設 true。
 * - 世界邊界依實際登記的 slot 範圍自動撐開,玩家走不出場地區——虛空世界最怕的就是有人一路
 *   往外走,沿路把空 chunk 全部生出來。
 *
 * ## Folia
 *
 * `createWorld` 在 Folia/Lecithin 上的門檻是「呼叫者必須在 global region tick thread 上」
 * (Phase 0-1 實測結論,見 `docs/hanatoki/CURRENT_SYSTEM.md` §1)。[ensureWorld] 因此**必須**
 * 由 global tick thread 呼叫;呼叫端用 [runOnGlobalRegion] 包住自己的 bootstrap 流程即可。
 */
class DungeonWorldProvisioner(private val plugin: Plugin) {

    /** worldName -> 目前已套用的邊界半徑(格)。再次登記更遠的 slot 時才需要撐開。 */
    private val borderRadius = ConcurrentHashMap<String, Double>()

    /**
     * 取得(必要時建立)一個副本世界。
     *
     * @param create false = 只查現有世界,查不到就回 null(給「世界由管理員/Multiverse 自己準備」
     *               的情境用,例如既有的常駐副本世界)。
     * @param generatorId 自訂地形生成器 id(見 [WorldGeneratorRegistry]);null = 引擎自帶的
     *               [VoidChunkGenerator] + 整套 void 世界安全網。
     * @return 世界,或建立失敗時 null(已記 severe log,呼叫端只要跳過該副本的 slot 登記)。
     */
    fun ensureWorld(worldName: String, create: Boolean, autoSave: Boolean, generatorId: String?): World? {
        Bukkit.getWorld(worldName)?.let { return it }
        if (!create) return null
        // ⚠ 生成器 id 填了但沒註冊時**不能**退回 void 生成器:那個世界的資料夾可能已經存在
        // (蒼櫻就是),用 void 生成器載入會讓之後生成的區塊全變虛空,而且不可逆。
        val generator = if (generatorId == null) {
            VoidChunkGenerator()
        } else {
            WorldGeneratorRegistry.create(generatorId) ?: run {
                plugin.logger.severe(
                    "[HanaToki] 副本世界 $worldName 指定的地形生成器 $generatorId 尚未註冊," +
                        "為了不讓既有地形被 void 生成器覆蓋,這個世界不會被建立/載入",
                )
                return null
            }
        }
        if (!Bukkit.getServer().isGlobalTickThread) {
            // 不是「稍後重試就好」的暫時狀況,是呼叫端把 bootstrap 排錯執行緒了——講清楚,
            // 不要在這裡偷偷派工(那會讓呼叫端拿到 null 卻以為只是世界不存在)。
            plugin.logger.severe(
                "[HanaToki] 副本世界 $worldName 建立失敗:createWorld 只能在 global region tick thread 上呼叫," +
                    "目前執行緒是 ${Thread.currentThread().name}",
            )
            return null
        }
        val creator = WorldCreator(worldName)
            .generator(generator)
            .environment(World.Environment.NORMAL)
            .generateStructures(false)
        // seed 只對 void 世界寫死 0(它本來就不看 seed);常駐地形世界的 seed 要維持世界資料夾
        // 自己記著的那個,寫死會讓既有世界之後生成的區塊跟已生成的對不起來。
        if (generatorId == null) creator.seed(0L)
        val world = runCatching { creator.createWorld() }.onFailure {
            plugin.logger.severe("[HanaToki] 副本世界 $worldName 建立失敗:${it.message}")
        }.getOrNull() ?: return null

        if (generatorId == null) {
            applyVoidWorldSettings(world, autoSave)
            buildSpawnPlatform(world)
            plugin.logger.info("[HanaToki] 副本世界就緒:$worldName(void 生成,auto-save=$autoSave)")
        } else {
            applyResidentWorldSettings(world, autoSave)
            plugin.logger.info("[HanaToki] 副本世界就緒:$worldName(生成器 $generatorId,auto-save=$autoSave)")
        }
        return world
    }

    /**
     * 常駐地形世界的設定。**刻意只有兩件事**:自動存檔開關,以及關掉自然生怪
     * (受控場地:怪由 encounter/actor 放)——這正好就是遷移前 `LycohinyaCore.DungeonService
     * .bootstrap()` 對蒼櫻做的全部,一條不多一條不少。
     *
     * void 世界那套安全網(世界邊界、出生點屏障平台、固定天色/天氣、關 random tick 與火燒)
     * 全部**不套用**:它們存在的理由是「虛空 + 場地逐格回滾」,對一個有真實地形、玩家會待著、
     * 場地不回滾的常駐世界只會造成可見的破壞(把人關進邊界、蓋掉生成器決定的出生點、天色被鎖)。
     */
    private fun applyResidentWorldSettings(world: World, autoSave: Boolean) {
        world.setAutoSave(autoSave)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
    }

    /**
     * 把世界邊界撐到足以涵蓋這個 slot(含 [margin] 格的餘裕)。邊界只會變大不會變小——
     * 同一個世界可能被多座副本或多個 slot 共用,後登記的不該把先登記的關在外面。
     */
    fun expandBorderFor(world: World, anchor: Location, margin: Int) {
        val needed = maxOf(Math.abs(anchor.x), Math.abs(anchor.z)) + margin
        val current = borderRadius[world.name] ?: 0.0
        if (needed <= current) return
        borderRadius[world.name] = needed
        val border = world.worldBorder
        border.setCenter(0.0, 0.0)
        border.size = needed * 2
    }

    /**
     * 副本世界的固定環境。玩家在裡面看到的天色/天氣是關卡演出的一部分,不該跟著主世界的
     * 日夜循環跑——同一座副本每次進去都應該長一樣。
     */
    private fun applyVoidWorldSettings(world: World, autoSave: Boolean) {
        world.setAutoSave(autoSave)
        world.difficulty = Difficulty.NORMAL
        world.setSpawnLocation(VoidChunkGenerator.SPAWN_X, VoidChunkGenerator.SPAWN_Y, VoidChunkGenerator.SPAWN_Z)

        // 沒有自然生怪:副本裡出現的每一隻都應該是 encounter/actor 放的。
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        SpawnCategory.entries.forEach { runCatching { world.setSpawnLimit(it, 0) } }

        // 固定成傍晚:刀塚的「戰事剛結束的那個下午」與其他副本的演出都靠世界時間定調,
        // 由內容層自己再覆寫 setTime 即可,這裡只保證它不會自己往前跑。
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        world.setStorm(false)
        world.setThundering(false)

        // 場地是程式化蓋出來又要逐格回滾的:任何會自己改方塊的機制都會讓 diff 對不上。
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0)
        world.setGameRule(GameRule.DO_FIRE_TICK, false)
        world.setGameRule(GameRule.MOB_GRIEFING, false)
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false)
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false)
        world.setGameRule(GameRule.DISABLE_RAIDS, true)
        world.setGameRule(GameRule.DO_INSOMNIA, false)
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false)
        world.setGameRule(GameRule.SPAWN_RADIUS, 0)
    }

    /**
     * 世界出生點下方的小平台。虛空世界的出生點沒有地板,任何一次「不經副本流程進到這個世界」
     * (管理員 `/tp`、重生點失效、外掛把人丟進來)都會直接掉進虛空。這塊 3×3 是安全網,
     * 不是玩法的一部分——刻意用屏障方塊,玩家不會誤以為那裡有內容。
     */
    private fun buildSpawnPlatform(world: World) {
        val y = VoidChunkGenerator.SPAWN_Y - 1
        Bukkit.getRegionScheduler().execute(plugin, world, VoidChunkGenerator.SPAWN_X shr 4, VoidChunkGenerator.SPAWN_Z shr 4) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    world.getBlockAt(VoidChunkGenerator.SPAWN_X + dx, y, VoidChunkGenerator.SPAWN_Z + dz).type = Material.BARRIER
                }
            }
        }
    }

    companion object {
        /**
         * 在 global region tick thread 上執行 [action]:已經在上面就直接跑(維持呼叫端的
         * 「onEnable 內同步完成」語意),否則派工過去。
         *
         * 存在理由:`createWorld` 的執行緒門檻(見類別 KDoc)。伺服器啟動時的 onEnable 本來就
         * 跑在 global tick thread 上,但 PlugMan 熱插拔是由指令觸發的——玩家下的指令跑在**該
         * 玩家的 region** 上,直接呼叫 createWorld 會失敗。
         */
        fun runOnGlobalRegion(plugin: Plugin, action: Runnable) {
            if (Bukkit.getServer().isGlobalTickThread) action.run()
            else Bukkit.getGlobalRegionScheduler().execute(plugin, action)
        }
    }
}
