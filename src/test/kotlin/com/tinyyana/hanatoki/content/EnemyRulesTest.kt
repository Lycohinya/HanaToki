package com.tinyyana.hanatoki.content

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.21 敵人規則 seeks-object／harasses-players、燈籠被啃(drain)、encounter marker-offset 的資料契約與純邏輯。 */
class EnemyRulesTest {

    // ---- LanternMachine.drain ----

    @Test
    fun `被啃進度可退、夾在 0、不退回 unlit`() {
        val lantern = LanternMachine(100.0)
        lantern.ignite()
        lantern.advance(30, 1.0)
        assertEquals(10.0, lantern.drain(10.0), 1e-9)
        assertEquals(0.2, lantern.fraction, 1e-9)
        assertEquals(20.0, lantern.drain(500.0), 1e-9, "最多扣到 0")
        assertEquals(0.0, lantern.fraction, 1e-9)
        assertEquals(LanternMachine.State.BURNING, lantern.state, "進度 0 也不回 unlit")
        assertEquals(0.0, lantern.drain(5.0), 1e-9)
        assertFalse(lantern.ignite(), "不回 unlit，所以不能重複點燃")
    }

    @Test
    fun `沒點燃的燈和已經亮的燈不能被啃，被啃也不會讓燈完成`() {
        val unlit = LanternMachine(100.0)
        assertEquals(0.0, unlit.drain(10.0))
        assertEquals(LanternMachine.State.UNLIT, unlit.state)
        val lit = LanternMachine(100.0)
        lit.ignite()
        assertEquals(LanternMachine.Outcome.COMPLETED, lit.advance(200, 1.0))
        assertEquals(0.0, lit.drain(50.0))
        assertEquals(1.0, lit.fraction, 1e-9)
        val burning = LanternMachine(100.0)
        burning.ignite()
        burning.drain(10.0)
        assertEquals(LanternMachine.State.BURNING, burning.state)
    }

    @Test
    fun `暫停中也會被啃，恢復後從被啃剩下的繼續，完成仍只一次`() {
        val lantern = LanternMachine(100.0)
        lantern.ignite()
        lantern.advance(60, 1.0)
        assertEquals(LanternMachine.Outcome.PAUSED, lantern.advance(2, 0.0))
        assertTrue(lantern.paused)
        lantern.drain(40.0)
        assertEquals(0.2, lantern.fraction, 1e-9, "暫停中照樣被啃")
        assertEquals(LanternMachine.Outcome.RESUMED, lantern.advance(10, 1.0))
        assertEquals(0.3, lantern.fraction, 1e-9)
        var completed = 0
        repeat(10) {
            if (lantern.advance(20, 1.0) == LanternMachine.Outcome.COMPLETED) completed++
        }
        assertEquals(1, completed)
        assertEquals(0.0, lantern.drain(30.0), "亮了就不再扣")
    }

    @Test
    fun `沒有人啃時前進行為與舊版一致`() {
        val lantern = LanternMachine(100.0)
        lantern.ignite()
        assertEquals(0.0, lantern.drain(drainTicks(0.0, 2)))
        lantern.advance(40, 1.0)
        assertEquals(0.4, lantern.fraction, 1e-9)
    }

    // ---- 計數與速率 ----

    @Test
    fun `啃燈速率各組各自套上限`() {
        assertEquals(0.0, drainRate(emptyList()))
        assertEquals(0.9, drainRate(listOf(DrainGroup(2, 3, 0.45))), 1e-9)
        assertEquals(1.35, drainRate(listOf(DrainGroup(5, 3, 0.45))), 1e-9, "超過 max-drainers 的不算")
        assertEquals(1.35 + 0.2, drainRate(listOf(DrainGroup(5, 3, 0.45), DrainGroup(1, 2, 0.2))), 1e-9)
        assertEquals(0.0, drainRate(listOf(DrainGroup(-1, 3, 0.45))), 1e-9)
    }

    @Test
    fun `一個 tick 的進度損失等於速率，一個 burn-second 等於 20 tick`() {
        // 每隻每秒 0.5 burn-second：1 秒 = 20 tick，損失 10 個燃燒 tick（= 0.5 個 burn-second）
        assertEquals(10.0, drainTicks(0.5, 20), 1e-9)
        assertEquals(0.0, drainTicks(0.5, 0))
        assertEquals(0.0, drainTicks(-1.0, 20))
        // 淨進度：1 人倍率 1.0，三隻 0.45 => 20 tick 內 +20 -27 = 淨退，站樁完成不了
        val lantern = LanternMachine(300.0)
        lantern.ignite()
        lantern.advance(100, 1.0)
        repeat(10) {
            lantern.drain(drainTicks(drainRate(listOf(DrainGroup(3, 3, 0.45))), 2))
            lantern.advance(2, 1.0)
        }
        assertTrue(lantern.fraction < 100.0 / 300.0, "三隻在啃時淨進度往下：${lantern.fraction}")
    }

    @Test
    fun `僵直與打散中的敵人由呼叫端排除，一隻都沒有時速率為 0`() {
        val alive = listOf(DrainGroup(0, 3, 0.45))
        assertEquals(0.0, drainRate(alive))
    }

    // ---- 碰到玩家 ----

    @Test
    fun `碰到玩家：水平距離與高度差都要在範圍內`() {
        assertTrue(withinContact(1.0, 0.5, 1.0, 1.8))
        assertFalse(withinContact(1.5, 0.0, 1.5, 1.8), "水平 2.12 > 1.8")
        assertFalse(withinContact(0.5, 3.0, 0.0, 1.8), "不同樓層不算")
        assertTrue(withinContact(0.0, -1.9, 0.0, 1.8))
    }

    @Test
    fun `碰到的冷卻每位玩家各算，幾隻同時貼上來也只吃一次`() {
        val cd = TouchCooldowns()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertTrue(cd.tryTouch(a, 100, 50))
        assertFalse(cd.tryTouch(a, 110, 50), "另一隻 10 tick 後也碰到同一人：冷卻中")
        assertTrue(cd.tryTouch(b, 110, 50), "別人不受影響")
        assertFalse(cd.tryTouch(a, 149, 50))
        assertTrue(cd.tryTouch(a, 150, 50))
        cd.forget(a)
        assertTrue(cd.tryTouch(a, 151, 50), "離場後重新計")
        cd.clear()
        assertTrue(cd.tryTouch(b, 152, 50))
    }

    @Test
    fun `推開方向遠離敵人，正好重疊時不挑隨機方向`() {
        val v = pushAway(0.0, 0.0, 3.0, 4.0, 0.5)
        assertEquals(0.3, v[0], 1e-9)
        assertEquals(0.4, v[1], 1e-9)
        val still = pushAway(2.0, 2.0, 2.0, 2.0, 0.5)
        assertEquals(0.0, still[0])
        assertEquals(0.0, still[1])
    }

    // ---- 解析 ----

    private val enemies = """
        enemies:
          gnawer:
            base-entity: zombie
            model: mist_ghost
            rules: [needs-flash, seeks-object]
            health: 6
            speed: 0.14
            damage: 0
            needs-flash: { whiff-cue: whiff, stun-cue: ghost_stun, dissolve-cue: ghost_dissolve, refill-cue: page_refill }
            seeks-object:
              object: lantern_a
              contest-radius: 2.5
              drain-per-second: 0.45
              max-drainers: 3
              ignores-players: true
              contest-cues: [lantern_gnawed]
          gust:
            base-entity: zombie
            rules: [needs-flash, harasses-players]
            health: 6
            needs-flash: { whiff-cue: whiff, stun-cue: ghost_stun, dissolve-cue: ghost_dissolve, refill-cue: page_refill }
            harasses-players:
              contact-radius: 1.8
              cooldown-ticks: 50
              scan-ticks: 8
              cues: [ghost_gust]
              effects:
                - { type: push, strength: 0.45, lift: 0.2 }
                - { type: tool-charges, tool: night_lantern, delta: -1 }
                - { type: potion, effect: "minecraft:slowness", duration-ticks: 40 }
                - { type: damage, amount: 1.0 }
    """.trimIndent()

    private fun withEnemies(extraEnc: String = "", enemiesText: String = enemies): Map<String, String?> {
        val enc = ContentFixtures.FILES.getValue("encounters.yml") + extraEnc
        return mapOf("enemies.yml" to enemiesText, "encounters.yml" to enc.replace("enemy: small_ghost", "enemy: gnawer"))
    }

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    @Test
    fun `兩個規則的欄位解析，預設值正確`() {
        val spec = ContentFixtures.parse(withEnemies())
        val seek = assertNotNull(spec.enemies.getValue("gnawer").seeksObject)
        assertEquals("lantern_a", seek.objectId)
        assertEquals(setOf("burning"), seek.states, "states 預設 burning")
        assertEquals(10, seek.repathTicks)
        assertEquals(1.0, seek.speed)
        assertEquals(2.5, seek.contestRadius)
        assertEquals(0.45, seek.drainPerSecond)
        assertEquals(3, seek.maxDrainers)
        assertTrue(seek.ignoresPlayers)
        assertEquals(listOf("lantern_gnawed"), seek.contestCues)
        assertEquals(1.0, seek.contestCueEverySeconds)
        assertNull(spec.enemies.getValue("gnawer").harassesPlayers)
        val harass = assertNotNull(spec.enemies.getValue("gust").harassesPlayers)
        assertEquals(1.8, harass.contactRadius)
        assertEquals(50, harass.cooldownTicks)
        assertEquals(8, harass.scanTicks)
        assertEquals(
            listOf(
                HarassEffect.Push(0.45, 0.2),
                HarassEffect.ToolCharges("night_lantern", -1),
                HarassEffect.Potion("minecraft:slowness", 40, 0),
                HarassEffect.Damage(1.0, 4.0),
            ),
            harass.effects,
        )
        assertNull(spec.enemies.getValue("gust").seeksObject)
        assertTrue(ContentValidator.validateReferences(spec).isEmpty(), ContentValidator.validateReferences(spec).toString())
    }

    @Test
    fun `舊資料零影響：沒有標籤的敵人兩個規則都是 null，marker-offset 預設 0`() {
        val spec = ContentFixtures.parse()
        val ghost = spec.enemies.getValue("small_ghost")
        assertNull(ghost.seeksObject)
        assertNull(ghost.harassesPlayers)
        assertTrue(spec.encounters.values.all { it.markerOffset == 0 })
    }

    @Test
    fun `標籤與區塊必須成對`() {
        val noBlock = enemies.substring(0, enemies.indexOf("    seeks-object:")) + enemies.substring(enemies.indexOf("  gust:"))
        assertTrue(errorsOf(withEnemies(enemiesText = noBlock)).any { "enemies.gnawer.seeks-object" in it && "需要這個區塊" in it })
        val noTag = enemies.replace("rules: [needs-flash, seeks-object]", "rules: [needs-flash]")
        assertTrue(errorsOf(withEnemies(enemiesText = noTag)).any { "enemies.gnawer.seeks-object" in it && "沒有 seeks-object 規則標籤" in it })
        val noTag2 = enemies.replace("rules: [needs-flash, harasses-players]", "rules: [needs-flash]")
        assertTrue(errorsOf(withEnemies(enemiesText = noTag2)).any { "enemies.gust.harasses-players" in it && "沒有 harasses-players 規則標籤" in it })
    }

    @Test
    fun `壞資料被擋並指名檔名和欄位`() {
        fun bad(from: String, to: String): List<String> = errorsOf(withEnemies(enemiesText = enemies.replace(from, to)))
        // rc.35:drain-per-second 0 合法(只移動不啃,影響交給燈籠的 interference),負數仍被擋
        assertTrue(bad("drain-per-second: 0.45", "drain-per-second: -1").any { "drain-per-second" in it })
        assertTrue(bad("contest-radius: 2.5", "contest-radius: 99").any { "contest-radius" in it })
        assertTrue(bad("max-drainers: 3", "max-drainers: 0").any { "max-drainers" in it })
        assertTrue(bad("max-drainers: 3", "max-drainers: 3\n      repath-ticks: 1").any { "repath-ticks" in it })
        assertTrue(bad("contest-radius: 2.5", "contest-radius: 2.5\n      speed: 9").any { "seeks-object.speed" in it })
        assertTrue(bad("ignores-players: true", "ignores-players: maybe").any { "ignores-players" in it })
        assertTrue(bad("contest-radius: 2.5", "contest-radius: 2.5\n      typo: 1").any { "typo" in it && "未知的欄位" in it })
        assertTrue(bad("contact-radius: 1.8", "contact-radius: 0").any { "harasses-players.contact-radius" in it })
        assertTrue(bad("cooldown-ticks: 50", "cooldown-ticks: -5").any { "cooldown-ticks" in it })
        assertTrue(bad("scan-ticks: 8", "scan-ticks: 500").any { "scan-ticks" in it })
        assertTrue(bad("type: push", "type: explode").any { "未知的效果" in it })
        assertTrue(bad("strength: 0.45", "strength: 9").any { "strength" in it })
        assertTrue(bad("effect: \"minecraft:slowness\"", "effect: slowness").any { "namespaced key" in it })
        assertTrue(bad("delta: -1", "delta: 0.5").any { "delta" in it })
        assertTrue(errorsOf(withEnemies(enemiesText = enemies.replace(Regex("(?s)effects:.*"), "effects: []"))).any { "至少要有一個效果" in it })
    }

    @Test
    fun `seeks-object 的物件、狀態與 tool-charges 的道具引用被擋`() {
        fun refs(from: String, to: String): List<String> =
            ContentValidator.validateReferences(ContentFixtures.parse(withEnemies(enemiesText = enemies.replace(from, to))))
        assertTrue(refs("object: lantern_a", "object: lantern_z").any { "seeks-object.object" in it && "lantern_z" in it })
        assertTrue(refs("object: lantern_a", "object: gate_corridor").any { "不是 lantern" in it })
        assertTrue(refs("contest-radius: 2.5", "contest-radius: 2.5\n      states: [exploded]").any { "沒有狀態" in it })
        assertTrue(refs("tool: night_lantern", "tool: ghost_lamp").any { "effects[1].tool" in it && "ghost_lamp" in it })
    }

    @Test
    fun `encounter 的 marker-offset 解析與範圍`() {
        val enc = ContentFixtures.FILES.getValue("encounters.yml")
        val ok = ContentFixtures.parse(mapOf("encounters.yml" to enc.replace("interval-ticks: 20", "interval-ticks: 20\n    marker-offset: 2")))
        assertEquals(2, ok.encounters.getValue("wave1").markerOffset)
        assertEquals(0, ok.encounters.getValue("intro").markerOffset)
        val bad = errorsOf(mapOf("encounters.yml" to enc.replace("interval-ticks: 20", "interval-ticks: 20\n    marker-offset: -1")))
        assertTrue(bad.any { "encounters.wave1.marker-offset" in it })
    }

    @Test
    fun `suppress-melee 預設 false，寫了才開`() {
        val spec = ContentFixtures.parse(withEnemies())
        assertFalse(spec.enemies.getValue("gust").harassesPlayers!!.suppressMelee)
        val on = ContentFixtures.parse(withEnemies(enemiesText = enemies.replace("scan-ticks: 8", "scan-ticks: 8\n      suppress-melee: true")))
        assertTrue(on.enemies.getValue("gust").harassesPlayers!!.suppressMelee)
        assertTrue(errorsOf(withEnemies(enemiesText = enemies.replace("scan-ticks: 8", "scan-ticks: 8\n      suppress-melee: 1"))).any { "suppress-melee" in it })
    }

    @Test
    fun `lantern-contested 提示解析，沒有 seeks-object 的敵人時被擋`() {
        val hint = "\n  hint_gnaw:\n    trigger: { type: lantern-contested, seconds: 8 }\n    stage: lantern_court\n    cues: [hint_gnaw]\n"
        val hints = ContentFixtures.FILES.getValue("hints.yml") + hint
        val ok = ContentFixtures.parse(withEnemies() + mapOf("hints.yml" to hints))
        assertEquals(HintTrigger.LanternContested(8.0), ok.hints.getValue("hint_gnaw").trigger)
        assertTrue(ContentValidator.validateReferences(ok).isEmpty(), ContentValidator.validateReferences(ok).toString())
        val noSeeker = ContentFixtures.parse(mapOf("hints.yml" to hints))
        assertTrue(ContentValidator.validateReferences(noSeeker).any { "lantern-contested" in it && "seeks-object" in it })
        assertTrue(errorsOf(mapOf("hints.yml" to hints.replace("seconds: 8", "seconds: 0"))).any { "seconds" in it })
        assertTrue(errorsOf(mapOf("hints.yml" to hints.replace("lantern-contested", "lantern-gnawed"))).any { "未知的提示觸發" in it })
    }

    @Test
    fun `已實作的規則標籤包含新的兩個，不再有警告`() {
        assertTrue(EnemyRules.SEEKS_OBJECT in EnemyRules.IMPLEMENTED && EnemyRules.HARASSES_PLAYERS in EnemyRules.IMPLEMENTED)
        val spec = ContentFixtures.parse(withEnemies())
        assertTrue(ContentValidator.warnings(spec).none { "seeks-object" in it || "harasses-players" in it })
    }
}
