package com.tinyyana.hanatoki.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.35:燈籠範圍干擾 `interference`、objective 條件 `lantern-interfered`、boss 定點 `home`、seeks-object `drain-per-second: 0`。舊資料零影響。 */
class Rc35Test {

    private val lanternBase = ContentFixtures.FILES.getValue("objects.yml")

    /** 在 lantern_a 底下加一段 interference(縮排 8 格接在 on-lit 之後)。 */
    private fun objectsWith(interference: String): String {
        val anchor = "    on-lit: { cues: [lantern_burst], light-radius: 12, clears-enemies: true, opens: gate_corridor }"
        check(anchor in lanternBase) { "fixture 變了" }
        return lanternBase.replace(anchor, anchor + "\n" + interference.trimIndent().prependIndent("    "))
    }

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    @Test
    fun `舊資料零影響`() {
        val spec = ContentFixtures.parse()
        assertNull((spec.objects.getValue("lantern_a") as LanternSpec).interference)
    }

    @Test
    fun `interference 解析，預設值`() {
        val spec = ContentFixtures.parse(
            mapOf(
                "objects.yml" to objectsWith(
                    """
                    interference: { enemies: [small_ghost], radius: 6, mode: rewind, rate-per-second: 0.5, cues: [lantern_gnawed] }
                    """,
                ),
            ),
        )
        val itf = assertNotNull((spec.objects.getValue("lantern_a") as LanternSpec).interference)
        assertEquals(LanternInterference(setOf("small_ghost"), 6.0, InterferenceMode.REWIND, 0.5, 4, listOf("lantern_gnawed"), 1.5), itf)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
    }

    @Test
    fun `pause 模式不倒退，rewind 一定要速率，未知模式與不存在的敵人被擋`() {
        val pause = ContentFixtures.parse(mapOf("objects.yml" to objectsWith("interference: { radius: 5, mode: pause, max-enemies: 2 }")))
        val p = assertNotNull((pause.objects.getValue("lantern_a") as LanternSpec).interference)
        assertEquals(InterferenceMode.PAUSE, p.mode)
        assertEquals(0.0, p.ratePerSecond)
        assertTrue(p.enemies.isEmpty(), "沒寫 enemies = 不限種類")
        assertTrue(errorsOf(mapOf("objects.yml" to objectsWith("interference: { radius: 5, mode: rewind }"))).any { "rate-per-second" in it })
        assertTrue(errorsOf(mapOf("objects.yml" to objectsWith("interference: { radius: 5, mode: pause, rate-per-second: 1 }"))).any { "rate-per-second" in it })
        assertTrue(errorsOf(mapOf("objects.yml" to objectsWith("interference: { radius: 5, mode: spin }"))).any { "mode" in it })
        val spec = ContentFixtures.parse(mapOf("objects.yml" to objectsWith("interference: { enemies: [nobody], radius: 5, mode: pause }")))
        assertTrue(ContentValidator.validateReferences(spec).any { "nobody" in it })
    }

    @Test
    fun `同一隻敵人對同一盞燈不能既啃又算干擾`() {
        val seeker = ContentFixtures.FILES.getValue("enemies.yml") + "\n" + """
              gnawer:
                base-entity: zombie
                rules: [needs-flash, seeks-object]
                health: 6
                needs-flash: { whiff-cue: whiff, stun-cue: ghost_stun, dissolve-cue: ghost_dissolve }
                seeks-object: { object: lantern_a, contest-radius: 2.5, drain-per-second: 0.5 }
        """.trimIndent().prependIndent("  ")
        val objects = objectsWith("interference: { enemies: [gnawer], radius: 6, mode: rewind, rate-per-second: 0.5 }")
        val dup = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("enemies.yml" to seeker, "objects.yml" to objects)))
        assertTrue(dup.any { "gnawer" in it && "重複" in it }, dup.toString())
        // drain-per-second: 0 = 只移動不啃，改由 interference 計，才合法
        val ok = ContentFixtures.parse(mapOf("enemies.yml" to seeker.replace("drain-per-second: 0.5", "drain-per-second: 0"), "objects.yml" to objects))
        assertEquals(emptyList(), ContentValidator.validateReferences(ok))
        assertEquals(0.0, ok.enemies.getValue("gnawer").seeksObject!!.drainPerSecond)
    }

    @Test
    fun `lantern-interfered 只能放在 objective，而且那一段要有 interference 的燈`() {
        val withStep = ContentFixtures.FILES.getValue("stages.yml").replace(
            "  lantern_court:\n",
            "  lantern_court:\n    objective:\n      - { text: demo.goal.interfered, while: { type: lantern-interfered } }\n      - { text: demo.goal.hold }\n",
        )
        check(withStep != ContentFixtures.FILES.getValue("stages.yml"))
        val noInterference = ContentFixtures.parse(mapOf("stages.yml" to withStep))
        assertEquals(Condition.LanternInterfered, noInterference.stage("lantern_court")!!.objective.first().during)
        assertTrue(Condition.LanternInterfered.isTransient)
        assertTrue(ContentValidator.validateReferences(noInterference).any { "lantern-interfered" in it })
        val ok = ContentFixtures.parse(mapOf("stages.yml" to withStep, "objects.yml" to objectsWith("interference: { radius: 6, mode: pause }")))
        assertEquals(emptyList(), ContentValidator.validateReferences(ok))
        val asComplete = ContentFixtures.FILES.getValue("stages.yml").replace(
            "      - { type: object-state, object: lantern_a, state: lit }",
            "      - { type: lantern-interfered }",
        )
        assertTrue(errorsOf(mapOf("stages.yml" to asComplete)).any { "lantern-interfered" in it })
    }

    // ---- boss home ----

    @Test
    fun `home 解析：預設速度、容差與等待，points 可以不寫`() {
        val noPoints = BossFixtures.NUN.replace("points: [nun_point_a, nun_point_b, nun_point_c]", "home: { marker: nun_point_a, offset: [0, 0, 10] }")
        check(noPoints != BossFixtures.NUN)
        val spec = BossFixtures.parse(mapOf("bosses.yml" to noPoints))
        val nun = spec.bosses.getValue("nun")
        assertEquals(BossHomeSpec("nun_point_a", listOf(0.0, 0.0, 10.0), 7.0, 1.5, 4.0), nun.home)
        assertTrue(nun.points.isEmpty())
        assertEquals(emptyList(), ContentValidator.validateScene(spec, BossFixtures.markers()))
        assertNull(BossFixtures.parse().bosses.getValue("nun").home, "舊資料沒有 home")
    }

    @Test
    fun `home 自訂欄位與錯誤`() {
        val custom = BossFixtures.NUN.replace(
            "points: [nun_point_a, nun_point_b, nun_point_c]",
            "points: [nun_point_a]\n    home: { marker: nun_point_b, return-speed: 9, tolerance: 2, max-wait-seconds: 6 }",
        )
        val nun = BossFixtures.parse(mapOf("bosses.yml" to custom)).bosses.getValue("nun")
        assertEquals(BossHomeSpec("nun_point_b", listOf(0.0, 0.0, 0.0), 9.0, 2.0, 6.0), nun.home)
        for (bad in listOf("offset: [1, 2]", "return-speed: 0", "tolerance: 99", "max-wait-seconds: -1")) {
            val text = BossFixtures.NUN.replace("points: [nun_point_a, nun_point_b, nun_point_c]", "home: { marker: nun_point_a, $bad }")
            assertTrue(assertFailsWith<ContentDataException> { BossFixtures.parse(mapOf("bosses.yml" to text)) }.errors.isNotEmpty(), bad)
        }
        val noMarker = BossFixtures.NUN.replace("points: [nun_point_a, nun_point_b, nun_point_c]", "home: { marker: nowhere }")
        val spec = BossFixtures.parse(mapOf("bosses.yml" to noMarker))
        assertTrue(ContentValidator.validateScene(spec, BossFixtures.markers()).any { "home.marker" in it })
        // 沒有 home 也沒有 points 還是錯
        val none = BossFixtures.NUN.replace("points: [nun_point_a, nun_point_b, nun_point_c]", "")
        assertTrue(assertFailsWith<ContentDataException> { BossFixtures.parse(mapOf("bosses.yml" to none)) }.errors.any { "points" in it })
    }
}
