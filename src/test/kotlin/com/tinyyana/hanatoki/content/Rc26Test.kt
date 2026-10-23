package com.tinyyana.hanatoki.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * rc.26 的資料契約:stage `arrive`／`on-arrive`(含 dialogs 序列)、encounter 觸發 `stage-arrive`、objective 條件
 * `arrived`／`lantern-burning`、hint `requires-arrival`、boss intro `await-arrive-dialogs`、場邊燈可寫 milestones。
 * 舊資料零影響:不寫這些欄位的內容和 rc.25 解析結果相同。執行期(抵達探測、對話序列、boss 出場)靠 BossRunTest 的 held 測試與實機驗證。
 */
class Rc26Test {

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    private fun bossErrorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { BossFixtures.parse(overrides) }.errors

    private val lanternStage = "    on-start: { music: demo.music.lantern }"

    private fun stages(extra: String): String = ContentFixtures.FILES.getValue("stages.yml").replace(lanternStage, "$lanternStage\n$extra")

    private val arriveBlock = """
            arrive: { marker: gate_corridor, radius: 3, offset: [2, 0, 1] }
            on-arrive: { cues: [arrive_cue], dialog: d0, dialogs: [d1, d2], dialog-timeout-seconds: 30, title: demo.title }
    """.trimIndent().lines().joinToString("\n") { "    " + it.trim() }

    @Test
    fun `舊資料零影響，沒寫新欄位的 stage、波次、提示照舊`() {
        val spec = ContentFixtures.parse()
        assertTrue(spec.stages.all { it.arrive == null && it.onArrive == StageEffects() })
        assertTrue(spec.hints.values.none { it.requiresArrival })
        assertNull(BossFixtures.parse().bosses.getValue("nun").intro)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
    }

    @Test
    fun `arrive 與 on-arrive 解析，marker 加偏移、半徑，對話序列與逾時`() {
        val spec = ContentFixtures.parse(mapOf("stages.yml" to stages(arriveBlock)))
        val stage = spec.stage("lantern_court")!!
        assertEquals(ArriveSpec("gate_corridor", 3.0, listOf(2.0, 0.0, 1.0)), stage.arrive)
        assertEquals(listOf("arrive_cue"), stage.onArrive.cues)
        assertEquals("d0", stage.onArrive.dialog)
        assertEquals(listOf("d1", "d2"), stage.onArrive.dialogs)
        assertEquals(30.0, stage.onArrive.dialogTimeoutSeconds)
        assertEquals("demo.title", stage.onArrive.titleKey)
        // on-start 不會因此改變
        assertEquals("demo.music.lantern", stage.onStart.music)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        assertEquals(emptyList(), ContentValidator.validateScene(spec, ContentFixtures.markers()))
    }

    @Test
    fun `arrive 的偏移預設 0，marker 不限 trigger 種類`() {
        val spec = ContentFixtures.parse(mapOf("stages.yml" to stages("    arrive: { marker: wave1_1, radius: 6 }")))
        assertEquals(listOf(0.0, 0.0, 0.0), spec.stage("lantern_court")!!.arrive!!.offset)
        assertEquals(emptyList(), ContentValidator.validateScene(spec, ContentFixtures.markers()))
    }

    @Test
    fun `arrive 的 marker 不存在被擋並指名，壞欄位被擋`() {
        val missing = ContentFixtures.parse(mapOf("stages.yml" to stages("    arrive: { marker: nowhere, radius: 3 }")))
        assertTrue(ContentValidator.validateScene(missing, ContentFixtures.markers()).any { "stages.lantern_court.arrive.marker" in it && "nowhere" in it })
        assertTrue(errorsOf(mapOf("stages.yml" to stages("    arrive: { marker: lantern_a, radius: 0 }"))).any { "radius" in it })
        assertTrue(errorsOf(mapOf("stages.yml" to stages("    arrive: { marker: lantern_a, radius: 3, offset: [1, 2] }"))).any { "offset" in it && "三個數字" in it })
        assertTrue(errorsOf(mapOf("stages.yml" to stages("    arrive: { marker: lantern_a, radius: 3, extra: 1 }"))).any { "extra" in it })
    }

    @Test
    fun `on-arrive 必須和 arrive 並用，dialogs 與 opens 的位置限制`() {
        val noArrive = errorsOf(mapOf("stages.yml" to stages("    on-arrive: { cues: [x] }")))
        assertTrue(noArrive.any { "on-arrive" in it && "arrive 並用" in it }, noArrive.toString())
        // dialogs 只有 on-arrive 可寫
        val onStart = errorsOf(mapOf("stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace(lanternStage, "    on-start: { dialogs: [d1] }")))
        assertTrue(onStart.any { "on-start.dialogs" in it && "未知的欄位" in it }, onStart.toString())
        val opens = errorsOf(mapOf("stages.yml" to stages("    arrive: { marker: lantern_a, radius: 3 }\n    on-arrive: { opens: [gate_corridor] }")))
        assertTrue(opens.any { "on-arrive.opens" in it && "未知的欄位" in it }, opens.toString())
    }

    @Test
    fun `stage-arrive 觸發需要所屬 stage 有 arrive`() {
        val enc = ContentFixtures.FILES.getValue("encounters.yml")
            .replace("trigger: { type: object-state, object: lantern_a, state: burning }", "trigger: { type: stage-arrive }")
        val ok = ContentFixtures.parse(mapOf("stages.yml" to stages(arriveBlock), "encounters.yml" to enc))
        assertEquals(Trigger.StageArrive, ok.encounters.getValue("wave1").trigger)
        assertEquals(emptyList(), ContentValidator.validateReferences(ok))
        val bad = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("encounters.yml" to enc)))
        assertTrue(bad.any { "encounters.wave1.trigger" in it && "stage-arrive" in it && "arrive" in it }, bad.toString())
    }

    @Test
    fun `objective 條件 arrived 與 lantern-burning，只能寫在 objective`() {
        val objective = """
            |    objective:
            |      - { text: g.go, until: { type: arrived } }
            |      - { text: g.burn, while: { type: lantern-burning }, until: { type: object-state, object: lantern_a, state: lit } }
            |      - { text: g.done }
        """.trimMargin()
        val spec = ContentFixtures.parse(mapOf("stages.yml" to stages("$arriveBlock\n$objective")))
        val steps = spec.stage("lantern_court")!!.objective
        assertEquals(Condition.Arrived, steps[0].until)
        assertEquals(Condition.LanternBurning, steps[1].during)
        assertTrue(Condition.Arrived.isTransient && Condition.LanternBurning.isTransient)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        // 沒寫 arrive 的 stage 用 arrived 被擋
        val noArrive = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("stages.yml" to stages(objective))))
        assertTrue(noArrive.any { "arrived 需要" in it && "arrive" in it }, noArrive.toString())
        // 當完成條件被擋
        val complete = errorsOf(mapOf("stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace("- { type: object-state, object: lantern_a, state: lit }", "- { type: arrived }")))
        assertTrue(complete.any { "arrived" in it && "不能當 stage 的完成條件" in it }, complete.toString())
        val burningComplete = errorsOf(mapOf("stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace("- { type: object-state, object: lantern_a, state: lit }", "- { type: lantern-burning }")))
        assertTrue(burningComplete.any { "lantern-burning" in it }, burningComplete.toString())
    }

    @Test
    fun `objective 的抵達步驟：抵達前顯示去那裡，抵達後換下一步，燒燈中換成燒燈步驟`() {
        val spec = ContentFixtures.parse(
            mapOf(
                "stages.yml" to stages(
                    "$arriveBlock\n" + """
                    |    objective:
                    |      - { text: g.go, until: { type: arrived } }
                    |      - { text: g.burn, while: { type: lantern-burning }, until: { type: object-state, object: lantern_a, state: lit } }
                    |      - { text: g.flash }
                    """.trimMargin(),
                ),
            ),
        )
        val steps = spec.stage("lantern_court")!!.objective
        fun pick(arrived: Boolean, burning: Boolean, lit: Boolean): String? = pickObjective(steps) { c ->
            when (c) {
                Condition.Arrived -> arrived
                Condition.LanternBurning -> burning
                is Condition.ObjectState -> lit
                else -> false
            }
        }?.textKey
        assertEquals("g.go", pick(arrived = false, burning = false, lit = false))
        assertEquals("g.flash", pick(arrived = true, burning = false, lit = false))
        assertEquals("g.burn", pick(arrived = true, burning = true, lit = false))
        assertEquals("g.flash", pick(arrived = true, burning = false, lit = false), "暫停或退回後沒在燒就回到點燈步驟")
    }

    @Test
    fun `lantern-burning 需要這個 stage 至少有一盞燈`() {
        val spec = ContentFixtures.parse(
            mapOf("stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace("    next: lantern_court", "    next: lantern_court\n    objective:\n      - { text: g.b, while: { type: lantern-burning } }\n      - { text: g.c }")),
        )
        val errors = ContentValidator.validateReferences(spec)
        assertTrue(errors.any { "lantern-burning 需要" in it }, errors.toString())
    }

    @Test
    fun `hint requires-arrival 需要有 arrive 的 stage`() {
        val hints = ContentFixtures.FILES.getValue("hints.yml") +
            "\n  hint_late:\n    trigger: { type: no-progress, seconds: 30 }\n    stage: lantern_court\n    requires-arrival: true\n    cues: [hint_idle]"
        val ok = ContentFixtures.parse(mapOf("stages.yml" to stages(arriveBlock), "hints.yml" to hints))
        assertTrue(ok.hints.getValue("hint_late").requiresArrival)
        assertFalse(ok.hints.getValue("hint_point").requiresArrival)
        assertEquals(emptyList(), ContentValidator.validateReferences(ok))
        val bad = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("hints.yml" to hints)))
        assertTrue(bad.any { "hints.hint_late.requires-arrival" in it }, bad.toString())
        val noStage = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("hints.yml" to hints.replace("    stage: lantern_court\n", ""))))
        assertTrue(noStage.any { "hints.hint_late.requires-arrival" in it }, noStage.toString())
    }

    private val bossStages = """
        dungeon: demo-boss
        namespace: demo
        stages:
          front_court:
            arrive: { marker: front_door, radius: 6 }
            on-arrive: { dialogs: [roof_01, roof_02] }
            complete:
              - { type: boss-defeated, boss: nun }
            clears-enemies: true
    """.trimIndent()

    @Test
    fun `boss intro await-arrive-dialogs 解析，需要 stage 的 on-arrive 有 dialogs`() {
        val bosses = BossFixtures.NUN.replace(
            "    points: [nun_point_a",
            "    start-delay-seconds: 20\n    intro: { marker: nun_roof_main, hold-seconds: 2, glide-seconds: 3, then-glide-to: nun_point_a, await-arrive-dialogs: true }\n    points: [nun_point_a",
        )
        val spec = BossFixtures.parse(mapOf("stages.yml" to bossStages, "bosses.yml" to bosses))
        assertTrue(spec.bosses.getValue("nun").intro!!.awaitArriveDialogs)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        val noDialogs = ContentValidator.validateReferences(
            BossFixtures.parse(mapOf("stages.yml" to bossStages.replace("on-arrive: { dialogs: [roof_01, roof_02] }", "on-arrive: { cues: [x] }"), "bosses.yml" to bosses)),
        )
        assertTrue(noDialogs.any { "await-arrive-dialogs" in it }, noDialogs.toString())
        // 沒寫時預設 false
        val plain = bosses.replace(", await-arrive-dialogs: true", "")
        assertFalse(BossFixtures.parse(mapOf("stages.yml" to bossStages, "bosses.yml" to plain)).bosses.getValue("nun").intro!!.awaitArriveDialogs)
    }

    @Test
    fun `場邊燈可以寫 milestones（重置後會重播），其他場邊燈限制照舊`() {
        val objects = BossFixtures.FILES.getValue("objects.yml").replace(
            "            burn-seconds: 3\n",
            "            burn-seconds: 3\n            milestones:\n              - { at: 0.34, cues: [glow_2] }\n              - { at: 0.67, cues: [glow_3] }\n",
        )
        val spec = BossFixtures.parse(mapOf("objects.yml" to objects))
        val lamp = spec.objects.getValue("boss_lamp_1") as LanternSpec
        assertEquals(2, lamp.milestones.size)
        assertTrue(ContentValidator.validateReferences(spec).none { "milestones" in it })
        // 其他限制仍擋:progress-bar
        val bar = BossFixtures.FILES.getValue("objects.yml").replace("            burn-seconds: 3\n", "            burn-seconds: 3\n            progress-bar: { text: demo.bar, color: PURPLE }\n")
        assertTrue(ContentValidator.validateReferences(BossFixtures.parse(mapOf("objects.yml" to bar))).any { "progress-bar" in it })
    }
}

class Rc26ChaseDelayTest {
    private fun enemies(extra: String) = """
        enemies:
          big_ghost:
            base-entity: zombie
            rules: [chases-players]
            health: 20
            chases-players: { speed: 1.1$extra }
    """.trimIndent()

    @Test
    fun `chases-players start-delay-seconds 解析，預設 0，範圍 0 到 60`() {
        val none = ContentFixtures.parse(mapOf("enemies.yml" to enemies("")))
        assertEquals(0.0, none.enemies.getValue("big_ghost").chasesPlayers!!.startDelaySeconds)
        val delayed = ContentFixtures.parse(mapOf("enemies.yml" to enemies(", start-delay-seconds: 8")))
        assertEquals(8.0, delayed.enemies.getValue("big_ghost").chasesPlayers!!.startDelaySeconds)
        val errors = assertFailsWith<ContentDataException> { ContentFixtures.parse(mapOf("enemies.yml" to enemies(", start-delay-seconds: 61"))) }.errors
        assertTrue(errors.any { "start-delay-seconds" in it }, errors.toString())
    }
}

class Rc26ChatTest {
    private val chat = "chat:\n  boss_phase2_a: { text: demo.chat.a, delay-seconds: 1.5 }\n  phase_b: { text: demo.chat.b }"

    @Test
    fun `chat yml 解析，delay 預設 0，沒檔案是空`() {
        assertTrue(ContentFixtures.parse().chat.isEmpty())
        val spec = ContentFixtures.parse(mapOf("chat.yml" to chat))
        assertEquals(ChatLine("boss_phase2_a", "demo.chat.a", 1.5), spec.chat.getValue("boss_phase2_a"))
        assertEquals(0.0, spec.chat.getValue("phase_b").delaySeconds)
    }

    @Test
    fun `chat yml 的壞資料與缺文案被擋`() {
        val noText = assertFailsWith<ContentDataException> { ContentFixtures.parse(mapOf("chat.yml" to "chat:\n  x: { delay-seconds: 1 }")) }.errors
        assertTrue(noText.any { "chat.yml" in it && "text" in it }, noText.toString())
        val bad = assertFailsWith<ContentDataException> { ContentFixtures.parse(mapOf("chat.yml" to "chat:\n  x: { text: a.b, extra: 1 }")) }.errors
        assertTrue(bad.any { "extra" in it }, bad.toString())
        val late = assertFailsWith<ContentDataException> { ContentFixtures.parse(mapOf("chat.yml" to "chat:\n  x: { text: a.b, delay-seconds: 90 }")) }.errors
        assertTrue(late.any { "delay-seconds" in it }, late.toString())
        val spec = ContentFixtures.parse(mapOf("chat.yml" to chat))
        val missing = ContentValidator.validateTexts(spec) { it == "demo.chat.a" || it == "demo.title" }
        assertTrue(missing.any { "chat.phase_b.text" in it && "demo.chat.b" in it }, missing.toString())
    }
}

class Rc26AuraTest {
    @Test
    fun `shield aura-cue 解析，預設 null`() {
        assertNull(BossFixtures.parse().bosses.getValue("nun").shield.auraCue)
        val spec = BossFixtures.parseBoss("shield: { halo-bone: halo, spark-cues: [shield_spark] }" to "shield: { halo-bone: halo, spark-cues: [shield_spark], aura-cue: halo_aura }")
        assertEquals("halo_aura", spec.bosses.getValue("nun").shield.auraCue)
    }
}
