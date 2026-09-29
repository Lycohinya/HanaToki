package com.tinyyana.hanatoki.inventory

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ReturnMailbox]:Run 期間移出的永久物品不會因為背包滿、寫檔失敗或崩潰而不可恢復地遺失。
 * 內容用任意位元組代表一件物品(實際是 `ItemStack.serializeAsBytes()`)。
 */
class ReturnMailboxTest {

    private val dir: File = Files.createTempDirectory("hanatoki-returns").toFile()
    private val logger: Logger = Logger.getLogger("ReturnMailboxTest")

    @AfterTest
    fun cleanup() {
        dir.walkBottomUp().forEach { it.setWritable(true); it.delete() }
    }

    private fun item(name: String) = name.toByteArray()

    @Test
    fun `deposit is visible in memory immediately and survives a restart after flush`() {
        val player = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        box.deposit(player, listOf(item("bread"), item("sword")))
        assertEquals(2, box.pendingCount(player))
        assertTrue(box.hasDirty())
        assertEquals(0, box.flushDirty())
        assertFalse(box.hasDirty())

        // 模擬崩潰重啟:新的實例只看磁碟。
        val reloaded = ReturnMailbox(dir, logger)
        assertEquals(2, reloaded.loadAll())
        assertEquals(listOf("bread", "sword"), reloaded.peek(player).map { String(it) })
    }

    @Test
    fun `full inventory leaves the rest in the box until space frees up`() {
        val player = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        box.deposit(player, listOf(item("a"), item("b"), item("c")))
        val attempted = box.peek(player)
        // 背包只放得下第一件,剩下兩件原樣留著。
        box.settle(player, attempted, attempted.drop(1))
        assertEquals(listOf("b", "c"), box.peek(player).map { String(it) })
        // 之後騰出空位,一次全部送完:箱子清空,檔案也刪掉。
        box.settle(player, box.peek(player), emptyList())
        assertEquals(0, box.pendingCount(player))
        box.flushDirty()
        assertTrue(dir.listFiles { _, n -> n.endsWith(".mailbox") }!!.isEmpty())
    }

    @Test
    fun `partial stack leftovers replace only the attempted entries`() {
        val player = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        box.deposit(player, listOf(item("arrows x64")))
        val attempted = box.peek(player)
        // 送件期間又有新東西進來(另一次巡檢),不能被結算吃掉。
        box.deposit(player, listOf(item("late")))
        box.settle(player, attempted, listOf(item("arrows x20")))
        assertEquals(listOf("late", "arrows x20"), box.peek(player).map { String(it) })
    }

    @Test
    fun `write failure keeps items in memory and retries until the disk works again`() {
        val player = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        // 讓目標檔名被一個資料夾佔住:原子改名一定失敗(不依賴作業系統權限)。
        val blocker = File(dir, "$player.mailbox").apply { mkdirs() }
        File(blocker, "keep").writeText("x")
        box.deposit(player, listOf(item("reward")))
        assertEquals(1, box.flushDirty())
        assertTrue(box.hasDirty())
        assertEquals(1, box.pendingCount(player)) // 東西沒有因為寫檔失敗而不見

        blocker.walkBottomUp().forEach { it.delete() }
        assertEquals(0, box.flushDirty())
        assertFalse(box.hasDirty())
        val reloaded = ReturnMailbox(dir, logger)
        reloaded.loadAll()
        assertEquals(listOf("reward"), reloaded.peek(player).map { String(it) })
    }

    @Test
    fun `corrupt files are quarantined instead of silently treated as empty`() {
        val player = UUID.randomUUID()
        File(dir, "$player.mailbox").writeText("garbage")
        val box = ReturnMailbox(dir, logger)
        assertEquals(0, box.loadAll())
        assertTrue(File(dir, "$player.mailbox.corrupt").exists())
    }

    @Test
    fun `crash between delivery and flush re-delivers instead of losing items`() {
        val player = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        box.deposit(player, listOf(item("gem")))
        box.flushDirty()
        // 送件完成、結算只到記憶體,還沒寫檔就崩潰:磁碟上仍是完整清單。
        box.settle(player, box.peek(player), emptyList())
        val afterCrash = ReturnMailbox(dir, logger)
        afterCrash.loadAll()
        assertEquals(listOf("gem"), afterCrash.peek(player).map { String(it) })
    }

    @Test
    fun `stale temp files from an interrupted write are ignored`() {
        val player = UUID.randomUUID()
        File(dir, "$player.mailbox.tmp").writeText("half")
        val box = ReturnMailbox(dir, logger)
        assertEquals(0, box.loadAll())
        assertFalse(File(dir, "$player.mailbox.tmp").exists())
    }

    @Test
    fun `arena recovery is idempotent per entity, across a restart`() {
        val player = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        val now = System.currentTimeMillis()
        assertTrue(box.recover(player, entity, item("trident"), now))
        // 同一個實體再出現一次(崩潰後從舊存檔回來):不再入箱
        assertFalse(box.recover(player, entity, item("trident"), now + 1_000))
        assertEquals(1, box.pendingCount(player))
        assertEquals(0, box.flushDirty())

        val reloaded = ReturnMailbox(dir, logger)
        reloaded.loadAll()
        assertTrue(reloaded.wasRecovered(player, entity))
        assertFalse(reloaded.recover(player, entity, item("trident"), now + 2_000))
        assertEquals(listOf("trident"), reloaded.peek(player).map { String(it) })
    }

    @Test
    fun `recovery keys outlive delivery so a late duplicate is still recognised`() {
        val player = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        box.recover(player, entity, item("trident"), System.currentTimeMillis())
        box.settle(player, box.peek(player), emptyList()) // 已經放回背包
        box.flushDirty()
        assertEquals(1, dir.listFiles { _, n -> n.endsWith(".mailbox") }!!.size, "物品送完了,鑰匙還要留著")

        val reloaded = ReturnMailbox(dir, logger)
        reloaded.loadAll()
        assertEquals(0, reloaded.pendingCount(player))
        assertFalse(reloaded.recover(player, entity, item("trident"), System.currentTimeMillis()))
    }

    @Test
    fun `expired recovery keys are pruned and the empty file removed`() {
        val player = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        box.recover(player, UUID.randomUUID(), item("trident"), 0)
        box.settle(player, box.peek(player), emptyList())
        assertTrue(box.flush(player, nowMs = 31L * 24 * 60 * 60 * 1000))
        assertTrue(dir.listFiles { _, n -> n.endsWith(".mailbox") }!!.isEmpty())
    }

    @Test
    fun `files without recovery keys keep the old format`() {
        val player = UUID.randomUUID()
        val box = ReturnMailbox(dir, logger)
        box.deposit(player, listOf(item("bread")))
        box.flushDirty()
        val bytes = File(dir, "$player.mailbox").readBytes()
        // MAGIC(4) 之後的版本號仍然是 1:回滾到舊版讀得懂
        assertEquals(1, java.nio.ByteBuffer.wrap(bytes, 4, 4).int)
    }
}
