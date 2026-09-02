package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.config.InstanceInventoryDef
import com.tinyyana.hanatoki.folia.PlayerOp
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * 局內背包的交易與恢復(ARCH §5.6「安全 Run 容器」)。
 *
 * ## 不變式(整個類別存在的理由)
 *
 * > **只要快照曾經成功落地,不論 JVM 在哪一行死掉,玩家最後都能回到一份合法的永久背包
 * > ——恰好一份,不多也不少。**
 *
 * 這個保證不是靠「小心地按順序做事」達成的,是靠兩個性質:
 *
 * 1. **還原是覆蓋不是相加**([InventorySnapshot.restore] 用 `setContents`)。所以恢復流程
 *    重跑幾次都一樣,不需要知道上次跑到哪裡。
 * 2. **[JournalState.CLEARING] 一定先落地,才准動玩家背包**。所以「journal 說沒動過」
 *    就真的沒動過,那時放棄快照是安全的;「journal 說可能動過」就無條件覆蓋還原,那時
 *    覆蓋也是安全的。中間沒有第三種情況。
 *
 * ## 交易順序(以及為什麼是這個順序)
 *
 * ```
 * ①  prepare()   PREPARED  ← 寫下 instanceId / 返回點 / slot,背包一個字都沒動
 * ②  teleportAsync 真的落地(失敗就走 abort(),什麼都不用還原)
 * ③  activate()  拍快照 → CLEARING 落地 → 確認背包沒變 → 清空 + 發局內裝 → ACTIVE
 * ④  restore()   RESTORING 落地 → 覆蓋還原 → 刪 journal
 * ```
 *
 * 返回點在 ① 就持久化,而不是只放在記憶體的 `ReturnPointRegistry` 裡:重啟後那份登記表是空的,
 * 玩家會被丟到重生點而不是他原本站的地方。
 *
 * 快照刻意在 ③(傳送落地之後)才拍,不在 ① 拍:在 ① 拍的話,「拍完 → 傳送 → 清空」中間
 * 玩家如果死在主世界,他的東西會掉一地**而快照裡還有一份**,還原時就複製了。落地之後才拍,
 * 那個視窗不存在。②→③ 之間玩家已經站在場地上,不會有別的插件動他的背包。
 *
 * ③ 裡面「確認背包沒變」那一步是必要的:寫 CLEARING 是非同步 I/O(ARCH §5.2 規則 4:
 * 持久層 I/O 不上 region thread),那次讓出執行緒的期間背包理論上仍可能被改。不比對的話,
 * 那段期間拿到的東西會在還原時被舊快照默默蓋掉。
 *
 * ## 執行緒
 *
 * - 所有背包讀寫都經 [PlayerOp](該玩家自己的 EntityScheduler,ARCH §5.1④:背包是實體狀態)。
 * - 所有 journal I/O 都經 [runAsync](AsyncScheduler,ARCH §5.2 規則 4)。
 * - 查表(`activeInstanceIdOf`)是無鎖 ConcurrentHashMap,任何執行緒可呼叫(事件層每次
 *   丟棄/拾取都會問)。
 */
class InstanceInventoryService(
    private val plugin: Plugin,
    private val journal: InstanceJournal,
) {

    /** instanceId -> 目前這筆交易的紀錄(磁碟上那份的記憶體鏡像)。 */
    private val records = ConcurrentHashMap<UUID, JournalRecord>()

    /**
     * playerId -> instanceId,**只登記「玩家手上已經是局內背包」的狀態**
     * ([JournalState.CLEARING] / [JournalState.ACTIVE])。
     *
     * PREPARED 不登記:那時玩家手上還是永久背包,把他算成「在 Run 裡」會讓局內物品的合法性
     * 判定在還沒有任何局內物品的時候就開始放行。RESTORING 也不登記:收斂中的那一刻起,
     * 所有局內物品都應該立刻變成不合法(這樣它們會被事件層擋下來、被還原覆蓋掉)。
     */
    private val activeByPlayer = ConcurrentHashMap<UUID, UUID>()

    val items: InstanceItemsImpl = InstanceItemsImpl(plugin) { playerId -> activeByPlayer[playerId] }

    fun activeInstanceIdOf(playerId: UUID): UUID? = activeByPlayer[playerId]

    fun recordOf(instanceId: UUID): JournalRecord? = records[instanceId]

    /** `/hanatoki admin journal` 用:目前所有未收斂的交易。 */
    fun snapshotRecords(): List<JournalRecord> = records.values.toList()

    // ---- ① prepare ----------------------------------------------------------

    /**
     * 登記一筆交易並持久化返回點。**在傳送之前呼叫。**
     *
     * 回傳 instanceId;null = journal 寫不進去(呼叫端必須中止這次進場——沒有 journal 就沒有
     * 崩潰安全,寧可讓玩家看到「進場失敗」也不要讓他帶著永久背包進一個還不回來的地方)。
     */
    fun prepare(
        playerId: UUID,
        dungeonId: String,
        slotId: String,
        returnPoint: Location?,
    ): CompletableFuture<UUID?> {
        val instanceId = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val record = JournalRecord(
            instanceId = instanceId,
            playerId = playerId,
            dungeonId = dungeonId,
            slotId = slotId,
            sessionId = null,
            state = JournalState.PREPARED,
            createdAtMs = now,
            updatedAtMs = now,
            returnPoint = returnPoint?.let {
                ReturnPointData(it.world.name, it.x, it.y, it.z, it.yaw, it.pitch)
            },
            snapshot = null,
        )
        return runAsync { journal.writeSync(record) }.thenApply { ok ->
            if (!ok) return@thenApply null
            records[instanceId] = record
            instanceId
        }
    }

    /** 進場在傳送成功之前失敗:PREPARED 依定義沒動過背包,直接丟掉這筆就是完整回滾。 */
    fun abort(instanceId: UUID): CompletableFuture<Void> {
        records.remove(instanceId)
        activeByPlayer.entries.removeIf { it.value == instanceId }
        return runAsync { journal.delete(instanceId) }.thenApply { null }
    }

    // ---- ③ activate ---------------------------------------------------------

    /**
     * 傳送落地之後:拍快照 → CLEARING 落地 → 換成局內背包 → ACTIVE。
     *
     * 回傳 false = 這次進場的背包交易失敗(玩家不在線、快照寫不進去、背包一直在變)。
     * 呼叫端要當成進場失敗處理,並呼叫 [restore] 收斂(對 PREPARED/CLEARING 都安全)。
     */
    fun activate(instanceId: UUID, sessionId: UUID, def: InstanceInventoryDef): CompletableFuture<Boolean> {
        val base = records[instanceId] ?: return CompletableFuture.completedFuture(false)
        return captureAndPersist(base.withSession(sessionId, System.currentTimeMillis()), def, attempt = 0)
    }

    /**
     * 拍快照 → 寫 CLEARING → 回到玩家 region 確認背包沒變 → 清空發裝 → 寫 ACTIVE。
     *
     * 背包在寫 journal 那個空檔被改到的話重跑一次(最多 [MAX_CAPTURE_ATTEMPTS] 次)。
     * 重試次數用完就放棄整筆交易而不是「將就用舊快照」——一個一直在變的背包代表有別的東西
     * 正在動它,那時候清空玩家背包是最不該做的事。
     */
    private fun captureAndPersist(
        base: JournalRecord,
        def: InstanceInventoryDef,
        attempt: Int,
    ): CompletableFuture<Boolean> {
        val player = plugin.server.getPlayer(base.playerId)
            ?: return CompletableFuture.completedFuture(false)
        if (attempt >= MAX_CAPTURE_ATTEMPTS) {
            plugin.logger.warning(
                "[HanaToki] instance=${base.instanceId} 連續 $MAX_CAPTURE_ATTEMPTS 次拍快照期間背包都在變動,放棄這次局內背包交易",
            )
            return CompletableFuture.completedFuture(false)
        }

        // ⚠ `PlayerOp.dispatch` 在玩家已登出/實體已 retired 時**不會執行 action**,只會完成它
        //   自己回傳的 future。少了下面這行 fallback,整條交易鏈會永遠停在這裡等一個不會來的
        //   結果——而那正是「進場途中登出」這個必測情境。`complete` 對已完成的 future 是 no-op,
        //   所以正常路徑不受影響(action 先跑完,dispatch 的 future 才完成)。
        val captured = CompletableFuture<InventorySnapshot?>()
        PlayerOp.dispatch(plugin, player) { p -> captured.complete(InventorySnapshot.capture(p)) }
            .whenComplete { _, _ -> captured.complete(null) }

        return captured.thenCompose { snapshot ->
            if (snapshot == null) return@thenCompose CompletableFuture.completedFuture(false)
            val clearing = base.withSnapshot(snapshot, JournalState.CLEARING, System.currentTimeMillis())
            runAsync { journal.writeSync(clearing) }.thenCompose { written ->
                if (!written) return@thenCompose CompletableFuture.completedFuture(false)
                applyInstanceInventory(clearing, snapshot, def, attempt)
            }
        }
    }

    private fun applyInstanceInventory(
        clearing: JournalRecord,
        snapshot: InventorySnapshot,
        def: InstanceInventoryDef,
        attempt: Int,
    ): CompletableFuture<Boolean> {
        val player = plugin.server.getPlayer(clearing.playerId)
            ?: return CompletableFuture.completedFuture(false)

        // CLEARING 已經落地了,從這一刻起「背包可能已經被動過」是磁碟上的事實,所以就算
        // 下面比對失敗要重試,記憶體鏡像也要先掛上——中間崩潰時恢復流程才會選擇「覆蓋還原」
        // 這條安全的路。
        records[clearing.instanceId] = clearing

        val outcome = CompletableFuture<Int>() // 0=成功 1=背包變了要重拍 2=玩家已離線
        PlayerOp.dispatch(plugin, player) { p ->
            if (!InventorySnapshot.matches(p, snapshot)) {
                outcome.complete(1)
                return@dispatch
            }
            p.inventory.clear()
            p.inventory.heldItemSlot = 0
            applyLoadout(p, def, clearing.instanceId.toString())
            outcome.complete(0)
        }.whenComplete { _, _ -> outcome.complete(2) } // 玩家在這一步之前登出(見上方 fallback 說明)

        return outcome.thenCompose { code ->
            when (code) {
                1 -> captureAndPersist(clearing, def, attempt + 1)
                0 -> {
                    val active = clearing.withState(JournalState.ACTIVE, System.currentTimeMillis())
                    runAsync { journal.writeSync(active) }.thenApply { ok ->
                        records[clearing.instanceId] = if (ok) active else clearing
                        // ACTIVE 沒寫成功也不算失敗:磁碟上留著 CLEARING,恢復動作完全一樣
                        // (覆蓋還原),只是 log 上看不出崩在哪一步。
                        if (!ok) {
                            plugin.logger.warning("[HanaToki] instance=${clearing.instanceId} ACTIVE 狀態寫入失敗,journal 留在 CLEARING(恢復行為相同)")
                        }
                        activeByPlayer[clearing.playerId] = clearing.instanceId
                        true
                    }
                }
                else -> CompletableFuture.completedFuture(false)
            }
        }
    }

    private fun applyLoadout(player: Player, def: InstanceInventoryDef, instanceId: String) {
        for (entry in def.loadout) {
            val material = Material.matchMaterial(entry.material)
            if (material == null) {
                plugin.logger.warning("[HanaToki] 局內起始裝備的 material「${entry.material}」不是已知方塊/物品,略過")
                continue
            }
            val stack = ItemStack(material, entry.amount)
            entry.displayName?.let { name ->
                stack.editMeta { meta ->
                    // 客戶端對有自訂名稱的物品預設強制斜體,要明確關掉才會是正體
                    meta.displayName(
                        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(name)
                            .decorationIfAbsent(
                                net.kyori.adventure.text.format.TextDecoration.ITALIC,
                                net.kyori.adventure.text.format.TextDecoration.State.FALSE,
                            ),
                    )
                }
            }
            // 局內起始裝備一律蓋 instance 標記——沒蓋的話它就是永久物品,玩家帶得出去。
            items.mark(stack, instanceId)
            val slot = entry.slot
            if (slot != null) player.inventory.setItem(slot, stack) else player.inventory.addItem(stack)
        }
    }

    // ---- 局外物品的隔離 ------------------------------------------------------

    /**
     * 把 [ForeignItemWarden] 從局內背包裡拿走的非本局物品,移進那份還沒還給玩家的快照。
     *
     * 東西不會消失:它直接進入「離場時要覆蓋回去的那份永久背包」,沿用既有的還原路徑,
     * 不需要第二套恢復語意。只有 ACTIVE/CLEARING(= 快照已經落地)的交易才收——其他狀態
     * 依定義沒有快照可以放,那時候應該根本不會有 Run 在跑。
     */
    fun quarantine(instanceId: UUID, stacks: List<ItemStack>): CompletableFuture<Boolean> {
        if (stacks.isEmpty()) return CompletableFuture.completedFuture(true)
        val record = records[instanceId] ?: return CompletableFuture.completedFuture(false)
        val snapshot = record.snapshot ?: run {
            plugin.logger.warning("[HanaToki] instance=$instanceId 沒有快照可以收 ${stacks.size} 件局外物品,那幾件已經遺失")
            return CompletableFuture.completedFuture(false)
        }
        val merged = InventorySnapshot.withAdded(snapshot, stacks) ?: run {
            plugin.logger.warning("[HanaToki] instance=$instanceId 的永久背包快照放不下 ${stacks.size} 件局外物品(滿了或解不開),那幾件已經遺失")
            return CompletableFuture.completedFuture(false)
        }
        val updated = record.withSnapshot(merged, record.state, System.currentTimeMillis())
        records[instanceId] = updated
        return runAsync { journal.writeSync(updated) }.thenApply { ok ->
            if (!ok) plugin.logger.warning("[HanaToki] instance=$instanceId 隔離物品的快照寫不進 journal(記憶體鏡像已更新,崩潰的話那幾件會遺失)")
            ok
        }
    }

    // ---- ④ restore ----------------------------------------------------------

    /**
     * 收斂一筆交易:通關/死亡/主動離場/admin reset/斷線逾時/關服/崩潰重啟全部走這裡。
     *
     * 回傳 true = 已經完全收斂(journal 也刪了)。
     * 回傳 false = 還沒收斂(通常是玩家不在線),**journal 會留在 [JournalState.RESTORING]**,
     * 由 [recoverOnJoin] 在他下次登入時接手。這不是錯誤路徑,是設計:離線玩家的背包只能等他回來。
     */
    fun restore(instanceId: UUID, reason: String): CompletableFuture<Boolean> {
        // ⚠ 同一筆交易可能同時被兩條路徑收斂(`kick` 一條、`handleSessionEnded` 一條,
        //   或登入恢復撞上 session 結束)。**後到的那條必須等前一條,不能自己再跑一遍。**
        //
        //   2026-08-29 失敗注入測到的實際後果:兩條各自跑時,慢的那條會在快的那條刪掉
        //   journal **之後**才把 RESTORING 寫回去,留下一筆殘留紀錄——玩家的背包當下是對的,
        //   但下次登入會拿那份舊快照再覆蓋一次,把他這段期間拿到的東西清掉。
        //
        //   用 putIfAbsent 存「進行中的那個 future」而不是 computeIfAbsent + whenComplete:
        //   後者在 startRestore 同步完成時會在 map 的 compute 裡再動同一個 map(遞迴更新)。
        val holder = CompletableFuture<Boolean>()
        val inFlight = converging.putIfAbsent(instanceId, holder)
        if (inFlight != null) return inFlight
        startRestore(instanceId, reason).whenComplete { ok, error ->
            converging.remove(instanceId)
            if (error != null) holder.completeExceptionally(error) else holder.complete(ok ?: false)
        }
        return holder
    }

    /** 進行中的收斂管線,一筆交易同時只會有一條(見 [restore])。 */
    private val converging = ConcurrentHashMap<UUID, CompletableFuture<Boolean>>()

    private fun startRestore(instanceId: UUID, reason: String): CompletableFuture<Boolean> {
        val record = records[instanceId] ?: journal.read(instanceId)
            ?: return CompletableFuture.completedFuture(true) // 早就收斂完了
        activeByPlayer.remove(record.playerId, instanceId)

        if (JournalRecovery.actionFor(record.state, record.snapshot != null) == RecoveryAction.DISCARD) {
            // 沒動過背包(或根本沒拍到快照)——丟掉這筆就是完整收斂。判斷本身在
            // [JournalRecovery](純函數,有單元測試);這裡只負責執行。
            return abort(instanceId).thenApply { true }
        }

        val restoring = record.withState(JournalState.RESTORING, System.currentTimeMillis())
        records[instanceId] = restoring
        return runAsync { journal.writeSync(restoring) }.thenCompose {
            writeSnapshotBack(restoring, reason, attempt = 0)
        }
    }

    /** 依玩家查他進行中的那筆交易並收斂。沒有就直接回 true。 */
    fun restoreForPlayer(playerId: UUID, reason: String): CompletableFuture<Boolean> {
        val instanceId = activeByPlayer[playerId]
            ?: records.values.firstOrNull { it.playerId == playerId }?.instanceId
            ?: return CompletableFuture.completedFuture(true)
        return restore(instanceId, reason)
    }

    /**
     * @param attempt 重試次數。**這個重試不是防禦性程式碼,是一個實測到的競態的修法**:
     *   還原派工到玩家自己的 EntityScheduler,而收斂流程同一時間也在把玩家傳送出副本世界。
     *   跨世界傳送會讓舊 region 的 entity **retired**,那條還原 task 就走 retired 分支
     *   ——action 根本沒跑,而 journal 已經標成 RESTORING。接著跨世界的 `purgeIllegal` 把
     *   局內物品清掉,玩家就得到一個空背包(2026-08-29 L4 實測:`/hanatoki leave` 之後
     *   背包全空)。呼叫端的順序已經改成「先還原、確定完成才送人回家」,這個重試是第二道
     *   保險:玩家還在線卻沒還成,就過一下再試,而不是靜靜地留到下次登入。
     */
    private fun writeSnapshotBack(record: JournalRecord, reason: String, attempt: Int): CompletableFuture<Boolean> {
        val snapshot = record.snapshot ?: return CompletableFuture.completedFuture(false)
        val player = plugin.server.getPlayer(record.playerId)
        if (player == null) {
            plugin.logger.info("[HanaToki] instance=${record.instanceId} 的玩家不在線,journal 留在 RESTORING,等他下次登入還原($reason)")
            return CompletableFuture.completedFuture(false)
        }
        if (player.isDead) {
            // ⚠ 死亡當下不能寫背包:`PlayerDeathEvent` 之後原版才會把背包清空成掉落物,
            //   這時候放回去的東西會被那一步一起清掉(而 journal 已經標成 RESTORING,
            //   看起來像還完了)。主要的接手點是重生事件(見 `HanaTokiListener.onRespawn`);
            //   這裡的短重試只是對付「還原恰好跟死亡同一瞬間」的那幾百毫秒。
            //   兩邊都沒接到也不會掉東西:journal 留在 RESTORING,下次登入照樣還。
            if (attempt < MAX_RESTORE_ATTEMPTS) return retryRestoreLater(record, reason, attempt + 1)
            plugin.logger.info("[HanaToki] instance=${record.instanceId} 的玩家死亡中,等重生後再還原($reason)")
            return CompletableFuture.completedFuture(false)
        }
        // null = action 根本沒跑(玩家在這一步之前登出);false = 快照解不開。兩者都不刪 journal,
        // 但嚴重程度差很多:前者是常態,後者要人看。
        val done = CompletableFuture<Boolean?>()
        PlayerOp.dispatch(plugin, player) { p ->
            done.complete(InventorySnapshot.restore(p, snapshot))
        }.whenComplete { _, _ -> done.complete(null) }
        return done.thenCompose { ok ->
            if (ok == null) {
                // action 沒跑到。玩家還在線 = 撞上 retired(通常是同時在跨世界傳送),值得重試;
                // 真的離線了才是「等他下次登入」。
                if (plugin.server.getPlayer(record.playerId) != null && attempt < MAX_RESTORE_ATTEMPTS) {
                    return@thenCompose retryRestoreLater(record, reason, attempt + 1)
                }
                plugin.logger.info("[HanaToki] instance=${record.instanceId} 還原沒有執行到(玩家離線或實體已 retired),journal 留在 RESTORING,下次登入再還($reason)")
                return@thenCompose CompletableFuture.completedFuture(false)
            }
            if (!ok) {
                // 快照解不開:**不刪 journal**。刪了就等於宣告已還原,而玩家其實什麼都沒拿回去。
                plugin.logger.severe(
                    "[HanaToki] instance=${record.instanceId} 的快照無法還原,journal 保留在 RESTORING 供人工處理($reason)",
                )
                return@thenCompose CompletableFuture.completedFuture(false)
            }
            records.remove(record.instanceId)
            runAsync { journal.delete(record.instanceId) }.thenApply {
                plugin.logger.info("[HanaToki] instance=${record.instanceId} 永久背包已還原($reason)")
                true
            }
        }
    }

    /** 隔一小段時間再還一次(見 [writeSnapshotBack] 的 `attempt` 說明)。 */
    private fun retryRestoreLater(record: JournalRecord, reason: String, attempt: Int): CompletableFuture<Boolean> {
        val next = CompletableFuture<Boolean>()
        val scheduled = try {
            Bukkit.getAsyncScheduler().runDelayed(
                plugin,
                { _ -> writeSnapshotBack(record, reason, attempt).whenComplete { ok, _ -> next.complete(ok ?: false) } },
                RESTORE_RETRY_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        } catch (e: Exception) {
            null
        }
        if (scheduled == null) next.complete(false)
        return next
    }

    /** 這筆交易持久化下來的返回點(重啟後記憶體登記表是空的,靠這個把人送回去)。 */
    fun returnLocationOf(instanceId: UUID): Location? {
        val rp = (records[instanceId] ?: journal.read(instanceId))?.returnPoint ?: return null
        val world = plugin.server.getWorld(rp.worldName) ?: return null
        return Location(world, rp.x, rp.y, rp.z, rp.yaw, rp.pitch)
    }

    // ---- 恢復 ---------------------------------------------------------------

    /**
     * onEnable 掃描未完成的 journal(ARCH §5.2 規則 6「onEnable 接在線玩家」的延伸)。
     *
     * 在線的立刻收斂,離線的留著等 [recoverOnJoin]。這裡不做任何「猜崩在哪」的判斷——
     * [restore] 已經依 journal 狀態決定要不要覆蓋還原。
     */
    fun recoverAll() {
        journal.cleanupTempFiles()
        val pending = journal.readAll()
        if (pending.isEmpty()) return
        plugin.logger.info("[HanaToki] 發現 ${pending.size} 筆未收斂的局內背包交易,開始恢復")
        for (record in pending) {
            records[record.instanceId] = record
            // ⚠ 記憶體鏡像掛回去,但 activeByPlayer **不掛**:重啟後那一局已經不存在了
            //   (session/slot 都是記憶體狀態),所以玩家手上的局內物品從這一刻起就不合法。
            restore(record.instanceId, "startup-recovery")
        }
    }

    /** 玩家登入時:有沒有欠他一份永久背包。 */
    fun recoverOnJoin(playerId: UUID) {
        val record = records.values.firstOrNull { it.playerId == playerId } ?: return
        restore(record.instanceId, "login-recovery")
    }

    /**
     * onDisable 收斂。
     *
     * **同步把所有 journal 標成 RESTORING,但不碰背包。** 兩個理由:
     * ①`AsyncScheduler` 的任務在插件停用時會被取消,非同步寫入不保證跑得完;
     * ②關服途中往別的玩家的背包寫入,在 Folia 上沒有安全的執行緒可用(region 正在停)。
     *
     * 標成 RESTORING 之後,不論是「關服重啟」還是「PlugMan 熱插拔後重新啟用」,
     * [recoverAll] 都會在下次啟用時把它收乾淨——而在那之前玩家手上的局內物品已經不合法。
     *
     * 熱插拔(伺服器沒有在關)時額外做一次 best-effort 的即時還原,讓玩家不用等重新啟用。
     */
    fun shutdownFlush() {
        val hotSwap = !Bukkit.isStopping()
        for (record in records.values.toList()) {
            if (record.state == JournalState.PREPARED || record.snapshot == null) continue
            val restoring = record.withState(JournalState.RESTORING, System.currentTimeMillis())
            journal.writeSync(restoring) // 同步:此時不能再依賴 AsyncScheduler
            records[record.instanceId] = restoring
            activeByPlayer.remove(record.playerId, record.instanceId)
            if (hotSwap) writeSnapshotBack(restoring, "plugin-disable", attempt = 0)
        }
    }

    // ---- 執行緒工具 ----------------------------------------------------------

    /**
     * 排到 AsyncScheduler(ARCH §5.2 規則 4:持久層 I/O 不上 region thread)。
     *
     * 插件已停用時 `runNow` 會拒絕排程,那時直接在當下這條執行緒跑完——那個情境只有
     * [shutdownFlush] 之後的殘局,寧可阻塞一下也不要讓 journal 寫入靜靜地消失。
     */
    private fun <T> runAsync(action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val scheduled = try {
            Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
                try {
                    future.complete(action())
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                }
            }
        } catch (e: Exception) {
            null
        }
        if (scheduled == null && !future.isDone) {
            try {
                future.complete(action())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    private companion object {
        const val MAX_CAPTURE_ATTEMPTS = 3

        /** 還原沒派工到(玩家仍在線)時的重試上限與間隔,見 [writeSnapshotBack]。 */
        const val MAX_RESTORE_ATTEMPTS = 6
        const val RESTORE_RETRY_DELAY_MS = 250L
    }
}
