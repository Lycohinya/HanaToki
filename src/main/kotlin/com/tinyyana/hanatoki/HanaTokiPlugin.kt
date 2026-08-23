package com.tinyyana.hanatoki

import com.tinyyana.hanatoki.api.PresenceBridge
import com.tinyyana.hanatoki.command.HanaTokiCommand
import com.tinyyana.hanatoki.stage.DungeonBehaviorRegistry
import com.tinyyana.hanatoki.testcontent.CombatTestBehavior
import com.tinyyana.hanatoki.testcontent.PuzzleTestBehavior
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
        core.registry.loadAll(dungeonsFile, core.slotPool) { name -> Bukkit.getWorld(name) }

        // Kotlin extension point(ARCH §3):內容判定邏輯不進 YAML,在這裡註冊 dungeonId -> behavior。
        // test-puzzle/test-combat 是引擎自帶的 architecture probe,不是正式副本內容(見兩個
        // behavior 類別的 KDoc)。
        DungeonBehaviorRegistry.register("test-puzzle", PuzzleTestBehavior())
        DungeonBehaviorRegistry.register("test-combat", CombatTestBehavior())

        val command = HanaTokiCommand(core)
        getCommand("hanatoki")?.setExecutor(command)
        getCommand("hanatoki")?.tabCompleter = command

        server.pluginManager.registerEvents(HanaTokiListener(core), this)

        // ARCH §5.2 規則 5:v1 用 GlobalRegionScheduler 驅動 tick 訊號,訊號本身只做無副作用/
        // 單 session 操作的查表與 session.tick() 呼叫(Phase 1 尚無跨 session 共享狀態需要序列化到
        // anchor region——若之後量測到 global tick 的跨 region hop 成本過高,再改成逐 instance
        // 在各自 anchor region 排 runAtFixedRate,見 MIGRATION_PLAN Phase 1 產出說明)。
        tickTaskHandle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, { _ ->
            if (acceptingNewSessions) core.tick()
        }, 20L, 20L)

        server.servicesManager.register(
            PresenceBridge::class.java,
            core,
            this,
            ServicePriority.Normal,
        )

        logger.info("[HanaToki] Phase 1 骨架已啟用")
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
