package com.tinyyana.hanatoki.expedition

import java.io.File
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-09 稽核問題 2 的迴歸測試:待送見證不能只活在記憶體佇列裡。
 *
 * 這一層照 `InstanceJournalTest` 的做法,直接測落地檔案本身——不需要真的模擬「JVM 重啟」,
 * 因為 [PendingEvidenceStore] 本來就是無狀態的:`loadAll()` 讀到的東西完全取決於磁碟上有
 * 什麼檔案,新開一個 store 實例(等同重啟後重新 new 一個)讀到的結果必須跟舊實例寫下去的
 * 完全一樣。
 */
class PendingEvidenceStoreTest {

    private val dir: File = File(System.getProperty("java.io.tmpdir"), "hanatoki-pending-evidence-test-${UUID.randomUUID()}")
    private val logger: Logger = Logger.getAnonymousLogger().apply { level = Level.OFF }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun evidence(
        kitId: UUID = UUID.randomUUID(),
        deployed: Boolean = false,
    ) = ExpeditionEvidence(
        kitId = kitId,
        deskId = UUID.randomUUID(),
        packRevision = 3L,
        ability = "JUNCTION",
        product = "preparation_lining",
        playerId = UUID.randomUUID(),
        dungeonId = "test-roguelike",
        encounterId = "enc-1",
        deployed = deployed,
        runId = UUID.randomUUID(),
        packedAtMs = 1_000L,
        resolvedAtMs = 2_000L,
    )

    @Test
    fun `寫入後就算重新開一個 store 實例也讀得回來(模擬 JVM 重啟)`() {
        val e = evidence()
        PendingEvidenceStore(dir, logger).writeSync(e)

        // 重新 new 一個實例,不沿用任何記憶體狀態——這就是「重啟」對這個類別而言的完整定義。
        val reopened = PendingEvidenceStore(dir, logger)
        val loaded = reopened.loadAll()

        assertEquals(1, loaded.size)
        val read = loaded.single()
        assertEquals(e.kitId, read.kitId)
        assertEquals(e.deskId, read.deskId)
        assertEquals(e.packRevision, read.packRevision)
        assertEquals(e.ability, read.ability)
        assertEquals(e.product, read.product)
        assertEquals(e.playerId, read.playerId)
        assertEquals(e.dungeonId, read.dungeonId)
        assertEquals(e.encounterId, read.encounterId)
        assertEquals(e.deployed, read.deployed)
        assertEquals(e.runId, read.runId)
        assertEquals(e.packedAtMs, read.packedAtMs)
        assertEquals(e.resolvedAtMs, read.resolvedAtMs)
    }

    @Test
    fun `刪除之後重開實例就讀不到了(送達之後真的清乾淨)`() {
        val e = evidence()
        val store = PendingEvidenceStore(dir, logger)
        store.writeSync(e)
        store.delete(e.kitId)

        assertTrue(PendingEvidenceStore(dir, logger).loadAll().isEmpty())
    }

    @Test
    fun `同一個 kitId 只留一份(檔名天生去重)`() {
        val kitId = UUID.randomUUID()
        val store = PendingEvidenceStore(dir, logger)
        store.writeSync(evidence(kitId, deployed = false))
        store.writeSync(evidence(kitId, deployed = true)) // 同一個 kit 的第二次寫入(重試)

        val loaded = PendingEvidenceStore(dir, logger).loadAll()
        assertEquals(1, loaded.size, "同一個 kitId 不該留下兩份待送檔案")
        assertTrue(loaded.single().deployed, "後寫的那份應該蓋掉前一份,不是疊加")
    }

    @Test
    fun `onDiskCount 反映磁碟上實際的筆數,不是黑箱`() {
        val store = PendingEvidenceStore(dir, logger)
        assertEquals(0, store.onDiskCount())
        val a = evidence()
        val b = evidence()
        store.writeSync(a)
        store.writeSync(b)
        assertEquals(2, store.onDiskCount())
        store.delete(a.kitId)
        assertEquals(1, store.onDiskCount())
    }

    @Test
    fun `寫入後不會留下 tmp 檔`() {
        PendingEvidenceStore(dir, logger).writeSync(evidence())
        val leftovers = dir.listFiles { _, name -> name.endsWith(".evidence.tmp") }
        assertTrue(leftovers == null || leftovers.isEmpty())
    }

    @Test
    fun `cleanupTempFiles 清掉上次崩在寫到一半的殘骸,不動正常檔案`() {
        File(dir, "${UUID.randomUUID()}.evidence.tmp").also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1))
        val store = PendingEvidenceStore(dir, logger)
        val survivor = evidence()
        store.writeSync(survivor)

        store.cleanupTempFiles()

        assertTrue(dir.listFiles { _, n -> n.endsWith(".evidence.tmp") }!!.isEmpty())
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun `壞掉的檔案會被隔離成 corrupt 而不是靜靜遺失`() {
        val store = PendingEvidenceStore(dir, logger)
        val good = evidence()
        store.writeSync(good)
        File(dir, "${UUID.randomUUID()}.evidence").writeBytes(byteArrayOf(0, 0, 0, 0))

        val loaded = store.loadAll()
        assertEquals(listOf(good.kitId), loaded.map { it.kitId })
        val quarantined = dir.listFiles { _, name -> name.endsWith(".corrupt") }
        assertEquals(1, quarantined?.size)
    }
}
