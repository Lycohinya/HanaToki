package com.tinyyana.hanatoki.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

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

    // ---- 無時限 / 死亡結算 / 局內背包(2026-08-29)--------------------------

    private fun minimal(extra: Map<String, Any?>): DungeonDefinition =
        DungeonDefinitionParser.parse("d", mapOf("world" to "w") + extra)

    @Test
    fun `session-time-limit-seconds 寫 unlimited 解析成 null`() {
        assertNull(minimal(mapOf("session-time-limit-seconds" to "unlimited")).sessionTimeLimitSeconds)
    }

    @Test
    fun `unlimited 的其他寫法都認得`() {
        for (word in listOf("none", "infinite", "endless", "-1", "UNLIMITED", " unlimited ")) {
            assertNull(
                minimal(mapOf("session-time-limit-seconds" to word)).sessionTimeLimitSeconds,
                "「$word」應該被當成無時限",
            )
        }
    }

    @Test
    fun `數字 -1 也是無時限`() {
        assertNull(minimal(mapOf("session-time-limit-seconds" to -1)).sessionTimeLimitSeconds)
    }

    @Test
    fun `正整數照舊解析成秒數`() {
        assertEquals(90L, minimal(mapOf("session-time-limit-seconds" to 90)).sessionTimeLimitSeconds)
    }

    @Test
    fun `沒寫的話維持既有預設 180 秒`() {
        assertEquals(180L, minimal(emptyMap()).sessionTimeLimitSeconds)
    }

    @Test
    fun `0 不是無時限而是設定錯誤(不讓人靠猜)`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            minimal(mapOf("session-time-limit-seconds" to 0))
        }
    }

    @Test
    fun `負數(-1 以外)是設定錯誤`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            minimal(mapOf("session-time-limit-seconds" to -30))
        }
    }

    @Test
    fun `看不懂的字串是設定錯誤,不會默默當成無時限`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            minimal(mapOf("session-time-limit-seconds" to "forever-ish"))
        }
    }

    @Test
    fun `death-resolution 預設 false,寫 true 才開`() {
        assertFalse(minimal(emptyMap()).deathResolution)
        assertTrue(minimal(mapOf("death-resolution" to true)).deathResolution)
    }

    @Test
    fun `沒寫 instance-inventory 的副本不做背包隔離`() {
        assertNull(minimal(emptyMap()).instanceInventory)
    }

    @Test
    fun `instance-inventory enabled false 等同沒開`() {
        assertNull(minimal(mapOf("instance-inventory" to mapOf("enabled" to false))).instanceInventory)
    }

    @Test
    fun `instance-inventory 的 loadout 解析出 material amount slot 與顯示名`() {
        val def = minimal(
            mapOf(
                "instance-inventory" to mapOf(
                    "enabled" to true,
                    "loadout" to listOf(
                        mapOf("material" to "STONE_SWORD", "amount" to 1, "slot" to 0, "name" to "<gray>試作刃</gray>"),
                        mapOf("material" to "BREAD", "amount" to 8),
                    ),
                ),
            ),
        )
        val inv = assertNotNull(def.instanceInventory)
        assertEquals(2, inv.loadout.size)
        assertEquals("STONE_SWORD", inv.loadout[0].material)
        assertEquals(0, inv.loadout[0].slot)
        assertEquals("<gray>試作刃</gray>", inv.loadout[0].displayName)
        assertEquals(8, inv.loadout[1].amount)
        assertNull(inv.loadout[1].slot, "沒寫 slot 就是找空位放")
        assertNull(inv.loadout[1].displayName)
    }

    @Test
    fun `空的 instance-inventory 是「進去就是空背包」而不是不開`() {
        val inv = assertNotNull(minimal(mapOf("instance-inventory" to mapOf("enabled" to true))).instanceInventory)
        assertTrue(inv.loadout.isEmpty())
    }

    @Test
    fun `loadout 缺 material 是設定錯誤`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            minimal(mapOf("instance-inventory" to mapOf("loadout" to listOf(mapOf("amount" to 1)))))
        }
    }

    @Test
    fun `loadout 的 slot 超出背包範圍是設定錯誤`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            minimal(
                mapOf("instance-inventory" to mapOf("loadout" to listOf(mapOf("material" to "BREAD", "slot" to 41)))),
            )
        }
    }

    @Test
    fun `loadout 的 amount 小於 1 是設定錯誤`() {
        assertFailsWith<DungeonDefinitionParser.DefinitionError> {
            minimal(
                mapOf("instance-inventory" to mapOf("loadout" to listOf(mapOf("material" to "BREAD", "amount" to 0)))),
            )
        }
    }
}