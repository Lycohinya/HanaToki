package com.tinyyana.hanatoki.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** rc.28:stage `tool-overrides`、tool `free-on-burning-lamp`、boss `heal.tools`、人偶 `harmless-while-frozen`、on-arrive `dialog-delay-seconds`。舊資料零影響。 */
class Rc28Test {

    private val anchor = "    on-start: { music: demo.music.lantern }"

    private fun stages(extra: String): String = ContentFixtures.FILES.getValue("stages.yml").replace(anchor, "$anchor\n$extra")

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    @Test
    fun `舊資料零影響`() {
        val spec = ContentFixtures.parse()
        assertTrue(spec.stages.all { it.toolOverrides.isEmpty() })
        assertFalse(spec.tools.getValue("night_lantern").freeOnBurningLamp)
        assertEquals(BossHealSpec(false, false, false), BossFixtures.parse().bosses.getValue("nun").heal)
        assertEquals(0.0, ContentFixtures.parse().stage("lantern_court")!!.onStart.dialogDelaySeconds)
    }

    @Test
    fun `tool-overrides 解析，沒寫的欄位是 null`() {
        val spec = ContentFixtures.parse(mapOf("stages.yml" to stages("    tool-overrides:\n      night_lantern: { charges: 8, passive-regen-seconds: 2.5, refill-on-dissolve: 2, fill-on-start: true }")))
        assertEquals(ToolOverride(8, 2.5, null, 2, true), spec.stage("lantern_court")!!.toolOverrides.getValue("night_lantern"))
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
    }

    @Test
    fun `tool-overrides 的壞資料被擋`() {
        val unknown = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("stages.yml" to stages("    tool-overrides:\n      nope: { charges: 8 }"))))
        assertTrue(unknown.any { "tool-overrides.nope" in it && "不存在" in it }, unknown.toString())
        val zone = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("stages.yml" to stages("    tool-overrides:\n      night_lantern: { zone-regen-seconds: 1 }"))))
        assertTrue(zone.any { "zone-regen-seconds" in it && "zone-regen" in it }, zone.toString())
        val empty = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("stages.yml" to stages("    tool-overrides:\n      night_lantern: { fill-on-start: true }"))))
        assertTrue(empty.any { "至少要覆寫一個欄位" in it }, empty.toString())
        assertTrue(errorsOf(mapOf("stages.yml" to stages("    tool-overrides:\n      night_lantern: { charges: 0 }"))).any { "charges" in it })
        assertTrue(errorsOf(mapOf("stages.yml" to stages("    tool-overrides:\n      night_lantern: { charges: 8, extra: 1 }"))).any { "extra" in it })
    }

    @Test
    fun `free-on-burning-lamp 與 heal tools 解析`() {
        val tools = ContentFixtures.FILES.getValue("tool.yml").replace("    charges: 3\n", "    charges: 3\n    free-on-burning-lamp: true\n")
        assertTrue(ContentFixtures.parse(mapOf("tool.yml" to tools)).tools.getValue("night_lantern").freeOnBurningLamp)
        val spec = BossFixtures.parseBoss("    wipe: { checkpoint: front_door }" to "    wipe: { checkpoint: front_door }\n    heal: { on-start: true, tools: true }")
        assertEquals(BossHealSpec(true, false, true), spec.bosses.getValue("nun").heal)
    }

    @Test
    fun `harmless-while-frozen 與 dialog-delay-seconds 解析`() {
        val enemies = BossFixtures.FILES.getValue("enemies.yml").replace("frozen-when-watched: {}", "frozen-when-watched: { harmless-while-frozen: true }")
        val spec = BossFixtures.parse(mapOf("enemies.yml" to enemies))
        assertTrue(spec.enemies.getValue("doll").frozenWhenWatched!!.harmlessWhileFrozen)
        assertFalse(BossFixtures.parse().enemies.getValue("doll").frozenWhenWatched!!.harmlessWhileFrozen)
        val arrive = ContentFixtures.parse(mapOf("stages.yml" to stages("    arrive: { marker: lantern_a, radius: 3 }\n    on-arrive: { dialog: d1, dialog-delay-seconds: 1.5 }")))
        assertEquals(1.5, arrive.stage("lantern_court")!!.onArrive.dialogDelaySeconds)
        val onStart = errorsOf(mapOf("stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace(anchor, "    on-start: { dialog-delay-seconds: 1 }")))
        assertTrue(onStart.any { "dialog-delay-seconds" in it && "未知的欄位" in it }, onStart.toString())
    }
}

/** 速度驅動移動(不經傳送，BetterModel 不會因為載體移除再加入而重送整個模型)。 */
class BossMoveModeTest {
    @Test
    fun `move-mode 解析，預設 teleport，未知值被擋`() {
        assertEquals(BossMoveMode.TELEPORT, BossFixtures.parse().bosses.getValue("nun").moveMode)
        val fast = BossFixtures.parseBoss("    wipe: { checkpoint: front_door }" to "    wipe: { checkpoint: front_door }\n    move-mode: velocity")
        assertEquals(BossMoveMode.VELOCITY, fast.bosses.getValue("nun").moveMode)
        val bad = BossFixtures.bossErrors("    wipe: { checkpoint: front_door }" to "    wipe: { checkpoint: front_door }\n    move-mode: fly")
        assertTrue(bad.any { "move-mode" in it && "未知" in it }, bad.toString())
    }

    @Test
    fun `速度驅動：兩 tick 後剛好到目標，站地上與空中都一樣`() {
        for (onGround in listOf(true, false)) {
            val v = velocityToward(0.0, 5.0, 0.0, 0.1, 5.0, -0.2, onGround)
            val friction = if (onGround) 0.546 else 0.91
            // 原版每 tick 先位移再乘摩擦：第一 tick v，第二 tick v × 摩擦
            assertEquals(0.1, v[0] * (1 + friction), 1.0E-9)
            assertEquals(-0.2, v[2] * (1 + friction), 1.0E-9)
            assertEquals(0.0, v[1], 1.0E-9)
        }
        val up = velocityToward(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false)
        assertEquals(1.0, up[1] * (1 + 0.98), 1.0E-9)
    }
}
