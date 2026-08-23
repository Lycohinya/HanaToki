package com.tinyyana.hanatoki.config

/**
 * ARCH §2「DungeonDefinition」——Phase 1 只有場地/時限/人數/重連欄位;stage 圖是 Phase 2 才有。
 */
data class DungeonDefinition(
    val id: String,
    val display: String,
    val worldName: String,
    val slotCount: Int,
    val slotSpacingBlocks: Int,
    val sessionTimeLimitSeconds: Long,
    val soloCap: Int,
    val partyCap: Int,
    val reconnectGraceSeconds: Long,
)

/**
 * 純解析邏輯:吃一個已經攤平的 `Map<String, Any?>`,不碰 Bukkit `ConfigurationSection`,
 * 讓 YAML 欄位規則可以直接單元測試(見 DungeonDefinitionParserTest)。
 * Bukkit 端的 loader 只是把 `ConfigurationSection.getKeys(false).associateWith { get(it) }`
 * 攤平後丟進來。
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

        return DungeonDefinition(
            id = id,
            display = str("display", id),
            worldName = str("world"),
            slotCount = slotCount,
            slotSpacingBlocks = int("slot-spacing-blocks", 1024),
            sessionTimeLimitSeconds = timeLimit,
            soloCap = int("solo-cap", 1),
            partyCap = int("party-cap", 4),
            reconnectGraceSeconds = long("reconnect-grace-seconds", 180),
        )
    }
}
