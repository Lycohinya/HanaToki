package com.tinyyana.hanatoki.folia

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/** 放方塊一律不套物理(rc.38):雕刻南瓜 onPlace 的傀儡組成判定在場景分批寫入時會 NPE。 */
class BlockWritesTest {
    private val calls = mutableListOf<String>()

    private fun recordingBlock(): Block = Proxy.newProxyInstance(Block::class.java.classLoader, arrayOf(Block::class.java)) { _, method, args ->
        calls += method.name + "/" + (args?.lastOrNull().toString())
        null
    } as Block

    private fun recordingData(): BlockData = Proxy.newProxyInstance(BlockData::class.java.classLoader, arrayOf(BlockData::class.java)) { _, _, _ -> null } as BlockData

    @Test
    fun `block data write passes applyPhysics false`() {
        BlockWrites.set(recordingBlock(), recordingData())
        assertEquals(listOf("setBlockData/false"), calls)
    }

    @Test
    fun `material write passes applyPhysics false`() {
        BlockWrites.set(recordingBlock(), Material.CARVED_PUMPKIN)
        assertEquals(listOf("setType/false"), calls)
    }
}
