package com.tinyyana.hanatoki.inventory

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Logger

/**
 * 一筆進場交易走到哪一步(ARCH §5.6「安全 Run 容器」)。
 *
 * 這個列舉的每一格都必須能回答同一個問題:**「JVM 現在死掉,玩家的永久背包在誰手上?」**
 * 恢復流程只看這個答案,不猜測崩潰發生在哪一行。
 */
enum class JournalState {
    /**
     * 交易已登記(instanceId、返回點、session 都寫下去了),但**玩家的背包一個字都還沒被動過**,
     * 快照也還沒拍。
     *
     * 恢復動作 = 什麼都不要寫進背包,只把玩家送回返回點並刪掉這筆。玩家手上那份就是唯一一份
     * 永久背包,對它做任何寫入都只會製造複製品或覆蓋掉他這段時間拿到的東西。
     */
    PREPARED,

    /**
     * 快照已經持久化,而且**引擎即將(或已經)清空玩家背包**。這是唯一「玩家背包可能已經不是
     * 永久狀態」的中間態,所以它必須先落地、才准動背包——順序反過來寫的話,「清完了但還沒
     * 記下來」那個瞬間崩潰,恢復流程會判定成 [PREPARED] 而放棄快照,玩家的東西就沒了。
     *
     * 恢復動作 = 用快照覆蓋(見 [InstanceInventoryService] 的冪等說明)。
     */
    CLEARING,

    /** Run 進行中,玩家手上是局內背包。恢復動作 = 用快照覆蓋。 */
    ACTIVE,

    /**
     * 收斂中(通關/死亡/離場/admin reset/關服)。恢復動作 = 用快照覆蓋(重跑安全)。
     *
     * 跟 [CLEARING]/[ACTIVE] 的恢復動作完全相同,分開是為了讓 `/hanatoki admin journal`
     * 與 log 看得出「這筆是崩在進場還是崩在收尾」——那是排查時第一個要問的問題。
     */
    RESTORING,
}

/** 恢復流程對一筆未完成交易能做的兩件事(見 [JournalRecovery])。 */
enum class RecoveryAction {
    /** 丟掉這筆,**不要寫任何東西進玩家背包**。 */
    DISCARD,

    /** 用快照覆蓋玩家背包(冪等,重跑安全)。 */
    RESTORE_SNAPSHOT,
}

/**
 * 「崩在這一步,該怎麼恢復」——整套崩潰安全的核心判斷,刻意抽成不碰 Bukkit 的純函數
 * (ARCH §5.2 規則 7),這樣它可以被單元測試直接打,而不是只能靠拔電源驗證。
 */
object JournalRecovery {

    /**
     * @param hasSnapshot 這筆紀錄有沒有快照。沒有快照就沒有東西可以還,不論狀態是什麼都只能
     *   [RecoveryAction.DISCARD]——這不是「放棄」,是「本來就沒有從玩家身上拿走東西」。
     *
     * 判斷只有一條線:**[JournalState.PREPARED] 代表引擎還沒動過玩家的背包**(CLEARING 一定
     * 先落地才准動),所以那時玩家手上那份就是唯一一份永久背包,寫任何東西進去都只會製造
     * 複製品或蓋掉他這段時間拿到的東西。其餘狀態一律覆蓋還原。
     */
    fun actionFor(state: JournalState, hasSnapshot: Boolean): RecoveryAction = when {
        !hasSnapshot -> RecoveryAction.DISCARD
        state == JournalState.PREPARED -> RecoveryAction.DISCARD
        else -> RecoveryAction.RESTORE_SNAPSHOT
    }
}

/**
 * 一筆進場交易的持久化紀錄。
 *
 * @param snapshot 進場前的永久背包(見 [InventorySnapshot]);[JournalState.PREPARED] 時是 null。
 */
class JournalRecord(
    val instanceId: UUID,
    val playerId: UUID,
    val dungeonId: String,
    val slotId: String,
    val sessionId: UUID?,
    val state: JournalState,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val returnPoint: ReturnPointData?,
    val snapshot: InventorySnapshot?,
) {
    fun withState(state: JournalState, nowMs: Long): JournalRecord =
        JournalRecord(instanceId, playerId, dungeonId, slotId, sessionId, state, createdAtMs, nowMs, returnPoint, snapshot)

    fun withSnapshot(snapshot: InventorySnapshot, state: JournalState, nowMs: Long): JournalRecord =
        JournalRecord(instanceId, playerId, dungeonId, slotId, sessionId, state, createdAtMs, nowMs, returnPoint, snapshot)

    fun withSession(sessionId: UUID?, nowMs: Long): JournalRecord =
        JournalRecord(instanceId, playerId, dungeonId, slotId, sessionId, state, createdAtMs, nowMs, returnPoint, snapshot)
}

/** 返回點:純資料,重啟後也還在(記憶體版的 `ReturnPointRegistry` 重啟就空了)。 */
class ReturnPointData(
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

/**
 * 進場交易的持久化 journal。
 *
 * ## 為什麼是自己寫的檔案而不是 SQLite
 *
 * HanaToki 是零依賴的通用引擎(ARCH §11:乾淨 Paper/Folia + 只有 HanaToki 就要能跑通),
 * 加一顆 JDBC driver 會讓它不再是「丟進去就能用」的 jar。而這裡要存的東西一筆就是一個
 * instance、生命週期短、沒有查詢需求——一個 instance 一個檔案是最貼合的形狀,連索引都不需要。
 * integration 側的 `CompletionStore` 用 SQLite 是因為它要跨月累積並做冪等查詢,情況不同。
 *
 * ## 崩潰安全怎麼來的
 *
 * 每次寫入都是 **temp 檔 → fsync → 原子改名**([writeSync])。改名在同一個資料夾內是檔案系統的
 * 原子操作,所以磁碟上永遠只會看到「上一個完整版本」或「這一個完整版本」,不會看到寫到一半的
 * 檔案。讀取端([readAll])遇到解析失敗的檔案不會靜默跳過,而是改名成 `.corrupt` 並記 severe
 * ——一筆讀不出來的 journal 代表有玩家的永久背包可能還沒還回去,那是要人看的事件。
 *
 * ## 執行緒
 *
 * [writeSync]/[readAll] 都是阻塞 I/O,**呼叫端負責把它們排到 AsyncScheduler**
 * (ARCH §5.2 規則 4「持久層 I/O 不上 region thread」)。這個類別自己不派工——它不知道
 * 呼叫端想不想等,而 [InstanceInventoryService] 的交易順序正好需要「等這次寫完再做下一步」。
 * 唯一的例外是 onDisable:那時 scheduler 已經停了,只能同步寫完(見該處註解)。
 */
class InstanceJournal(private val dir: File, private val logger: Logger) {

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            logger.severe("[HanaToki] 無法建立 journal 資料夾 ${dir.path},局內背包隔離將無法保證崩潰安全")
        }
    }

    private fun fileFor(instanceId: UUID) = File(dir, "$instanceId.journal")

    /**
     * 原子寫入一筆紀錄。回傳 false = 寫入失敗(呼叫端**必須**把它當成「這次進場不能繼續」,
     * 不能當警告忽略——journal 寫不進去就沒有崩潰安全可言)。
     */
    fun writeSync(record: JournalRecord): Boolean {
        val target = fileFor(record.instanceId)
        val tmp = File(dir, "${record.instanceId}.journal.tmp")
        return try {
            java.io.FileOutputStream(tmp).use { fos ->
                // ⚠ 不要對包在外面的 stream 用 `use`:它關閉時會把底下的 FileOutputStream 一起
                //   關掉,接著的 `fd.sync()` 就變成對已關閉的 fd 呼叫,直接丟 SyncFailedException
                //   ——整個 journal 寫入會靜靜地全部失敗(2026-08-29 單元測試抓到)。
                //   這裡只 flush 到 fos,fsync 之後才由外層的 use 關檔。
                val out = DataOutputStream(java.io.BufferedOutputStream(fos))
                encode(out, record)
                out.flush()
                // flush 只把資料交給作業系統;fsync 才是「斷電後還在」。這一步就是整套崩潰
                // 安全的地基,不能因為它慢就省掉。
                fos.fd.sync()
            }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (e: IOException) {
            logger.severe("[HanaToki] journal 寫入失敗 instance=${record.instanceId}:${e.message}")
            runCatching { tmp.delete() }
            false
        }
    }

    /** 交易正常收斂(RESTORED)= 刪檔。刪不掉會在下次啟動被重跑一次恢復,而恢復是冪等的。 */
    fun delete(instanceId: UUID) {
        val f = fileFor(instanceId)
        if (f.exists() && !f.delete()) {
            logger.warning("[HanaToki] journal 刪除失敗 instance=$instanceId,下次啟動會重跑一次恢復(冪等,不會出事)")
        }
    }

    fun read(instanceId: UUID): JournalRecord? {
        val f = fileFor(instanceId)
        if (!f.exists()) return null
        return decodeFile(f)
    }

    /** 掃出所有未完成的交易(啟動恢復用)。無法解析的檔案會被隔離成 `.corrupt` 並記 severe。 */
    fun readAll(): List<JournalRecord> {
        val files = dir.listFiles { _, name -> name.endsWith(".journal") } ?: return emptyList()
        val out = mutableListOf<JournalRecord>()
        for (f in files) {
            val record = decodeFile(f)
            if (record == null) {
                quarantine(f)
                continue
            }
            out += record
        }
        return out
    }

    /** 啟動時清掉上次崩在「寫到一半」的 temp 檔——它們依定義沒有被任何紀錄指向。 */
    fun cleanupTempFiles() {
        dir.listFiles { _, name -> name.endsWith(".journal.tmp") }?.forEach { it.delete() }
    }

    private fun quarantine(f: File) {
        val dest = File(dir, f.name + ".corrupt")
        logger.severe("[HanaToki] journal 檔案無法解析,已隔離成 ${dest.name} —— 這代表可能有玩家的永久背包還沒還回去,請人工檢查")
        runCatching { Files.move(f.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun decodeFile(f: File): JournalRecord? = try {
        DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { decode(it) }
    } catch (e: Exception) {
        logger.warning("[HanaToki] journal 解析失敗 ${f.name}:${e.message}")
        null
    }

    // ---- 編碼 ----------------------------------------------------------------
    //
    // 自訂二進位而不是 YAML/JSON:snapshot 是 `ItemStack.serializeItemsAsBytes` 的原始位元組
    // (原版 NBT,跨版本升級由核心自己處理),塞進文字格式要先 Base64 再解,多一層可能出錯的
    // 轉換,而這裡沒有任何人需要手動讀這個檔案。

    private fun encode(out: DataOutputStream, r: JournalRecord) {
        out.writeInt(MAGIC)
        out.writeInt(FORMAT_VERSION)
        writeUuid(out, r.instanceId)
        writeUuid(out, r.playerId)
        out.writeUTF(r.dungeonId)
        out.writeUTF(r.slotId)
        out.writeBoolean(r.sessionId != null)
        r.sessionId?.let { writeUuid(out, it) }
        out.writeUTF(r.state.name)
        out.writeLong(r.createdAtMs)
        out.writeLong(r.updatedAtMs)
        out.writeBoolean(r.returnPoint != null)
        r.returnPoint?.let { rp ->
            out.writeUTF(rp.worldName)
            out.writeDouble(rp.x); out.writeDouble(rp.y); out.writeDouble(rp.z)
            out.writeFloat(rp.yaw); out.writeFloat(rp.pitch)
        }
        out.writeBoolean(r.snapshot != null)
        r.snapshot?.let { s ->
            out.writeInt(s.heldSlot)
            out.writeInt(s.contentsSize)
            out.writeInt(s.itemBytes.size)
            out.write(s.itemBytes)
        }
    }

    private fun decode(input: DataInputStream): JournalRecord? {
        if (input.readInt() != MAGIC) return null
        val version = input.readInt()
        if (version != FORMAT_VERSION) {
            logger.warning("[HanaToki] journal 格式版本 $version 不是目前的 $FORMAT_VERSION")
            return null
        }
        val instanceId = readUuid(input)
        val playerId = readUuid(input)
        val dungeonId = input.readUTF()
        val slotId = input.readUTF()
        val sessionId = if (input.readBoolean()) readUuid(input) else null
        val state = JournalState.valueOf(input.readUTF())
        val createdAt = input.readLong()
        val updatedAt = input.readLong()
        val returnPoint = if (input.readBoolean()) {
            ReturnPointData(
                input.readUTF(),
                input.readDouble(), input.readDouble(), input.readDouble(),
                input.readFloat(), input.readFloat(),
            )
        } else {
            null
        }
        val snapshot = if (input.readBoolean()) {
            val heldSlot = input.readInt()
            val contentsSize = input.readInt()
            val len = input.readInt()
            val bytes = ByteArray(len)
            input.readFully(bytes)
            InventorySnapshot(bytes, heldSlot, contentsSize)
        } else {
            null
        }
        return JournalRecord(
            instanceId, playerId, dungeonId, slotId, sessionId, state,
            createdAt, updatedAt, returnPoint, snapshot,
        )
    }

    private fun writeUuid(out: DataOutputStream, id: UUID) {
        out.writeLong(id.mostSignificantBits)
        out.writeLong(id.leastSignificantBits)
    }

    private fun readUuid(input: DataInputStream): UUID = UUID(input.readLong(), input.readLong())

    private companion object {
        const val MAGIC = 0x48544A31 // "HTJ1"
        const val FORMAT_VERSION = 1
    }
}
