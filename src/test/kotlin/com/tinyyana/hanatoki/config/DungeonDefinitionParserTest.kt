package com.tinyyana.hanatoki.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DungeonDefinitionParserTest {
    @Test
    fun `完整欄位解析正確`() {
        val def = DungeonDefinitionParser.parse(
            "test-empty",
            mapOf(
                "display" to "測試用空副本",
                "world" to "world",
                "slot-count" to 2,
                "slot-spacing-blocks" to 1024,
                "session-time-limit-seconds" to 60,
                "solo-cap" to 1,
                "party-cap" to 4,
                "reconnect-grace-seconds" to 30,
            ),
        )
        assertEquals("test-empty", def.id)
        assertEquals("測試用空副本", def.display)
        assertEquals("world", def.worldName)
        assertEquals(2, def.slotCount)
        assertEquals(1024, def.slotSpacingBlocks)
        assertEquals(60L, def.sessionTimeLimitSeconds)
        assertEquals(1, def.soloCap)
        assertEquals(4, def.partyCap)
        assertEquals(30L, def.reconnectGraceSeconds)
    }

    @Test
    fun `缺少 world 欄位丟出 DefinitionError`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            DungeonDefinitionParser.parse("bad", mapOf("display" to "x"))
        }
    }

    @Test
    fun `slot-count 小於 1 丟出 DefinitionError`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            DungeonDefinitionParser.parse("bad", mapOf("world" to "world", "slot-count" to 0))
        }
    }

    @Test
    fun `session-time-limit-seconds 小於等於 0 丟出 DefinitionError`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            DungeonDefinitionParser.parse(
                "bad",
                mapOf("world" to "world", "session-time-limit-seconds" to 0),
            )
        }
    }

    @Test
    fun `stage 圖與 interaction encounter 解析正確`() {
        val def = DungeonDefinitionParser.parse(
            "test-puzzle",
            mapOf(
                "world" to "world",
                "stages" to mapOf(
                    "start" to "entry",
                    "list" to mapOf(
                        "entry" to emptyMap<String, Any?>(),
                        "puzzle" to mapOf("timeout-seconds" to 120),
                    ),
                ),
                "interactions" to mapOf(
                    "lamp_a" to mapOf("x" to -2, "y" to 0, "z" to 0, "kind" to "right-click"),
                    "goal-plate" to mapOf("x" to 0, "y" to 0, "z" to 5, "kind" to "physical"),
                ),
                "encounters" to mapOf(
                    "ambush-wave" to mapOf("entity" to "ZOMBIE", "count" to 2, "x" to 0, "y" to 0, "z" to 0, "radius" to 2.0),
                ),
            ),
        )
        assertEquals("entry", def.stageGraph?.startStage)
        assertEquals(120L, def.stageGraph?.stage("puzzle")?.timeoutSeconds)
        assertEquals(InteractionKind.RIGHT_CLICK, def.interactions["lamp_a"]?.kind)
        assertEquals(InteractionKind.PHYSICAL, def.interactions["goal-plate"]?.kind)
        assertEquals(-2, def.interactions["lamp_a"]?.dx)
        assertEquals("ZOMBIE", def.encounters["ambush-wave"]?.entityType)
        assertEquals(2, def.encounters["ambush-wave"]?.count)
    }

    @Test
    fun `stage start 不在 list 裡丟出 DefinitionError`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            DungeonDefinitionParser.parse(
                "bad",
                mapOf(
                    "world" to "world",
                    "stages" to mapOf("start" to "missing", "list" to mapOf("entry" to emptyMap<String, Any?>())),
                ),
            )
        }
    }

    @Test
    fun `沒給 stages 時 stageGraph 為 null(向後相容 test-empty)`() {
        val def = DungeonDefinitionParser.parse("test-empty", mapOf("world" to "world"))
        assertEquals(null, def.stageGraph)
    }

    @Test
    fun `沒給的欄位吃預設值`() {
        val def = DungeonDefinitionParser.parse("d", mapOf("world" to "world"))
        assertEquals("d", def.display) // 沒給 display 時退回用 id
        assertEquals(1, def.slotCount)
        assertEquals(1024, def.slotSpacingBlocks)
        assertEquals(180L, def.sessionTimeLimitSeconds)
        assertEquals(1, def.soloCap)
        assertEquals(4, def.partyCap)
        assertEquals(180L, def.reconnectGraceSeconds)
        // 新欄位的預設值必須是「既有行為」——session 形態、void 生成器、anchor 在 y=64、
        // 進場落點就是 anchor 本身。任何一個預設值變了都會靜靜地改掉刀塚的行為。
        assertEquals(ExecutionMode.SESSION, def.mode)
        assertEquals(null, def.worldGeneratorId)
        assertEquals(64, def.anchorY)
        assertEquals(0.0, def.spawnOffsetX)
        assertEquals(0.0, def.spawnOffsetY)
        assertEquals(0.0, def.spawnOffsetZ)
        assertEquals(0f, def.spawnYaw)
    }

    @Test
    fun `常駐形態欄位解析正確`() {
        val def = DungeonDefinitionParser.parse(
            "pale-cherry",
            mapOf(
                "world" to "lyco_pale_cherry",
                "mode" to "persistent",
                "world-generator" to "lycohinya:pale-cherry",
                "anchor-y" to 73,
                "spawn-offset-x" to 0.5,
                "spawn-offset-z" to 21.5,
                "spawn-yaw" to 180,
            ),
        )
        assertEquals(ExecutionMode.PERSISTENT, def.mode)
        assertEquals("lycohinya:pale-cherry", def.worldGeneratorId)
        assertEquals(73, def.anchorY)
        assertEquals(0.5, def.spawnOffsetX)
        assertEquals(21.5, def.spawnOffsetZ)
        assertEquals(180f, def.spawnYaw)
    }

    @Test
    fun `不認得的 mode 丟出 DefinitionError`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            DungeonDefinitionParser.parse("bad", mapOf("world" to "world", "mode" to "shared"))
        }
    }

    @Test
    fun `常駐形態的 slot-count 必須是 1`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            DungeonDefinitionParser.parse("bad", mapOf("world" to "world", "mode" to "persistent", "slot-count" to 2))
        }
    }

    @Test
    fun `test-only 預設 false,正式副本不會被上線閘門誤擋`() {
        val def = DungeonDefinitionParser.parse("real", mapOf("world" to "w"))
        assertEquals(false, def.testOnly)
    }

    @Test
    fun `test-only 讀得出來`() {
        // 這是上線閘門的第一半:標了的定義由 DungeonRegistry 在 enable-test-dungeons: false 時
        // 整條跳過(世界不建、slot 不登記、指令查不到)。
        val def = DungeonDefinitionParser.parse("probe", mapOf("world" to "w", "test-only" to true))
        assertEquals(true, def.testOnly)
    }
}
