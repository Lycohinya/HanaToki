package com.tinyyana.hanatoki.map

import org.bukkit.block.BlockFace
import org.bukkit.block.structure.StructureRotation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 直接讀 jar 會帶出去的 probe 資產(不是測試自己手寫的近似版)。 */
class MapAssetLayerTest {
    private val library = MapAssetLibrary(DirectoryMapAssetSource(Path.of("src/main/resources/map-assets")))
    private fun ref(path: String) = MapAssetRef("hanatoki_probe", path)

    @Test
    fun `marker 與 connector 從 structure block 和 jigsaw 解析出來,並換成要放的方塊`() {
        val end = library.template(ref("end_room"))
        assertEquals(4903, end.dataVersion)
        assertEquals(16, end.id.revision.length)
        val byKind = end.markers.groupBy { it.kind }
        assertEquals(setOf(MarkerKind.MOB_SPAWN, MarkerKind.DOOR, MarkerKind.TRIGGER, MarkerKind.LOOT), byKind.keys)
        val door = byKind.getValue(MarkerKind.DOOR).single()
        assertEquals(MapPos(4, 1, 7), door.pos)
        assertEquals(BlockFace.SOUTH, door.facing)
        assertEquals(mapOf("id" to "exit"), door.properties)
        assertEquals("1.5", byKind.getValue(MarkerKind.TRIGGER).single().properties["radius"])

        val connector = end.connectors.single()
        assertEquals(BlockFace.NORTH, connector.front)
        assertEquals("hanatoki_probe:entry", connector.name)
        assertTrue(!connector.expandable)

        val states = HashMap<MapPos, String>()
        end.forEachBlock { x, y, z, state -> states[MapPos(x, y, z)] = state }
        assertEquals("minecraft:air", states[door.pos])
        assertEquals("minecraft:air", states[connector.pos])
        assertTrue(states.values.none { it.startsWith("minecraft:structure_block") || it.startsWith("minecraft:jigsaw") })
    }

    @Test
    fun `marker metadata 拼錯直接拒絕,註解 marker 略過`() {
        assertFailsWith<IllegalArgumentException> { MarkerSpec.parse("mobspawn id=a", MapPos(0, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { MarkerSpec.parse("loot table", MapPos(0, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { MarkerSpec.parse("door facing=northeast", MapPos(0, 0, 0)) }
        assertNull(MarkerSpec.parse("# 建築者留言", MapPos(0, 0, 0)))
    }

    @Test
    fun `固定 Structure 旋轉後 marker 座標與朝向跟著轉,且可對齊 anchor`() {
        val entrance = library.template(ref("entrance"))
        val layout = MapLayout.single(entrance, MapPos(0, 0, 0), StructureRotation.CLOCKWISE_90)
            .alignMarker(MarkerKind.PLAYER_SPAWN, MapPos(1000, 64, -20))
        val spawn = layout.markers().single { it.kind == MarkerKind.PLAYER_SPAWN }
        assertEquals(Triple(1000, 64, -20), Triple(spawn.x, spawn.y, spawn.z))
        assertEquals(BlockFace.WEST, spawn.facing) // south 順時針 90° = west
        val b = layout.bounds
        assertEquals(7, b.maxX - b.minX + 1)
        assertEquals(7, b.maxZ - b.minZ + 1)
        // 原點 (0,0,0) 在 CW90 下仍是原點:spawn 區域 (3,1,3) → (-3,1,3)
        assertEquals(MapPos(1003, 63, -23), layout.pieces.single().origin)

        val cells = layout.cellsByChunk()
        assertEquals(entrance.blockCount, cells.values.sumOf { it.size })
        assertTrue(cells.values.first().keys.all { it.rotation == StructureRotation.CLOCKWISE_90 })
    }

    @Test
    fun `Jigsaw 組出 入口 → 走廊 → 終點 的封閉序列,同 seed 同結果`() {
        val planner = JigsawPlanner(library)
        val layout = planner.plan(ref("entrance"), StructureRotation.NONE, 42L, 8)
        val paths = layout.pieces.map { it.template.id.path }
        assertEquals(3, paths.size)
        assertEquals("entrance", paths[0])
        assertTrue(paths[1].startsWith("corridor_"))
        assertEquals("end_room", paths[2])

        // 每個接口:子 connector 正好在父 connector 前一格,而且正面相對。
        for (i in 1 until layout.pieces.size) {
            val parent = layout.pieces[i - 1]
            val child = layout.pieces[i]
            val out = parent.template.connectors.single { it.expandable }
            val inbound = child.template.connectors.first { it.name == out.target }
            val outPos = parent.origin.plus(MapRotation.rotate(out.pos, parent.rotation))
            val inPos = child.origin.plus(MapRotation.rotate(inbound.pos, child.rotation))
            val outFront = MapRotation.rotate(out.front, parent.rotation)
            assertEquals(outPos.offset(outFront), inPos)
            assertEquals(outFront.oppositeFace, MapRotation.rotate(inbound.front, child.rotation))
        }
        assertTrue(layout.markers().any { it.kind == MarkerKind.PLAYER_SPAWN })
        assertTrue(layout.markers().any { it.kind == MarkerKind.TRIGGER })
        // 跨 chunk:入口 7 + 走廊 ≥10 + 終點 9 格長。
        assertTrue(layout.cellsByChunk().size > 1)

        val again = planner.plan(ref("entrance"), StructureRotation.NONE, 42L, 8)
        assertEquals(layout.pieces.map { it.template.id to it.origin }, again.pieces.map { it.template.id to it.origin })
        val seeds = (0L until 16L).map { seed -> planner.plan(ref("entrance"), StructureRotation.NONE, seed, 8).pieces[1].template.id.path }.toSet()
        assertEquals(setOf("corridor_long", "corridor_short"), seeds) // 權重池兩個候選都會被抽到
    }

    @Test
    fun `塊數不夠封閉就失敗,不回傳留著開口的半張圖`() {
        assertFailsWith<IllegalStateException> { JigsawPlanner(library).plan(ref("entrance"), StructureRotation.NONE, 1L, 2) }
    }

    @Test
    fun `cleanup 只還原仍屬於 generation 的格子`() {
        val world = HashMap<MapPos, String>()
        val cells = OwnedCells<String>()
        fun place(pos: MapPos, state: String) {
            val before = world[pos] ?: "air"
            world[pos] = state
            cells.record(pos.x, pos.y, pos.z, before, state)
        }
        place(MapPos(0, 0, 0), "stone")
        place(MapPos(1, 0, 0), "oak_stairs[facing=north]")
        place(MapPos(2, 0, 0), "stone")
        world[MapPos(1, 0, 0)] = "oak_stairs[facing=south]" // 同 Material,不同狀態 = 被別人改過
        world[MapPos(2, 0, 0)] = "glass"
        val counts = cells.revert({ x, y, z -> world[MapPos(x, y, z)] ?: "air" }, { x, y, z, s -> world[MapPos(x, y, z)] = s })
        assertEquals(listOf(1, 2), counts.toList())
        assertEquals("air", world[MapPos(0, 0, 0)])
        assertEquals("oak_stairs[facing=south]", world[MapPos(1, 0, 0)])
        assertEquals("glass", world[MapPos(2, 0, 0)])
    }

    @Test
    fun `revision 是內容雜湊`() {
        val a = library.template(ref("corridor_long")).id
        val b = library.template(ref("corridor_short")).id
        assertNotEquals(a.revision, b.revision)
        assertEquals(a, library.template(ref("corridor_long")).id)
    }
}
