package com.tinyyana.hanatoki.inventory

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Run 期間被移出局內背包的**永久物品**,等玩家離場、背包有空位時再放回去。
 *
 * ## 為什麼不再塞進永久背包快照(2026-09-27 深域事故)
 *
 * 舊版 [ForeignItemWarden] 把抓到的東西直接併進 journal 的永久背包快照:
 * ① 快照只找空格、不疊堆,滿了整批回 null——東西已經從背包拿走,結果「那幾件已經遺失」;
 * ② 被誤判的合法局內物品(帶局內章)也一起被併進去,還原後變成「永久背包裡的失效局內物品」,
 *    下一次跨世界/登入的掃描再把它刪掉。快照是整套崩潰安全的權威狀態,不該被巡檢改寫。
 *
 * 這裡是一個**獨立**的暫存箱:只收沒有局內章的永久物品(呼叫端負責分類,見 [RunItemLegality]),
 * 不碰快照,也不限格數,所以不存在「放不下所以遺失」這條路。
 *
 * ## 持久化
 *
 * 一位玩家一個檔,每次寫入 temp → fsync → 原子改名(同 [InstanceJournal])。記憶體是權威鏡像:
 * 寫檔失敗時東西仍在記憶體裡(照樣會被送回),標成 dirty 由下一輪 [flushDirty] 重寫,
 * 只有「寫檔持續失敗 + 伺服器崩潰」同時發生才會遺失,而那時 log 裡會有 severe。
 *
 * 內容是 `ItemStack.serializeAsBytes()` 的原始位元組,這個類別本身不碰 Bukkit
 * (單元測試直接打,見 ReturnMailboxTest)。
 *
 * ## 執行緒
 *
 * 記憶體操作(deposit/peek/settle)任何執行緒都可以呼叫;實際上都發生在該玩家自己的 EntityScheduler。
 * [flush]/[flushDirty]/[loadAll] 是阻塞 I/O,呼叫端負責排到 AsyncScheduler(啟用/停用時除外)。
 */
class ReturnMailbox(private val dir: File, private val logger: Logger) {

    private val pending = ConcurrentHashMap<UUID, List<ByteArray>>()
    private val dirty: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val ioLock = Any()

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            logger.severe("[HanaToki] 無法建立暫存箱資料夾 ${dir.path},Run 期間移出的物品只存在記憶體裡")
        }
    }

    /** 把幾件物品放進玩家的暫存箱(記憶體立即生效,並標成待寫檔)。 */
    fun deposit(playerId: UUID, payloads: List<ByteArray>) {
        if (payloads.isEmpty()) return
        pending.merge(playerId, payloads.toList()) { old, new -> old + new }
        dirty += playerId
    }

    /**
     * 這位玩家目前的待送物品。**不取出**:送的過程中磁碟上那份一直保留完整清單,
     * 送完再用 [settle] 一次換成剩下的——中途崩潰頂多重送,不會遺失。
     */
    fun peek(playerId: UUID): List<ByteArray> = pending[playerId].orEmpty()

    /**
     * 一次送件的結算:把 [attempted](先前 [peek] 拿到的那幾件,以參照比對)換成 [leftovers]
     * (放不下、要繼續等的部分)。送件期間新 [deposit] 進來的東西不受影響。
     */
    fun settle(playerId: UUID, attempted: List<ByteArray>, leftovers: List<ByteArray>) {
        pending.compute(playerId) { _, current ->
            val rest = current.orEmpty().filter { entry -> attempted.none { it === entry } } + leftovers
            rest.ifEmpty { null }
        }
        dirty += playerId
    }

    fun pendingCount(playerId: UUID): Int = pending[playerId]?.size ?: 0

    fun playersWithPending(): Set<UUID> = pending.keys.toSet()

    fun hasDirty(): Boolean = dirty.isNotEmpty()

    /** 把這位玩家目前的記憶體狀態寫進磁碟(空了就刪檔)。回傳 false = 寫入失敗,仍標著 dirty。 */
    fun flush(playerId: UUID): Boolean = synchronized(ioLock) {
        // 先清 dirty 再讀狀態:讀完之後才發生的 deposit 會重新標 dirty,下一輪一定寫得到。
        dirty -= playerId
        val current = pending[playerId].orEmpty()
        val ok = if (current.isEmpty()) delete(playerId) else write(playerId, current)
        if (!ok) dirty += playerId
        ok
    }

    /** 重寫所有待寫檔的玩家,回傳仍然失敗的人數。 */
    fun flushDirty(): Int = dirty.toList().count { !flush(it) }

    /** 啟用時載入磁碟上的全部暫存箱。解析失敗的檔案隔離成 `.corrupt`,不會被當成空的覆蓋掉。 */
    fun loadAll(): Int {
        val files = dir.listFiles { _, name -> name.endsWith(SUFFIX) } ?: return 0
        dir.listFiles { _, name -> name.endsWith("$SUFFIX.tmp") }?.forEach { it.delete() }
        var loaded = 0
        for (f in files) {
            val decoded = try {
                DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { decode(it) }
            } catch (e: Exception) {
                null
            }
            if (decoded == null) {
                val dest = File(dir, f.name + ".corrupt")
                logger.severe("[HanaToki] 暫存箱檔案 ${f.name} 無法解析,已隔離成 ${dest.name},請人工檢查")
                runCatching { Files.move(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                continue
            }
            val (playerId, items) = decoded
            if (items.isEmpty()) continue
            pending.merge(playerId, items) { old, new -> old + new }
            loaded += items.size
        }
        return loaded
    }

    private fun fileFor(playerId: UUID) = File(dir, "$playerId$SUFFIX")

    private fun delete(playerId: UUID): Boolean {
        val f = fileFor(playerId)
        if (!f.exists()) return true
        if (f.delete()) return true
        logger.warning("[HanaToki] 暫存箱檔案刪除失敗 player=$playerId")
        return false
    }

    private fun write(playerId: UUID, items: List<ByteArray>): Boolean {
        val target = fileFor(playerId)
        val tmp = File(dir, "$playerId$SUFFIX.tmp")
        return try {
            java.io.FileOutputStream(tmp).use { fos ->
                val out = DataOutputStream(java.io.BufferedOutputStream(fos))
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeLong(playerId.mostSignificantBits)
                out.writeLong(playerId.leastSignificantBits)
                out.writeInt(items.size)
                for (bytes in items) {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
                out.flush()
                fos.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            logger.severe("[HanaToki] 暫存箱寫入失敗 player=$playerId:${e.message}(物品仍在記憶體,會再重試)")
            runCatching { tmp.delete() }
            false
        }
    }

    private fun decode(input: DataInputStream): Pair<UUID, List<ByteArray>>? {
        if (input.readInt() != MAGIC) return null
        if (input.readInt() != FORMAT_VERSION) return null
        val playerId = UUID(input.readLong(), input.readLong())
        val count = input.readInt()
        if (count < 0) return null
        val items = (0 until count).map {
            val len = input.readInt()
            if (len < 0) return null
            ByteArray(len).also { input.readFully(it) }
        }
        return playerId to items
    }

    private companion object {
        const val MAGIC = 0x48544D31 // "HTM1"
        const val FORMAT_VERSION = 1
        const val SUFFIX = ".mailbox"
    }
}
