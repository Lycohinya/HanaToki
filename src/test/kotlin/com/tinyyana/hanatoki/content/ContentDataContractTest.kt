package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.config.DungeonDefinitionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 鎖資料契約:loader 拒絕壞資料,錯誤指名檔名+ID+欄位;跨檔參照不存在的 ID 一律擋下。 */
class ContentDataContractTest {

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    @Test
    fun `完整範例解析成功,欄位對得上`() {
        val spec = ContentFixtures.parse()
        assertEquals("demo-patrol", spec.dungeonId)
        assertEquals(listOf("entry_court", "lantern_court"), spec.stages.map { it.id })
        assertEquals("entry_court", spec.startStage)
        val lantern = assertIs<LanternSpec>(spec.objects.getValue("lantern_a"))
        assertEquals(15.0, lantern.burnSeconds)
        assertEquals(mapOf(2 to 1.5, 3 to 2.0), lantern.speedByPlayers)
        assertEquals("gate_corridor", lantern.onLit.opens)
        assertIs<GateSpec>(spec.objects.getValue("gate_corridor"))
        assertEquals("ZOMBIE", spec.enemies.getValue("small_ghost").baseEntity)
        assertEquals(mapOf(1 to 3, 2 to 4, 4 to 5), spec.encounters.getValue("wave1").counts)
        assertEquals(3, spec.tools.getValue("night_lantern").startCharges)
        assertIs<HintTrigger.NearMarker>(spec.hints.getValue("hint_point").trigger)
        assertTrue(ContentValidator.validateReferences(spec).isEmpty(), ContentValidator.validateReferences(spec).toString())
        assertTrue(ContentValidator.validateScene(spec, ContentFixtures.markers()).isEmpty(), ContentValidator.validateScene(spec, ContentFixtures.markers()).toString())
    }

    @Test
    fun `YAML key 含點的 ID 被擋,錯誤指名檔名和 ID`() {
        val errors = errorsOf(mapOf("objects.yml" to "objects:\n  lantern.a:\n    type: lantern\n"))
        assertTrue(errors.any { it.startsWith("objects.yml objects.lantern.a") && "不合法" in it }, errors.toString())
    }

    @Test
    fun `引用處的 ID 含點也被擋`() {
        val errors = errorsOf(
            mapOf(
                "stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace("next: lantern_court", "next: lantern.court"),
            ),
        )
        assertTrue(errors.any { it.startsWith("stages.yml stages.entry_court.next") && "不合法" in it }, errors.toString())
    }

    @Test
    fun `拼錯的欄位名被擋,不會悄悄少一個功能`() {
        val errors = errorsOf(
            mapOf("objects.yml" to ContentFixtures.FILES.getValue("objects.yml").replace("burn-seconds", "burn-secs")),
        )
        assertTrue(errors.any { "objects.lantern_a.burn-secs" in it && "未知的欄位" in it }, errors.toString())
        assertTrue(errors.any { "objects.lantern_a.burn-seconds" in it && "缺少必要欄位" in it }, errors.toString())
    }

    @Test
    fun `數值範圍錯誤指名欄位`() {
        val errors = errorsOf(
            mapOf("objects.yml" to ContentFixtures.FILES.getValue("objects.yml").replace("burn-seconds: 15", "burn-seconds: -1")),
        )
        assertTrue(errors.any { it.startsWith("objects.yml objects.lantern_a.burn-seconds") }, errors.toString())
    }

    @Test
    fun `缺必要檔案和未知檔案都拒絕`() {
        assertTrue(errorsOf(mapOf("scene.yml" to null)).any { it.startsWith("scene.yml") && "缺少必要資料檔" in it })
        assertTrue(errorsOf(mapOf("bosses.yml" to "x: 1")).any { it.startsWith("bosses.yml") && "未知的資料檔" in it })
    }

    @Test
    fun `一次收齊所有錯誤,不是遇到第一個就停`() {
        val errors = errorsOf(
            mapOf(
                "objects.yml" to "objects:\n  a.b:\n    type: lantern\n  c:\n    type: door\n",
                "enemies.yml" to "enemies:\n  g:\n    health: 1\n",
            ),
        )
        assertTrue(errors.size >= 3, errors.toString())
    }

    @Test
    fun `重複的 YAML key 拒絕`() {
        assertFailsWith<ContentDataException> { ContentYaml.parse("objects.yml", "objects:\n  a: {}\n  a: {}\n") }
    }

    @Test
    fun `未知的規則標籤被擋,保留標籤接受但有警告`() {
        val base = ContentFixtures.FILES.getValue("enemies.yml")
        assertTrue(errorsOf(mapOf("enemies.yml" to base.replace("[needs-flash]", "[teleports]"))).any { "未知的規則標籤" in it })
        val reserved = ContentFixtures.parse(mapOf("enemies.yml" to base.replace("[needs-flash]", "[needs-flash, frozen-when-watched]")))
        assertTrue(ContentValidator.warnings(reserved).any { "frozen-when-watched" in it })
    }

    @Test
    fun `needs-flash 標籤沒有對應區塊就拒絕`() {
        val base = ContentFixtures.FILES.getValue("enemies.yml")
        val stripped = base.lines().filterNot { it.contains("needs-flash: {") }.joinToString("\n")
        assertTrue(errorsOf(mapOf("enemies.yml" to stripped)).any { "enemies.small_ghost.needs-flash" in it })
    }

    // ---- 跨檔參照 ----

    private fun refErrors(overrides: Map<String, String?>): List<String> =
        ContentValidator.validateReferences(ContentFixtures.parse(overrides))

    @Test
    fun `引用不存在的 stage、敵人、波次、物件 都被擋並指名`() {
        val stages = ContentFixtures.FILES.getValue("stages.yml")
        assertTrue(refErrors(mapOf("stages.yml" to stages.replace("next: lantern_court", "next: nowhere"))).any { "stages.entry_court.next" in it && "nowhere" in it })
        assertTrue(refErrors(mapOf("stages.yml" to stages.replace("encounter: intro", "encounter: ghost_town"))).any { "ghost_town" in it })
        assertTrue(refErrors(mapOf("stages.yml" to stages.replace("object: lantern_a", "object: lantern_z"))).any { "lantern_z" in it })
        val enc = ContentFixtures.FILES.getValue("encounters.yml")
        assertTrue(refErrors(mapOf("encounters.yml" to enc.replace("enemy: small_ghost", "enemy: big_ghost"))).any { "encounters.intro.enemy" in it && "big_ghost" in it })
    }

    @Test
    fun `物件狀態名不合法被擋`() {
        val stages = ContentFixtures.FILES.getValue("stages.yml")
        assertTrue(refErrors(mapOf("stages.yml" to stages.replace("state: lit", "state: shining"))).any { "shining" in it && "沒有狀態" in it })
        val enc = ContentFixtures.FILES.getValue("encounters.yml")
        assertTrue(refErrors(mapOf("encounters.yml" to enc.replace("state: burning", "state: open"))).any { "沒有狀態" in it })
    }

    @Test
    fun `opens 必須指向 gate`() {
        val objs = ContentFixtures.FILES.getValue("objects.yml")
        assertTrue(refErrors(mapOf("objects.yml" to objs.replace("opens: gate_corridor", "opens: lantern_a"))).any { "不是 gate" in it })
        assertTrue(refErrors(mapOf("objects.yml" to objs.replace("opens: gate_corridor", "opens: missing_gate"))).any { "missing_gate" in it })
    }

    @Test
    fun `stage 鏈繞圈或有走不到的 stage 被擋`() {
        val stages = ContentFixtures.FILES.getValue("stages.yml")
        val loop = stages.replace("on-complete: { cues: [lantern_done] }", "on-complete: { cues: [lantern_done] }\n    next: entry_court")
        assertTrue(refErrors(mapOf("stages.yml" to loop)).any { "繞圈" in it })
        val orphan = stages.replace("    next: lantern_court", "")
        assertTrue(refErrors(mapOf("stages.yml" to orphan)).any { "走不到" in it })
    }

    @Test
    fun `波次觸發鏈繞圈被擋`() {
        val enc = """
            encounters:
              a: { stage: entry_court, enemy: small_ghost, marker-group: intro, trigger: { type: encounter-cleared, encounter: b }, counts: { 1: 1 } }
              b: { stage: entry_court, enemy: small_ghost, marker-group: intro, trigger: { type: encounter-cleared, encounter: a }, counts: { 1: 1 } }
        """.trimIndent()
        val errors = ContentValidator.validateReferences(ContentFixtures.parse(mapOf("encounters.yml" to enc, "stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace("encounter: intro", "encounter: a"))))
        assertTrue(errors.any { "繞圈" in it }, errors.toString())
    }

    // ---- 場景 marker ----

    @Test
    fun `場景缺 marker 或 marker 重複被擋`() {
        val spec = ContentFixtures.parse()
        val markers = ContentFixtures.markers()
        assertTrue(ContentValidator.validateScene(spec, markers.filterNot { it.id == "lantern_a" }).any { "objects.lantern_a.marker" in it })
        assertTrue(ContentValidator.validateScene(spec, markers.filterNot { it.id == "entry" }).any { "entry" in it })
        assertTrue(ContentValidator.validateScene(spec, markers.filterNot { it.properties["group"] == "wave1" }).any { "encounters.wave1.marker-group" in it })
        assertTrue(ContentValidator.validateScene(spec, markers + markers.first { it.id == "lantern_a" }).any { "重複" in it })
    }

    @Test
    fun `door marker 缺 facing 或 w h 被擋`() {
        val spec = ContentFixtures.parse()
        val all = ContentFixtures.markers()
        val door = all.first { it.id == "gate_corridor" }
        val others = all.filterNot { it === door }
        assertTrue(ContentValidator.validateScene(spec, others + door.copy(properties = mapOf("id" to "gate_corridor"))).any { "w=" in it })
        assertTrue(ContentValidator.validateScene(spec, others + door.copy(facing = null)).any { "facing" in it })
        assertTrue(ContentValidator.validateScene(spec, others + door.copy(kind = com.tinyyana.hanatoki.map.MarkerKind.TRIGGER)).any { "必須是 door" in it })
    }

    @Test
    fun `marker ID 含點被擋`() {
        val spec = ContentFixtures.parse()
        val bad = ContentFixtures.markers() + ContentFixtures.markers().first { it.id == "hedge_ghost" }.copy(properties = mapOf("id" to "a.b", "group" to "intro"))
        assertTrue(ContentValidator.validateScene(spec, bad).any { "a.b" in it && "不合法" in it })
    }

    // ---- 和 dungeons.yml 對帳 ----

    private fun def(extra: Map<String, Any?> = emptyMap(), stages: Map<String, Any?>? = mapOf(
        "start" to "entry_court", "list" to mapOf("entry_court" to emptyMap<String, Any?>(), "lantern_court" to emptyMap<String, Any?>()),
    )) = DungeonDefinitionParser.parse(
        "demo-patrol",
        buildMap {
            put("world", "w")
            put("spawn-offset-x", 1.5)
            put("spawn-offset-y", 1.0)
            put("spawn-offset-z", 0.5)
            put("instance-inventory", mapOf("enabled" to true, "loadout" to emptyList<Any>()))
            if (stages != null) put("stages", stages)
            putAll(extra)
        },
    )

    @Test
    fun `dungeons yml 對帳通過與失敗`() {
        val spec = ContentFixtures.parse()
        val entry = ContentFixtures.markers().first { it.id == "entry" }
        val ok = ContentValidator.validateDungeon(spec, def(), entry)
        assertTrue(ok.isEmpty(), ok.toString())
        // 落點沒對上 player_spawn
        assertTrue(ContentValidator.validateDungeon(spec, def(mapOf("spawn-offset-x" to 9.5)), entry).any { "player_spawn" in it })
        // stage 圖不一致
        assertTrue(ContentValidator.validateDungeon(spec, def(stages = mapOf("start" to "entry_court", "list" to mapOf("entry_court" to emptyMap<String, Any?>()))), entry).any { "lantern_court" in it })
        assertTrue(ContentValidator.validateDungeon(spec, def(stages = null), entry).any { "stages.start/list" in it })
        // 有 tool 卻沒開局內背包
        assertTrue(ContentValidator.validateDungeon(spec, def(mapOf("instance-inventory" to mapOf("enabled" to false))), entry).any { "instance-inventory" in it })
    }

    @Test
    fun `文案 key 不存在被擋`() {
        val spec = ContentFixtures.parse()
        val known = setOf("demo.title", "tool.name", "tool.lore")
        assertTrue(ContentValidator.validateTexts(spec) { it in known }.isEmpty())
        val missing = ContentValidator.validateTexts(spec) { it != "tool.name" }
        assertTrue(missing.any { "tools.night_lantern.name" in it && "tool.name" in it })
    }

    @Test
    fun `人數對照表取小於等於人數的最大 key`() {
        val counts = mapOf(1 to 3, 2 to 4, 4 to 5)
        assertEquals(3, pickByPlayers(counts, 1, 0))
        assertEquals(4, pickByPlayers(counts, 3, 0))
        assertEquals(5, pickByPlayers(counts, 9, 0))
        assertEquals(7, pickByPlayers(mapOf(2 to 1), 1, 7))
        assertNotNull(ContentIds.RULE)
        assertFalse(ContentIds.valid("a.b"))
        assertTrue(ContentIds.valid("wave1_1-x"))
    }
}
