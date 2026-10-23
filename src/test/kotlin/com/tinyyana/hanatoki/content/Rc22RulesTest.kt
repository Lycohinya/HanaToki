package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.map.MapAssetId
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MarkerKind
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.22:frozen-when-watched、chases-players、flees-when-powered、power-up 物件、最後一段結算開關的資料契約與純邏輯。 */
class Rc22RulesTest {

    private val enemies = """
        enemies:
          doll:
            base-entity: zombie
            model: doll
            rules: [needs-flash, frozen-when-watched]
            health: 10
            needs-flash: { whiff-cue: whiff, stun-cue: ghost_stun, dissolve-cue: ghost_dissolve, refill-cue: page_refill }
            frozen-when-watched:
              half-angle: 60
              max-distance: 20
              scan-ticks: 4
              speed: 0.9
              moving-animation: walk
              poses: [tilt_head, reach]
              move-cues: [doll_click]
              freeze-cues: [doll_stop]
              cue-cooldown-ticks: 8
          big_ghost:
            base-entity: zombie
            rules: [chases-players, flees-when-powered]
            health: 20
            chases-players:
              speed: 1.1
              target: lowest-health
              sight-range: 20
              give-up-range: 30
              repath-ticks: 8
              contact:
                contact-radius: 1.6
                cooldown-ticks: 40
                suppress-melee: true
                effects:
                  - { type: push, strength: 0.6, lift: 0.3 }
                  - { type: damage, amount: 2.0, min-health: 6 }
            flees-when-powered:
              speed: 1.4
              flee-distance: 14
              touch-radius: 2
              animation: scared
              powered-cue: ghost_scared
              dissolve-cue: ghost_pop
          small_ghost:
            base-entity: zombie
            rules: [needs-flash]
            health: 6
            needs-flash: { whiff-cue: whiff, stun-cue: ghost_stun, dissolve-cue: ghost_dissolve, refill-cue: page_refill }
    """.trimIndent()

    private val powerUp = listOf(
        "  pumpkin_1:",
        "    type: power-up",
        "    marker: pumpkin_1",
        "    radius: 1.5",
        "    seconds: 8",
        "    respawn-seconds: 30",
        "    start-cues: [powered_music]",
        "    end-cues: [powered_end]",
        "    states:",
        "      taken: { blocks: [ { offset: [0, 0, 0], block: \"minecraft:air\" } ] }",
        "      ready: { blocks: [ { offset: [0, 0, 0], block: \"minecraft:jack_o_lantern\" } ] }",
    ).joinToString("\n")

    private fun objectsWithPowerUp(): String = ContentFixtures.FILES.getValue("objects.yml") + "\n" + powerUp

    private fun withRules(enemiesText: String = enemies, objectsText: String = objectsWithPowerUp()): Map<String, String?> =
        mapOf("enemies.yml" to enemiesText, "objects.yml" to objectsText)

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { ContentFixtures.parse(overrides) }.errors

    private fun markersWithPumpkin(kind: MarkerKind = MarkerKind.TRIGGER): List<MapMarker> =
        ContentFixtures.markers() + MapMarker(kind, MapAssetId("demo", "x", "0123456789abcdef"), 40, 1, 0, null, mapOf("id" to "pumpkin_1"))

    // ---- 解析 ----

    @Test
    fun `三個新規則與 power-up 物件解析成功，預設值與覆寫都對`() {
        val spec = ContentFixtures.parse(withRules())
        val doll = spec.enemies.getValue("doll")
        val frozen = assertNotNull(doll.frozenWhenWatched)
        assertEquals(60.0, frozen.halfAngle)
        assertEquals(20.0, frozen.maxDistance)
        assertTrue(frozen.occlusion, "occlusion 預設開")
        assertEquals(4, frozen.scanTicks)
        assertEquals(0.9, frozen.speed)
        assertEquals(1.0, frozen.bodyHeight)
        assertEquals(listOf(PoseSpec("tilt_head", "tilt_head"), PoseSpec("reach", "reach")), frozen.poses)
        assertEquals("walk", frozen.movingAnimation)
        assertEquals(listOf("doll_click"), frozen.moveCues)
        assertEquals(listOf("doll_stop"), frozen.freezeCues)
        assertEquals(8, frozen.cueCooldownTicks)
        assertTrue(doll.immuneToDamage && doll.frameworkMoves && doll.scanned && doll.needsSnapshots)

        val ghost = spec.enemies.getValue("big_ghost")
        val chase = assertNotNull(ghost.chasesPlayers)
        assertEquals(1.1, chase.speed)
        assertEquals(ChaseTarget.LOWEST_HEALTH, chase.target)
        assertEquals(20.0, chase.sightRange)
        assertEquals(30.0, chase.giveUpRange)
        assertEquals(8, chase.repathTicks)
        val contact = assertNotNull(chase.contact)
        assertEquals(1.6, contact.contactRadius)
        assertTrue(contact.suppressMelee)
        assertEquals(listOf(HarassEffect.Push(0.6, 0.3), HarassEffect.Damage(2.0, 6.0)), contact.effects)
        assertEquals(contact, ghost.contact, "contact 取自 chases-players 內嵌的接觸效果")
        val flee = assertNotNull(ghost.fleesWhenPowered)
        assertEquals(1.4, flee.speed)
        assertEquals(14.0, flee.fleeDistance)
        assertEquals(2.0, flee.touchRadius)
        assertEquals(8, flee.repathTicks, "repath-ticks 預設 8")
        assertEquals("scared", flee.animation)
        assertEquals("ghost_scared", flee.poweredCue)
        assertEquals("ghost_pop", flee.dissolveCue)
        assertEquals(12, flee.dissolveTicks)

        val pump = assertIs<PowerUpSpec>(spec.objects.getValue("pumpkin_1"))
        assertEquals("pumpkin_1", pump.markerId)
        assertEquals(1.5, pump.radius)
        assertEquals(8.0, pump.seconds)
        assertTrue(pump.consume, "consume 預設 true")
        assertEquals(30.0, pump.respawnSeconds)
        assertEquals(listOf("powered_music"), pump.startCues)
        assertEquals(listOf("powered_end"), pump.endCues)
        assertEquals(setOf("ready", "taken"), pump.states.keys)
        assertTrue(ContentValidator.validateReferences(spec).isEmpty(), ContentValidator.validateReferences(spec).toString())
        assertTrue(ContentValidator.validateScene(spec, markersWithPumpkin()).isEmpty(), ContentValidator.validateScene(spec, markersWithPumpkin()).toString())
        assertTrue(ContentValidator.warnings(spec).none { "保留" in it })
    }

    @Test
    fun `區塊寫空的就用預設值，人偶預設四種姿勢`() {
        val text = """
            enemies:
              doll:
                base-entity: zombie
                rules: [frozen-when-watched]
                health: 10
                frozen-when-watched: {}
              ghost:
                base-entity: zombie
                rules: [chases-players]
                health: 10
                chases-players: {}
        """.trimIndent()
        val spec = ContentFixtures.parse(mapOf("enemies.yml" to text))
        val f = spec.enemies.getValue("doll").frozenWhenWatched!!
        assertEquals(listOf("tilt_head", "reach", "hug_knees", "look_back"), f.poses.map { it.id })
        assertEquals(55.0, f.halfAngle)
        assertEquals(24.0, f.maxDistance)
        assertEquals(1.0, f.speed)
        assertEquals(6, f.cueCooldownTicks)
        assertNull(f.movingAnimation)
        assertFalse(spec.enemies.getValue("doll").needsFlash != null)
        val c = spec.enemies.getValue("ghost").chasesPlayers!!
        assertEquals(ChaseTarget.NEAREST, c.target)
        assertEquals(32.0, c.sightRange)
        assertEquals(48.0, c.giveUpRange, 1e-9)
        assertEquals(1.0, c.speed)
        assertEquals(10, c.repathTicks)
        assertNull(c.contact)
        assertNull(spec.enemies.getValue("ghost").contact)
    }

    @Test
    fun `姿勢可以寫成 id 對動畫名的區塊`() {
        val text = enemies.replace("poses: [tilt_head, reach]", "poses: { tilt: pose_tilt, stretch: pose_reach }")
        val f = ContentFixtures.parse(withRules(text)).enemies.getValue("doll").frozenWhenWatched!!
        assertEquals(listOf(PoseSpec("tilt", "pose_tilt"), PoseSpec("stretch", "pose_reach")), f.poses)
    }

    @Test
    fun `舊資料零影響：沒有新標籤的敵人新欄位全是 null，不吃一般傷害的只有新規則與 needs-flash`() {
        val spec = ContentFixtures.parse()
        val ghost = spec.enemies.getValue("small_ghost")
        assertNull(ghost.frozenWhenWatched)
        assertNull(ghost.chasesPlayers)
        assertNull(ghost.fleesWhenPowered)
        assertTrue(spec.stages.all { it.resolve == null && !it.clearsEnemies })
        assertTrue(spec.objects.values.none { it is PowerUpSpec })
        val plain = """
            enemies:
              mob:
                base-entity: zombie
                rules: []
                health: 4
        """.trimIndent()
        val mob = ContentFixtures.parse(mapOf("enemies.yml" to plain)).enemies.getValue("mob")
        assertFalse(mob.immuneToDamage, "沒有任何規則的怪還是一般會被打死的怪")
        assertFalse(mob.scanned)
    }

    @Test
    fun `新規則的標籤與區塊必須成對`() {
        val noBlock = enemies.replace(Regex("(?s)    frozen-when-watched:.*?  big_ghost:"), "  big_ghost:")
        assertTrue(errorsOf(withRules(noBlock)).any { "enemies.doll.frozen-when-watched" in it && "需要這個區塊" in it })
        val noTag = enemies.replace("rules: [needs-flash, frozen-when-watched]", "rules: [needs-flash]")
        assertTrue(errorsOf(withRules(noTag)).any { "enemies.doll.frozen-when-watched" in it && "沒有 frozen-when-watched 規則標籤" in it })
        val noChase = enemies.replace("rules: [chases-players, flees-when-powered]", "rules: [flees-when-powered]")
        val chaseErrors = errorsOf(withRules(noChase))
        assertTrue(chaseErrors.any { "enemies.big_ghost.chases-players" in it && "沒有 chases-players 規則標籤" in it }, chaseErrors.toString())
        assertTrue(chaseErrors.any { "flees-when-powered 必須和 chases-players" in it }, chaseErrors.toString())
        val noFlee = enemies.replace("rules: [chases-players, flees-when-powered]", "rules: [chases-players]")
        assertTrue(errorsOf(withRules(noFlee)).any { "enemies.big_ghost.flees-when-powered" in it && "沒有 flees-when-powered 規則標籤" in it })
    }

    @Test
    fun `壞資料被擋並指名檔名和欄位`() {
        fun bad(from: String, to: String): List<String> = errorsOf(withRules(enemies.replace(from, to)))
        assertTrue(bad("half-angle: 60", "half-angle: 120").any { "frozen-when-watched.half-angle" in it })
        assertTrue(bad("half-angle: 60", "half-angle: 1").any { "half-angle" in it })
        assertTrue(bad("max-distance: 20", "max-distance: 500").any { "max-distance" in it })
        assertTrue(bad("scan-ticks: 4", "scan-ticks: 1").any { "frozen-when-watched.scan-ticks" in it })
        assertTrue(bad("scan-ticks: 4", "scan-ticks: 4\n      typo: 1").any { "typo" in it && "未知的欄位" in it })
        assertTrue(bad("poses: [tilt_head, reach]", "poses: [reach, reach]").any { "姿勢 ID 重複" in it })
        assertTrue(bad("poses: [tilt_head, reach]", "poses: []").any { "至少要有一種姿勢" in it })
        assertTrue(bad("cue-cooldown-ticks: 8", "cue-cooldown-ticks: -1").any { "cue-cooldown-ticks" in it })
        assertTrue(bad("target: lowest-health", "target: strongest").any { "未知的目標策略" in it })
        assertTrue(bad("sight-range: 20", "sight-range: 1").any { "chases-players.sight-range" in it })
        assertTrue(bad("give-up-range: 30", "give-up-range: 10").any { "give-up-range" in it && "不可小於 sight-range" in it })
        assertTrue(bad("speed: 1.1", "speed: 9").any { "chases-players.speed" in it })
        assertTrue(bad("contact-radius: 1.6", "contact-radius: 0").any { "chases-players.contact.contact-radius" in it })
        assertTrue(bad("type: push", "type: explode").any { "未知的效果" in it })
        assertTrue(bad("flee-distance: 14", "flee-distance: 1").any { "flees-when-powered.flee-distance" in it })
        assertTrue(bad("touch-radius: 2", "touch-radius: 0").any { "touch-radius" in it })
    }

    @Test
    fun `移動規則每個敵人只能掛一個，接觸效果只能一組`() {
        val both = enemies.replace("rules: [needs-flash, frozen-when-watched]", "rules: [needs-flash, frozen-when-watched, chases-players]")
            .replace("    frozen-when-watched:\n", "    chases-players: {}\n    frozen-when-watched:\n")
        assertTrue(errorsOf(withRules(both)).any { "每個敵人只能掛一個" in it })
        val twoContacts = enemies.replace("rules: [chases-players, flees-when-powered]", "rules: [chases-players, flees-when-powered, harasses-players]")
            .replace("    flees-when-powered:\n", "    harasses-players:\n      contact-radius: 1.5\n      effects:\n        - { type: push, strength: 0.4 }\n    flees-when-powered:\n")
        assertTrue(errorsOf(withRules(twoContacts)).any { "chases-players" in it && "接觸效果只能一組" in it })
    }

    @Test
    fun `power-up 物件的欄位範圍與預設值`() {
        val base = objectsWithPowerUp()
        fun bad(from: String, to: String): List<String> = errorsOf(withRules(objectsText = base.replace(from, to)))
        assertTrue(bad("seconds: 8", "seconds: 0").any { "objects.pumpkin_1.seconds" in it })
        assertTrue(bad("seconds: 8", "seconds: 9999").any { "objects.pumpkin_1.seconds" in it })
        assertTrue(bad("radius: 1.5", "radius: 0.1").any { "objects.pumpkin_1.radius" in it })
        assertTrue(bad("respawn-seconds: 30", "respawn-seconds: -1").any { "respawn-seconds" in it })
        assertTrue(bad("    seconds: 8\n", "").any { "objects.pumpkin_1.seconds" in it && "缺少必要欄位" in it })
        assertTrue(bad("taken: {", "exploded: {").any { "未知的狀態" in it })
        assertTrue(bad("radius: 1.5", "radius: 1.5\n    typo: 1").any { "typo" in it && "未知的欄位" in it })
        assertTrue(bad("type: power-up", "type: pumpkin").any { "未知的物件類型" in it && "power-up" in it })
        val defaults = ContentFixtures.parse(
            withRules(objectsText = ContentFixtures.FILES.getValue("objects.yml") + "\n  p:\n    type: power-up\n    marker: pumpkin_1\n    seconds: 6\n"),
        ).objects.getValue("p") as PowerUpSpec
        assertEquals(1.5, defaults.radius)
        assertTrue(defaults.consume)
        assertEquals(0.0, defaults.respawnSeconds)
        assertTrue(defaults.startCues.isEmpty() && defaults.endCues.isEmpty())
    }

    @Test
    fun `power-up 的 marker 必須是 trigger，flees-when-powered 需要至少一個 power-up 物件`() {
        val spec = ContentFixtures.parse(withRules())
        assertTrue(ContentValidator.validateScene(spec, markersWithPumpkin(MarkerKind.MOB_SPAWN)).any { "objects.pumpkin_1.marker" in it && "power-up" in it && "trigger" in it })
        assertTrue(ContentValidator.validateScene(spec, ContentFixtures.markers()).any { "objects.pumpkin_1.marker" in it && "沒有 id=pumpkin_1" in it })
        val noPower = ContentFixtures.parse(mapOf("enemies.yml" to enemies))
        assertTrue(ContentValidator.validateReferences(noPower).any { "enemies.big_ghost.flees-when-powered" in it && "power-up" in it })
    }

    @Test
    fun `chases-players 的 contact 引用不存在的道具被擋`() {
        val text = enemies.replace("- { type: push, strength: 0.6, lift: 0.3 }", "- { type: tool-charges, tool: ghost_lamp, delta: -1 }")
        val refs = ContentValidator.validateReferences(ContentFixtures.parse(withRules(text)))
        assertTrue(refs.any { "chases-players.contact.effects[0].tool" in it && "ghost_lamp" in it }, refs.toString())
    }

    @Test
    fun `power-up 的狀態可以當 stage 完成條件，taken 與 ready 之外的狀態被擋`() {
        val stages = ContentFixtures.FILES.getValue("stages.yml")
            .replace("- { type: object-state, object: lantern_a, state: lit }", "- { type: object-state, object: pumpkin_1, state: taken }")
        val ok = ContentFixtures.parse(withRules() + mapOf("stages.yml" to stages))
        assertTrue(ContentValidator.validateReferences(ok).isEmpty(), ContentValidator.validateReferences(ok).toString())
        val bad = ContentFixtures.parse(withRules() + mapOf("stages.yml" to stages.replace("state: taken", "state: lit")))
        assertTrue(ContentValidator.validateReferences(bad).any { "pumpkin_1" in it && "沒有狀態" in it })
    }

    // ---- 最後一段結算開關 ----

    private val resolveBlock = "\n    resolve:\n      result: cleared\n      dialog-timeout-seconds: 30\n      epilogue:\n" +
        "        - { cue: ep_fade, wait-seconds: 2 }\n        - { dialog: ep_talk }\n        - { cue: ep_end, marker: entry, wait-seconds: 1.5 }"

    private fun stagesWith(extraOnLast: String): Map<String, String?> = mapOf(
        "stages.yml" to ContentFixtures.FILES.getValue("stages.yml").replace(
            "on-complete: { cues: [lantern_done] }", "on-complete: { cues: [lantern_done] }$extraOnLast",
        ),
    )

    @Test
    fun `resolve 區塊解析：尾聲依序、預設值、true 簡寫`() {
        val spec = ContentFixtures.parse(stagesWith(resolveBlock))
        val resolve = assertNotNull(spec.stage("lantern_court")!!.resolve)
        assertEquals("cleared", resolve.result)
        assertEquals(30.0, resolve.dialogTimeoutSeconds)
        assertEquals(
            listOf(
                EpilogueStep("ep_fade", null, 2.0, null),
                EpilogueStep(null, "ep_talk", 0.0, null),
                EpilogueStep("ep_end", null, 1.5, "entry"),
            ),
            resolve.epilogue,
        )
        assertNull(spec.stage("entry_court")!!.resolve, "沒寫的 stage 不受影響")
        assertTrue(ContentValidator.validateReferences(spec).isEmpty(), ContentValidator.validateReferences(spec).toString())
        assertTrue(ContentValidator.validateScene(spec, ContentFixtures.markers()).isEmpty(), ContentValidator.validateScene(spec, ContentFixtures.markers()).toString())

        val short = ContentFixtures.parse(stagesWith("\n    resolve: true")).stage("lantern_court")!!.resolve!!
        assertEquals("cleared", short.result)
        assertTrue(short.epilogue.isEmpty())
        assertEquals(120.0, short.dialogTimeoutSeconds)
        val custom = ContentFixtures.parse(stagesWith("\n    resolve: { result: mist-cleared }")).stage("lantern_court")!!.resolve!!
        assertEquals("mist-cleared", custom.result)
        assertNull(ContentFixtures.parse(stagesWith("\n    resolve: false")).stage("lantern_court")!!.resolve)
    }

    @Test
    fun `resolve 只能用在最後一段，必須有完成條件，壞尾聲被擋`() {
        // 有 next 的 stage 不能結算
        val withNext = ContentFixtures.FILES.getValue("stages.yml").replace("    next: lantern_court", "    next: lantern_court\n    resolve: true")
        assertTrue(errorsOf(mapOf("stages.yml" to withNext)).any { "stages.entry_court.resolve" in it && "只有最後一段" in it })
        // 沒有 complete 就永遠不會結算
        val noComplete = ContentFixtures.FILES.getValue("stages.yml").replace(
            "    complete:\n      - { type: object-state, object: lantern_a, state: lit }\n    on-complete: { cues: [lantern_done] }",
            "    on-complete: { cues: [lantern_done] }\n    resolve: true",
        )
        assertTrue(errorsOf(mapOf("stages.yml" to noComplete)).any { "stages.lantern_court.resolve" in it && "必須寫 complete" in it })
        fun bad(block: String): List<String> = errorsOf(stagesWith(block))
        assertTrue(bad("\n    resolve: 3").any { "stages.lantern_court.resolve" in it && "區塊" in it })
        assertTrue(bad("\n    resolve: { typo: 1 }").any { "typo" in it && "未知的欄位" in it })
        assertTrue(bad("\n    resolve: { result: bad.key }").any { "resolve.result" in it && "不合法" in it })
        assertTrue(bad("\n    resolve: { dialog-timeout-seconds: 0 }").any { "dialog-timeout-seconds" in it })
        assertTrue(bad("\n    resolve: { epilogue: [] }").any { "epilogue" in it && "不可為空" in it })
        assertTrue(bad("\n    resolve: { epilogue: [ { cue: a, dialog: b } ] }").any { "cue 或 dialog 其中一個" in it })
        assertTrue(bad("\n    resolve: { epilogue: [ {} ] }").any { "至少要有 cue、dialog 或 wait-seconds" in it })
        assertTrue(bad("\n    resolve: { epilogue: [ { dialog: b, marker: entry } ] }").any { "dialog 步驟沒有原點" in it })
        assertTrue(bad("\n    resolve: { epilogue: [ { cue: a, wait-seconds: -1 } ] }").any { "wait-seconds" in it })
    }

    @Test
    fun `尾聲的 marker 要在場景裡，clears-enemies 解析`() {
        val spec = ContentFixtures.parse(stagesWith("\n    clears-enemies: true\n    resolve: { epilogue: [ { cue: a, marker: nowhere } ] }"))
        assertTrue(spec.stage("lantern_court")!!.clearsEnemies)
        assertTrue(ContentValidator.validateScene(spec, ContentFixtures.markers()).any { "resolve.epilogue[0].marker" in it && "nowhere" in it })
        assertTrue(errorsOf(stagesWith("\n    clears-enemies: maybe")).any { "clears-enemies" in it })
    }

    // ---- 純邏輯 ----

    @Test
    fun `yaw 與 pitch 換成視線向量，符號正確`() {
        fun assertDir(yaw: Double, pitch: Double, x: Double, y: Double, z: Double) {
            val d = lookDirection(yaw, pitch)
            assertEquals(x, d[0], 1e-9, "x yaw=$yaw pitch=$pitch")
            assertEquals(y, d[1], 1e-9, "y yaw=$yaw pitch=$pitch")
            assertEquals(z, d[2], 1e-9, "z yaw=$yaw pitch=$pitch")
        }
        assertDir(0.0, 0.0, 0.0, 0.0, 1.0)
        assertDir(90.0, 0.0, -1.0, 0.0, 0.0)
        assertDir(-90.0, 0.0, 1.0, 0.0, 0.0)
        assertDir(180.0, 0.0, 0.0, 0.0, -1.0)
        assertDir(0.0, -90.0, 0.0, 1.0, 0.0)
        assertDir(0.0, 90.0, 0.0, -1.0, 0.0)
        assertDir(0.0, 45.0, 0.0, -Math.sqrt(0.5), Math.sqrt(0.5))
        assertEquals(1.62, eyeHeight(false))
        assertEquals(1.27, eyeHeight(true))
    }

    @Test
    fun `視錐判定：角度、距離、背後、蹲下的眼睛高度`() {
        // 站在原點、眼睛 1.62、看向 +Z
        fun seen(x: Double, y: Double, z: Double, yaw: Double = 0.0, pitch: Double = 0.0, half: Double = 55.0, max: Double = 20.0) =
            inViewCone(0.0, 1.62, 0.0, yaw, pitch, x, y, z, half, max)
        assertTrue(seen(0.0, 1.62, 10.0), "正前方")
        assertTrue(seen(5.0, 1.62, 10.0), "偏 26 度")
        assertFalse(seen(10.0, 1.62, 5.0), "偏 63 度，半角 55 之外")
        assertFalse(seen(10.0, 1.62, 0.1), "側面 90 度")
        assertFalse(seen(0.0, 1.62, -5.0), "背後")
        assertFalse(seen(0.0, 1.62, 30.0), "超過最大距離")
        assertTrue(seen(-5.0, 1.62, 0.0, yaw = 90.0), "yaw 90 看向 -X")
        assertFalse(seen(5.0, 1.62, 0.0, yaw = 90.0), "yaw 90 的背後是 +X")
        assertFalse(seen(0.0, 1.62, 10.0, pitch = -80.0), "抬頭看天花板，看不到前方的人偶")
        assertTrue(seen(0.0, 6.0, 1.0, pitch = -75.0), "抬頭看正上方的東西")
        assertEquals(0.0, viewAngleDegrees(0.0, 1.62, 0.0, 0.0, 0.0, 0.0, 1.62, 0.0), "貼在眼睛上算 0 度")
        assertEquals(90.0, viewAngleDegrees(0.0, 0.0, 0.0, 0.0, 0.0, 5.0, 0.0, 0.0), 1e-9)
    }

    @Test
    fun `凍結狀態機：轉換才記一次，姿勢不連續重複，聲音有冷卻，reset 清乾淨`() {
        val state = FreezeState()
        assertEquals(FreezeState.Change.NONE, state.update(false))
        assertEquals(FreezeState.Change.FROZE, state.update(true))
        assertEquals(FreezeState.Change.NONE, state.update(true))
        assertTrue(state.frozen)
        assertEquals(FreezeState.Change.MOVED, state.update(false))
        assertFalse(state.frozen)
        val random = Random(7)
        var last = -1
        repeat(300) {
            val pick = state.pickPose(4, random)
            assertTrue(pick in 0..3)
            assertTrue(pick != last, "連續兩次不重複")
            last = pick
        }
        assertEquals(0, FreezeState().pickPose(1, random))
        assertEquals(-1, FreezeState().pickPose(0, random))
        assertTrue(state.moveCueDue(100, 8))
        assertFalse(state.moveCueDue(104, 8))
        assertTrue(state.moveCueDue(108, 8))
        state.update(true)
        state.reset()
        assertFalse(state.frozen)
        assertEquals(-1, state.poseIndex)
        assertTrue(state.moveCueDue(0, 8), "reset 後聲音冷卻也清了")
    }

    @Test
    fun `追擊目標：策略、索敵距離、放棄距離`() {
        val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")
        val list = listOf(ChaseCandidate(a, 5.0, 20.0), ChaseCandidate(b, 10.0, 5.0), ChaseCandidate(c, 25.0, 1.0))
        assertEquals(a, pickChaseTarget(list, ChaseTarget.NEAREST, 20.0, 30.0, null))
        assertEquals(b, pickChaseTarget(list, ChaseTarget.LOWEST_HEALTH, 20.0, 30.0, null), "c 血最少但在索敵距離外")
        assertEquals(b, pickChaseTarget(list, ChaseTarget.FARTHEST, 20.0, 30.0, null))
        assertEquals(c, pickChaseTarget(list, ChaseTarget.LOWEST_HEALTH, 40.0, 60.0, null))
        // 沒有人在索敵距離內：已鎖定的在放棄距離內繼續追，超過就放棄
        val far = listOf(ChaseCandidate(c, 25.0, 1.0))
        assertEquals(c, pickChaseTarget(far, ChaseTarget.NEAREST, 20.0, 30.0, c))
        assertNull(pickChaseTarget(far, ChaseTarget.NEAREST, 20.0, 24.0, c), "超過放棄距離")
        assertNull(pickChaseTarget(far, ChaseTarget.NEAREST, 20.0, 30.0, null), "沒鎖定過就不追")
        assertNull(pickChaseTarget(far, ChaseTarget.NEAREST, 20.0, 30.0, a), "鎖定的人已離場")
        assertNull(pickChaseTarget(emptyList(), ChaseTarget.NEAREST, 20.0, 30.0, a))
        assertEquals(ChaseTarget.LOWEST_HEALTH, ChaseTarget.of("lowest-health"))
        assertNull(ChaseTarget.of("nope"))
    }

    @Test
    fun `逃跑目標點：遠離威脅，偏轉角度保持距離，重疊時朝 +X`() {
        val straight = fleePoint(0.0, 0.0, -5.0, 0.0, 12.0, 0.0)
        assertEquals(12.0, straight[0], 1e-9)
        assertEquals(0.0, straight[1], 1e-9)
        for (angle in FLEE_ANGLES) {
            val p = fleePoint(3.0, 4.0, 0.0, 0.0, 10.0, angle)
            assertEquals(10.0, Math.hypot(p[0] - 3.0, p[1] - 4.0), 1e-9, "angle=$angle")
        }
        val overlap = fleePoint(1.0, 1.0, 1.0, 1.0, 8.0, 0.0)
        assertEquals(9.0, overlap[0], 1e-9)
        assertEquals(1.0, overlap[1], 1e-9)
        assertEquals(0.0, FLEE_ANGLES.first())
    }

    @Test
    fun `powered 計時：授予、到期、刷新取較晚、離場移除、清掉`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val tracker = PoweredTracker()
        assertFalse(tracker.active)
        assertEquals(2, tracker.grant(listOf(a, b), 0, 160))
        assertTrue(tracker.active)
        assertTrue(tracker.isPowered(a, 159))
        assertFalse(tracker.isPowered(a, 160))
        assertEquals(setOf(a, b), tracker.poweredIds(10))
        assertEquals(150, tracker.remainingTicks(a, 10))
        tracker.grant(listOf(a), 100, 160)
        assertEquals(setOf(b), tracker.expire(160).toSet(), "a 被刷新到 260，只有 b 到期")
        assertTrue(tracker.isPowered(a, 200))
        tracker.grant(listOf(a), 120, 20)
        assertTrue(tracker.isPowered(a, 259), "再吃一次不會縮短")
        assertTrue(tracker.remove(a))
        assertFalse(tracker.remove(a))
        assertFalse(tracker.active)
        tracker.grant(listOf(a, b), 0, 40)
        assertEquals(setOf(a, b), tracker.clear().toSet())
        assertFalse(tracker.active)
        assertEquals(0, tracker.remainingTicks(a, 0))
    }

    @Test
    fun `power-up 狀態機：消耗、重生、不消耗的冷卻`() {
        val consumable = PowerUpMachine(consume = true, respawnTicks = 600, poweredTicks = 160)
        assertTrue(consumable.canTake(0))
        assertTrue(consumable.take(0))
        assertFalse(consumable.take(0), "同時間不會被拿兩次")
        assertEquals(PowerUpMachine.State.TAKEN, consumable.state)
        assertFalse(consumable.tryRespawn(599))
        assertTrue(consumable.tryRespawn(600))
        assertEquals(PowerUpMachine.State.READY, consumable.state)
        assertTrue(consumable.take(600))

        val once = PowerUpMachine(consume = true, respawnTicks = 0, poweredTicks = 160)
        assertTrue(once.take(0))
        assertFalse(once.tryRespawn(Long.MAX_VALUE / 2), "respawn 0 = 不重生")
        assertFalse(once.canTake(1_000_000))

        val reusable = PowerUpMachine(consume = false, respawnTicks = 0, poweredTicks = 160)
        assertTrue(reusable.take(0))
        assertEquals(PowerUpMachine.State.READY, reusable.state, "不消耗：外觀一直是 ready")
        assertFalse(reusable.canTake(100), "powered 還沒結束前不會連續觸發")
        assertTrue(reusable.canTake(160))
        val slow = PowerUpMachine(consume = false, respawnTicks = 400, poweredTicks = 160)
        slow.take(0)
        assertFalse(slow.canTake(399))
        assertTrue(slow.canTake(400))
    }

    @Test
    fun `尾聲序列與對話等待名單`() {
        val steps = listOf(EpilogueStep("a", null, 1.0, null), EpilogueStep(null, "d", 0.0, null))
        val sequence = EpilogueSequence(steps)
        assertFalse(sequence.finished)
        assertEquals("a", sequence.current()?.cue)
        assertEquals("d", sequence.advance()?.dialog)
        assertEquals(1, sequence.position)
        assertNull(sequence.advance())
        assertTrue(sequence.finished)
        assertNull(sequence.advance(), "播完再 advance 不會越界")
        assertEquals(2, sequence.position)
        assertTrue(EpilogueSequence(emptyList()).finished, "沒有尾聲 = 一開始就播完")

        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val wait = DialogWait(listOf(a, b))
        assertFalse(wait.complete)
        wait.done(a)
        assertEquals(1, wait.size)
        wait.remove(b)
        assertTrue(wait.complete, "離場的人不會卡住")
        assertTrue(DialogWait(emptyList()).complete)
    }

    @Test
    fun `身體計畫：被看著的人偶被閃光照到再恢復，仍被看著就留在凍結，不會被放出來`() {
        val alive = EnemyMachine.State.ALIVE
        // 沒被看：AI 開、移動動畫
        assertEquals(BodyPlan(true, BodyAnim.MOVING), bodyPlan(alive, frozen = false, fleeing = false, isDoll = true))
        // 被看：AI 關、姿勢
        assertEquals(BodyPlan(false, BodyAnim.POSE), bodyPlan(alive, frozen = true, fleeing = false, isDoll = true))
        // 被閃光照到：僵直(AI 關、僵直動畫)，凍結旗標還留著
        val machine = EnemyMachine()
        assertTrue(machine.stun())
        assertEquals(BodyPlan(false, BodyAnim.STUNNED), bodyPlan(machine.state, frozen = true, fleeing = false, isDoll = true))
        // 僵直結束：旗標仍是 frozen(還被看著)→ 回到凍結姿勢，AI 保持關
        assertTrue(machine.recover(machine.stunEpoch))
        assertEquals(BodyPlan(false, BodyAnim.POSE), bodyPlan(machine.state, frozen = true, fleeing = false, isDoll = true))
        // 僵直期間視線移開(旗標被 reset 或沒更新成 frozen=false)：恢復後才動起來
        assertEquals(BodyPlan(true, BodyAnim.MOVING), bodyPlan(machine.state, frozen = false, fleeing = false, isDoll = true))
        // 打散中不再碰
        assertTrue(machine.dissolve())
        assertNull(bodyPlan(machine.state, frozen = true, fleeing = false, isDoll = true))
        assertNull(bodyPlan(EnemyMachine.State.GONE, frozen = false, fleeing = false, isDoll = false))
        // 追擊者：逃跑外觀、一般外觀；powered 結束後回預設
        assertEquals(BodyPlan(true, BodyAnim.FLEEING), bodyPlan(alive, frozen = false, fleeing = true, isDoll = false))
        assertEquals(BodyPlan(true, BodyAnim.IDLE), bodyPlan(alive, frozen = false, fleeing = false, isDoll = false))
        // 僵直中的逃跑者：只顯示僵直，旗標留到恢復
        assertEquals(BodyPlan(false, BodyAnim.STUNNED), bodyPlan(EnemyMachine.State.STUNNED, frozen = false, fleeing = true, isDoll = false))
    }
}
