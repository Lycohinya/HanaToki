package com.tinyyana.hanatoki.inventory

import java.io.File
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * journal 的持久化行為。**這一層可以不碰 Bukkit 就測完**——[InventorySnapshot] 的位元組對
 * journal 而言只是一段不透明的 `ByteArray`(真正的序列化/還原是 Bukkit 的事,在 L3/L4 驗)。
 */
class InstanceJournalTest {

    private val dir: File = File(System.getProperty("java.io.tmpdir"), "hanatoki-journal-test-${UUID.randomUUID()}")
    private val logger: Logger = Logger.getAnonymousLogger().apply { level = Level.OFF }
    private val journal = InstanceJournal(dir, logger)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun record(
        instanceId: UUID = UUID.randomUUID(),
        state: JournalState = JournalState.PREPARED,
        snapshot: InventorySnapshot? = null,
        sessionId: UUID? = null,
        returnPoint: ReturnPointData? = ReturnPointData("world", 1.5, 64.0, -2.5, 90f, 10f),
    ) = JournalRecord(
        instanceId = instanceId,
        playerId = UUID.randomUUID(),
        dungeonId = "test-roguelike",
        slotId = "test-roguelike#0",
        sessionId = sessionId,
        state = state,
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        returnPoint = returnPoint,
        snapshot = snapshot,
    )

    @Test
    fun `寫入後讀得回完全一樣的內容`() {
        val snapshot = InventorySnapshot(byteArrayOf(1, 2, 3, -128, 127), heldSlot = 4, contentsSize = 41)
        val original = record(state = JournalState.ACTIVE, snapshot = snapshot, sessionId = UUID.randomUUID())
        assertTrue(journal.writeSync(original))

        val read = assertNotNull(journal.read(original.instanceId))
        assertEquals(original.instanceId, read.instanceId)
        assertEquals(original.playerId, read.playerId)
        assertEquals(original.dungeonId, read.dungeonId)
        assertEquals(original.slotId, read.slotId)
        assertEquals(original.sessionId, read.sessionId)
        assertEquals(JournalState.ACTIVE, read.state)
        assertEquals(original.createdAtMs, read.createdAtMs)
        assertEquals(original.updatedAtMs, read.updatedAtMs)
        assertEquals("world", read.returnPoint?.worldName)
        assertEquals(1.5, read.returnPoint?.x)
        assertEquals(-2.5, read.returnPoint?.z)
        assertEquals(90f, read.returnPoint?.yaw)
        assertContentEquals(snapshot.itemBytes, read.snapshot?.itemBytes)
        assertEquals(4, read.snapshot?.heldSlot)
        assertEquals(41, read.snapshot?.contentsSize)
    }

    @Test
    fun `沒有快照與沒有返回點的紀錄也能來回`() {
        val original = record(snapshot = null, returnPoint = null)
        assertTrue(journal.writeSync(original))
        val read = assertNotNull(journal.read(original.instanceId))
        assertNull(read.snapshot)
        assertNull(read.returnPoint)
        assertNull(read.sessionId)
    }

    @Test
    fun `同一個 instance 重複寫入是就地取代,不會留下兩份`() {
        val id = UUID.randomUUID()
        journal.writeSync(record(id, JournalState.PREPARED))
        journal.writeSync(
            record(id, JournalState.ACTIVE, snapshot = InventorySnapshot(byteArrayOf(9), 0, 41)),
        )
        val all = journal.readAll()
        assertEquals(1, all.size)
        assertEquals(JournalState.ACTIVE, all.single().state)
    }

    @Test
    fun `寫入後不會留下 temp 檔(原子改名已經把它換掉了)`() {
        journal.writeSync(record())
        val leftovers = dir.listFiles { _, name -> name.endsWith(".journal.tmp") }
        assertTrue(leftovers == null || leftovers.isEmpty(), "不該留下 .journal.tmp")
    }

    @Test
    fun `delete 之後就掃不到了`() {
        val r = record()
        journal.writeSync(r)
        journal.delete(r.instanceId)
        assertNull(journal.read(r.instanceId))
        assertTrue(journal.readAll().isEmpty())
    }

    @Test
    fun `readAll 掃得到多筆`() {
        repeat(3) { journal.writeSync(record()) }
        assertEquals(3, journal.readAll().size)
    }

    @Test
    fun `壞掉的檔案會被隔離成 corrupt 而不是靜靜跳過`() {
        val good = record()
        journal.writeSync(good)
        File(dir, "${UUID.randomUUID()}.journal").writeBytes(byteArrayOf(0, 0, 0, 0, 1, 2))

        val all = journal.readAll()
        assertEquals(listOf(good.instanceId), all.map { it.instanceId })
        val quarantined = dir.listFiles { _, name -> name.endsWith(".corrupt") }
        assertEquals(1, quarantined?.size, "壞檔案要被改名成 .corrupt 留給人工檢查")
    }

    @Test
    fun `cleanupTempFiles 清掉上次崩在寫到一半的殘骸`() {
        File(dir, "${UUID.randomUUID()}.journal.tmp").writeBytes(byteArrayOf(1))
        val survivor = record()
        journal.writeSync(survivor)

        journal.cleanupTempFiles()

        assertTrue(dir.listFiles { _, n -> n.endsWith(".journal.tmp") }!!.isEmpty())
        assertEquals(1, journal.readAll().size, "正常的 journal 不該被 cleanup 掃到")
    }

    @Test
    fun `withState 與 withSnapshot 不會弄丟其他欄位`() {
        val rp = ReturnPointData("nether", 3.0, 70.0, 4.0, 1f, 2f)
        val base = record(returnPoint = rp, sessionId = UUID.randomUUID())
        val snapshot = InventorySnapshot(byteArrayOf(7, 7), 8, 41)

        val clearing = base.withSnapshot(snapshot, JournalState.CLEARING, 5_000L)
        assertEquals(JournalState.CLEARING, clearing.state)
        assertEquals(base.instanceId, clearing.instanceId)
        assertEquals(base.createdAtMs, clearing.createdAtMs, "createdAt 是交易的出生時間,不該被更新覆蓋")
        assertEquals(5_000L, clearing.updatedAtMs)
        assertEquals(rp, clearing.returnPoint)
        assertEquals(base.sessionId, clearing.sessionId)

        val restoring = clearing.withState(JournalState.RESTORING, 6_000L)
        assertContentEquals(snapshot.itemBytes, restoring.snapshot?.itemBytes, "轉狀態不該弄丟快照")
        assertEquals(base.createdAtMs, restoring.createdAtMs)
    }

    @Test
    fun `讀一個不存在的 instance 回 null 而不是丟例外`() {
        assertNull(journal.read(UUID.randomUUID()))
    }

    @Test
    fun `建構子會自己把資料夾建起來`() {
        assertTrue(dir.exists())
        assertFalse(File(dir, "nothing.journal").exists())
    }
}
