package com.tinyyana.hanatoki.config

import com.tinyyana.hanatoki.instance.SlotPool
import com.tinyyana.hanatoki.world.DungeonWorldProvisioner
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.logging.Logger

/**
 * 啟動/reload 時解析一次的副本定義表(ARCH §2「啟動/reload 解析一次」)。
 * Bukkit 端的薄殼:讀 `dungeons.yml`,把每個 section 攤平丟給純邏輯的
 * [DungeonDefinitionParser],再依 `slot-count`/`slot-spacing-blocks` 產生固定 anchor
 * 座標登記進 [SlotPool]——v1 用「沿 X 軸每隔 spacing 格排一個 slot」的最簡單佈局
 * (ARCH §6 方案 A「相距 ≥1024 格的場地天然各自成 region」)。
 */
class DungeonRegistry(
    private val plugin: Plugin,
    private val logger: Logger,
    private val worlds: DungeonWorldProvisioner,
) {
    val definitions = linkedMapOf<String, DungeonDefinition>()

    /**
     * 「**不能把玩家留在裡面**」的副本世界名。玩家送回主世界、重生點導向、登入撿回都查它
     * (見 `ReturnPointRegistry` / `HanaTokiCore.sendHome` / `HanaTokiListener`)。
     *
     * ⚠ 只收 [ExecutionMode.SESSION] 形態、由引擎自己建立的場地世界。
     * **常駐副本([ExecutionMode.PERSISTENT])的世界刻意不在這裡面**:那是一個有地形、有出生點、
     * 玩家可以正常登出登入的世界,語意跟這份名單剛好相反。把它放進來的話,玩家在蒼櫻裡死一次、
     * 或在裡面登出再登入,就會被「安全網」送出副本——那不是現況行為(MIGRATION_PLAN §5.0 決策 E)。
     */
    val dungeonWorldNames = linkedSetOf<String>()

    /** 常駐副本的 worldName -> dungeonId。成員資格跟著「人在不在那個世界」走時查這張表。 */
    val persistentDungeonIdByWorld = linkedMapOf<String, String>()

    /** slotId -> (interactionId -> 絕對座標)。StageContext/Listener 查這裡把事件轉譯成 interactionId。 */
    val interactionLocations = linkedMapOf<String, MutableMap<String, Location>>()

    /** slotId -> (encounterId -> 絕對座標)。encounter/ 模組 spawn 時查這裡。 */
    val encounterLocations = linkedMapOf<String, MutableMap<String, Location>>()

    fun loadAll(file: File, slotPool: SlotPool<Location>) {
        definitions.clear()
        interactionLocations.clear()
        encounterLocations.clear()
        dungeonWorldNames.clear()
        persistentDungeonIdByWorld.clear()
        slotPool.unregisterAll()
        loadFile(file, slotPool)
    }

    /**
     * 追加載入一份副本定義檔,**不清空**已載入的內容。
     *
     * 存在理由:正式副本的內容屬 integration/內容插件(ARCH §11),它的副本定義應該跟內容住在
     * 同一個 repo/同一個 dataFolder,而不是要求管理員手動把一段 YAML 貼進引擎自己的
     * `dungeons.yml`(那份是引擎的檔案,升級時會被拿來比對,混進內容定義只會讓兩邊都難維護)。
     * 內容插件在 onEnable 時把自己的定義檔交進來即可——它 `depend` HanaToki,一定比引擎晚啟動,
     * 這時候引擎的 `loadAll` 已經跑完。
     *
     * 同 id 重複載入 = 後者覆蓋前者(reload 語意,同 [SlotPool.register])。
     */
    fun loadAdditional(file: File, slotPool: SlotPool<Location>) {
        loadFile(file, slotPool)
    }

    private fun loadFile(file: File, slotPool: SlotPool<Location>) {
        if (!file.exists()) {
            logger.warning("[HanaToki] 副本定義檔不存在,略過:${file.path}")
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("dungeons") ?: run {
            logger.warning("[HanaToki] ${file.name} 沒有 dungeons: 區塊")
            return
        }
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            // ⚠ `ConfigurationSection.getValues(false)` **不會**把巢狀 section 遞迴轉成 Map——
            // 巢狀 key 的值原樣是 `MemorySection`,對 `as? Map<String, Any?>` 的 cast 會靜默失敗
            // 成 null(2026-08-23 L4 測試抓到:test-puzzle 的 stageGraph 一直是 null,entry stage
            // 從未真的執行)。改用下面手寫的 [sectionToMap] 遞迴轉換,stages/interactions/
            // encounters 這幾個巢狀區塊才能保持純 Map,parser 才能繼續脫離 Bukkit 單元測試。
            val raw = sectionToMap(section)
            val def = try {
                DungeonDefinitionParser.parse(id, raw)
            } catch (e: DungeonDefinitionParser.DefinitionError) {
                logger.warning("[HanaToki] 副本定義 $id 解析失敗,跳過:${e.message}")
                continue
            }
            definitions[id] = def

            // 專屬副本世界不存在就在這裡建起來(ARCH §6 的世界層,見 DungeonWorldProvisioner)。
            // ⚠ 這條路徑要求呼叫端已在 global region tick thread 上——loadAll/loadAdditional 的
            //   兩個入口都由 HanaTokiCore 包在 `DungeonWorldProvisioner.runOnGlobalRegion` 裡。
            val world = worlds.ensureWorld(def.worldName, def.worldCreate, def.worldAutoSave, def.worldGeneratorId)
            if (world == null) {
                logger.warning("[HanaToki] 副本 $id 指定的世界 ${def.worldName} 不存在也無法建立,slot 未登記")
                continue
            }
            when (def.mode) {
                ExecutionMode.SESSION -> if (def.worldCreate) dungeonWorldNames += world.name
                ExecutionMode.PERSISTENT -> persistentDungeonIdByWorld[world.name] = id
            }
            for (i in 0 until def.slotCount) {
                val slotId = "$id#$i"
                val anchor = Location(
                    world,
                    (def.anchorOffsetX + i * def.slotSpacingBlocks).toDouble(),
                    def.anchorY.toDouble(),
                    def.anchorOffsetZ.toDouble(),
                )
                slotPool.register(id, slotId, anchor)
                // 世界邊界只對 void 世界撐開:那是「別讓人一路往外走把空 chunk 全生出來」的
                // 安全網,對有真實地形的常駐世界套下去等於把玩家關進一個看得見的牆。
                if (def.worldCreate && def.worldGeneratorId == null) {
                    worlds.expandBorderFor(world, anchor, def.worldBorderMargin)
                }

                val interMap = linkedMapOf<String, Location>()
                for (inter in def.interactions.values) {
                    interMap[inter.id] = anchor.clone().add(inter.dx.toDouble(), inter.dy.toDouble(), inter.dz.toDouble())
                }
                interactionLocations[slotId] = interMap

                val encMap = linkedMapOf<String, Location>()
                for (enc in def.encounters.values) {
                    encMap[enc.id] = anchor.clone().add(enc.dx.toDouble(), enc.dy.toDouble(), enc.dz.toDouble())
                }
                encounterLocations[slotId] = encMap
            }
        }
        logger.info("[HanaToki] ${file.name} 載入後共 ${definitions.size} 座副本定義、${definitions.values.sumOf { it.slotCount }} 個 slot")
    }

    private fun sectionToMap(section: ConfigurationSection): Map<String, Any?> =
        section.getKeys(false).associateWith { key ->
            when (val v = section.get(key)) {
                is ConfigurationSection -> sectionToMap(v)
                else -> v
            }
        }

    /** 依世界座標反查是哪個 slot 的哪個 interaction(PlayerInteractEvent handler 用)。 */
    fun findInteraction(location: Location): Pair<String, String>? {
        for ((slotId, map) in interactionLocations) {
            for ((interId, loc) in map) {
                if (loc.world == location.world && loc.blockX == location.blockX &&
                    loc.blockY == location.blockY && loc.blockZ == location.blockZ
                ) {
                    return slotId to interId
                }
            }
        }
        return null
    }
}
