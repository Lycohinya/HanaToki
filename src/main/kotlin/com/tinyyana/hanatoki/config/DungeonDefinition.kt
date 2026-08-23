package com.tinyyana.hanatoki.config

import com.tinyyana.hanatoki.stage.StageDefinition
import com.tinyyana.hanatoki.stage.StageGraph

/** ARCH §2「Interaction」的物理綁定方式:v1 只做這兩種,夠三燈引路/開門用。 */
enum class InteractionKind { RIGHT_CLICK, PHYSICAL }

/** 相對 slot anchor 的整數偏移座標(不是絕對座標——絕對座標由 [DungeonRegistry] 依 slot 展開)。 */
data class InteractionDef(val id: String, val dx: Int, val dy: Int, val dz: Int, val kind: InteractionKind)

/** ARCH §5「Encounter」的最小 spawn 描述:一種敵人、固定數量、固定偏移中心 + 散開半徑。 */
data class EncounterDef(val id: String, val entityType: String, val count: Int, val dx: Int, val dy: Int, val dz: Int, val radius: Double)

/**
 * ARCH §2「DungeonDefinition」——Phase 2 新增 stage 圖 / interaction / encounter / UI 資料欄位
 * (§7「UI 只提供資料能力」)。`stageGraph == null` = Phase 1 舊行為(空副本,進去、計時、超時送回,
 * 沒有 stage/interaction 系統介入),保持向後相容(現有 `test-empty` 定義不用改)。
 */
data class DungeonDefinition(
    val id: String,
    val display: String,
    val worldName: String,
    val slotCount: Int,
    val slotSpacingBlocks: Int,
    /**
     * slot anchor 的基準座標偏移(沿 X/Z 軸)。多座副本共用同一個 world 時(v1 test 副本的
     * 常態,見 `dungeons.yml`),各自的 slot 都是從 `(0,64,0)` 沿 X 軸展開——不給偏移的話
     * 兩座副本的 slot#0 會落在同一個座標,直接互相污染。預設 0 保留 Phase 1 `test-empty`
     * 的既有行為(當時只有一座副本,不需要偏移)。
     */
    val anchorOffsetX: Int = 0,
    val anchorOffsetZ: Int = 0,
    val sessionTimeLimitSeconds: Long,
    val soloCap: Int,
    val partyCap: Int,
    val reconnectGraceSeconds: Long,
    val tags: List<String> = emptyList(),
    val expectedMinutes: String = "",
    val description: String = "",
    val stageGraph: StageGraph? = null,
    val interactions: Map<String, InteractionDef> = emptyMap(),
    val encounters: Map<String, EncounterDef> = emptyMap(),
)

/**
 * 純解析邏輯:吃一個已經攤平的 `Map<String, Any?>`,不碰 Bukkit `ConfigurationSection`,
 * 讓 YAML 欄位規則可以直接單元測試(見 DungeonDefinitionParserTest)。
 * Bukkit 端的 loader 用 `ConfigurationSection.getValues(false)`(遞迴把巢狀 section 轉成
 * `Map<String, Any?>`)產生這裡吃的輸入,不是逐 key 手動攤平——巢狀的 `stages`/`interactions`/
 * `encounters` 才能保持純 Map,這裡才能繼續脫離 Bukkit 單元測試。
 */
object DungeonDefinitionParser {
    class DefinitionError(message: String) : IllegalArgumentException(message)

    fun parse(id: String, raw: Map<String, Any?>): DungeonDefinition {
        fun str(key: String, default: String? = null): String =
            (raw[key] as? String) ?: default ?: throw DefinitionError("dungeons.$id.$key 缺少必要欄位")

        fun int(key: String, default: Int): Int =
            (raw[key] as? Number)?.toInt() ?: default

        fun long(key: String, default: Long): Long =
            (raw[key] as? Number)?.toLong() ?: default

        val slotCount = int("slot-count", 1)
        if (slotCount < 1) throw DefinitionError("dungeons.$id.slot-count 必須 >= 1")

        val timeLimit = long("session-time-limit-seconds", 180)
        if (timeLimit <= 0) throw DefinitionError("dungeons.$id.session-time-limit-seconds 必須 > 0")

        @Suppress("UNCHECKED_CAST")
        val tags = (raw["tags"] as? List<*>)?.map { it.toString() } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val stagesRaw = raw["stages"] as? Map<String, Any?>
        val stageGraph = stagesRaw?.let { parseStageGraph(id, it) }

        @Suppress("UNCHECKED_CAST")
        val interactionsRaw = raw["interactions"] as? Map<String, Any?> ?: emptyMap()
        val interactions = interactionsRaw.entries.associate { (interId, v) ->
            @Suppress("UNCHECKED_CAST")
            val m = v as? Map<String, Any?> ?: throw DefinitionError("dungeons.$id.interactions.$interId 格式錯誤")
            interId to parseInteraction(id, interId, m)
        }

        @Suppress("UNCHECKED_CAST")
        val encountersRaw = raw["encounters"] as? Map<String, Any?> ?: emptyMap()
        val encounters = encountersRaw.entries.associate { (encId, v) ->
            @Suppress("UNCHECKED_CAST")
            val m = v as? Map<String, Any?> ?: throw DefinitionError("dungeons.$id.encounters.$encId 格式錯誤")
            encId to parseEncounter(id, encId, m)
        }

        return DungeonDefinition(
            id = id,
            display = str("display", id),
            worldName = str("world"),
            slotCount = slotCount,
            slotSpacingBlocks = int("slot-spacing-blocks", 1024),
            anchorOffsetX = int("anchor-offset-x", 0),
            anchorOffsetZ = int("anchor-offset-z", 0),
            sessionTimeLimitSeconds = timeLimit,
            soloCap = int("solo-cap", 1),
            partyCap = int("party-cap", 4),
            reconnectGraceSeconds = long("reconnect-grace-seconds", 180),
            tags = tags,
            expectedMinutes = (raw["expected-minutes"] as? String) ?: "",
            description = (raw["description"] as? String) ?: "",
            stageGraph = stageGraph,
            interactions = interactions,
            encounters = encounters,
        )
    }

    private fun parseStageGraph(dungeonId: String, raw: Map<String, Any?>): StageGraph {
        val start = raw["start"] as? String
            ?: throw DefinitionError("dungeons.$dungeonId.stages.start 缺少必要欄位")
        @Suppress("UNCHECKED_CAST")
        val listRaw = raw["list"] as? Map<String, Any?>
            ?: throw DefinitionError("dungeons.$dungeonId.stages.list 缺少必要欄位")
        val stages = listRaw.entries.associate { (stageId, v) ->
            @Suppress("UNCHECKED_CAST")
            val m = (v as? Map<String, Any?>) ?: emptyMap()
            stageId to StageDefinition(
                id = stageId,
                timeoutSeconds = (m["timeout-seconds"] as? Number)?.toLong(),
                timeoutTransition = m["timeout-transition"] as? String,
            )
        }
        if (start !in stages) throw DefinitionError("dungeons.$dungeonId.stages.start=$start 不在 list 裡")
        return StageGraph(start, stages)
    }

    private fun parseInteraction(dungeonId: String, interactionId: String, m: Map<String, Any?>): InteractionDef {
        fun i(key: String): Int = (m[key] as? Number)?.toInt() ?: 0
        val kindStr = (m["kind"] as? String) ?: "right-click"
        val kind = when (kindStr) {
            "right-click" -> InteractionKind.RIGHT_CLICK
            "physical" -> InteractionKind.PHYSICAL
            else -> throw DefinitionError("dungeons.$dungeonId.interactions.$interactionId.kind=$kindStr 不是已知類型")
        }
        return InteractionDef(interactionId, i("x"), i("y"), i("z"), kind)
    }

    private fun parseEncounter(dungeonId: String, encounterId: String, m: Map<String, Any?>): EncounterDef {
        fun i(key: String): Int = (m[key] as? Number)?.toInt() ?: 0
        val entity = (m["entity"] as? String)
            ?: throw DefinitionError("dungeons.$dungeonId.encounters.$encounterId.entity 缺少必要欄位")
        val count = (m["count"] as? Number)?.toInt() ?: 1
        val radius = (m["radius"] as? Number)?.toDouble() ?: 2.0
        return EncounterDef(encounterId, entity, count, i("x"), i("y"), i("z"), radius)
    }
}
