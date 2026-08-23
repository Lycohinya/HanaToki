package com.tinyyana.hanatoki.config

import com.tinyyana.hanatoki.instance.SlotPool
import org.bukkit.Location
import org.bukkit.World
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

    fun loadAll(file: File, slotPool: SlotPool<Location>, resolveWorld: (String) -> World?) {
        definitions.clear()
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
            val raw = section.getKeys(false).associateWith { section.get(it) }
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
                val anchor = Location(world, (i * def.slotSpacingBlocks).toDouble(), 64.0, 0.0)
                slotPool.register(id, slotId, anchor)
            }
        }
        logger.info("[HanaToki] 載入 ${definitions.size} 座副本定義,共 ${definitions.values.sumOf { it.slotCount }} 個 slot")
    }
}
