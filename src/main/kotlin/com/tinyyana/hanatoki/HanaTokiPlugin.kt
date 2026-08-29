package com.tinyyana.hanatoki

import com.tinyyana.hanatoki.api.DungeonAccess
import com.tinyyana.hanatoki.api.PresenceBridge
import com.tinyyana.hanatoki.command.HanaTokiCommand
import com.tinyyana.hanatoki.stage.DungeonBehaviorRegistry
import com.tinyyana.hanatoki.testcontent.CombatTestBehavior
import com.tinyyana.hanatoki.testcontent.PuzzleTestBehavior
import com.tinyyana.hanatoki.testcontent.RoguelikeShellBehavior
import com.tinyyana.hanatoki.world.DungeonWorldProvisioner
import com.tinyyana.hanatoki.world.VoidChunkGenerator
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class HanaTokiPlugin : JavaPlugin() {
    lateinit var core: HanaTokiCore
        private set

    private var tickTaskHandle: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null
    private var acceptingNewSessions = true

    override fun onEnable() {
        core = HanaTokiCore(this)

        saveResource("dungeons.yml", false)
        saveResource("messages.yml", false)
        core.texts.reload(File(dataFolder, "messages.yml"))
        val dungeonsFile = File(dataFolder, "dungeons.yml")
        // 載入會順帶建立副本專屬世界(`world-create: true` 的定義),而 Folia/Lecithin 的
        // `createWorld` 只能在 global region tick thread 上呼叫。伺服器啟動時的 onEnable 本來
        // 就在那條執行緒上(直接跑),PlugMan 熱插拔則是由指令觸發、跑在別的 region 上(派工過去)。
        DungeonWorldProvisioner.runOnGlobalRegion(this) {
            core.registry.loadAll(dungeonsFile, core.slotPool)
        }

        // Kotlin extension point(ARCH §3):內容判定邏輯不進 YAML,在這裡註冊 dungeonId -> behavior。
        // test-puzzle/test-combat 是引擎自帶的 architecture probe,不是正式副本內容(見兩個
        // behavior 類別的 KDoc)。
        DungeonBehaviorRegistry.register("test-puzzle", PuzzleTestBehavior())
        DungeonBehaviorRegistry.register("test-combat", CombatTestBehavior())
        DungeonBehaviorRegistry.register("test-roguelike", RoguelikeShellBehavior())

        val command = HanaTokiCommand(core)
        getCommand("hanatoki")?.setExecutor(command)
        getCommand("hanatoki")?.tabCompleter = command

        server.pluginManager.registerEvents(HanaTokiListener(core), this)
        // 局內物品的洩漏防線(丟棄/拾取/容器/漏斗)。跟主 listener 分開註冊:它只在有副本
        // 開了局內背包時才會真的擋東西,而且規則自成一組,混進主 listener 會讓兩邊都難讀。
        server.pluginManager.registerEvents(core.instanceItemGuard, this)

        // ARCH §5.2 規則 5:v1 用 GlobalRegionScheduler 驅動 tick 訊號,訊號本身只做無副作用/
        // 單 session 操作的查表與 session.tick() 呼叫(Phase 1 尚無跨 session 共享狀態需要序列化到
        // anchor region——若之後量測到 global tick 的跨 region hop 成本過高,再改成逐 instance
        // 在各自 anchor region 排 runAtFixedRate,見 MIGRATION_PLAN Phase 1 產出說明)。
        tickTaskHandle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, { _ ->
            if (acceptingNewSessions) core.tick()
        }, 20L, 20L)

        // 上次沒收斂完的局內背包交易(崩潰重啟/上次 onDisable 標成 RESTORING 的那些)。
        // 一定要在 listener 註冊之後:在線玩家的還原會經他們自己的 EntityScheduler,
        // 而剛登入的玩家由 `HanaTokiListener.onJoin` 接手。
        core.recoverInstanceInventories()

        server.servicesManager.register(
            PresenceBridge::class.java,
            core,
            this,
            ServicePriority.Normal,
        )
        // 外部 UI(LycohinyaCore 的 `/lyco dungeon` 選單)用的進出入口,見 api/DungeonAccess。
        server.servicesManager.register(
            DungeonAccess::class.java,
            core,
            this,
            ServicePriority.Normal,
        )
        // 局內物品所有權(道具插件鑄造局內武器時蓋章用),見 api/InstanceItems。
        server.servicesManager.register(
            com.tinyyana.hanatoki.api.InstanceItems::class.java,
            core.instanceInventory.items,
            this,
            ServicePriority.Normal,
        )

        logger.info("[HanaToki] 已啟用")
    }

    /**
     * 副本世界如果被別人(Multiverse、`bukkit.yml` 的 worlds 區塊)載入,要拿到的仍然是
     * [VoidChunkGenerator] 而不是原版地形——這台伺服器上真的裝了 Multiverse,一旦它用預設
     * 生成器把某座副本世界 import 進來,那個世界會開始長出真的地形,場地就泡在裡面了。
     *
     * 只認 HanaToki 自己登記過的副本世界名(`world-create: true` 的那些),其餘回 null 交還原版。
     *
     * ⚠ 常駐副本(蒼櫻)有自己的地形生成器,**不能**一律回 [VoidChunkGenerator]——那會讓
     * Multiverse 掛載時把一個既有的地形世界接上 void 生成器。查定義拿它登記的生成器 id
     * (見 `world/WorldGeneratorRegistry`);查不到就回 null 交還原版,而不是猜一個。
     */
    override fun getDefaultWorldGenerator(worldName: String, id: String?): org.bukkit.generator.ChunkGenerator? {
        if (!this::core.isInitialized) return null
        val def = core.registry.definitions.values.firstOrNull { it.worldCreate && it.worldName == worldName }
            ?: return null
        if (def.worldGeneratorId == null) return VoidChunkGenerator()
        return com.tinyyana.hanatoki.world.WorldGeneratorRegistry.create(def.worldGeneratorId)
    }

    override fun onDisable() {
        // PlugMan 熱插拔硬規則(全 repo 慣例):停排程 → 清實體/session → 交把手 → flush → 關連線。
        acceptingNewSessions = false
        tickTaskHandle?.cancel()
        tickTaskHandle = null

        server.servicesManager.unregisterAll(this)

        if (this::core.isInitialized) {
            core.shutdownAll()
        }

        logger.info("[HanaToki] 已停用,所有 session 結為 abandoned 並回收 slot")
    }
}
