package com.tinyyana.hanatoki.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.27:power-up 浮空實體 `prop`、目標文字 `{nearest:power-up}` 的方位計算。舊資料零影響。 */
class Rc27Test {

    private fun objectsWith(extra: String) = BossFixtures.FILES.getValue("objects.yml").replace(
        "            seconds: 6\n", "            seconds: 6\n$extra\n",
    )

    @Test
    fun `舊資料沒有 prop`() {
        val spec = BossFixtures.parse()
        assertTrue(spec.objects.values.filterIsInstance<PowerUpSpec>().all { it.prop == null })
    }

    @Test
    fun `prop 解析，預設值與範圍`() {
        val full = objectsWith("            prop: { item: JACK_O_LANTERN, scale: 0.9, height: 1.1, bob: 0.3, bob-seconds: 4, spin-degrees: 60 }")
        val p = (BossFixtures.parse(mapOf("objects.yml" to full)).objects.getValue("pumpkin_boss_1") as PowerUpSpec).prop
        assertEquals(PowerUpProp("JACK_O_LANTERN", 0.9, 1.1, 0.3, 4.0, 60.0), p)
        val minimal = objectsWith("            prop: { item: JACK_O_LANTERN }")
        assertEquals(PowerUpProp("JACK_O_LANTERN", 1.0, 1.0, 0.3, 3.0, 90.0), (BossFixtures.parse(mapOf("objects.yml" to minimal)).objects.getValue("pumpkin_boss_1") as PowerUpSpec).prop)
    }

    @Test
    fun `prop 壞資料被擋`() {
        fun errors(prop: String) = assertFailsWith<ContentDataException> { BossFixtures.parse(mapOf("objects.yml" to objectsWith("            prop: $prop"))) }.errors
        assertTrue(errors("{ scale: 1 }").any { "prop.item" in it && "缺少必要欄位" in it })
        assertTrue(errors("{ item: \"jack o lantern\" }").any { "prop.item" in it })
        assertTrue(errors("{ item: JACK_O_LANTERN, scale: 0 }").any { "prop.scale" in it })
        assertTrue(errors("{ item: JACK_O_LANTERN, extra: 1 }").any { "extra" in it })
    }

    @Test
    fun `方位八向以玩家面向為準，yaw 0 面向南、順時針增加`() {
        // 玩家 yaw 0 面向南(+z)：目標在正南 = 前，正東(+x) = 左(南方的右手邊是西)，正西(-x) = 右
        assertEquals("front", directionOctant(0.0, 0.0, 10.0))
        assertEquals("left", directionOctant(0.0, 10.0, 0.0))
        assertEquals("right", directionOctant(0.0, -10.0, 0.0))
        assertEquals("back", directionOctant(0.0, 0.0, -10.0))
        assertEquals("front-left", directionOctant(0.0, 10.0, 10.0))
        assertEquals("front-right", directionOctant(0.0, -10.0, 10.0))
        assertEquals("back-left", directionOctant(0.0, 10.0, -10.0))
        assertEquals("back-right", directionOctant(0.0, -10.0, -10.0))
        // 轉身：yaw 180 面向北(-z)，目標在北 = 前；yaw 90 面向西(-x)，目標在正西 = 前、正南 = 左
        assertEquals("front", directionOctant(180.0, 0.0, -10.0))
        assertEquals("front", directionOctant(90.0, -10.0, 0.0))
        assertEquals("left", directionOctant(90.0, 0.0, 10.0))
        assertEquals("right", directionOctant(90.0, 0.0, -10.0))
        // yaw 超過 ±180(Minecraft 會給 -180..180，但不能因此出錯)
        assertEquals("front", directionOctant(-270.0, -10.0, 0.0))
        assertEquals("front", directionOctant(0.0, 0.0, 0.0))
    }

    @Test
    fun `方位 22 度以內都算正前方，23 度以上換前側`() {
        val rad = Math.toRadians(20.0)
        assertEquals("front", directionOctant(0.0, -Math.sin(rad) * 10, Math.cos(rad) * 10))
        val rad2 = Math.toRadians(25.0)
        assertEquals("front-right", directionOctant(0.0, -Math.sin(rad2) * 10, Math.cos(rad2) * 10))
        assertNull(null)
    }
}
