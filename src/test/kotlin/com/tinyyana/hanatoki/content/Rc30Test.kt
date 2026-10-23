package com.tinyyana.hanatoki.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.30:場邊燈遠距光束、跪下連擊、落雷／掃地點燈、熄燈燒著的燈、偏向照燈的人。舊資料零影響。 */
class Rc30Test {

    private fun lampsWith(extra: String): String = BossFixtures.FILES.getValue("objects.yml").replace("            burn-seconds: 3\n", "            burn-seconds: 3\n$extra\n")

    private fun errorsOf(overrides: Map<String, String?>): List<String> =
        assertFailsWith<ContentDataException> { BossFixtures.parse(overrides) }.errors

    @Test
    fun `舊資料零影響`() {
        val spec = BossFixtures.parse()
        assertTrue(spec.objects.values.filterIsInstance<LanternSpec>().all { it.beam == null })
        val nun = spec.bosses.getValue("nun")
        assertNull(nun.kneel.combo)
        assertTrue(nun.moves.values.all { it.lampBoost == 0.0 && it.lightingBias == 0.0 })
        val snuff = nun.moves.getValue("snuff").shape as MoveShape.Snuff
        assertNull(snuff.burningMinFraction)
        assertEquals(0.0, snuff.glideStopDistance)
    }

    @Test
    fun `lantern beam 解析，預設值與壞資料`() {
        val full = lampsWith("            beam: { range: 18, angle: 24, sustain-seconds: 1.2, speed: 0.6, requires-sight: true, hit-cues: [bh], blocked-cues: [bb], boss-block-radius: 2 }")
        val beam = (BossFixtures.parse(mapOf("objects.yml" to full)).objects.getValue("boss_lamp_1") as LanternSpec).beam
        assertEquals(LanternBeam(18.0, 24.0, 1.2, 0.6, true, listOf("bh"), listOf("bb"), 2.0), beam)
        val minimal = lampsWith("            beam: { range: 12 }")
        assertEquals(LanternBeam(12.0, 20.0, 1.0, 0.7, true, emptyList(), emptyList(), 1.8), (BossFixtures.parse(mapOf("objects.yml" to minimal)).objects.getValue("boss_lamp_1") as LanternSpec).beam)
        assertTrue(errorsOf(mapOf("objects.yml" to lampsWith("            beam: { angle: 20 }"))).any { "beam.range" in it && "缺少必要欄位" in it })
        assertTrue(errorsOf(mapOf("objects.yml" to lampsWith("            beam: { range: 12, extra: 1 }"))).any { "extra" in it })
        assertTrue(errorsOf(mapOf("objects.yml" to lampsWith("            beam: { range: 12, speed: 0 }"))).any { "speed" in it })
    }

    @Test
    fun `kneel combo 解析`() {
        val spec = BossFixtures.parseBoss(
            "kneel: { seconds: 10, cues: [kneel_cue] }" to
                "kneel: { seconds: 10, cues: [kneel_cue], combo: { step: 0.15, cap: 2.5, window-seconds: 1.5, hit-cues: [kh], text: k.text, countdown-seconds: 3, countdown-cues: [kt], countdown-text: k.count } }",
        )
        assertEquals(KneelCombo(0.15, 2.5, 1.5, listOf("kh"), "k.text", 3.0, listOf("kt"), "k.count"), spec.bosses.getValue("nun").kneel.combo)
        val bad = BossFixtures.bossErrors("kneel: { seconds: 10, cues: [kneel_cue] }" to "kneel: { seconds: 10, cues: [kneel_cue], combo: { cap: 2 } }")
        assertTrue(bad.any { "combo.step" in it && "缺少必要欄位" in it }, bad.toString())
        // 文案 key 要存在
        val missing = ContentValidator.validateTexts(spec) { it == "boss.bar" || it == "boss.downed.hud" || it == "boss.downed.title" }
        assertTrue(missing.any { "kneel.combo.text" in it && "k.text" in it } && missing.any { "kneel.combo.countdown-text" in it }, missing.toString())
    }

    @Test
    fun `move 的 lamp-boost、lighting-bias、snuff 新欄位解析`() {
        val spec = BossFixtures.parseBoss(
            "strike: { shape: circle, weight: 3, warn-seconds: 2, radius: 2.5, damage-percent: 0.3, animation: tele_strike }" to
                "strike: { shape: circle, weight: 3, warn-seconds: 2, radius: 2.5, damage-percent: 0.3, animation: tele_strike, max-targets: 2, lamp-boost: 1.0, lamp-radius: 2, lamp-boost-cues: [bolt], lighting-bias: 0.7 }",
        )
        val strike = spec.bosses.getValue("nun").moves.getValue("strike")
        assertEquals(1.0, strike.lampBoost); assertEquals(2.0, strike.lampRadius); assertEquals(listOf("bolt"), strike.lampBoostCues); assertEquals(0.7, strike.lightingBias)
        val snuffSpec = BossFixtures.parseBoss(
            "snuff: { shape: snuff, weight: 2, warn-seconds: 3, animation: tele_snuff, snuff-cues: [nun_hum] }" to
                "snuff: { shape: snuff, weight: 2, warn-seconds: 3, animation: tele_snuff, snuff-cues: [nun_hum], burning-min-fraction: 0.5, burning-weight: 3, glide-stop-distance: 5, glide-speed: 8 }",
        )
        val snuff = snuffSpec.bosses.getValue("nun").moves.getValue("snuff").shape as MoveShape.Snuff
        assertEquals(0.5, snuff.burningMinFraction); assertEquals(3, snuff.burningWeight); assertEquals(5.0, snuff.glideStopDistance); assertEquals(8.0, snuff.glideSpeed)
        // lighting-bias 只有 circle 可寫
        val bad = BossFixtures.bossErrors(
            "sweep: { shape: ring, weight: 3, cooldown-seconds: 6, warn-seconds: 2, max-radius: 9," to "sweep: { shape: ring, lighting-bias: 0.5, weight: 3, cooldown-seconds: 6, warn-seconds: 2, max-radius: 9,",
        )
        assertTrue(bad.any { "lighting-bias" in it && "未知的欄位" in it }, bad.toString())
    }

    @Test
    fun `光束瞄準：距離與夾角都要在範圍內`() {
        // 眼睛在原點看向 +x，目標在 (10, 0, 0)
        assertTrue(beamAimed(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 10.0, 0.0, 0.0, 18.0, 20.0))
        assertFalse(beamAimed(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 20.0, 0.0, 0.0, 18.0, 20.0), "超過距離")
        // 夾角 10 度剛好在半角內，11 度出去
        fun at(deg: Double) = beamAimed(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 10.0 * Math.cos(Math.toRadians(deg)), 0.0, 10.0 * Math.sin(Math.toRadians(deg)), 18.0, 20.0)
        assertTrue(at(9.9)); assertFalse(at(10.5))
        assertFalse(beamAimed(0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 10.0, 0.0, 0.0, 18.0, 20.0), "背對")
        assertFalse(beamAimed(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 18.0, 20.0), "沒有方向")
    }

    @Test
    fun `boss 本體擋光：線段穿過圓柱才算，貼著端點與繞過去不算`() {
        // 眼睛 (0,1.6,0) 到燈 (20,2.5,0)，boss 站在 (10,0,0)，半徑 1.8、高 4.5
        assertTrue(bodyBlocksSegment(0.0, 1.6, 0.0, 20.0, 2.5, 0.0, 10.0, 0.0, 0.0, 1.8, 4.5))
        // 偏開 3 格繞過去
        assertFalse(bodyBlocksSegment(0.0, 1.6, 3.0, 20.0, 2.5, 3.0, 10.0, 0.0, 0.0, 1.8, 4.5))
        // 從 boss 頭頂上方看過去(線段高度都在圓柱之上)
        assertFalse(bodyBlocksSegment(0.0, 8.0, 0.0, 20.0, 8.0, 0.0, 10.0, 0.0, 0.0, 1.8, 4.5))
        // boss 在眼睛後面或燈後面
        assertFalse(bodyBlocksSegment(0.0, 1.6, 0.0, 20.0, 2.5, 0.0, -10.0, 0.0, 0.0, 1.8, 4.5))
        assertFalse(bodyBlocksSegment(0.0, 1.6, 0.0, 20.0, 2.5, 0.0, 30.0, 0.0, 0.0, 1.8, 4.5))
        // 半徑 0 = 擋不住
        assertFalse(bodyBlocksSegment(0.0, 1.6, 0.0, 20.0, 2.5, 0.0, 10.0, 0.0, 0.0, 0.0, 4.5))
    }

    @Test
    fun `連擊倍率與燒燈外力推進`() {
        assertEquals(1.0, comboMultiplier(1, 0.15, 2.0))
        assertEquals(1.3, comboMultiplier(3, 0.15, 2.0), 1.0E-9)
        assertEquals(2.0, comboMultiplier(20, 0.15, 2.0))
        assertEquals(1.0, comboMultiplier(0, 0.15, 2.0))
        val m = LanternMachine(60.0)
        assertEquals(LanternMachine.Outcome.NONE, m.boost(30.0), "沒點燃不吃")
        assertTrue(m.ignite())
        assertEquals(LanternMachine.Outcome.NONE, m.boost(30.0))
        assertEquals(0.5, m.fraction, 1.0E-9)
        assertEquals(LanternMachine.Outcome.COMPLETED, m.boost(40.0))
        assertEquals(LanternMachine.State.LIT, m.state)
        assertEquals(LanternMachine.Outcome.NONE, m.boost(10.0), "亮了不再推進")
    }
}
