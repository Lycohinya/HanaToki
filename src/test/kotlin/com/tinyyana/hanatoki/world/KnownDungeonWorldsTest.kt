package com.tinyyana.hanatoki.world

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KnownDungeonWorldsTest {

    /** 種子要涵蓋正式服每座虛空副本世界,而且只有刀塚是「每局重蓋、開機清區塊」的那一種。 */
    @Test
    fun `shipped seed knows every dungeon world and only tachizuka is non-persistent`() {
        val seed = Files.createTempFile("known", ".yml").toFile()
        seed.writeText(File("src/main/resources/known-worlds.yml").readText())
        val worlds = KnownDungeonWorlds(seed).read()
        assertEquals(
            setOf("hanatoki_tachizuka", "hanatoki_roguelike", "hanatoki_pale_cherry", "hanatoki_pandora_echo", "hanatoki_rain_court"),
            worlds.keys,
        )
        assertEquals(listOf("hanatoki_tachizuka"), worlds.filterValues { !it }.keys.toList())
    }

    @Test
    fun `provisioning keeps the file current`() {
        val file = Files.createTempFile("known", ".yml").toFile().also { it.delete() }
        val known = KnownDungeonWorlds(file)
        known.remember("hanatoki_new", false)
        known.remember("hanatoki_new", true)
        assertEquals(mapOf("hanatoki_new" to true), known.read())
        known.forget("hanatoki_new")
        assertFalse(known.read().containsKey("hanatoki_new"))
    }

    @Test
    fun `only chunk data is ever purged`() {
        assertEquals(listOf("region", "entities", "poi"), KnownDungeonWorlds.CHUNK_DATA)
    }
}
