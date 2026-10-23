package com.tinyyana.hanatoki.content

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * rc.25 的資料契約與純邏輯:encounter `spawn-marker`／`spawn-offset`、power-up `beacon-cue`、objective 條件
 * `powered-used`／`enemy-dispersed`、hint 觸發 `enemy-dispersed`、boss `heal`、目標列動作列清理的追蹤器。
 * 舊資料零影響:不寫這些欄位的內容和 rc.24 解析結果相同。
 */
class Rc25Test {

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    private val encountersWith: (String) -> String = { extra ->
        ContentFixtures.FILES.getValue("encounters.yml").replace("    counts: { 1: 1, 3: 2 }", "    counts: { 1: 1, 3: 2 }\n$extra")
    }

    // ---- encounter spawn-marker ----

    @Test
    fun `舊資料零影響，沒寫新欄位的 encounter、power-up、boss 照舊`() {
        val spec = ContentFixtures.parse()
        assertTrue(spec.encounters.values.all { it.spawnMarker == null && it.spawnOffset.isEmpty() })
        val boss = BossFixtures.parse().bosses.getValue("nun")
        assertEquals(BossHealSpec(false, false), boss.heal)
        assertTrue(BossFixtures.parse().objects.values.filterIsInstance<PowerUpSpec>().all { it.beaconCue == null })
    }

    @Test
    fun `spawn-marker 與 spawn-offset 解析，不需要 marker-group`() {
        val text = ContentFixtures.FILES.getValue("encounters.yml")
            .replace("    marker-group: intro\n", "    spawn-marker: lantern_a\n    spawn-offset: [0, 0, 6.5]\n")
        val spec = ContentFixtures.parse(mapOf("encounters.yml" to text))
        val intro = spec.encounters.getValue("intro")
        assertEquals("lantern_a", intro.spawnMarker)
        assertEquals(listOf(0.0, 0.0, 6.5), intro.spawnOffset)
        assertEquals("", intro.markerGroup)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        // 場景核對:marker 存在就過(不要求 mob_spawn 組);不存在被擋並指名
        assertEquals(emptyList(), ContentValidator.validateScene(spec, ContentFixtures.markers()))
        val missing = ContentFixtures.parse(mapOf("encounters.yml" to text.replace("lantern_a", "nowhere")))
        assertTrue(ContentValidator.validateScene(missing, ContentFixtures.markers()).any { "encounters.intro.spawn-marker" in it && "nowhere" in it })
    }

    @Test
    fun `spawn-marker 的壞資料被擋，並用 marker-group、offset 沒有 marker、offset 不是三個數字`() {
        val both = ContentFixtures.FILES.getValue("encounters.yml").replace("    marker-group: intro\n", "    marker-group: intro\n    spawn-marker: lantern_a\n")
        assertTrue(errorsOf(mapOf("encounters.yml" to both)).any { "encounters.intro.marker-group" in it && "擇一" in it })
        val offsetOnly = ContentFixtures.FILES.getValue("encounters.yml").replace("    marker-group: intro\n", "    marker-group: intro\n    spawn-offset: [0, 0, 1]\n")
        assertTrue(errorsOf(mapOf("encounters.yml" to offsetOnly)).any { "spawn-offset" in it && "spawn-marker" in it })
        val bad = ContentFixtures.FILES.getValue("encounters.yml").replace("    marker-group: intro\n", "    spawn-marker: lantern_a\n    spawn-offset: [0, 1]\n")
        assertTrue(errorsOf(mapOf("encounters.yml" to bad)).any { "spawn-offset" in it && "三個數字" in it })
        val far = ContentFixtures.FILES.getValue("encounters.yml").replace("    marker-group: intro\n", "    spawn-marker: lantern_a\n    spawn-offset: [0, 0, 200]\n")
        assertTrue(errorsOf(mapOf("encounters.yml" to far)).any { "spawn-offset" in it })
    }

    // ---- power-up beacon ----

    @Test
    fun `power-up beacon-cue 解析`() {
        val objects = BossFixtures.FILES.getValue("objects.yml").replace("            seconds: 6\n", "            seconds: 6\n            beacon-cue: pumpkin_beacon\n")
        val spec = BossFixtures.parse(mapOf("objects.yml" to objects))
        assertEquals("pumpkin_beacon", (spec.objects.getValue("pumpkin_boss_1") as PowerUpSpec).beaconCue)
    }

    // ---- objective 條件 ----

    private val mazeStages = """
        dungeon: demo-boss
        namespace: demo
        stages:
          front_court:
            objective:
              - { text: goal.find, until: { type: powered-used } }
              - { text: goal.powered, while: { type: powered }, until: { type: enemy-dispersed, enemy: mist } }
              - { text: goal.exit }
            complete:
              - { type: boss-defeated, boss: nun }
    """.trimIndent()

    @Test
    fun `powered-used 與 enemy-dispersed 解析成 objective 條件`() {
        val spec = BossFixtures.parse(mapOf("stages.yml" to mazeStages))
        val steps = spec.stage("front_court")!!.objective
        assertEquals(Condition.PoweredUsed, steps[0].until)
        assertEquals(Condition.Powered, steps[1].during)
        assertEquals(Condition.EnemyDispersed("mist"), steps[1].until)
        assertNull(steps[2].until)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        assertTrue(Condition.PoweredUsed.isTransient && Condition.EnemyDispersed("x").isTransient)
    }

    @Test
    fun `南瓜迷宮三步目標，找南瓜燈 到 powered 時追她 到 碰散她或 powered 結束後去出口`() {
        val steps = BossFixtures.parse(mapOf("stages.yml" to mazeStages)).stage("front_court")!!.objective
        fun pick(poweredNow: Boolean, used: Boolean, dispersed: Boolean): String? = pickObjective(steps) { c ->
            when (c) {
                Condition.Powered -> poweredNow
                Condition.PoweredUsed -> used
                is Condition.EnemyDispersed -> dispersed
                else -> false
            }
        }?.textKey
        assertEquals("goal.find", pick(poweredNow = false, used = false, dispersed = false), "進迷宮:找南瓜燈")
        assertEquals("goal.powered", pick(poweredNow = true, used = true, dispersed = false), "吃到南瓜燈:追她")
        assertEquals("goal.exit", pick(poweredNow = true, used = true, dispersed = true), "碰散她:去出口(powered 還沒結束也一樣)")
        assertEquals("goal.exit", pick(poweredNow = false, used = true, dispersed = false), "powered 結束她沒被碰散:去出口")
        assertEquals("goal.exit", pick(poweredNow = false, used = true, dispersed = true))
    }

    @Test
    fun `累積旗標不能當 stage 的完成條件，引用的敵人與 power-up 要存在`() {
        val complete = assertFailsWith<ContentDataException> {
            BossFixtures.parse(mapOf("stages.yml" to mazeStages.replace("- { type: boss-defeated, boss: nun }", "- { type: powered-used }")))
        }.errors
        assertTrue(complete.any { "powered-used 是 stage 內累積的旗標" in it }, complete.toString())
        val enemy = assertFailsWith<ContentDataException> {
            BossFixtures.parse(mapOf("stages.yml" to mazeStages.replace("- { type: boss-defeated, boss: nun }", "- { type: enemy-dispersed, enemy: mist }")))
        }.errors
        assertTrue(enemy.any { "enemy-dispersed 是 stage 內累積的旗標" in it }, enemy.toString())
        val ghost = ContentValidator.validateReferences(BossFixtures.parse(mapOf("stages.yml" to mazeStages.replace("enemy: mist }", "enemy: ghostly }"))))
        assertTrue(ghost.any { "objective[1].until.enemy" in it && "ghostly" in it }, ghost.toString())
        val noEnemy = assertFailsWith<ContentDataException> {
            BossFixtures.parse(mapOf("stages.yml" to mazeStages.replace("until: { type: enemy-dispersed, enemy: mist }", "until: { type: enemy-dispersed }")))
        }.errors
        assertTrue(noEnemy.any { "objective[1].until.enemy" in it && "缺少必要欄位" in it }, noEnemy.toString())
        // 沒有 power-up 物件的內容:powered-used 永遠不成立
        val stages = ContentFixtures.FILES.getValue("stages.yml")
            .replace("    next: lantern_court", "    next: lantern_court\n    objective:\n      - { text: g, until: { type: powered-used } }\n      - { text: h }")
        val none = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("stages.yml" to stages)))
        assertTrue(none.any { "PoweredUsed" in it && "power-up" in it }, none.toString())
    }

    // ---- hint enemy-dispersed ----

    @Test
    fun `hint 觸發 enemy-dispersed 解析，敵人要存在`() {
        val hints = ContentFixtures.FILES.getValue("hints.yml") + "\n  hint_gone:\n    trigger: { type: enemy-dispersed, enemy: small_ghost }\n    cues: [hint_gone]\n"
        val spec = ContentFixtures.parse(mapOf("hints.yml" to hints))
        assertEquals(HintTrigger.EnemyDispersed("small_ghost"), spec.hints.getValue("hint_gone").trigger)
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        val bad = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("hints.yml" to hints.replace("enemy: small_ghost", "enemy: nobody"))))
        assertTrue(bad.any { "hints.hint_gone.trigger.enemy" in it && "nobody" in it }, bad.toString())
    }

    // ---- boss heal ----

    @Test
    fun `boss heal 解析，預設關`() {
        val text = BossFixtures.NUN.replace("    hit-cooldown-ticks: 0\n", "    hit-cooldown-ticks: 0\n    heal: { on-start: true, on-kneel: true }\n")
        assertEquals(BossHealSpec(true, true), BossFixtures.parse(mapOf("bosses.yml" to text)).bosses.getValue("nun").heal)
        val onlyKneel = BossFixtures.parse(mapOf("bosses.yml" to text.replace("on-start: true, ", ""))).bosses.getValue("nun").heal
        assertEquals(BossHealSpec(false, true), onlyKneel)
        val unknown = assertFailsWith<ContentDataException> { BossFixtures.parse(mapOf("bosses.yml" to text.replace("on-kneel: true", "on-win: true"))) }.errors
        assertTrue(unknown.any { "heal.on-win" in it && "未知的欄位" in it }, unknown.toString())
    }

    // ---- 目標列動作列清理的追蹤器 ----

    @Test
    fun `目標列追蹤器，誰收過就清誰，離場只清自己，局結束全清且只清一次`() {
        val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        val t = ObjectiveHudTracker()
        assertTrue(t.drain().isEmpty(), "沒收過目標列的局結束不必清")
        t.shown(a); t.shown(a); t.shown(b)
        assertEquals(2, t.size)
        assertTrue(t.forget(a), "離場的人收過目標列 → 要清")
        assertFalse(t.forget(a), "同一個人不清兩次")
        assertEquals(listOf(b), t.drain())
        assertTrue(t.drain().isEmpty(), "局結束全清後再 drain 沒有東西，不會重複清")
        assertFalse(t.forget(b))
    }
}
