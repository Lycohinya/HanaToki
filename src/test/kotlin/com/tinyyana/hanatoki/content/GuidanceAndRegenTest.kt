package com.tinyyana.hanatoki.content

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.0.0-rc.20:燈火自動回補(ChargeMeter)、stage 目標(objective)、世界標示與個人提示的資料契約。 */
class GuidanceAndRegenTest {

    // ---- ChargeMeter ----

    @Test
    fun `回補永遠不超過上限,滿了進度歸零`() {
        val meter = ChargeMeter(passiveSeconds = 3.0, zoneSeconds = 1.0)
        val rnd = Random(7)
        var charges = 0
        repeat(5000) {
            val gained = meter.advance(rnd.nextDouble(0.0, 4.0), charges, 5, rnd.nextBoolean())
            assertTrue(gained >= 0)
            charges += gained
            assertTrue(charges <= 5, "燈火 $charges 超過上限 5")
            if (rnd.nextInt(4) == 0) charges = (charges - rnd.nextInt(0, 3)).coerceAtLeast(0) // 模擬閃光用掉
            assertTrue(meter.progress in 0.0..1.0)
        }
        assertEquals(0, meter.advance(10.0, 5, 5, true), "已滿不再補")
        assertEquals(0.0, meter.progress, "已滿進度歸零,下一次用掉不會立刻被補")
    }

    @Test
    fun `燈火 0 在被動回補秒數內一定回到可用,不依賴任何敵人或區域`() {
        for (passive in listOf(1.0, 4.0, 8.0, 30.0)) {
            val meter = ChargeMeter(passive, null)
            var waited = 0.0
            var charges = 0
            // 以 mist-patrol 的探測間隔 0.5 秒推進;最壞情況也不能超過 passive + 一個探測間隔
            while (charges == 0 && waited <= passive + 0.5) {
                charges += meter.advance(0.5, charges, 5, inZone = false)
                waited += 0.5
            }
            assertTrue(charges >= 1, "被動 $passive 秒的道具在 $waited 秒後仍是 0 燈火")
        }
    }

    @Test
    fun `大步長不會少算也不會多算`() {
        val meter = ChargeMeter(8.0, null)
        assertEquals(2, meter.advance(16.0, 0, 5, false))
        assertEquals(3, meter.advance(100.0, 2, 5, false), "只補到上限")
    }

    @Test
    fun `光源區內改用較快的速率,離開區域退回被動,兩者不相加`() {
        val meter = ChargeMeter(passiveSeconds = 8.0, zoneSeconds = 2.0)
        assertEquals(3, meter.advance(6.0, 0, 5, inZone = true), "區內每 2 秒 1 格")
        val outside = ChargeMeter(8.0, 2.0)
        assertEquals(0, outside.advance(6.0, 0, 5, inZone = false), "區外 6 秒還不到被動的 8 秒")
        val both = ChargeMeter(2.0, 2.0)
        assertEquals(1, both.advance(2.0, 0, 5, inZone = true), "被動與區域取快的,不是 1/2+1/2")
    }

    @Test
    fun `只有光源區沒有被動時,區外永遠不回(所以驗證器要警告)`() {
        val zoneOnly = ChargeMeter(null, 2.0)
        assertEquals(0, zoneOnly.advance(1000.0, 0, 5, inZone = false))
        assertEquals(1, zoneOnly.advance(2.0, 0, 5, inZone = true))
    }

    @Test
    fun `燈火條文字`() {
        assertEquals("●●○○○", chargePips(2, 5))
        assertEquals("○○○", chargePips(-3, 3))
        assertEquals("●●●", chargePips(9, 3))
    }

    // ---- pickObjective ----

    @Test
    fun `目標顯示第一個還沒達成的步驟,全部達成才沒有`() {
        val steps = listOf(
            ObjectiveStep("a", Condition.ObjectState("lantern_a", "burning")),
            ObjectiveStep("b", Condition.ObjectState("lantern_a", "lit")),
        )
        assertEquals("a", pickObjective(steps) { false }?.textKey)
        assertEquals("b", pickObjective(steps) { (it as Condition.ObjectState).state == "burning" }?.textKey)
        assertNull(pickObjective(steps) { true })
        val sticky = listOf(ObjectiveStep("only", null))
        assertEquals("only", pickObjective(sticky) { true }?.textKey, "沒寫 until 的步驟永遠停在那一步")
    }


    // ---- 燈籠世界標示等級 ----

    @Test
    fun `亮圈等級 2 時人一定在可點燃半徑內,不論從哪個等級走過來`() {
        val ready = 4.5
        val rnd = Random(11)
        var level = 0
        repeat(20000) {
            val d = rnd.nextDouble(0.0, 50.0)
            level = lanternHintLevel(d, level, radius = 40.0, readyRadius = ready, hasReady = true, slack = 1.0)
            if (level == 2) assertTrue(d <= ready, "等級 2 但距離 $d > $ready")
            if (level == 1) assertTrue(d <= 41.0)
            if (d > 41.0) assertEquals(0, level)
        }
    }

    @Test
    fun `亮圈向內遲滯、遠處提示向外遲滯`() {
        fun lv(d: Double, cur: Int) = lanternHintLevel(d, cur, 40.0, 4.5, true, 1.0)
        assertEquals(1, lv(4.4, 0), "剛走進 4.5 還不亮圈,要再進一格(3.5)")
        assertEquals(2, lv(3.5, 0))
        assertEquals(2, lv(4.4, 2), "亮著時走到 4.4 仍亮")
        assertEquals(1, lv(4.6, 2), "超過 4.5 就退回遠處提示,不會在點燃範圍外還亮著")
        assertEquals(1, lv(40.5, 1), "遠處提示往外多留 1 格")
        assertEquals(0, lv(40.5, 0))
        assertEquals(0, lv(41.5, 1))
        assertEquals(1, lanternHintLevel(0.0, 0, 40.0, 4.5, hasReady = false, slack = 1.0), "沒有 ready-cue 就只有遠處提示")
    }

    // ---- 資料契約(fixture 都已 trimIndent:頂層 key 0 格、項目 2 格、欄位 4 格)----

    private fun stagesWith(objective: String): String =
        ContentFixtures.FILES.getValue("stages.yml").replace("    next: lantern_court", "    next: lantern_court\n$objective")

    private val goodObjective = listOf(
        "    objective:",
        "      - { text: goal.ghost, until: { type: encounter-cleared, encounter: intro } }",
        "      - { text: goal.path }",
        "    objective-done: goal.done",
        "    objective-every-seconds: 1",
    ).joinToString("\n")

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    private fun refs(overrides: Map<String, String?>): List<String> =
        ContentValidator.validateReferences(ContentFixtures.parse(overrides))

    @Test
    fun `舊資料零影響,新欄位預設全是關的`() {
        val spec = ContentFixtures.parse()
        assertTrue(spec.stages.all { it.objective.isEmpty() && it.objectiveDoneKey == null && it.objectiveEverySeconds == 0.0 })
        val lantern = assertIs<LanternSpec>(spec.objects.getValue("lantern_a"))
        assertEquals(0.0, lantern.igniteRadius)
        assertNull(lantern.hint)
        val tool = spec.tools.getValue("night_lantern")
        assertNull(tool.passiveRegenSeconds)
        assertNull(tool.zoneRegen)
        assertTrue(tool.drainedCues.isEmpty() && tool.gainCues.isEmpty())
        val nf = spec.enemies.getValue("small_ghost").needsFlash!!
        assertNull(nf.aliveCue)
        assertNull(nf.stunnedCue)
    }

    @Test
    fun `stage objective 解析步驟、完成文案與重播間隔`() {
        val spec = ContentFixtures.parse(mapOf("stages.yml" to stagesWith(goodObjective)))
        val stage = spec.stage("entry_court")!!
        assertEquals(2, stage.objective.size)
        assertEquals("goal.ghost", stage.objective[0].textKey)
        assertIs<Condition.EncounterCleared>(stage.objective[0].until)
        assertNull(stage.objective[1].until)
        assertEquals("goal.done", stage.objectiveDoneKey)
        assertEquals(1.0, stage.objectiveEverySeconds)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
    }

    @Test
    fun `objective 也可以直接寫單一文案 key`() {
        val spec = ContentFixtures.parse(mapOf("stages.yml" to stagesWith("    objective: goal.single")))
        assertEquals(listOf(ObjectiveStep("goal.single", null)), spec.stage("entry_court")!!.objective)
    }

    @Test
    fun `objective 欄位壞資料被擋並指名檔名和路徑`() {
        assertTrue(errorsOf(mapOf("stages.yml" to stagesWith("    objective:\n      - { txt: goal.a }"))).any {
            it.startsWith("stages.yml stages.entry_court.objective[0]") && "未知的欄位" in it
        })
        assertTrue(errorsOf(mapOf("stages.yml" to stagesWith("    objective:\n      - { until: { type: encounter-cleared, encounter: intro } }"))).any {
            "objective[0].text" in it && "缺少必要欄位" in it
        })
        assertTrue(errorsOf(mapOf("stages.yml" to stagesWith("    objective: []"))).any { "步驟清單不可為空" in it })
        assertTrue(errorsOf(mapOf("stages.yml" to stagesWith("    objective: goal.a\n    objective-every-seconds: 0.1"))).any { "objective-every-seconds" in it })
        assertTrue(errorsOf(mapOf("stages.yml" to stagesWith("    objective-every-seconds: 2"))).any { "必須寫 objective" in it })
    }

    @Test
    fun `objective 的 until 引用錯誤被擋`() {
        fun objectiveRefs(objective: String) = refs(mapOf("stages.yml" to stagesWith(objective)))
        assertTrue(objectiveRefs("    objective:\n      - { text: g, until: { type: object-state, object: nope, state: lit } }").any { "objective[0]" in it && "nope" in it })
        assertTrue(objectiveRefs("    objective:\n      - { text: g, until: { type: encounter-cleared, encounter: ghost_town } }").any { "ghost_town" in it })
        // reach 只偵測寫在 complete 裡的 marker
        assertTrue(objectiveRefs("    objective:\n      - { text: g, until: { type: reach, marker: candle_path, radius: 3 } }").any { "reach" in it && "complete" in it })
        // 沒寫 until 的步驟不是最後一個
        assertTrue(objectiveRefs("    objective:\n      - { text: g }\n      - { text: h }").any { "沒寫 until" in it })
    }

    @Test
    fun `objective 文案 key 不存在被擋`() {
        val spec = ContentFixtures.parse(mapOf("stages.yml" to stagesWith(goodObjective)))
        val errors = ContentValidator.validateTexts(spec) { it == "goal.ghost" }
        assertTrue(errors.any { "objective[1].text" in it && "goal.path" in it }, errors.toString())
        assertTrue(errors.any { "objective-done" in it && "goal.done" in it }, errors.toString())
    }

    private fun toolYaml(extra: String) =
        ContentFixtures.FILES.getValue("tool.yml") + "\n" + extra.trimIndent().lines().joinToString("\n") { "    $it" }

    @Test
    fun `tool 回補欄位解析與範圍驗證`() {
        val tool = toolYaml(
            """
            passive-regen-seconds: 8
            zone-regen: { seconds: 2, radius: 6, states: [burning, lit], markers: [lantern_a] }
            """,
        ).replace("cues: { flash: flash, empty: flash_empty }", "cues: { flash: flash, empty: flash_empty, drained: lamp_drained, gain: lamp_gain }")
        val spec = ContentFixtures.parse(mapOf("tool.yml" to tool))
        val t = spec.tools.getValue("night_lantern")
        assertEquals(8.0, t.passiveRegenSeconds)
        assertEquals(ZoneRegen(2.0, 6.0, setOf("burning", "lit"), listOf("lantern_a")), t.zoneRegen)
        assertEquals(listOf("lamp_drained"), t.drainedCues)
        assertEquals(listOf("lamp_gain"), t.gainCues)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        assertEquals(emptyList(), ContentValidator.validateScene(spec, ContentFixtures.markers()))
        assertFalse(ContentValidator.warnings(spec).any { "passive-regen-seconds" in it })
    }

    @Test
    fun `tool 回補壞資料被擋`() {
        assertTrue(errorsOf(mapOf("tool.yml" to toolYaml("passive-regen-seconds: 0"))).any { "tools.night_lantern.passive-regen-seconds" in it })
        assertTrue(errorsOf(mapOf("tool.yml" to toolYaml("passive-regen-seconds: -5"))).any { "tools.night_lantern.passive-regen-seconds" in it })
        assertTrue(errorsOf(mapOf("tool.yml" to toolYaml("zone-regen: { seconds: 2, radius: 6, states: [unlit] }"))).any { "不能當光源區狀態" in it })
        assertTrue(errorsOf(mapOf("tool.yml" to toolYaml("zone-regen: { seconds: 2 }"))).any { "zone-regen.radius" in it && "缺少必要欄位" in it })
        assertTrue(errorsOf(mapOf("tool.yml" to toolYaml("zone-regen: { seconds: 2, radius: 6, sec: 3 }"))).any { "zone-regen.sec" in it && "未知的欄位" in it })
        val spec = ContentFixtures.parse(mapOf("tool.yml" to toolYaml("zone-regen: { seconds: 2, radius: 6, markers: [nowhere] }")))
        assertTrue(ContentValidator.validateScene(spec, ContentFixtures.markers()).any { "zone-regen.markers[0]" in it && "nowhere" in it })
    }

    @Test
    fun `沒有被動回補的道具有警告,不擋註冊`() {
        val spec = ContentFixtures.parse()
        assertTrue(ContentValidator.warnings(spec).any { "tools.night_lantern" in it && "passive-regen-seconds" in it })
    }

    @Test
    fun `燈籠的點燃半徑與世界標示解析、壞資料被擋`() {
        val objs = ContentFixtures.FILES.getValue("objects.yml")
        val anchor = "    stage: lantern_court\n    burn-seconds: 15"
        fun withLines(lines: String) = objs.replace(anchor, "    stage: lantern_court\n$lines\n    burn-seconds: 15")
        val ok = ContentFixtures.parse(
            mapOf(
                "objects.yml" to withLines(
                    "    ignite-radius: 5\n    hint: { cue: lantern_hint, ready-cue: lantern_ready, radius: 30, ready-radius: 5, requires-tool: night_lantern }",
                ),
            ),
        )
        val lantern = assertIs<LanternSpec>(ok.objects.getValue("lantern_a"))
        assertEquals(5.0, lantern.igniteRadius)
        assertEquals(ObjectHint("lantern_hint", "lantern_ready", 30.0, 5.0, "night_lantern"), lantern.hint)
        assertEquals(emptyList(), ContentValidator.validateReferences(ok))

        fun hintErrors(hint: String) = errorsOf(mapOf("objects.yml" to withLines("    hint: $hint")))
        assertTrue(hintErrors("{ cue: h, ready-cue: r, radius: 5, ready-radius: 9 }").any { "ready-radius" in it && "不可大於" in it })
        assertTrue(hintErrors("{ cue: h, ready-cue: r, radius: 5 }").any { "ready-radius" in it && "必須寫" in it })
        assertTrue(hintErrors("{ cue: h }").any { "hint.radius" in it && "缺少必要欄位" in it })
        assertTrue(hintErrors("{ cue: h, radius: 5, color: red }").any { "hint.color" in it && "未知的欄位" in it })
        assertTrue(refs(mapOf("objects.yml" to withLines("    hint: { cue: h, radius: 5, requires-tool: sword }"))).any { "hint.requires-tool" in it && "sword" in it })
        assertTrue(errorsOf(mapOf("objects.yml" to withLines("    ignite-radius: -1"))).any { "ignite-radius" in it })
    }

    @Test
    fun `敵人狀態標示 cue 解析`() {
        val en = ContentFixtures.FILES.getValue("enemies.yml").replace(
            "refill-cue: page_refill }",
            "refill-cue: page_refill, alive-cue: ghost_aura, stunned-cue: ghost_marked }",
        )
        val nf = ContentFixtures.parse(mapOf("enemies.yml" to en)).enemies.getValue("small_ghost").needsFlash!!
        assertEquals("ghost_aura", nf.aliveCue)
        assertEquals("ghost_marked", nf.stunnedCue)
        assertTrue(errorsOf(mapOf("enemies.yml" to en.replace("alive-cue: ghost_aura", "alive-cue: ghost.aura"))).any { "alive-cue" in it && "不合法" in it })
    }

    @Test
    fun `個人提示觸發解析,沒有道具就擋`() {
        val hints = ContentFixtures.FILES.getValue("hints.yml") + "\n" + listOf(
            "  hint_empty:",
            "    trigger: { type: tool-empty }",
            "    once: false",
            "    repeat-seconds: 25",
            "    cues: [hint_empty]",
            "  hint_unarmed:",
            "    trigger: { type: unequipped-near-enemy, radius: 8 }",
            "    once: false",
            "    cues: [hint_unarmed]",
        ).joinToString("\n")
        val spec = ContentFixtures.parse(mapOf("hints.yml" to hints))
        assertIs<HintTrigger.ToolEmpty>(spec.hints.getValue("hint_empty").trigger)
        assertEquals(HintTrigger.UnequippedNearEnemy(8.0), spec.hints.getValue("hint_unarmed").trigger)
        assertEquals(25.0, spec.hints.getValue("hint_empty").repeatSeconds)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))

        val errors = refs(mapOf("hints.yml" to hints, "tool.yml" to null))
        assertTrue(errors.any { "hints.hint_empty.trigger" in it }, errors.toString())
        assertTrue(errors.any { "hints.hint_unarmed.trigger" in it }, errors.toString())

        assertTrue(errorsOf(mapOf("hints.yml" to hints.replace("radius: 8", "rad: 8"))).any { "trigger.rad" in it && "未知的欄位" in it })
        assertTrue(errorsOf(mapOf("hints.yml" to hints.replace("type: unequipped-near-enemy, radius: 8", "type: unequipped-near-enemy"))).any { "trigger.radius" in it && "缺少必要欄位" in it })
    }
}
