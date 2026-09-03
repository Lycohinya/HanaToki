package com.tinyyana.hanatoki.folia

import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkWaveRunnerTest {
    @Test
    fun `wave size doubles when fast halves when slow and is clamped`() {
        assertEquals(4, ChunkWaveRunner.adjustWave(2, 1_000_000L))
        assertEquals(8, ChunkWaveRunner.adjustWave(8, 1_000_000L))
        assertEquals(2, ChunkWaveRunner.adjustWave(4, 20_000_000L))
        assertEquals(1, ChunkWaveRunner.adjustWave(1, 20_000_000L))
        assertEquals(4, ChunkWaveRunner.adjustWave(4, 5_000_000L))
    }

    @Test
    fun `chunk report prints milliseconds`() {
        val report = ChunkWorkReport(chunks = 64, waves = 12, preloadMillis = 30, workMillis = 900, maxChunkMillis = 4.25, totalChunkMillis = 210.0)
        assertEquals("chunks=64 waves=12 preload=30ms work=900ms maxChunk=4.3ms sumChunk=210ms", report.toString())
    }
}
