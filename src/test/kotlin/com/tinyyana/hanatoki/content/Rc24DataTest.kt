package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.map.MapAssetId
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MarkerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * rc.24 的資料契約:stage `on-complete.opens`、objective `while` 與狀態型條件(powered、boss-kneel)、
 * boss `intro`／`hit-cues`、倒下提示 `self-hint-cues`／`ally-hint-cues`／`ally-hud-text`、光環迴旋取樣欄位。
 * 舊資料零影響:沒寫這些欄位的內容解析結果與 rc.23 相同。
 */
class Rc24DataTest {

    // ---- stage on-complete.opens ----

    private fun stages(onComplete: String, objective: String = ""): String =
        ContentFixtures.FILES.getValue("stages.yml")
            .replace("    next: lantern_court", "    next: lantern_court\n    on-complete: $onComplete\n$objective")

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    @Test
    fun `舊資料零影響，沒寫 opens、while 的內容照舊`() {
        val spec = ContentFixtures.parse()
        assertTrue(spec.stages.all { it.onComplete.opens.isEmpty() })
        val boss = BossFixtures.parse().bosses.getValue("nun")
        assertTrue(boss.hitCues.isEmpty())
        assertNull(boss.intro)
        assertTrue(boss.downed.selfHintCues.isEmpty() && boss.downed.allyHintCues.isEmpty())
        assertNull(boss.downed.allyHudTextKey)
        val spin = boss.moves.getValue("spin").shape as MoveShape.Spin
        assertTrue(spin.beamCues.isEmpty() && spin.gapCues.isEmpty())
        assertEquals(4, spin.sampleTicks)
        assertEquals(3, spin.beams)
        assertEquals(6, spin.pointsPerLine)
        assertEquals(false, spin.hasVisual)
        assertEquals(0, spin.cuesPerSample)
    }

    @Test
    fun `on-complete opens 解析成 gate 物件清單，跨檔參照核對得過`() {
        val spec = ContentFixtures.parse(mapOf("stages.yml" to stages("{ cues: [done_cue], opens: [gate_corridor] }")))
        val stage = spec.stage("entry_court")!!
        assertEquals(listOf("gate_corridor"), stage.onComplete.opens)
        assertEquals(listOf("done_cue"), stage.onComplete.cues)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        // 單一 id 也可以直接寫成純量
        val scalar = ContentFixtures.parse(mapOf("stages.yml" to stages("{ opens: gate_corridor }")))
        assertEquals(listOf("gate_corridor"), scalar.stage("entry_court")!!.onComplete.opens)
    }

    @Test
    fun `opens 指向不存在或不是 gate 的物件被擋，on-start 不能寫 opens`() {
        val notGate = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("stages.yml" to stages("{ opens: [lantern_a] }"))))
        assertTrue(notGate.any { "stages.entry_court.on-complete.opens[0]" in it && "不是 gate" in it }, notGate.toString())
        val missing = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("stages.yml" to stages("{ opens: [nope_gate] }"))))
        assertTrue(missing.any { "opens[0]" in it && "nope_gate" in it }, missing.toString())
        val onStart = errorsOf(mapOf("stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace("cues: [intro_cue]", "cues: [intro_cue]\n      opens: [gate_corridor]")))
        assertTrue(onStart.any { "on-start.opens" in it && "未知的欄位" in it }, onStart.toString())
    }

    // ---- objective while / until 的狀態型條件 ----

    private val bossStages = """
        dungeon: demo-boss
        namespace: demo
        stages:
          front_court:
            objective:
              - { text: goal.strike, while: { type: boss-kneel, boss: nun } }
              - { text: goal.powered, while: { type: powered } }
              - { text: goal.lamps, until: { type: boss-defeated, boss: nun } }
            objective-done: goal.done
            complete:
              - { type: boss-defeated, boss: nun }
            clears-enemies: true
    """.trimIndent()

    @Test
    fun `objective 的 while 解析成條件，powered 與 boss-kneel 可用在 until 與 while`() {
        val spec = BossFixtures.parse(mapOf("stages.yml" to bossStages))
        val steps = spec.stage("front_court")!!.objective
        assertEquals(ObjectiveStep("goal.strike", null, Condition.BossKneel("nun")), steps[0])
        assertEquals(ObjectiveStep("goal.powered", null, Condition.Powered), steps[1])
        assertEquals(ObjectiveStep("goal.lamps", Condition.BossDefeated("nun"), null), steps[2])
        // 有 while 的步驟不受「沒寫 until 只能放最後」限制
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        val until = BossFixtures.parse(mapOf("stages.yml" to bossStages.replace("{ text: goal.powered, while: { type: powered } }", "{ text: goal.powered, until: { type: powered } }")))
        assertEquals(Condition.Powered, until.stage("front_court")!!.objective[1].until)
    }

    @Test
    fun `沒有 while 也沒有 until 的步驟仍然只能放最後一個`() {
        val spec = BossFixtures.parse(mapOf("stages.yml" to bossStages.replace("{ text: goal.powered, while: { type: powered } }", "{ text: goal.powered }")))
        val errors = ContentValidator.validateReferences(spec)
        assertTrue(errors.any { "objective[1].until" in it && "只能放最後一個" in it }, errors.toString())
    }

    @Test
    fun `powered 與 boss-kneel 會來去,不能當 stage 的完成條件`() {
        val powered = assertFailsWith<ContentDataException> {
            BossFixtures.parse(mapOf("stages.yml" to bossStages.replace("complete:\n      - { type: boss-defeated, boss: nun }", "complete:\n      - { type: powered }")))
        }.errors
        assertTrue(powered.any { "stages.front_court.complete[0]" in it && "powered 會來去" in it }, powered.toString())
        val kneel = assertFailsWith<ContentDataException> {
            BossFixtures.parse(mapOf("stages.yml" to bossStages.replace("complete:\n      - { type: boss-defeated, boss: nun }", "complete:\n      - { type: boss-kneel, boss: nun }")))
        }.errors
        assertTrue(kneel.any { "boss-kneel 會來去" in it }, kneel.toString())
        val unknown = assertFailsWith<ContentDataException> {
            BossFixtures.parse(mapOf("stages.yml" to bossStages.replace("type: powered }", "type: sleeping }")))
        }.errors
        assertTrue(unknown.any { "enemy-dispersed、boss-kneel" in it }, unknown.toString())
    }

    @Test
    fun `狀態型條件的引用核對，powered 要有 power-up 物件，boss-kneel 的 boss 要存在且在同一個 stage`() {
        // 沒有 power-up 物件的內容:powered 永遠不會成立
        val noPowerUp = ContentFixtures.parse(mapOf("stages.yml" to stages("{ cues: [x] }", "    objective:\n      - { text: g, while: { type: powered } }\n      - { text: h }")))
        val e1 = ContentValidator.validateReferences(noPowerUp)
        assertTrue(e1.any { "objective[0].while" in it && "power-up" in it }, e1.toString())
        // 不存在的 boss
        val ghost = BossFixtures.parse(mapOf("stages.yml" to bossStages.replace("boss: nun } }\n", "boss: ghost } }\n")))
        val e2 = ContentValidator.validateReferences(ghost)
        assertTrue(e2.any { "objective[0].while.boss" in it && "ghost" in it }, e2.toString())
        // boss-kneel 缺 boss 欄位
        val missing = assertFailsWith<ContentDataException> { BossFixtures.parse(mapOf("stages.yml" to bossStages.replace("{ type: boss-kneel, boss: nun }", "{ type: boss-kneel }"))) }.errors
        assertTrue(missing.any { "objective[0].while.boss" in it && "缺少必要欄位" in it }, missing.toString())
    }

    @Test
    fun `pickObjective，while 不成立的步驟被跳過、成立時顯示，條件回來又會顯示`() {
        val steps = listOf(
            ObjectiveStep("strike", null, Condition.BossKneel("nun")),
            ObjectiveStep("powered", null, Condition.Powered),
            ObjectiveStep("lamps", Condition.BossDefeated("nun")),
        )
        fun pick(vararg met: Condition) = pickObjective(steps) { it in met }?.textKey
        assertEquals("lamps", pick())
        assertEquals("powered", pick(Condition.Powered))
        assertEquals("strike", pick(Condition.BossKneel("nun")), "跪下優先於其後的步驟")
        assertEquals("strike", pick(Condition.BossKneel("nun"), Condition.Powered))
        assertEquals("lamps", pick(), "跪下結束後回到點燈")
        assertNull(pick(Condition.BossDefeated("nun")), "打倒後全部不符 → 交給 objective-done")
        // while 與 until 並用:while 成立且 until 未達成才顯示
        val both = listOf(ObjectiveStep("x", Condition.BossDefeated("nun"), Condition.Powered))
        assertEquals("x", pickObjective(both) { it == Condition.Powered }?.textKey)
        assertNull(pickObjective(both) { it == Condition.BossDefeated("nun") })
        assertNull(pickObjective(both) { false })
    }

    // ---- boss intro／hit-cues ----

    private fun parseBoss(vararg replace: Pair<String, String>): ContentSpec {
        var text = BossFixtures.NUN
        for ((a, b) in replace) {
            check(a in text) { "測試字串沒有命中:$a" }
            text = text.replace(a, b)
        }
        return BossFixtures.parse(mapOf("bosses.yml" to text))
    }

    private fun bossErrors(vararg replace: Pair<String, String>): List<String> =
        assertFailsWith<ContentDataException> { parseBoss(*replace) }.errors

    private val introLine = "hit-cooldown-ticks: 0" to
        "hit-cooldown-ticks: 0\n    start-delay-seconds: 5\n    hit-cues: [nun_hit]\n    intro: { marker: nun_roof_main, cues: [intro_cue], animation: phase_up, hold-seconds: 3, glide-seconds: 1.5, then-glide-to: nun_point_a }"

    @Test
    fun `boss intro 與 hit-cues 解析，預設值`() {
        val boss = parseBoss(introLine).bosses.getValue("nun")
        assertEquals(listOf("nun_hit"), boss.hitCues)
        val intro = assertNotNull(boss.intro)
        assertEquals("nun_roof_main", intro.marker)
        assertEquals(listOf("intro_cue"), intro.cues)
        assertEquals("phase_up", intro.animation)
        assertEquals(3.0, intro.holdSeconds)
        assertEquals(1.5, intro.glideSeconds)
        assertEquals("nun_point_a", intro.glideTo)
        assertEquals(3.0, intro.animationSeconds, "預設")
        // 只寫 marker:其餘預設
        val minimal = parseBoss("hit-cooldown-ticks: 0" to "hit-cooldown-ticks: 0\n    start-delay-seconds: 5\n    intro: { marker: nun_roof_main }").bosses.getValue("nun").intro!!
        assertEquals(2.0, minimal.holdSeconds)
        assertEquals(2.0, minimal.glideSeconds)
        assertNull(minimal.glideTo)
        assertNull(minimal.animation)
        assertEquals(emptyList(), minimal.cues)
    }

    @Test
    fun `intro 壞資料被擋，缺 marker、停留加滑行超過 start-delay、未知欄位`() {
        assertTrue(bossErrors("hit-cooldown-ticks: 0" to "hit-cooldown-ticks: 0\n    intro: { cues: [a] }").any { "bosses.nun.intro.marker" in it && "缺少必要欄位" in it })
        val tooLong = bossErrors("hit-cooldown-ticks: 0" to "hit-cooldown-ticks: 0\n    start-delay-seconds: 4\n    intro: { marker: nun_roof_main, hold-seconds: 3, glide-seconds: 2 }")
        assertTrue(tooLong.any { "intro.hold-seconds" in it && "start-delay-seconds" in it }, tooLong.toString())
        assertTrue(bossErrors("hit-cooldown-ticks: 0" to "hit-cooldown-ticks: 0\n    intro: { marker: nun_roof_main, speed: 2 }").any { "intro.speed" in it && "未知的欄位" in it })
    }

    @Test
    fun `intro 的 marker 與落點必須在場景裡`() {
        val spec = parseBoss(introLine)
        val errors = ContentValidator.validateScene(spec, BossFixtures.markers())
        assertTrue(errors.any { "bosses.nun.intro.marker" in it && "nun_roof_main" in it }, errors.toString())
        val roof = MapMarker(MarkerKind.TRIGGER, MapAssetId("demo", "front", "0123456789abcdef"), 40, 12, 0, null, mapOf("id" to "nun_roof_main"))
        assertEquals(emptyList(), ContentValidator.validateScene(spec, BossFixtures.markers() + roof))
        val badGlide = parseBoss("hit-cooldown-ticks: 0" to "hit-cooldown-ticks: 0\n    start-delay-seconds: 5\n    intro: { marker: nun_roof_main, then-glide-to: nowhere }")
        assertTrue(ContentValidator.validateScene(badGlide, BossFixtures.markers() + roof).any { "intro.then-glide-to" in it && "nowhere" in it })
    }

    // ---- 倒下提示 ----

    @Test
    fun `倒下提示欄位解析，ally-hud-text 要有對應文案 key`() {
        val spec = parseBoss("hud-text: boss.downed.hud," to "hud-text: boss.downed.hud, self-hint-cues: [hint_self], ally-hint-cues: [hint_ally_a, hint_ally_b], ally-hud-text: boss.ally.hud,")
        val d = spec.bosses.getValue("nun").downed
        assertEquals(listOf("hint_self"), d.selfHintCues)
        assertEquals(listOf("hint_ally_a", "hint_ally_b"), d.allyHintCues)
        assertEquals("boss.ally.hud", d.allyHudTextKey)
        val missing = ContentValidator.validateTexts(spec) { it in BossFixtures.TEXT_KEYS }
        assertTrue(missing.any { "bosses.nun.downed.ally-hud-text" in it && "boss.ally.hud" in it }, missing.toString())
        assertEquals(emptyList(), ContentValidator.validateTexts(spec) { it in BossFixtures.TEXT_KEYS + "boss.ally.hud" })
    }

    // ---- 光環迴旋取樣欄位 ----

    private val spinHead = "spin: { shape: spin, phase: 3, weight: 2, warn-seconds: 2, radius: 11, inner-radius: 2, gap-degrees: 80, degrees-per-second: 40, duration-seconds: 8, damage-percent: 0.2, animation: tele_spin }"

    private fun spinWith(extra: String) = spinHead to spinHead.replace("damage-percent: 0.2,", "damage-percent: 0.2, $extra,")

    @Test
    fun `spin 取樣欄位解析，每輪 cue 數是 缺口 2 條邊緣 + 光束的總和`() {
        val spin = parseBoss(spinWith("beam-cues: [b1], gap-cues: [g1, g2], sample-ticks: 6, beams: 2, points-per-line: 4"))
            .bosses.getValue("nun").moves.getValue("spin").shape as MoveShape.Spin
        assertEquals(listOf("b1"), spin.beamCues)
        assertEquals(listOf("g1", "g2"), spin.gapCues)
        assertEquals(6, spin.sampleTicks)
        assertEquals(2, spin.beams)
        assertEquals(4, spin.pointsPerLine)
        assertTrue(spin.hasVisual)
        assertEquals(2 * 2 * 4 + 2 * 1 * 4, spin.cuesPerSample)
        // 只寫缺口 cue:不算光束
        val gapOnly = parseBoss(spinWith("gap-cues: [g1]")).bosses.getValue("nun").moves.getValue("spin").shape as MoveShape.Spin
        assertEquals(2 * 1 * 6, gapOnly.cuesPerSample)
    }

    @Test
    fun `spin 取樣的頻率與數量有上限`() {
        assertTrue(bossErrors(spinWith("gap-cues: [g], sample-ticks: 2")).any { "sample-ticks" in it })
        assertTrue(bossErrors(spinWith("gap-cues: [g], sample-ticks: 80")).any { "sample-ticks" in it })
        assertTrue(bossErrors(spinWith("beam-cues: [b], beams: 12")).any { "beams" in it })
        assertTrue(bossErrors(spinWith("beam-cues: [b], points-per-line: 20")).any { "points-per-line" in it })
        // 單項都合法但總數超過每輪上限 80:2 × 3 cue × 12 點 = 72 + 8 條光束 × 12 點 = 96 > 80
        val over = bossErrors(spinWith("gap-cues: [g1, g2, g3], beam-cues: [b], beams: 8, points-per-line: 12"))
        assertTrue(over.any { "moves.spin" in it && "上限 ${MoveShape.Spin.MAX_SAMPLE_CUES}" in it }, over.toString())
        // 邊界:剛好 80 以內合法
        val ok = parseBoss(spinWith("gap-cues: [g], beam-cues: [b], beams: 3, points-per-line: 8")).bosses.getValue("nun").moves.getValue("spin").shape as MoveShape.Spin
        assertTrue(ok.cuesPerSample <= MoveShape.Spin.MAX_SAMPLE_CUES, "${ok.cuesPerSample}")
        assertIs<MoveShape.Spin>(ok)
    }
}
