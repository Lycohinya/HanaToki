package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.map.MapAssetId
import com.tinyyana.hanatoki.map.MapMarker
import com.tinyyana.hanatoki.map.MarkerKind
import org.bukkit.block.BlockFace

/** 測試用的最小完整內容(結構照 docs/CONTENT-DATA.md 的範例)。 */
object ContentFixtures {
    val FILES: Map<String, String> = mapOf(
        "stages.yml" to """
            dungeon: demo-patrol
            namespace: demo
            stages:
              entry_court:
                on-start:
                  cues: [intro_cue]
                  music: demo.music.explore
                  dialog: demo_opening
                  title: demo.title
                complete:
                  - { type: encounter-cleared, encounter: intro }
                next: lantern_court
              lantern_court:
                on-start: { music: demo.music.lantern }
                complete:
                  - { type: object-state, object: lantern_a, state: lit }
                on-complete: { cues: [lantern_done] }
        """.trimIndent(),
        "scene.yml" to """
            entry: entry
            pieces:
              entry_court: { asset: "demo:entry_court", origin: [0, 0, 0] }
              lantern_court: { asset: "demo:lantern_court", origin: [48, 0, 0], rotation: 90 }
        """.trimIndent(),
        "objects.yml" to """
            objects:
              lantern_a:
                type: lantern
                marker: lantern_a
                stage: lantern_court
                burn-seconds: 15
                stay-radius: 5
                speed-by-players: { 2: 1.5, 3: 2.0 }
                hold-cue: lantern_burning
                repeat-cue: { cue: lantern_burn, every-seconds: 2 }
                milestones:
                  - { at: 0.33, cues: [third_1] }
                pause-cues: [lantern_fade]
                states:
                  unlit: { blocks: [ { offset: [0, 1, 0], block: "minecraft:lantern" } ] }
                  burning: { cues: [lantern_ignite] }
                  lit: { cues: [lantern_lit] }
                on-lit: { cues: [lantern_burst], light-radius: 12, clears-enemies: true, opens: gate_corridor }
              gate_corridor:
                type: gate
                marker: gate_corridor
                column-ticks: 4
                states:
                  open: { cues: [gate_open] }
        """.trimIndent(),
        "enemies.yml" to """
            enemies:
              small_ghost:
                base-entity: zombie
                model: mist_ghost
                rules: [needs-flash]
                health: 6
                speed: 0.16
                damage: 1.0
                scale: 0.7
                by-players: { 2: { health: 1.3 } }
                spawn-cues: [ghost_appear]
                needs-flash: { whiff-cue: whiff, stun-cue: ghost_stun, dissolve-cue: ghost_dissolve, refill-cue: page_refill }
        """.trimIndent(),
        "encounters.yml" to """
            encounters:
              intro:
                stage: entry_court
                enemy: small_ghost
                marker-group: intro
                trigger: { type: stage-start }
                counts: { 1: 1, 3: 2 }
              wave1:
                stage: lantern_court
                enemy: small_ghost
                marker-group: wave1
                trigger: { type: object-state, object: lantern_a, state: burning }
                counts: { 1: 3, 2: 4, 4: 5 }
                interval-ticks: 20
        """.trimIndent(),
        "tool.yml" to """
            tools:
              night_lantern:
                material: LANTERN
                name: tool.name
                lore: [tool.lore]
                charges: 3
                cone-angle: 50
                range: 8
                stun-seconds: 3
                cooldown-ticks: 14
                refill-on-dissolve: 1
                cues: { flash: flash, empty: flash_empty }
        """.trimIndent(),
        "hints.yml" to """
            hints:
              hint_flash_first:
                trigger: { type: first-whiff }
                cues: [hint_flash_first]
              hint_idle:
                trigger: { type: no-progress, seconds: 20 }
                cues: [hint_idle]
              hint_point:
                trigger: { type: near-marker, marker: lantern_a, radius: 12 }
                stage: lantern_court
                cues: [hint_lantern]
        """.trimIndent(),
    )

    fun raws(overrides: Map<String, String?> = emptyMap()): Map<String, Map<String, Any?>> {
        val merged = LinkedHashMap(FILES)
        for ((k, v) in overrides) if (v == null) merged.remove(k) else merged[k] = v
        return merged.mapValues { (name, text) -> ContentYaml.parse(name, text) }
    }

    fun parse(overrides: Map<String, String?> = emptyMap()): ContentSpec = ContentParser(raws(overrides)).parse()

    private val ASSET = MapAssetId("demo", "x", "0123456789abcdef")

    private fun marker(kind: MarkerKind, x: Int, props: Map<String, String>, facing: BlockFace? = null) =
        MapMarker(kind, ASSET, x, 1, 0, facing, props)

    /** 和 [FILES] 對得上的場景 marker。 */
    fun markers(): List<MapMarker> = buildList {
        add(marker(MarkerKind.PLAYER_SPAWN, 1, mapOf("id" to "entry"), BlockFace.NORTH))
        add(marker(MarkerKind.MOB_SPAWN, 2, mapOf("id" to "hedge_ghost", "group" to "intro")))
        add(marker(MarkerKind.TRIGGER, 3, mapOf("id" to "lantern_a")))
        for (i in 1..5) add(marker(MarkerKind.MOB_SPAWN, 10 + i, mapOf("id" to "wave1_$i", "group" to "wave1")))
        add(marker(MarkerKind.DOOR, 30, mapOf("id" to "gate_corridor", "w" to "4", "h" to "3"), BlockFace.SOUTH))
    }
}
