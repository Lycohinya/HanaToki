package com.tinyyana.hanatoki.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.31:小怪固定比例傷害 `damage-percent`、人偶靠近加速 `rush`、霧鬼吹燈 `chases-players.snuff-lamps`。舊資料零影響。 */
class Rc31Test {

    private val ghost = """

          ghost:
            base-entity: zombie
            rules: [needs-flash, chases-players]
            health: 6
            needs-flash: { whiff-cue: whiff, stun-cue: stun, dissolve-cue: dissolve }
            chases-players:
              contact:
                contact-radius: 1.6
                effects:
                  - { type: damage-percent, percent: 0.05 }
              snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }
    """.trimEnd()

    private fun withGhost(replace: Pair<String, String>? = null): Map<String, String?> {
        val base = BossFixtures.FILES.getValue("enemies.yml") + "\n" + ghost.trimIndent().prependIndent("  ")
        val text = if (replace == null) base else base.replace(replace.first, replace.second).also { check(it != base) { "測試字串沒有命中：${replace.first}" } }
        return mapOf("enemies.yml" to text)
    }

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { BossFixtures.parse(overrides) }.errors

    @Test
    fun `舊資料零影響`() {
        val spec = BossFixtures.parse()
        assertNull(spec.enemies.getValue("doll").frozenWhenWatched!!.rush)
        assertNull(spec.enemies.values.firstNotNullOfOrNull { it.chasesPlayers?.snuffLamps })
    }

    @Test
    fun `damage-percent 與 snuff-lamps 解析，預設值`() {
        val spec = BossFixtures.parse(withGhost())
        val chase = assertNotNull(spec.enemies.getValue("ghost").chasesPlayers)
        assertEquals(listOf<HarassEffect>(HarassEffect.DamagePercent(0.05, 4.0)), chase.contact!!.effects)
        val s = assertNotNull(chase.snuffLamps)
        assertEquals(SnuffLampsSpec(listOf("boss_lamp_1", "boss_lamp_2"), setOf("burning", "lit"), 1.0, 6.0, 2.0, 0.8, 10.0, null, emptyList()), s)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
    }

    @Test
    fun `snuff-lamps 自訂欄位`() {
        val spec = BossFixtures.parse(
            withGhost("snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }" to
                "snuff-lamps: { objects: [boss_lamp_3], states: [lit], preference: 0.6, decide-seconds: 4, reach-radius: 1.5, dwell-seconds: 0.5, cooldown-seconds: 12, speed: 1.4, cues: [snuff_out] }"),
        )
        assertEquals(
            SnuffLampsSpec(listOf("boss_lamp_3"), setOf("lit"), 0.6, 4.0, 1.5, 0.5, 12.0, 1.4, listOf("snuff_out")),
            spec.enemies.getValue("ghost").chasesPlayers!!.snuffLamps,
        )
    }

    @Test
    fun `damage-percent 壞資料被擋`() {
        assertTrue(errorsOf(withGhost("percent: 0.05" to "percent: 0")).any { "percent" in it }, "percent 0")
        assertTrue(errorsOf(withGhost("percent: 0.05" to "percent: 1.5")).any { "percent" in it }, "percent 1.5")
        assertTrue(errorsOf(withGhost("percent: 0.05" to "amount: 1")).any { "amount" in it || "percent" in it })
    }

    @Test
    fun `snuff-lamps 壞資料被擋`() {
        assertTrue(errorsOf(withGhost("objects: [boss_lamp_1, boss_lamp_2]" to "objects: []")).any { "objects" in it })
        assertTrue(errorsOf(withGhost("snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }" to "snuff-lamps: { objects: [boss_lamp_1], states: [unlit] }")).any { "states" in it })
        assertTrue(errorsOf(withGhost("snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }" to "snuff-lamps: { objects: [boss_lamp_1], preference: 1.5 }")).any { "preference" in it })
        assertTrue(errorsOf(withGhost("snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }" to "snuff-lamps: { objects: [boss_lamp_1], extra: 1 }")).any { "extra" in it })
        val missing = ContentValidator.validateReferences(BossFixtures.parse(withGhost("boss_lamp_2]" to "no_such_lamp]")))
        assertTrue(missing.any { "snuff-lamps.objects" in it && "no_such_lamp" in it }, missing.toString())
        val notLantern = ContentValidator.validateReferences(BossFixtures.parse(withGhost("boss_lamp_2]" to "pumpkin_boss_1]")))
        assertTrue(notLantern.any { "snuff-lamps.objects" in it && "不是 lantern" in it }, notLantern.toString())
    }

    private val retreatBlock = "snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }\n      retreat: { seconds: 2.5, distance: 6, speed: 1.1 }\n      pressure: { distance: 8, cues: [chase_growl], far-interval-ticks: 44, near-interval-ticks: 20 }"

    @Test
    fun `retreat 與 pressure 解析，預設值`() {
        val spec = BossFixtures.parse(withGhost("snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }" to retreatBlock))
        val chase = spec.enemies.getValue("ghost").chasesPlayers!!
        assertEquals(RetreatSpec(2.5, 6.0, 1.1), chase.retreat)
        assertEquals(PressureSpec(8.0, listOf("chase_growl"), 44, 20), chase.pressure)
        val defaults = BossFixtures.parse(withGhost("snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }" to "retreat: { seconds: 2 }\n      pressure: { distance: 8, cues: [x] }"))
        val d = defaults.enemies.getValue("ghost").chasesPlayers!!
        assertEquals(RetreatSpec(2.0, 6.0, null), d.retreat)
        assertEquals(PressureSpec(8.0, listOf("x"), 40, 16), d.pressure)
        assertNull(BossFixtures.parse(withGhost()).enemies.getValue("ghost").chasesPlayers!!.retreat)
    }

    @Test
    fun `retreat 與 pressure 壞資料被擋`() {
        fun bad(block: String) = errorsOf(withGhost("snuff-lamps: { objects: [boss_lamp_1, boss_lamp_2] }" to block))
        assertTrue(bad("retreat: { distance: 6 }").any { "seconds" in it })
        assertTrue(bad("retreat: { seconds: 0 }").any { "seconds" in it })
        assertTrue(bad("pressure: { distance: 8, cues: [] }").any { "cues" in it })
        assertTrue(bad("pressure: { cues: [x] }").any { "distance" in it })
        assertTrue(bad("pressure: { distance: 8, cues: [x], far-interval-ticks: 10, near-interval-ticks: 20 }").any { "near-interval-ticks" in it })
        assertTrue(bad("pressure: { distance: 8, cues: [x], zzz: 1 }").any { "zzz" in it })
        // 沒有接觸效果的追擊者不能寫 retreat
        val noContact = BossFixtures.FILES.getValue("enemies.yml") + "\n" +
            "  plain:\n    base-entity: zombie\n    rules: [chases-players]\n    health: 6\n    chases-players:\n      retreat: { seconds: 2 }"
        assertTrue(errorsOf(mapOf("enemies.yml" to noContact)).any { "retreat" in it || "seconds" in it })
    }

    @Test
    fun `PressureSpec 間隔隨距離線性縮短`() {
        val p = PressureSpec(8.0, listOf("x"), 44, 20)
        assertEquals(44, p.intervalAt(8.0))
        assertEquals(44, p.intervalAt(20.0))
        assertEquals(20, p.intervalAt(0.0))
        assertEquals(32, p.intervalAt(4.0))
        assertTrue(p.intervalAt(2.0) < p.intervalAt(6.0))
    }

    @Test
    fun `rush 解析與壞資料`() {
        val enemies = BossFixtures.FILES.getValue("enemies.yml")
        val spec = BossFixtures.parse(
            mapOf("enemies.yml" to enemies.replace("frozen-when-watched: {}", "frozen-when-watched: { rush: { distance: 8, speed-multiplier: 1.35, cues: [doll_click], cue-interval-ticks: 6 } }")),
        )
        assertEquals(RushSpec(8.0, 1.35, listOf("doll_click"), 6), spec.enemies.getValue("doll").frozenWhenWatched!!.rush)
        val defaults = BossFixtures.parse(mapOf("enemies.yml" to enemies.replace("frozen-when-watched: {}", "frozen-when-watched: { rush: { distance: 8, speed-multiplier: 1.5 } }")))
        assertEquals(RushSpec(8.0, 1.5, emptyList(), 8), defaults.enemies.getValue("doll").frozenWhenWatched!!.rush)
        assertTrue(errorsOf(mapOf("enemies.yml" to enemies.replace("frozen-when-watched: {}", "frozen-when-watched: { rush: { distance: 8, speed-multiplier: 0.5 } }"))).any { "speed-multiplier" in it })
        assertTrue(errorsOf(mapOf("enemies.yml" to enemies.replace("frozen-when-watched: {}", "frozen-when-watched: { rush: { speed-multiplier: 1.5 } }"))).any { "distance" in it })
        assertTrue(errorsOf(mapOf("enemies.yml" to enemies.replace("frozen-when-watched: {}", "frozen-when-watched: { rush: { distance: 8, speed-multiplier: 1.5, x: 1 } }"))).any { "x" in it })
    }

    @Test
    fun `pickSnuffLamp 偏好與鎖定`() {
        val near = SnuffCandidate("a", 3.0)
        val far = SnuffCandidate("b", 9.0)
        assertNull(pickSnuffLamp(emptyList(), null, 1.0, 0.0))
        assertEquals("a", pickSnuffLamp(listOf(far, near), null, 1.0, 0.99))
        assertEquals("a", pickSnuffLamp(listOf(far, near), null, 0.6, 0.59))
        assertNull(pickSnuffLamp(listOf(far, near), null, 0.6, 0.6))
        assertNull(pickSnuffLamp(listOf(far, near), null, 0.0, 0.0))
        // 已鎖定且仍合格:不重擲、不換最近的
        assertEquals("b", pickSnuffLamp(listOf(far, near), "b", 0.0, 0.99))
        // 鎖定的燈不合格了:重新擲
        assertEquals("a", pickSnuffLamp(listOf(near), "b", 1.0, 0.5))
        // 距離相同取 id 小的，結果穩定
        assertEquals("a", pickSnuffLamp(listOf(SnuffCandidate("b", 4.0), SnuffCandidate("a", 4.0)), null, 1.0, 0.0))
    }

    @Test
    fun `rushSpeed 只在距離內加速並有上限`() {
        val rush = RushSpec(8.0, 1.5, emptyList(), 8)
        assertEquals(1.0, rushSpeed(1.0, null, 2.0))
        assertEquals(1.0, rushSpeed(1.0, rush, null))
        assertEquals(1.5, rushSpeed(1.0, rush, 8.0))
        assertEquals(1.0, rushSpeed(1.0, rush, 8.01))
        assertEquals(RUSH_SPEED_CAP, rushSpeed(3.0, RushSpec(8.0, 3.0, emptyList(), 8), 1.0))
    }
}
