package com.tinyyana.hanatoki.inventory

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
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
        carryIn: List<CarryInEscrow> = emptyList(),
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
        carryIn = carryIn,
    )

    @Test
    fun `carryIn 攜入清單來回不失真`() {
        val kitId = UUID.randomUUID()
        val original = record(
            carryIn = listOf(
                CarryInEscrow(kitId, "lophinya", "kit", byteArrayOf(5, 6, 7), consumed = false),
                CarryInEscrow(UUID.randomUUID(), "lophinya", "kit", byteArrayOf(1), consumed = true, deployEncounterId = "enc-1"),
            ),
        )
        assertTrue(journal.writeSync(original))
        val read = assertNotNull(journal.read(original.instanceId))
        assertEquals(2, read.carryIn.size)
        val unconsumed = read.carryIn.first { it.kitId == kitId }
        assertFalse(unconsumed.consumed)
        assertNull(unconsumed.deployEncounterId)
        assertContentEquals(byteArrayOf(5, 6, 7), unconsumed.itemBytes)
        val consumed = read.carryIn.first { it.kitId != kitId }
        assertTrue(consumed.consumed)
        assertEquals("enc-1", consumed.deployEncounterId)
    }

    @Test
    fun `沒有攜入物的既有紀錄照舊來回`() {
        val original = record()
        assertTrue(journal.writeSync(original))
        val read = assertNotNull(journal.read(original.instanceId))
        assertTrue(read.carryIn.isEmpty())
    }

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

    // ---- 2026-09 稽核問題 1:evidenceDispatched 的向後相容 --------------------------------

    @Test
    fun `evidenceDispatched 來回不失真`() {
        val original = record(state = JournalState.RESTORING).withEvidenceDispatched(true)
        assertTrue(journal.writeSync(original))
        val read = assertNotNull(journal.read(original.instanceId))
        assertTrue(read.evidenceDispatched, "寫 true 讀回來要還是 true")
    }

    @Test
    fun `新紀錄預設 evidenceDispatched 是 false`() {
        val original = record()
        assertTrue(journal.writeSync(original))
        val read = assertNotNull(journal.read(original.instanceId))
        assertFalse(read.evidenceDispatched)
    }

    /**
     * 手工組一份**舊格式(FORMAT_VERSION 2,沒有 evidenceDispatched 這個 boolean)**的檔案,
     * 模擬「這個修法上線之前就已經寫在磁碟上」的紀錄——不是靠 mock,是真的位元組格式差異。
     *
     * 驗證兩件事(缺一不可):
     * ① 讀得回來,不會被當成壞檔案隔離(向後相容的底線)。
     * ② `evidenceDispatched` 預設 false,而且 snapshot/carryIn/state 等其他欄位一個位元都沒變
     *   ——這對應「不得讓既有玩家的背包還原行為改變一個位元」的要求:格式升級只加欄位,
     *   不改寫任何舊資料本來就有的內容。
     */
    @Test
    fun `讀到沒有 evidenceDispatched 的舊格式紀錄時預設 false,且背包相關欄位完全不變`() {
        val instanceId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val kitId = UUID.randomUUID()
        val snapshotBytes = byteArrayOf(1, 2, 3, 4, 5)
        val carryBytes = byteArrayOf(9, 8, 7)
        val f = File(dir, "$instanceId.journal")

        writeLegacyV2Record(
            f, instanceId, playerId,
            dungeonId = "test-roguelike", slotId = "test-roguelike#0",
            sessionId = null, state = JournalState.RESTORING,
            createdAtMs = 111L, updatedAtMs = 222L,
            returnPoint = ReturnPointData("world", 1.0, 2.0, 3.0, 4f, 5f),
            snapshot = InventorySnapshot(snapshotBytes, heldSlot = 2, contentsSize = 41),
            carryIn = listOf(CarryInEscrow(kitId, "lophinya", "kit", carryBytes, consumed = false)),
        )

        val read = assertNotNull(journal.read(instanceId), "舊格式(version 2)紀錄不該被當成壞檔案拒絕")
        assertFalse(read.evidenceDispatched, "沒有這個欄位的舊紀錄要預設當作『還沒發過見證』")

        // 背包還原真正會用到的欄位:一個位元都不能變。
        assertEquals(JournalState.RESTORING, read.state)
        assertContentEquals(snapshotBytes, read.snapshot?.itemBytes)
        assertEquals(2, read.snapshot?.heldSlot)
        assertEquals(41, read.snapshot?.contentsSize)
        assertEquals(1, read.carryIn.size)
        assertContentEquals(carryBytes, read.carryIn.single().itemBytes)
        assertFalse(read.carryIn.single().consumed)
        assertEquals(playerId, read.playerId)
        assertEquals("world", read.returnPoint?.worldName)

        // 也確認 readAll()(啟動恢復掃描用的那條路徑)一樣讀得到、一樣向後相容。
        val all = journal.readAll()
        assertEquals(1, all.size)
        assertFalse(all.single().evidenceDispatched)
        val quarantined = dir.listFiles { _, name -> name.endsWith(".corrupt") }
        assertTrue(quarantined == null || quarantined.isEmpty(), "舊格式不該被隔離")
    }

    /** 照抄 FORMAT_VERSION=2 時代的 [InstanceJournal] 編碼邏輯,寫出一份沒有 evidenceDispatched 的檔案。 */
    private fun writeLegacyV2Record(
        target: File,
        instanceId: UUID,
        playerId: UUID,
        dungeonId: String,
        slotId: String,
        sessionId: UUID?,
        state: JournalState,
        createdAtMs: Long,
        updatedAtMs: Long,
        returnPoint: ReturnPointData?,
        snapshot: InventorySnapshot?,
        carryIn: List<CarryInEscrow>,
    ) {
        fun writeUuid(out: DataOutputStream, id: UUID) {
            out.writeLong(id.mostSignificantBits)
            out.writeLong(id.leastSignificantBits)
        }
        FileOutputStream(target).use { fos ->
            val out = DataOutputStream(BufferedOutputStream(fos))
            out.writeInt(0x48544A31) // MAGIC,同 InstanceJournal
            out.writeInt(2) // FORMAT_VERSION 2:carryIn 有,evidenceDispatched 沒有
            writeUuid(out, instanceId)
            writeUuid(out, playerId)
            out.writeUTF(dungeonId)
            out.writeUTF(slotId)
            out.writeBoolean(sessionId != null)
            sessionId?.let { writeUuid(out, it) }
            out.writeUTF(state.name)
            out.writeLong(createdAtMs)
            out.writeLong(updatedAtMs)
            out.writeBoolean(returnPoint != null)
            returnPoint?.let { rp ->
                out.writeUTF(rp.worldName)
                out.writeDouble(rp.x); out.writeDouble(rp.y); out.writeDouble(rp.z)
                out.writeFloat(rp.yaw); out.writeFloat(rp.pitch)
            }
            out.writeBoolean(snapshot != null)
            snapshot?.let { s ->
                out.writeInt(s.heldSlot)
                out.writeInt(s.contentsSize)
                out.writeInt(s.itemBytes.size)
                out.write(s.itemBytes)
            }
            out.writeInt(carryIn.size)
            for (c in carryIn) {
                writeUuid(out, c.kitId)
                out.writeUTF(c.pdcNamespace)
                out.writeUTF(c.pdcKey)
                out.writeInt(c.itemBytes.size)
                out.write(c.itemBytes)
                out.writeBoolean(c.consumed)
                out.writeBoolean(c.deployEncounterId != null)
                c.deployEncounterId?.let { out.writeUTF(it) }
            }
            // 版本 2 到此結束——沒有 evidenceDispatched 的 boolean。
            out.flush()
            fos.fd.sync()
        }
    }
}
