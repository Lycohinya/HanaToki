package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.config.CarryInDef
import com.tinyyana.hanatoki.config.InstanceInventoryDef
import com.tinyyana.hanatoki.expedition.ExpeditionCustodyDecision
import com.tinyyana.hanatoki.expedition.ExpeditionDispatcher
import com.tinyyana.hanatoki.expedition.ExpeditionEvidence
import com.tinyyana.hanatoki.expedition.ExpeditionKitPdc
import com.tinyyana.hanatoki.expedition.ExpeditionKitReader
import com.tinyyana.hanatoki.expedition.KitStatus
import com.tinyyana.hanatoki.folia.PlayerOp
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
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
    /** 見 [InstanceItemsImpl] 的同名參數:多人副本的隊友合法性判定要轉接 `SessionManager`。 */
    sessionMembersOf: (UUID) -> Collection<UUID> = { emptyList() },
    /** 整備包見證出口(見 `com.tinyyana.hanatoki.expedition.ExpeditionSink`)。 */
    private val expeditionDispatcher: ExpeditionDispatcher = ExpeditionDispatcher(plugin),
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

    val items: InstanceItemsImpl = InstanceItemsImpl(plugin, { playerId -> activeByPlayer[playerId] }, sessionMembersOf)

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
            // 可帶入物(見 CarryInDef):從**這次捕捉到的**永久背包快照裡挑出符合規則的格位——
            // 一定要用剛拍到的這份而不是上一次 attempt 的舊快照,重拍代表背包在這期間變過。
            val extraction = extractCarryIn(snapshot, def.carryIn)
            val clearing = base.withSnapshot(extraction.strippedSnapshot, JournalState.CLEARING, System.currentTimeMillis())
                .withCarryIn(extraction.escrow)
            runAsync { journal.writeSync(clearing) }.thenCompose { written ->
                if (!written) return@thenCompose CompletableFuture.completedFuture(false)
                // `matches()` 仍然要對**完整**快照比對(見下方 applyInstanceInventory):清空之前
                // 玩家背包裡還躺著那件即將被攜入的物品,拿 stripped 版本去比對永遠對不上。
                applyInstanceInventory(clearing, snapshot, def, attempt)
            }
        }
    }

    /** [extractCarryIn] 的結果:寫進 journal 的那份(已挖空攜入格位)快照 + 攜入清單。 */
    private class CarryExtraction(val strippedSnapshot: InventorySnapshot, val escrow: List<CarryInEscrow>)

    /**
     * 純選取邏輯([CarryInSelection])包一層 Bukkit PDC 讀取:把快照解回 [ItemStack] 陣列,
     * 用規則挑出要攜入的格位,回傳「已挖空那些格位的快照」+ 每一件的 [CarryInEscrow]。
     *
     * `itemBytes` 存的是**這一刻、還沒蓋 instance 章**的物品——之後不論是 restore 時原樣還給
     * 玩家,還是 startRestore 重讀六個 `lophinya:*` key 組見證,都不需要玩家在線或重新掃背包。
     */
    private fun extractCarryIn(snapshot: InventorySnapshot, rules: List<CarryInDef>): CarryExtraction {
        if (rules.isEmpty()) return CarryExtraction(snapshot, emptyList())
        val decoded = try {
            ItemStack.deserializeItemsFromBytes(snapshot.itemBytes)
        } catch (e: Exception) {
            return CarryExtraction(snapshot, emptyList())
        }
        val candidates = decoded.indices.mapNotNull { i ->
            val stack = decoded[i] ?: return@mapNotNull null
            if (stack.type == Material.AIR) return@mapNotNull null
            val meta = stack.itemMeta ?: return@mapNotNull null
            val pdc = meta.persistentDataContainer
            val values = rules.mapNotNull { rule ->
                val key = NamespacedKey(rule.pdcNamespace, rule.pdcKey)
                pdc.get(key, PersistentDataType.STRING)?.let { "${rule.pdcNamespace}:${rule.pdcKey}" to it }
            }.toMap()
            if (values.isEmpty()) null else CarryCandidate(i, values)
        }
        // 整備包契約(lophinya:kit)是一條特別嚴格的規則:除了「識別值存在且是合法 UUID」
        // (CarryInSelection 已經檢查過)之外,契約要求的其餘六個 PDC key 也都要能解析,
        // 一個缺就整份 fail closed——這件物品當成沒有比對到攜入規則,照舊留在永久背包快照裡。
        // 這不影響其他非 lophinya:kit 的一般攜入規則(它們只認識別值)。
        val selections = CarryInSelection.select(candidates, rules).filter { sel ->
            if (sel.rule.pdcNamespace != ExpeditionKitPdc.NAMESPACE || sel.rule.pdcKey != ExpeditionKitPdc.KIT) {
                true
            } else {
                val stack = decoded[sel.slotIndex]
                stack != null && ExpeditionKitReader.read(stack) != null
            }
        }
        if (selections.isEmpty()) return CarryExtraction(snapshot, emptyList())

        val chosenSlots = selections.map { it.slotIndex }.toSet()
        val escrow = selections.map { sel ->
            val stack = decoded[sel.slotIndex]!!
            CarryInEscrow(
                kitId = sel.kitId,
                pdcNamespace = sel.rule.pdcNamespace,
                pdcKey = sel.rule.pdcKey,
                itemBytes = ItemStack.serializeItemsAsBytes(arrayOf(stack)),
            )
        }
        val stripped = Array(decoded.size) { i ->
            if (i in chosenSlots) ItemStack(Material.AIR) else (decoded[i] ?: ItemStack(Material.AIR))
        }
        val strippedBytes = ItemStack.serializeItemsAsBytes(stripped)
        return CarryExtraction(InventorySnapshot(strippedBytes, snapshot.heldSlot, snapshot.contentsSize), escrow)
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
            // 攜入物品(見 CarryInDef):先在**原地**蓋上 instance 章,再清空——不能先 clear()
            // 整個背包,那樣會把還沒蓋章的攜入物品跟其他東西一起清掉。上面的 matches() 已經
            // 確認背包跟捕捉快照當下完全一致,所以這裡用同一組識別值一定找得到同一件物品。
            markCarryInPlace(p, clearing.carryIn, clearing.instanceId.toString())
            clearNonCarryInSlots(p, clearing.instanceId.toString())
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
            val occupied = slot != null && player.inventory.getItem(slot)?.type?.let { it != Material.AIR } == true
            if (slot != null && !occupied) {
                player.inventory.setItem(slot, stack)
            } else {
                if (occupied) {
                    plugin.logger.warning(
                        "[HanaToki] instance=$instanceId 局內起始裝備槽位 $slot 已被攜入物品占用,改放進背包空位",
                    )
                }
                player.inventory.addItem(stack)
            }
        }
    }

    /**
     * 把 [carryIn] 裡的每一件物品,在它**現在所在的格位**蓋上 instance 章。
     *
     * 不靠記憶體裡的 slot index([CarryInSelection] 選取時的格位)——重新用識別值([items] 的
     * PDC key)在目前背包裡比對一次,原因見 [captureAndPersist] 的 `matches()` 保證:走到這裡
     * 之前已經確認背包內容跟捕捉快照當下逐位元組相同,所以識別值一定能唯一對回同一件物品,
     * 而不依賴「格位沒有在兩次讀取之間被搬動」這個更弱的假設。
     */
    private fun markCarryInPlace(player: Player, carryIn: List<CarryInEscrow>, instanceId: String) {
        if (carryIn.isEmpty()) return
        val pending = carryIn.associateBy { it.kitId }.toMutableMap()
        val contents = player.inventory.contents
        for (slot in contents.indices) {
            if (pending.isEmpty()) break
            val stack = contents[slot] ?: continue
            if (stack.type == Material.AIR) continue
            val meta = stack.itemMeta ?: continue
            val matchedKit = pending.keys.firstOrNull { kitId ->
                val escrow = pending.getValue(kitId)
                val key = NamespacedKey(escrow.pdcNamespace, escrow.pdcKey)
                meta.persistentDataContainer.get(key, PersistentDataType.STRING) == kitId.toString()
            } ?: continue
            items.mark(stack, instanceId)
            player.inventory.setItem(slot, stack)
            pending.remove(matchedKit)
        }
        if (pending.isNotEmpty()) {
            plugin.logger.warning(
                "[HanaToki] instance=$instanceId 有 ${pending.size} 件攜入物品在蓋章前就從背包消失(不應發生,已略過)",
            )
        }
    }

    /** 清空背包裡**沒有**被蓋上這個 instance 章的格位——已經蓋章的攜入物品原地保留。 */
    private fun clearNonCarryInSlots(player: Player, instanceId: String) {
        val inventory = player.inventory
        val contents = inventory.contents
        for (slot in contents.indices) {
            val stack = contents[slot] ?: continue
            if (items.isInstanceScoped(stack) && items.instanceIdOf(stack) == instanceId) continue
            inventory.setItem(slot, null)
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

        // 見證出口只在「第一次」從 ACTIVE/CLEARING 轉進 RESTORING 時發一次(見 ExpeditionSink
        // 的 KDoc)。已經是 RESTORING 的話代表這是同一筆交易的重試/第二條收斂路徑撞進來
        // (見本函式呼叫端 [restore] 的說明),不能再發一次——不然同一個 kit 會被見證兩次。
        if (record.state != JournalState.RESTORING) {
            dispatchExpeditionEvidence(record, System.currentTimeMillis())
        }

        val restoring = record.withState(JournalState.RESTORING, System.currentTimeMillis())
        records[instanceId] = restoring
        return runAsync { journal.writeSync(restoring) }.thenCompose {
            writeSnapshotBack(restoring, reason, attempt = 0)
        }
    }

    /**
     * 對這筆交易攜入的每一個整備包(`pdc == lophinya:kit`)各發一次見證,不論有沒有被
     * [consumeCarriedKit] 消耗過(用 `deployed` 分辨)。額度([RewardQuotaLookup])用完
     * **不會**擋這裡——見證出口跟獎勵發放是兩條完全獨立的路徑,這個函式從頭到尾不查任何
     * quota 型別。
     */
    private fun dispatchExpeditionEvidence(record: JournalRecord, resolvedAtMs: Long) {
        for (escrow in record.carryIn) {
            if (escrow.pdcNamespace != ExpeditionKitPdc.NAMESPACE || escrow.pdcKey != ExpeditionKitPdc.KIT) continue
            val stack = try {
                ItemStack.deserializeItemsFromBytes(escrow.itemBytes).getOrNull(0)
            } catch (e: Exception) {
                null
            }
            val data = stack?.let { ExpeditionKitReader.read(it) }
            if (data == null) {
                plugin.logger.warning(
                    "[HanaToki] instance=${record.instanceId} kitId=${escrow.kitId} 見證前重讀整備包資料失敗(不應發生,已放棄這筆見證)",
                )
                continue
            }
            expeditionDispatcher.dispatch(
                ExpeditionEvidence(
                    kitId = escrow.kitId,
                    deskId = data.deskId,
                    packRevision = data.packRevision,
                    ability = data.ability,
                    product = data.product,
                    playerId = record.playerId,
                    dungeonId = record.dungeonId,
                    encounterId = escrow.deployEncounterId ?: "",
                    deployed = escrow.consumed,
                    runId = record.sessionId,
                    packedAtMs = data.packedAtMs,
                    resolvedAtMs = resolvedAtMs,
                ),
            )
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
            val ok = InventorySnapshot.restore(p, snapshot)
            // 沒被消耗掉的攜入物品原樣還回來(見 CarryInDef)。放在快照覆蓋**之後**——
            // `inv.contents = target` 是整組覆蓋,先放的話會被這一步蓋掉。
            if (ok) restoreUnconsumedCarryIn(p, record.instanceId, record.carryIn)
            done.complete(ok)
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

    /**
     * 把 [carryIn] 裡**沒被消耗**的物品原樣加回玩家永久背包(見 [ExpeditionCustodyDecision.shouldReturn])。
     * 已消耗的不處理——它們已經在 [consumeCarriedKit] 裡被真的移除了,這裡不重建。
     *
     * `escrow.itemBytes` 是 extraction 當下、還沒蓋 instance 章的物品,所以不需要額外脫章。
     * 背包滿了放不下的話丟在玩家腳下,而不是靜靜遺失。
     */
    private fun restoreUnconsumedCarryIn(player: Player, instanceId: UUID, carryIn: List<CarryInEscrow>) {
        for (escrow in carryIn) {
            if (!ExpeditionCustodyDecision.shouldReturn(KitStatus(escrow.kitId, escrow.consumed))) continue
            val stack = try {
                ItemStack.deserializeItemsFromBytes(escrow.itemBytes).getOrNull(0)
            } catch (e: Exception) {
                null
            }
            if (stack == null || stack.type == Material.AIR) {
                plugin.logger.warning("[HanaToki] instance=$instanceId kitId=${escrow.kitId} 攜入物品還原時解不開,已遺失")
                continue
            }
            val leftover = player.inventory.addItem(stack)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
        }
    }

    // ---- ExpeditionCustody(整備包首次有效部署,見 com.tinyyana.hanatoki.expedition) ------

    /** 這位玩家目前這次 run 帶進來、還沒消耗的整備包 kitId。沒有進行中的交易就回空清單。 */
    fun carriedKitsOf(playerId: UUID): List<UUID> {
        val instanceId = activeByPlayer[playerId] ?: return emptyList()
        val record = records[instanceId] ?: return emptyList()
        return ExpeditionCustodyDecision.carried(expeditionKitStatusesOf(record))
    }

    private fun expeditionKitStatusesOf(record: JournalRecord): List<KitStatus> =
        record.carryIn
            .filter { it.pdcNamespace == ExpeditionKitPdc.NAMESPACE && it.pdcKey == ExpeditionKitPdc.KIT }
            .map { KitStatus(it.kitId, it.consumed) }

    /**
     * 首次有效消耗(見 `ExpeditionCustody.deploy`)。**先物理移除、後更新 record**——中間丟例外
     * 的話寧可留下「物品沒了但帳沒記到」這種需要人工核對的窄縫,也不要反過來(記了帳但物品
     * 還在,等於免費複製一份效果)。
     *
     * ⚠ **刻意同步、不派工到 [PlayerOp]**:呼叫端(內容插件的 `DungeonBehavior` callback,
     * 典型情境是這位玩家自己觸發的 interaction)本來就已經在這位玩家所屬的 region 序列執行區
     * 執行(Folia 對玩家觸發的事件本來就派在該玩家自己的 region)——在那個情境裡用
     * `PlayerOp.dispatch` 再派工一次、卻要同步等它完成,會在同一條執行緒上等一個只能靠這條
     * 執行緒自己继续 tick 才會被排到的任務,等於自我鎖死。**呼叫端的責任**:只在觸發這次部署
     * 的那位玩家自己的 callback 裡呼叫,不要從計時器、其他玩家的 callback,或任何不保證是
     * 這位玩家所屬 region 的地方呼叫這個方法。
     *
     * ⚠ 已知窄縫:如果這個呼叫跟 `restore()` 在同一個 tick 內對同一個 instance 同時觸發
     * (玩家在部署整備包的同一瞬間斷線/被踢/逾時),`records` 的最終狀態可能跟物理背包狀態
     * 有一瞬間的落差(見下面的 `computeIfPresent` 保護與其後的 warning log)。這個窗口需要
     * 兩件事在同一刻撞在一起,機率極低,目前接受這個限制而不是為它加一整條跨 instance 的
     * 序列化佇列。
     */
    fun consumeCarriedKit(playerId: UUID, kitId: UUID, encounterId: String): Boolean {
        val instanceId = activeByPlayer[playerId] ?: return false
        val record = records[instanceId] ?: return false
        if (!ExpeditionCustodyDecision.canDeploy(record.state, expeditionKitStatusesOf(record), kitId)) return false
        val escrow = record.carryIn.firstOrNull { it.kitId == kitId } ?: return false
        val player = plugin.server.getPlayer(playerId) ?: return false

        if (!removeCarryInItem(player, instanceId.toString(), escrow)) return false

        val updated = records.computeIfPresent(instanceId) { _, current ->
            if (current.state != JournalState.ACTIVE) current
            else current.withCarryIn(current.carryIn.map { if (it.kitId == kitId) it.withConsumed(encounterId) else it })
        }
        val committed = updated?.carryIn?.firstOrNull { it.kitId == kitId }?.consumed == true
        if (!committed) {
            plugin.logger.warning(
                "[HanaToki] instance=$instanceId kitId=$kitId 物品已物理移除,但 instance 狀態已變更,消耗紀錄未落地(已知窄縫,見 consumeCarriedKit KDoc)",
            )
            return false
        }
        runAsync { journal.writeSync(updated) }.whenComplete { ok, _ ->
            if (ok != true) {
                plugin.logger.warning(
                    "[HanaToki] instance=$instanceId kitId=$kitId 消耗狀態寫入 journal 失敗(記憶體已更新,崩潰重啟後這件可能被誤判成未消耗而重新退回)",
                )
            }
        }
        return true
    }

    /** 在玩家背包裡找到這個 instance 章 + 這個 kitId 的那一件並移除。找不到回 false。 */
    private fun removeCarryInItem(player: Player, instanceId: String, escrow: CarryInEscrow): Boolean {
        val inventory = player.inventory
        val contents = inventory.contents
        val key = NamespacedKey(escrow.pdcNamespace, escrow.pdcKey)
        for (slot in contents.indices) {
            val stack = contents[slot] ?: continue
            if (stack.type == Material.AIR) continue
            if (!items.isInstanceScoped(stack) || items.instanceIdOf(stack) != instanceId) continue
            val raw = stack.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING) ?: continue
            if (raw != escrow.kitId.toString()) continue
            inventory.setItem(slot, null)
            return true
        }
        return false
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
