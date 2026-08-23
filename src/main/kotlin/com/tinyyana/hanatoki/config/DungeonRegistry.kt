package com.tinyyana.hanatoki.config

import com.tinyyana.hanatoki.instance.SlotPool
import org.bukkit.Location
import org.bukkit.World
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
class DungeonRegistry(private val plugin: Plugin, private val logger: Logger) {
    val definitions = linkedMapOf<String, DungeonDefinition>()

    /** slotId -> (interactionId -> 絕對座標)。StageContext/Listener 查這裡把事件轉譯成 interactionId。 */
    val interactionLocations = linkedMapOf<String, MutableMap<String, Location>>()

    /** slotId -> (encounterId -> 絕對座標)。encounter/ 模組 spawn 時查這裡。 */
    val encounterLocations = linkedMapOf<String, MutableMap<String, Location>>()

    fun loadAll(file: File, slotPool: SlotPool<Location>, resolveWorld: (String) -> World?) {
        definitions.clear()
        interactionLocations.clear()
        encounterLocations.clear()
        slotPool.unregisterAll()
        if (!file.exists()) {
            logger.warning("[HanaToki] dungeons.yml 不存在,沒有任何副本定義被載入:${file.path}")
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("dungeons") ?: run {
            logger.warning("[HanaToki] dungeons.yml 沒有 dungeons: 區塊")
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

            val world = resolveWorld(def.worldName)
            if (world == null) {
                logger.warning("[HanaToki] 副本 $id 指定的世界 ${def.worldName} 不存在,slot 未登記")
                continue
            }
            for (i in 0 until def.slotCount) {
                val slotId = "$id#$i"
                val anchor = Location(
                    world,
                    (def.anchorOffsetX + i * def.slotSpacingBlocks).toDouble(),
                    64.0,
                    def.anchorOffsetZ.toDouble(),
                )
                slotPool.register(id, slotId, anchor)

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
        logger.info("[HanaToki] 載入 ${definitions.size} 座副本定義,共 ${definitions.values.sumOf { it.slotCount }} 個 slot")
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
