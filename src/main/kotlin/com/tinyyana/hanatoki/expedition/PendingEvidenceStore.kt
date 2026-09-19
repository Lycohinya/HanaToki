package com.tinyyana.hanatoki.expedition

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Logger

/**
 * [ExpeditionEvidence] 還沒送到 sink 之前的落地處(稽核問題 2 的修法)。
 *
 * [ExpeditionDispatcher] 原本只把待送見證放在 `ConcurrentLinkedQueue`——那份佇列只活在
 * JVM 記憶體裡,重啟(不論是關服重啟還是崩潰)都會把它清空,而佇列本身完全不會被看見
 * (`pendingCount()` 只有 admin 主動查才看得到)。這裡照抄 [com.tinyyana.hanatoki.inventory.InstanceJournal]
 * 的落地手法(temp 檔 → fsync → 原子改名),讓「這筆見證還沒送出去」變成磁碟上的事實,
 * 而不是只存在於某個還活著的 JVM 裡。
 *
 * 檔名用 kitId 而不是隨機 id:同一個 kit 只應該有一筆待送見證,重複呼叫 [writeSync] 蓋掉
 * 舊檔是有意的覆蓋(同一個 kitId 的見證內容應該完全相同,見 [ExpeditionDispatcher] 呼叫端),
 * 不是遺失。這也讓「同一個 kitId 不會被送兩次」这个不變式在磁碟層就先天成立一半——
 * 另一半(送成功後刪檔)由呼叫端在確定送達後呼叫 [delete] 完成。
 */
class PendingEvidenceStore(private val dir: File, private val logger: Logger) {

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            logger.severe("[HanaToki] 無法建立見證待送資料夾 ${dir.path},關服期間排隊中的見證會遺失")
        }
    }

    private fun fileFor(kitId: UUID) = File(dir, "$kitId.evidence")

    /** 原子寫入一筆待送見證。呼叫端負責排到 AsyncScheduler(這個類別自己不派工,理由同 InstanceJournal)。 */
    fun writeSync(e: ExpeditionEvidence) {
        val target = fileFor(e.kitId)
        val tmp = File(dir, "${e.kitId}.evidence.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                // 不要對外層再包一層 `use`——跟 InstanceJournal 同一個坑:外層 stream 關閉時
                // 會把 fos 一起關掉,接著 `fd.sync()` 對已關閉的 fd 呼叫會直接丟例外。
                val out = DataOutputStream(BufferedOutputStream(fos))
                encode(out, e)
                out.flush()
                fos.fd.sync()
            }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (ex: IOException) {
            logger.severe("[HanaToki] 見證待送落地失敗 kitId=${e.kitId}:${ex.message}")
            runCatching { tmp.delete() }
        }
    }

    /** 已經確定送達 sink,這筆待送紀錄可以刪了。刪不掉不是嚴重錯誤——下次啟動重讀一次再送一次
     * 是安全的:[ExpeditionSink] 的呼叫端(`LophinyaMasteryBridge.record`)本來就要處理重複投遞。 */
    fun delete(kitId: UUID) {
        val f = fileFor(kitId)
        if (f.exists() && !f.delete()) {
            logger.warning("[HanaToki] 見證待送檔案刪除失敗 kitId=$kitId,下次啟動會重讀一次再送一次")
        }
    }

    /** 啟動時讀回所有還沒送出去的見證。無法解析的檔案隔離成 `.corrupt` 並記 severe——那代表一筆見證真的遺失了。 */
    fun loadAll(): List<ExpeditionEvidence> {
        val files = dir.listFiles { _, name -> name.endsWith(".evidence") } ?: return emptyList()
        val out = mutableListOf<ExpeditionEvidence>()
        for (f in files) {
            val e = decodeFile(f)
            if (e == null) {
                val dest = File(dir, f.name + ".corrupt")
                logger.severe("[HanaToki] 見證待送檔案無法解析,已隔離成 ${dest.name}——這筆見證已經遺失,請人工檢查")
                runCatching { Files.move(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                continue
            }
            out += e
        }
        return out
    }

    /** 啟動時清掉上次崩在「寫到一半」的 temp 檔(同 InstanceJournal.cleanupTempFiles)。 */
    fun cleanupTempFiles() {
        dir.listFiles { _, name -> name.endsWith(".evidence.tmp") }?.forEach { it.delete() }
    }

    /** 「現在有幾筆還沒送出去」——讓待送佇列不是黑箱(稽核問題 2 明文要求)。直接數磁碟上的檔案,
     * 不用記憶體佇列的 size:這樣即使記憶體佇列因為某個未知路徑跟磁碟不同步,回報的仍是可信的事實。 */
    fun onDiskCount(): Int = dir.listFiles { _, name -> name.endsWith(".evidence") }?.size ?: 0

    private fun encode(out: DataOutputStream, e: ExpeditionEvidence) {
        out.writeInt(MAGIC)
        out.writeInt(FORMAT_VERSION)
        writeUuid(out, e.kitId)
        writeUuid(out, e.deskId)
        out.writeLong(e.packRevision)
        out.writeUTF(e.ability)
        out.writeUTF(e.product)
        writeUuid(out, e.playerId)
        out.writeUTF(e.dungeonId)
        out.writeUTF(e.encounterId)
        out.writeBoolean(e.deployed)
        out.writeBoolean(e.runId != null)
        e.runId?.let { writeUuid(out, it) }
        out.writeLong(e.packedAtMs)
        out.writeLong(e.resolvedAtMs)
    }

    private fun decode(input: DataInputStream): ExpeditionEvidence? {
        if (input.readInt() != MAGIC) return null
        val version = input.readInt()
        if (version != FORMAT_VERSION) {
            logger.warning("[HanaToki] 見證待送檔案格式版本 $version 不是目前的 $FORMAT_VERSION")
            return null
        }
        val kitId = readUuid(input)
        val deskId = readUuid(input)
        val packRevision = input.readLong()
        val ability = input.readUTF()
        val product = input.readUTF()
        val playerId = readUuid(input)
        val dungeonId = input.readUTF()
        val encounterId = input.readUTF()
        val deployed = input.readBoolean()
        val runId = if (input.readBoolean()) readUuid(input) else null
        val packedAtMs = input.readLong()
        val resolvedAtMs = input.readLong()
        return ExpeditionEvidence(
            kitId = kitId,
            deskId = deskId,
            packRevision = packRevision,
            ability = ability,
            product = product,
            playerId = playerId,
            dungeonId = dungeonId,
            encounterId = encounterId,
            deployed = deployed,
            runId = runId,
            packedAtMs = packedAtMs,
            resolvedAtMs = resolvedAtMs,
        )
    }

    private fun decodeFile(f: File): ExpeditionEvidence? = try {
        DataInputStream(BufferedInputStream(FileInputStream(f))).use { decode(it) }
    } catch (ex: Exception) {
        logger.warning("[HanaToki] 見證待送檔案解析失敗 ${f.name}:${ex.message}")
        null
    }

    private fun writeUuid(out: DataOutputStream, id: UUID) {
        out.writeLong(id.mostSignificantBits)
        out.writeLong(id.leastSignificantBits)
    }

    private fun readUuid(input: DataInputStream): UUID = UUID(input.readLong(), input.readLong())

    private companion object {
        const val MAGIC = 0x48544532 // "HTE2":HanaToki 見證(Evidence)待送落地檔
        const val FORMAT_VERSION = 1
    }
}
