package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.map.MapAssetId
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MarkerKind

/** rc.23 Boss 測試用的最小完整內容：單一 stage（前庭）、四盞場邊燈、兩個南瓜燈、兩種敵人、一個三階段的 boss。 */
object BossFixtures {
    private fun lamp(i: Int) = """
          boss_lamp_$i:
            type: lantern
            marker: boss_lamp_$i
            stage: front_court
            burn-seconds: 3
            stay-radius: 4
            ignite-radius: 4
            on-lit: { cues: [lamp_lit] }
            states:
              unlit: { blocks: [ { offset: [0, 1, 0], block: "minecraft:lantern" } ] }
    """.trimEnd()

    private fun pumpkin(i: Int) = """
          pumpkin_boss_$i:
            type: power-up
            marker: pumpkin_boss_$i
            stage: front_court
            seconds: 6
    """.trimEnd()

    val NUN: String = """
        bosses:
          nun:
            stage: front_court
            model: lamp_nun
            scale: 2.0
            health: 40
            by-players: { 2: { health: 1.6, damage: 1.0 }, 3: { health: 2.2, damage: 0.8 } }
            hit-cooldown-ticks: 0
            points: [nun_point_a, nun_point_b, nun_point_c]
            phases:
              - { at: 1.0, music: boss.p1 }
              - { at: 0.55, cues: [phase_2] }
              - { at: 0.25, cues: [phase_3] }
            moves:
              sweep: { shape: ring, weight: 3, cooldown-seconds: 6, warn-seconds: 2, max-radius: 9, damage-percent: 0.25, push: { strength: 0.5, lift: 0.3 }, animation: tele_sweep, warn-cues: [sweep_warn], dodge-cues: [dodge] }
              strike: { shape: circle, weight: 3, warn-seconds: 2, radius: 2.5, damage-percent: 0.3, animation: tele_strike }
              summon: { shape: summon, weight: 2, warn-seconds: 1.5, enemy: mist, markers: [mist_1, mist_2, mist_3], max-alive: 3, animation: tele_summon }
              snuff: { shape: snuff, weight: 2, warn-seconds: 3, animation: tele_snuff, snuff-cues: [nun_hum] }
              cross: { shape: cross, phase: 2, weight: 2, warn-seconds: 2, damage-percent: 0.3, animation: tele_cross }
              dolls: { shape: summon, phase: 2, weight: 1, enemy: doll, markers: [doll_corner_1, doll_corner_2, doll_corner_3, doll_corner_4], max-alive: 4, animation: tele_dolls }
              spin: { shape: spin, phase: 3, weight: 2, warn-seconds: 2, radius: 11, inner-radius: 2, gap-degrees: 80, degrees-per-second: 40, duration-seconds: 8, damage-percent: 0.2, animation: tele_spin }
            shield: { halo-bone: halo, spark-cues: [shield_spark] }
            gems:
              - { lamp: boss_lamp_1, bone: gem_1, beam-cues: [beam] }
              - { lamp: boss_lamp_2, bone: gem_2, beam-cues: [beam] }
              - { lamp: boss_lamp_3, bone: gem_3, beam-cues: [beam] }
              - { lamp: boss_lamp_4, bone: gem_4, beam-cues: [beam] }
            kneel: { seconds: 10, cues: [kneel_cue] }
            pumpkins: { objects: [pumpkin_boss_1, pumpkin_boss_2], from-phase: 3 }
            downed: { revive-points: [revive_1, revive_2, revive_3], hud-text: boss.downed.hud, title: boss.downed.title }
            wipe: { checkpoint: front_door }
            bar: { text: boss.bar, color: RED }
    """.trimIndent()

    val FILES: Map<String, String> = mapOf(
        "stages.yml" to """
            dungeon: demo-boss
            namespace: demo
            stages:
              front_court:
                complete:
                  - { type: boss-defeated, boss: nun }
                clears-enemies: true
        """.trimIndent(),
        "scene.yml" to """
            entry: entry
            pieces:
              front: { asset: "demo:front", origin: [0, 0, 0] }
        """.trimIndent(),
        "objects.yml" to "objects:\n" + (1..4).joinToString("\n") { lamp(it) } + "\n" + (1..2).joinToString("\n") { pumpkin(it) },
        "enemies.yml" to """
            enemies:
              mist:
                base-entity: zombie
                rules: [needs-flash]
                health: 6
                needs-flash: { whiff-cue: whiff, stun-cue: stun, dissolve-cue: dissolve }
              doll:
                base-entity: zombie
                rules: [needs-flash, frozen-when-watched]
                health: 10
                needs-flash: { whiff-cue: whiff, stun-cue: stun, dissolve-cue: dissolve }
                frozen-when-watched: {}
        """.trimIndent(),
        "bosses.yml" to NUN,
    )

    fun raws(overrides: Map<String, String?> = emptyMap()): Map<String, Map<String, Any?>> {
        val merged = LinkedHashMap(FILES)
        for ((k, v) in overrides) if (v == null) merged.remove(k) else merged[k] = v
        return merged.mapValues { (name, text) -> ContentYaml.parse(name, text) }
    }

    fun parse(overrides: Map<String, String?> = emptyMap()): ContentSpec = ContentParser(raws(overrides)).parse()

    /** 只換 boss 檔裡的一段文字（測錯誤用）。 */
    fun parseBoss(replace: Pair<String, String>): ContentSpec {
        val text = NUN.replace(replace.first, replace.second)
        check(text != NUN) { "測試字串沒有命中：${replace.first}" }
        return parse(mapOf("bosses.yml" to text))
    }

    fun bossErrors(replace: Pair<String, String>): List<String> {
        val text = NUN.replace(replace.first, replace.second)
        check(text != NUN) { "測試字串沒有命中：${replace.first}" }
        return kotlin.test.assertFailsWith<ContentDataException> { parse(mapOf("bosses.yml" to text)) }.errors
    }

    private val ASSET = MapAssetId("demo", "front", "0123456789abcdef")

    private fun marker(kind: MarkerKind, x: Int, id: String) = MapMarker(kind, ASSET, x, 1, 0, null, mapOf("id" to id))

    /** 和 [FILES] 對得上的場景 marker。 */
    fun markers(): List<MapMarker> = buildList {
        add(marker(MarkerKind.PLAYER_SPAWN, 0, "entry"))
        var x = 1
        for (id in listOf("nun_point_a", "nun_point_b", "nun_point_c", "mist_1", "mist_2", "mist_3", "doll_corner_1", "doll_corner_2", "doll_corner_3", "doll_corner_4", "revive_1", "revive_2", "revive_3", "front_door")) {
            add(marker(MarkerKind.MOB_SPAWN, x++, id))
        }
        for (i in 1..4) add(marker(MarkerKind.TRIGGER, x++, "boss_lamp_$i"))
        for (i in 1..2) add(marker(MarkerKind.TRIGGER, x++, "pumpkin_boss_$i"))
    }

    val TEXT_KEYS = setOf("boss.bar", "boss.downed.hud", "boss.downed.title")
}
