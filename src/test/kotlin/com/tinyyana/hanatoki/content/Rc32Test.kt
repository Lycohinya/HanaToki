package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.map.MapAssetId
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MarkerKind
import com.tinyyana.hanatoki.reward.RunStats
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** rc.32:探索收集物 `collectible`(解析、驗證、逐人可見狀態機、本局收集帳、first／found 判定、stat 鍵)。舊資料零影響。 */
class Rc32Test {

    private val alice = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
    private val bob = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
    private val carol = UUID.fromString("00000000-0000-4000-8000-0000000000c3")

    private fun withCollectibles(extra: String): ContentSpec {
        val objects = BossFixtures.FILES.getValue("objects.yml") + "\n" + extra
        return BossFixtures.parse(mapOf("objects.yml" to objects))
    }

    @Test
    fun `舊資料沒有 collectible`() {
        assertTrue(BossFixtures.parse().objects.values.none { it is CollectibleSpec })
    }

    @Test
    fun `collectible 解析，預設值與完整欄位`() {
        val spec = withCollectibles(
            listOf(
                "collect_entry_1:",
                "  type: collectible",
                "  marker: collect_entry_1",
                "  stage: front_court",
                "  radius: 2",
                "  n: \"一\"",
                "  found-first: collect.first",
                "  found-repeat: collect.repeat",
                "  cue: collect_found",
                "  prop: { item: PAPER, scale: 0.5, height: 1.2, bob: 0.2, bob-seconds: 4, spin-degrees: 60, glow: true }",
                "collect_lantern_1:",
                "  type: collectible",
                "  marker: collect_lantern_1",
            ).joinToString("\n") { "          $it" },
        )
        val a = spec.objects.getValue("collect_entry_1") as CollectibleSpec
        assertEquals("front_court", a.stage)
        assertEquals(2.0, a.radius)
        assertEquals("一", a.label)
        assertEquals("collect.first", a.foundFirstKey)
        assertEquals("collect.repeat", a.foundRepeatKey)
        assertEquals("collect_found", a.cue)
        assertEquals(PowerUpProp("PAPER", 0.5, 1.2, 0.2, 4.0, 60.0, true), a.prop)
        assertTrue(a.states.isEmpty())
        val b = spec.objects.getValue("collect_lantern_1") as CollectibleSpec
        assertEquals(1.5, b.radius)
        assertEquals("", b.label)
        assertNull(b.stage); assertNull(b.foundFirstKey); assertNull(b.foundRepeatKey); assertNull(b.cue); assertNull(b.prop)
        assertEquals(emptyList(), ObjectStates.valid(a))
    }

    @Test
    fun `power-up 的 prop 沒寫 glow 仍是 false`() {
        val p = (BossFixtures.parse().objects.getValue("pumpkin_boss_1") as PowerUpSpec)
        assertNull(p.prop)
        assertFalse(PowerUpProp("JACK_O_LANTERN", 1.0, 1.0, 0.3, 3.0, 90.0).glow)
    }

    @Test
    fun `collectible 壞資料被擋`() {
        fun errors(body: String) = assertFailsWith<ContentDataException> {
            withCollectibles("          c1:\n            type: collectible\n$body")
        }.errors
        assertTrue(errors("            radius: 2").any { "marker" in it && "缺少必要欄位" in it })
        assertTrue(errors("            marker: m\n            radius: 0.1").any { "radius" in it })
        assertTrue(errors("            marker: m\n            extra: 1").any { "extra" in it })
        assertTrue(errors("            marker: m\n            prop: { item: PAPER, glow: yes please }").any { "glow" in it })
        assertTrue(errors("            marker: m\n            prop: { item: PAPER, other: 1 }").any { "other" in it })
        val unknown = assertFailsWith<ContentDataException> { withCollectibles("          c1:\n            type: collectable\n            marker: m") }.errors
        assertTrue(unknown.any { "collectible" in it })
    }

    private val asset = MapAssetId("demo", "front", "0123456789abcdef")
    private fun marker(kind: MarkerKind, id: String) = MapMarker(kind, asset, 40, 1, 0, null, mapOf("id" to id))

    @Test
    fun `collectible 的 marker 必須是 trigger，文案 key 要存在`() {
        val spec = withCollectibles("          c1:\n            type: collectible\n            marker: c1\n            found-first: collect.first\n            found-repeat: collect.repeat")
        assertTrue(ContentValidator.validateScene(spec, BossFixtures.markers() + marker(MarkerKind.MOB_SPAWN, "c1")).any { "objects.c1.marker" in it && "collectible" in it && "trigger" in it })
        assertEquals(emptyList(), ContentValidator.validateScene(spec, BossFixtures.markers() + marker(MarkerKind.TRIGGER, "c1")))
        val texts = ContentValidator.validateTexts(spec) { it in BossFixtures.TEXT_KEYS || it == "collect.first" }
        assertTrue(texts.any { "objects.c1.found-repeat" in it && "collect.repeat" in it }, texts.toString())
        assertFalse(texts.any { "found-first" in it })
    }

    // ---- 逐人可見狀態機 ----

    private val tokenA = Any()
    private val tokenB = Any()

    @Test
    fun `可見狀態機，只對沒收過的在線成員開，收過的關掉，其他人不受影響`() {
        val book = CollectViewerBook()
        val online = mapOf(alice to tokenA, bob to tokenB)
        // 實體還沒生成完成:什麼都不做
        assertTrue(book.plan(false, online, emptySet()).isEmpty)
        // 生成完成:兩個人都開
        val first = book.plan(true, online, emptySet())
        assertEquals(setOf(alice, bob), first.show.toSet())
        assertTrue(first.hide.isEmpty() && first.drop.isEmpty())
        book.markShown(alice, tokenA); book.markShown(bob, tokenB)
        assertTrue(book.plan(true, online, emptySet()).isEmpty)
        // alice 收下:只關 alice，bob 仍然看得到
        val afterPick = book.plan(true, online, setOf(alice))
        assertEquals(listOf(alice), afterPick.hide)
        assertTrue(afterPick.show.isEmpty() && afterPick.drop.isEmpty())
        book.markHidden(alice)
        assertFalse(book.isShown(alice)); assertTrue(book.isShown(bob))
        // 已經關了就不會再被要求開
        assertTrue(book.plan(true, online, setOf(alice)).isEmpty)
    }

    @Test
    fun `可見狀態機，沒有套用成功就不記帳，下一輪再要求`() {
        val book = CollectViewerBook()
        val online = mapOf(alice to tokenA)
        assertEquals(listOf(alice), book.plan(true, online, emptySet()).show)
        // 開失敗(部件不在玩家的 region)→ 沒有 markShown → 下一輪仍然要求開
        assertEquals(listOf(alice), book.plan(true, online, emptySet()).show)
    }

    @Test
    fun `可見狀態機，重連換了 Player 物件要重開，離線的記帳作廢，已收過的重連後不會再開`() {
        val book = CollectViewerBook()
        book.markShown(alice, tokenA); book.markShown(bob, tokenB)
        // alice 斷線(不在線)、bob 在線:alice 記帳作廢，沒有東西可關
        val offline = book.plan(true, mapOf(bob to tokenB), emptySet())
        assertEquals(listOf(alice), offline.drop)
        assertTrue(offline.hide.isEmpty() && offline.show.isEmpty())
        book.forget(alice)
        // alice 重連,新物件:要重開
        val newToken = Any()
        assertEquals(listOf(alice), book.plan(true, mapOf(alice to newToken, bob to tokenB), emptySet()).show)
        // 記帳還留著舊物件而人已換成新物件(沒來得及 forget):作廢並重開
        book.markShown(alice, tokenA)
        val swapped = book.plan(true, mapOf(alice to newToken), emptySet())
        assertTrue(alice in swapped.drop)
        assertEquals(listOf(alice), swapped.show)
        // 本局已經收過的人重連:不開
        book.forget(alice)
        assertTrue(book.plan(true, mapOf(alice to newToken), setOf(alice)).show.isEmpty())
    }

    @Test
    fun `可見狀態機，實體被收掉或局結束時整批作廢，clear 清空`() {
        val book = CollectViewerBook()
        book.markShown(alice, tokenA); book.markShown(bob, tokenB)
        val gone = book.plan(false, mapOf(alice to tokenA, bob to tokenB), emptySet())
        assertEquals(setOf(alice, bob), gone.drop.toSet())
        assertTrue(gone.show.isEmpty() && gone.hide.isEmpty())
        assertEquals(setOf(alice, bob), book.shownIds())
        book.clear()
        assertTrue(book.shownIds().isEmpty())
    }

    // ---- 本局收集帳與 stat ----

    @Test
    fun `收集帳，每人各自一份，同一人同一處只算一次`() {
        val ledger = CollectLedger()
        assertTrue(ledger.add(alice, "s1"))
        assertFalse(ledger.add(alice, "s1"))
        assertTrue(ledger.add(bob, "s1"))
        assertTrue(ledger.add(alice, "s2"))
        assertEquals(setOf(alice, bob), ledger.collectedBy("s1"))
        assertEquals(setOf(alice), ledger.collectedBy("s2"))
        assertEquals(setOf("s1", "s2"), ledger.spotsOf(alice))
        assertEquals(setOf("s1"), ledger.spotsOf(bob))
        assertTrue(ledger.spotsOf(carol).isEmpty())
        assertTrue(ledger.has(bob, "s1")); assertFalse(ledger.has(bob, "s2"))
        ledger.clear()
        assertFalse(ledger.has(alice, "s1"))
    }

    @Test
    fun `stat 寫入，逐人鍵與整局合計，沒收到的人沒有逐人鍵`() {
        val stats = HashMap<String, Long>()
        RunStats.record(stats, CollectRules.statName("collect_entry_1"), alice)
        RunStats.record(stats, CollectRules.statName("collect_entry_1"), bob)
        RunStats.record(stats, CollectRules.statName("collect_lantern_1"), alice)
        assertEquals(1L, stats["collect.collect_entry_1.$alice"])
        assertEquals(1L, stats["collect.collect_entry_1.$bob"])
        assertEquals(2L, stats["collect.collect_entry_1"])
        assertEquals(1L, stats["collect.collect_lantern_1.$alice"])
        assertNull(stats["collect.collect_lantern_1.$bob"])
        assertNull(stats["collect.collect_entry_1.$carol"])
    }

    // ---- first／found／文字／距離 ----

    private fun spot(first: String? = "t.first", repeat: String? = "t.repeat") =
        CollectibleSpec("collect_entry_3", "m", null, 1.5, "三", first, repeat, null, null)

    @Test
    fun `first 判定，不知道終身紀錄一律當第一次`() {
        assertTrue(CollectRules.isFirst(null, "a"))
        assertTrue(CollectRules.isFirst(setOf("b"), "a"))
        assertFalse(CollectRules.isFirst(setOf("a"), "a"))
    }

    @Test
    fun `found 進度，有終身紀錄時是聯集，沒有時只算本局`() {
        assertEquals(2, CollectRules.found(null, setOf("a", "b")))
        assertEquals(3, CollectRules.found(setOf("a", "z"), setOf("a", "b")))
        assertEquals(1, CollectRules.found(emptySet(), setOf("a")))
    }

    @Test
    fun `ActionBar 文字 key 與佔位符`() {
        assertEquals("t.first", CollectRules.textKey(spot(), true))
        assertEquals("t.repeat", CollectRules.textKey(spot(), false))
        assertEquals("t.first", CollectRules.textKey(spot(repeat = null), false))
        assertNull(CollectRules.textKey(spot(first = null, repeat = null), true))
        assertEquals(mapOf("n" to "三", "found" to "4", "total" to "9"), CollectRules.textParams(spot(), 4, 9))
    }

    @Test
    fun `距離，量身體中心到浮空實體中心`() {
        // marker 方塊底面中心 (10.5, 64, 20.5)，實體高 1.0 → 中心 y=65；玩家腳底 y=64 → 身體中心 64.9
        assertTrue(CollectRules.within(10.5, 64.0, 21.9, 10.5, 64.0, 20.5, 1.0, 1.5))
        assertFalse(CollectRules.within(10.5, 64.0, 22.1, 10.5, 64.0, 20.5, 1.0, 1.5))
        // 差一層樓(腳底高 4 格)不算
        assertFalse(CollectRules.within(10.5, 68.0, 20.5, 10.5, 64.0, 20.5, 1.0, 1.5))
        assertTrue(CollectRules.within(10.5, 64.0, 20.5, 10.5, 64.0, 20.5, 1.0, 0.5))
    }
}
