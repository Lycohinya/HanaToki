package com.tinyyana.hanatoki.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** rc.23:bosses.yml 的資料契約——解析預設值、拒絕壞資料（指名檔名＋ID＋欄位）、跨檔參照與場景核對。 */
class BossDataTest {

    @Test
    fun `完整範例解析成功，欄位與預設值對得上`() {
        val spec = BossFixtures.parse()
        val boss = spec.bosses.getValue("nun")
        assertEquals("front_court", boss.stage)
        assertEquals("ZOMBIE", boss.baseEntity)
        assertEquals("lamp_nun", boss.model)
        assertEquals(40.0, boss.health)
        assertEquals(1.6, boss.byPlayers.getValue(2).health)
        assertEquals(0.8, boss.byPlayers.getValue(3).damage)
        assertEquals(listOf(1.0, 0.55, 0.25), boss.phases.map { it.at })
        assertEquals("boss.p1", boss.phases[0].onEnter.music)
        assertEquals(listOf("phase_2"), boss.phases[1].onEnter.cues)
        assertEquals(7, boss.moves.size)
        val sweep = boss.moves.getValue("sweep")
        assertIs<MoveShape.Ring>(sweep.shape)
        assertEquals(9.0, (sweep.shape as MoveShape.Ring).maxRadius)
        assertEquals(2.0, (sweep.shape as MoveShape.Ring).bandWidth) // 預設
        assertEquals(0.25, sweep.damagePercent)
        assertEquals(0.5, sweep.push?.strength)
        assertEquals(1, sweep.fromPhase)
        assertEquals(2, boss.moves.getValue("cross").fromPhase)
        assertEquals(3, boss.moves.getValue("spin").fromPhase)
        assertIs<MoveShape.Snuff>(boss.moves.getValue("snuff").shape)
        assertEquals(0.0, boss.moves.getValue("snuff").damagePercent)
        val summon = boss.moves.getValue("summon").shape as MoveShape.Summon
        assertEquals("mist", summon.enemy)
        assertEquals(3, summon.maxAlive)
        assertEquals(listOf("boss_lamp_1", "boss_lamp_2", "boss_lamp_3", "boss_lamp_4"), boss.lampIds)
        assertEquals("gem_1", boss.gems[0].bone)
        assertEquals("halo", boss.shield.haloBone)
        assertEquals(10.0, boss.kneel.seconds)
        assertEquals(LampReset.ON_RISE, boss.kneel.lampsReset)
        assertEquals(3, boss.pumpkins?.fromPhase)
        // 預設值
        assertEquals("ADVENTURE", boss.downed.gameMode)
        assertEquals(-0.8, boss.downed.speedModifier)
        assertTrue(boss.downed.invulnerable)
        assertEquals(DownedMove.NEAREST_REVIVE, boss.downed.move)
        assertEquals(WipeHealth.FULL, boss.wipe.health)
        assertEquals(WipePhase.FIRST, boss.wipe.phase)
        assertEquals("front_door", boss.wipe.checkpoint)
        assertEquals(1.0, boss.hitWeight)
        assertEquals(1.0, boss.poweredHitWeight)
        assertEquals(0.0, boss.minAttackStrength, "預設不限：Paper 在傷害事件前就重置蓄力，事件裡讀到的蓄力幾乎是 0")
        // fixture 把冷卻設成 0；沒寫時的預設是 12 tick
        val noCooldown = BossFixtures.parse(mapOf("bosses.yml" to BossFixtures.NUN.replace("hit-cooldown-ticks: 0", "gap-seconds: 2.5")))
        assertEquals(12, noCooldown.bosses.getValue("nun").hitCooldownTicks)
        assertEquals("boss.bar", boss.bar?.textKey)
        assertEquals("kneel", boss.animations.kneel)
        assertEquals("sit_end", boss.animations.sitEnd)
    }

    @Test
    fun `stage 完成條件 boss-defeated 解析成功，並能引用到 boss`() {
        val spec = BossFixtures.parse()
        val cond = spec.stage("front_court")!!.complete.single()
        assertEquals(Condition.BossDefeated("nun"), cond)
        assertTrue(ContentValidator.validateReferences(spec).isEmpty(), ContentValidator.validateReferences(spec).toString())
    }

    @Test
    fun `整份範例通過跨檔參照、場景與文案核對`() {
        val spec = BossFixtures.parse()
        assertEquals(emptyList(), ContentValidator.validateReferences(spec))
        assertEquals(emptyList(), ContentValidator.validateScene(spec, BossFixtures.markers()))
        assertEquals(emptyList(), ContentValidator.validateTexts(spec) { it in BossFixtures.TEXT_KEYS })
    }

    @Test
    fun `沒有 bosses 檔的內容完全不受影響`() {
        val spec = BossFixtures.parse(mapOf("bosses.yml" to null, "stages.yml" to BossFixtures.FILES.getValue("stages.yml").replace("{ type: boss-defeated, boss: nun }", "{ type: object-state, object: boss_lamp_1, state: lit }")))
        assertTrue(spec.bosses.isEmpty())
    }

    @Test
    fun `boss-defeated 引用不存在的 boss 被擋`() {
        val spec = BossFixtures.parse(mapOf("bosses.yml" to null))
        val errors = ContentValidator.validateReferences(spec)
        assertTrue(errors.any { "stages.front_court.complete[0].boss" in it && "bosses.yml" in it }, errors.toString())
    }

    @Test
    fun `拼錯的欄位被擋，指名 boss 與欄位`() {
        val errors = BossFixtures.bossErrors("hit-cooldown-ticks: 0" to "hit-cooldown-tick: 0")
        assertTrue(errors.any { it.startsWith("bosses.yml bosses.nun.hit-cooldown-tick") && "未知的欄位" in it }, errors.toString())
    }

    @Test
    fun `階段門檻：第一個必須 1_0 且嚴格遞減`() {
        val first = BossFixtures.bossErrors("- { at: 1.0, music: boss.p1 }" to "- { at: 0.9, music: boss.p1 }")
        assertTrue(first.any { "bosses.nun.phases" in it && "1.0" in it }, first.toString())
        val order = BossFixtures.bossErrors("- { at: 0.25, cues: [phase_3] }" to "- { at: 0.6, cues: [phase_3] }")
        assertTrue(order.any { "bosses.nun.phases" in it && "由大到小" in it }, order.toString())
    }

    @Test
    fun `招式的 phase 不可超過階段數`() {
        val errors = BossFixtures.bossErrors("spin: { shape: spin, phase: 3," to "spin: { shape: spin, phase: 4,")
        assertTrue(errors.any { "bosses.nun.moves.spin.phase" in it && "不可大於階段數" in it }, errors.toString())
    }

    @Test
    fun `未知的招式形狀被擋`() {
        val errors = BossFixtures.bossErrors("shape: circle" to "shape: triangle")
        assertTrue(errors.any { "moves.strike.shape" in it && "triangle" in it }, errors.toString())
    }

    @Test
    fun `會傷人的招式必須寫 damage-percent，不傷人的招式不可以寫`() {
        val missing = BossFixtures.bossErrors("radius: 2.5, damage-percent: 0.3," to "radius: 2.5,")
        assertTrue(missing.any { "moves.strike.damage-percent" in it && "缺少必要欄位" in it }, missing.toString())
        val extra = BossFixtures.bossErrors("shape: snuff, weight: 2," to "shape: snuff, damage-percent: 0.2, weight: 2,")
        assertTrue(extra.any { "moves.snuff.damage-percent" in it && "未知的欄位" in it }, extra.toString())
    }

    @Test
    fun `全滅重來必須有檢查點 marker`() {
        val errors = BossFixtures.bossErrors("wipe: { checkpoint: front_door }" to "wipe: { health: full }")
        assertTrue(errors.any { "bosses.nun.wipe.checkpoint" in it && "缺少必要欄位" in it }, errors.toString())
    }

    @Test
    fun `召喚招式沒有生成點被擋`() {
        val errors = BossFixtures.bossErrors("markers: [mist_1, mist_2, mist_3]," to "")
        assertTrue(errors.any { "moves.summon.markers" in it }, errors.toString())
    }

    @Test
    fun `倒下的遊戲模式只接受 SURVIVAL ADVENTURE SPECTATOR`() {
        val errors = BossFixtures.bossErrors("downed: { revive-points" to "downed: { gamemode: creative, revive-points")
        assertTrue(errors.any { "bosses.nun.downed.gamemode" in it && "CREATIVE" in it }, errors.toString())
    }

    @Test
    fun `場邊燈不能有血量條、光源方塊、開門，也不能被兩個 boss 共用`() {
        val objects = BossFixtures.FILES.getValue("objects.yml")
        val bar = objects.replace("burn-seconds: 3", "burn-seconds: 3\n            progress-bar: { text: boss.bar }")
        var errors = ContentValidator.validateReferences(BossFixtures.parse(mapOf("objects.yml" to bar)))
        assertTrue(errors.any { "gems[0].lamp" in it && "progress-bar" in it }, errors.toString())
        val light = objects.replace("on-lit: { cues: [lamp_lit] }", "on-lit: { cues: [lamp_lit], light-radius: 8 }")
        errors = ContentValidator.validateReferences(BossFixtures.parse(mapOf("objects.yml" to light)))
        assertTrue(errors.any { "on-lit.light-radius" in it && "必須是 0" in it }, errors.toString())
    }

    @Test
    fun `gems 引用不是 lantern 的物件或不存在的物件被擋`() {
        var errors = ContentValidator.validateReferences(BossFixtures.parseBoss("lamp: boss_lamp_4" to "lamp: pumpkin_boss_1"))
        assertTrue(errors.any { "gems[3].lamp" in it && "不是 lantern" in it }, errors.toString())
        errors = ContentValidator.validateReferences(BossFixtures.parseBoss("lamp: boss_lamp_4" to "lamp: nope_lamp"))
        assertTrue(errors.any { "gems[3].lamp" in it && "nope_lamp" in it }, errors.toString())
    }

    @Test
    fun `pumpkins 必須是 power-up 物件`() {
        val errors = ContentValidator.validateReferences(BossFixtures.parseBoss("objects: [pumpkin_boss_1, pumpkin_boss_2]" to "objects: [pumpkin_boss_1, boss_lamp_1]"))
        assertTrue(errors.any { "pumpkins.objects[1]" in it && "不是 power-up" in it }, errors.toString())
    }

    @Test
    fun `召喚招式引用不存在的敵人被擋`() {
        val errors = ContentValidator.validateReferences(BossFixtures.parseBoss("enemy: mist," to "enemy: ghostly,"))
        assertTrue(errors.any { "moves.summon.enemy" in it && "ghostly" in it }, errors.toString())
    }

    @Test
    fun `一個 stage 只能有一個 boss`() {
        val twin = BossFixtures.NUN + "\n" + BossFixtures.NUN.lines().drop(1).joinToString("\n").replaceFirst("  nun:", "  nun2:")
        val spec = BossFixtures.parse(mapOf("bosses.yml" to twin))
        val errors = ContentValidator.validateReferences(spec)
        assertTrue(errors.any { "stage「front_court」有 2 個 boss" in it }, errors.toString())
    }

    @Test
    fun `場景缺 marker 時指名是哪一項`() {
        val spec = BossFixtures.parse()
        val missing = BossFixtures.markers().filter { it.id != "revive_2" && it.id != "front_door" }
        val errors = ContentValidator.validateScene(spec, missing)
        assertTrue(errors.any { "bosses.nun.downed.revive-points[1]" in it && "revive_2" in it }, errors.toString())
        assertTrue(errors.any { "bosses.nun.wipe.checkpoint" in it && "front_door" in it }, errors.toString())
    }

    @Test
    fun `缺少文案 key 被擋`() {
        val spec = BossFixtures.parse()
        val errors = ContentValidator.validateTexts(spec) { it == "boss.bar" }
        assertTrue(errors.any { "bosses.nun.downed.hud-text" in it }, errors.toString())
        assertTrue(errors.any { "bosses.nun.downed.title" in it }, errors.toString())
    }

    @Test
    fun `沒有任何 phase 1 的招式被擋`() {
        val spec = BossFixtures.parse(mapOf("bosses.yml" to BossFixtures.NUN.replace("{ shape: ring, weight: 3,", "{ shape: ring, phase: 2, weight: 3,")
            .replace("{ shape: circle, weight: 3,", "{ shape: circle, phase: 2, weight: 3,")
            .replace("{ shape: summon, weight: 2,", "{ shape: summon, phase: 2, weight: 2,")
            .replace("{ shape: snuff, weight: 2,", "{ shape: snuff, phase: 2, weight: 2,")))
        val errors = ContentValidator.validateReferences(spec)
        assertTrue(errors.any { "bosses.nun.moves" in it && "phase 1" in it }, errors.toString())
    }

    @Test
    fun `ContentFileNames 認得 bosses_yml`() {
        assertTrue("bosses.yml" in ContentFileNames.ALL)
        assertNotNull(ContentFileNames.OPTIONAL.firstOrNull { it == ContentFileNames.BOSSES })
        val ex = assertFailsWith<ContentDataException> { BossFixtures.parse(mapOf("bossez.yml" to "x: 1")) }
        assertTrue(ex.errors.any { "bossez.yml" in it && "未知的資料檔" in it })
    }
}
