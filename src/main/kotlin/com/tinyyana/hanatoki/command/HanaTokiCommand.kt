package com.tinyyana.hanatoki.command

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.folia.PlayerOp
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * Phase 1 最小指令樹(migration plan 明文:無 GUI)。`enter`/`leave` 是 Phase 1 唯一的玩家
 * 進場管道(GUI 是 Phase 2+ 才接);`admin` 底下四個子指令是任務要求的最小管理工具集。
 */
class HanaTokiCommand(private val core: HanaTokiCore) : CommandExecutor, TabCompleter {

    /**
     * admin debug 指令目前生出來的 boss model(見 `model spawn`)——只記在指令這一層,
     * 不是引擎狀態。owner 收斂("debug:<uuid>")才是真正的安全網(玩家離線/插件關閉都會
     * 經 [com.tinyyana.hanatoki.presentation.BossModels.closeOwner]/`closeAll` 收掉),
     * 這裡只是拿來讓 `play`/`base`/`stop`/`locators`/`list` 知道「現在是哪一個」。
     */
    private val debugModels = java.util.concurrent.ConcurrentHashMap<java.util.UUID, com.tinyyana.hanatoki.presentation.BossModelHandle>()

    private companion object {
        /** `model locators` 印哪些骨頭——潘朵拉的少女：殘響模型上全部的 `loc_*` 空骨頭。 */
        val DEBUG_LOCATOR_NAMES = listOf(
            "loc_hand_r", "loc_hand_l", "loc_dice", "loc_halo", "loc_eyes", "loc_chest",
            "loc_mark", "loc_feet", "loc_rule", "loc_echo_1", "loc_echo_2", "loc_echo_3",
        )
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("§7/hanatoki <enter <dungeonId>|leave|admin <list|kick|reset|debug>>")
            return true
        }
        when (args[0].lowercase()) {
            "enter" -> handleEnter(sender, args)
            "leave" -> handleLeave(sender)
            "admin" -> handleAdmin(sender, args)
            else -> sender.sendMessage("§c未知子指令:${args[0]}")
        }
        return true
    }

    /**
     * `/hanatoki enter <dungeonId> [player2] [player3]...`——多人組隊進場(ARCH §2「Session 裡的
     * 玩家集合」原本就支援多人,Phase 1 指令只暴露了單人;Phase 2 的多玩家同時互動驗證需要真的把
     * 兩個玩家丟進同一個 session,這裡補齊指令面,不是新架構)。額外玩家名字找不到/不在線一律
     * 略過並提示,不整個取消進場。
     */
    private fun handleEnter(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run { sender.sendMessage("§c只有玩家能進場"); return }
        if (!player.hasPermission("hanatoki.enter")) { sender.sendMessage("§c沒有權限"); return }
        val dungeonId = args.getOrNull(1) ?: run { sender.sendMessage("§c用法:/hanatoki enter <dungeonId> [player2] ..."); return }
        val extraPlayers = args.drop(2).mapNotNull { name ->
            Bukkit.getPlayerExact(name) ?: run { sender.sendMessage("§c找不到玩家 $name,略過"); null }
        }
        val party = (listOf(player) + extraPlayers).distinct()
        val display = core.registry.definitions[dungeonId]?.display ?: dungeonId
        if (!core.hasDungeon(dungeonId)) {
            sender.sendMessage(core.texts.format("session.no-slot", mapOf("dungeon" to display)))
            return
        }
        // 進場交易(記返回點、分 slot、傳送、局內背包)全部在 HanaTokiCore/DungeonEntry 裡,
        // 指令只負責把結果講給人聽——以前這裡自己 teleportAsync 又自己補救失敗,那份邏輯
        // 跟 DungeonAccess 那條路各寫了一次,而且兩邊的回滾程度不一樣。
        core.enterParty(party, dungeonId).whenComplete { outcome, error ->
            if (error != null || outcome == null) {
                sender.sendMessage("§c進場交易丟出例外:" + error?.message)
                return@whenComplete
            }
            if (!outcome.succeeded()) {
                sender.sendMessage("§c進場失敗(" + outcome.status() + "):" + outcome.failureReason() + " / 已回滾=" + outcome.rolledBack())
                return@whenComplete
            }
            reportEntered(party, display, outcome.sessionId())
        }
    }

    /** 進場成功之後對每位成員說明這一局的時限(Endless Run 顯示 ∞ 而不是一串假秒數)。 */
    private fun reportEntered(party: List<Player>, display: String, sessionIdRaw: String?) {
        val session = sessionIdRaw
            ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?.let { core.sessionManager.sessionById(it) }
        val seconds = when {
            session == null -> "?"
            !session.hasTimeLimit() -> "∞"
            else -> ((session.timeLimitMs ?: 0L) / 1000).toString()
        }
        party.forEach { member ->
            PlayerOp.message(
                core.plugin,
                member.uniqueId,
                core.texts.format("session.entered", mapOf("dungeon" to display, "seconds" to seconds)),
            )
        }
    }

    private fun handleLeave(sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage("§c只有玩家能離開"); return }
        // 走 DungeonAccess 那條:session 型副本等同以前的 kick,常駐副本則會真的把人傳出去
        // (常駐世界不在 `dungeonWorldNames` 裡,`kick` 內部的 sendHome 對它是 no-op)。
        core.leaveDungeon(player.uniqueId)
        sender.sendMessage(core.texts.format("session.left"))
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("hanatoki.admin")) { sender.sendMessage("§c沒有權限"); return }
        val sub = args.getOrNull(1)?.lowercase()
        when (sub) {
            "content-disable", "content-reload" -> {
                val name = args.getOrNull(2) ?: run { sender.sendMessage("§c用法:/hanatoki admin $sub <plugin>"); return }
                val target = Bukkit.getPluginManager().getPlugin(name) ?: run { sender.sendMessage("§c找不到插件 $name"); return }
                if (!core.isContentOwner(target.name)) { sender.sendMessage("§c$name 沒有註冊副本內容"); return }
                if (target === core.plugin) { sender.sendMessage("§c不能用內容指令停用引擎"); return }
                sender.sendMessage("§7正在停止 $name 的入口並收回玩家與場地…")
                core.closeContentOwner(name).whenComplete { _, error ->
                    com.tinyyana.hanatoki.world.DungeonWorldProvisioner.runOnGlobalRegion(core.plugin, Runnable {
                        if (error != null) {
                            sender.sendMessage("§c收回失敗，未停用 $name: ${error.message}")
                        } else {
                            Bukkit.getPluginManager().disablePlugin(target)
                            if (sub == "content-reload") Bukkit.getPluginManager().enablePlugin(target)
                            sender.sendMessage("§a$name ${if (sub == "content-reload") "已重新啟用" else "已停用；替換 JAR 請依卸載／停服流程"}")
                        }
                    })
                }
            }
            "list" -> {
                val sessions = core.sessionManager.snapshot()
                if (sessions.isEmpty()) { sender.sendMessage("§7目前沒有進行中的 session"); return }
                sessions.forEach {
                    sender.sendMessage("§7session=${it.sessionId} dungeon=${it.dungeonId} slot=${it.slotId} members=${it.activeMembers().size}")
                }
            }
            "kick" -> {
                val name = args.getOrNull(2) ?: run { sender.sendMessage("§c用法:/hanatoki admin kick <player>"); return }
                val target = Bukkit.getPlayerExact(name) ?: run { sender.sendMessage("§c找不到玩家 $name"); return }
                core.kick(target.uniqueId)
                sender.sendMessage("§a已將 $name 移出所在 session")
            }
            "reset" -> {
                val slotId = args.getOrNull(2) ?: run { sender.sendMessage("§c用法:/hanatoki admin reset <slotId>"); return }
                core.adminReset(slotId)
                sender.sendMessage("§a已重置 slot $slotId")
            }
            "debug" -> {
                sender.sendMessage("§7=== HanaToki debug ===")
                sender.sendMessage("§7副本定義:${core.registry.definitions.keys}")
                core.registry.definitions.keys.forEach { id ->
                    sender.sendMessage("§7  $id: free=${core.slotPool.freeCount(id)}/${core.slotPool.totalCount(id)}")
                }
                sender.sendMessage("§7進行中 session 數:${core.sessionManager.snapshot().size}")
                sender.sendMessage("§7副本世界:${core.registry.dungeonWorldNames}")
                sender.sendMessage("§7未收斂的局內背包交易:" + core.instanceInventory.snapshotRecords().size + " 筆(明細:/hanatoki admin journal)")
                sender.sendMessage("§7" + core.stageEngine.dynamicEncounters.debugTotals())
            }
            // 這台核心實際接受哪些 Mannequin 姿勢。`Mannequin.validPoses()` 是 runtime 橋接,
            // API jar 與 JavaDoc 都查不到內容,而且 Lecithin 是 Folia 分支不一定跟 Paper 一致
            // ——內容層要用哪個姿勢之前,在**實際跑的核心**上問這一句,不要憑文件推。
            "poses" -> {
                val poses = core.actorController.handleFor(java.util.UUID.randomUUID()).validPoseNames()
                sender.sendMessage("§7Mannequin 可用姿勢(${poses.size}):${poses.joinToString(", ").ifEmpty { "(核心未提供)" }}")
            }
            // 局內背包交易的現況(同步面:改了功能就要有對應的管理視角)。
            "journal" -> {
                val records = core.instanceInventory.snapshotRecords()
                if (records.isEmpty()) { sender.sendMessage("§7目前沒有未收斂的局內背包交易"); return }
                sender.sendMessage("§7=== 局內背包 journal(" + records.size + " 筆未收斂)===")
                records.sortedBy { it.createdAtMs }.forEach { r ->
                    val who = Bukkit.getOfflinePlayer(r.playerId).name ?: r.playerId.toString()
                    val age = (System.currentTimeMillis() - r.updatedAtMs) / 1000
                    sender.sendMessage(
                        "§7  " + r.state + " instance=" + r.instanceId + " player=" + who +
                            " dungeon=" + r.dungeonId + " slot=" + r.slotId +
                            " 快照=" + (if (r.snapshot != null) "有" else "無") + " " + age + "s 前更新",
                    )
                }
                sender.sendMessage("§7(ACTIVE/RESTORING 表示還欠玩家一份永久背包,他下次登入就會還)")
            }
            // 強制收斂某一筆交易(玩家離線太久、或 journal 卡住需要人工推一把)。
            "restore" -> {
                val raw = args.getOrNull(2) ?: run { sender.sendMessage("§c用法:/hanatoki admin restore <instanceId>"); return }
                val instanceId = runCatching { java.util.UUID.fromString(raw) }.getOrNull()
                    ?: run { sender.sendMessage("§c" + raw + " 不是合法的 instanceId"); return }
                core.instanceInventory.restore(instanceId, "admin-restore").thenAccept { ok ->
                    sender.sendMessage(if (ok) "§a已還原 instance=" + instanceId else "§e尚未還原(玩家不在線或快照有問題),journal 保留")
                }
            }
            // 測試專用 hook(見 HanaTokiCore.testMutateSlot KDoc),不是 Phase 1 交付的玩法功能。
            "difftest" -> {
                val slotId = args.getOrNull(2) ?: run { sender.sendMessage("§c用法:/hanatoki admin difftest <slotId> <count>"); return }
                val count = args.getOrNull(3)?.toIntOrNull() ?: 500
                core.testMutateSlot(slotId, count).thenAccept { done ->
                    sender.sendMessage("§7difftest 完成:slot=$slotId mutated=$done pending=${core.diffRecorderFor(slotId).pendingCount()}")
                }
            }
            "diffrollback" -> {
                val slotId = args.getOrNull(2) ?: run { sender.sendMessage("§c用法:/hanatoki admin diffrollback <slotId>"); return }
                val before = core.diffRecorderFor(slotId).pendingCount()
                core.testRollbackSlot(slotId).thenAccept {
                    sender.sendMessage("§7diffrollback 完成:slot=$slotId reverted=$before pending=${core.diffRecorderFor(slotId).pendingCount()}")
                }
            }
            // 潘朵拉的少女：殘響外觀呈現的 debug 工具(見 presentation/BossModels.kt),BetterModel
            // 沒裝就整棵子指令直接回友善訊息,不散在每個分支重複判斷。
            "model" -> handleModelDebug(sender, args)
            // Map Asset layer 的 executable contract(見 testcontent/StructureProbe),只在隔離測試環境用。
            "mapprobe" -> {
                val slotId = args.getOrNull(2)
                val mode = args.getOrNull(3)?.lowercase()
                if (slotId == null || mode == null) { sender.sendMessage("§c用法:/hanatoki admin mapprobe <slotId> <fixed|jigsaw|foreign|fail|cancel> [seed]"); return }
                com.tinyyana.hanatoki.testcontent.StructureProbe.run(core, sender, slotId, mode, args.getOrNull(4)?.toLongOrNull() ?: 0L)
            }
            else -> sender.sendMessage("§7/hanatoki admin <list|kick <player>|reset <slotId>|debug|poses|journal|restore <instanceId>|difftest <slotId> <count>|diffrollback <slotId>|mapprobe <slotId> <mode>|model <spawn|play|base|stop|locators|list|clear>>")
        }
    }

    /**
     * `/hanatoki admin model ...`:潘朵拉的少女：殘響外觀呈現的 debug 工具。玩法(階段/技能/
     * hitbox/傷害)不在這裡——這裡只驗證 BetterModel 那一側的呈現(模型/動畫/locator)能不能動。
     */
    private fun handleModelDebug(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run { sender.sendMessage("§c只有玩家能用這個 debug 指令"); return }
        if (!core.bossModels.available) { sender.sendMessage("§c沒有裝 BetterModel,這個 debug 指令用不了"); return }
        val owner = "debug:${player.uniqueId}"
        when (args.getOrNull(2)?.lowercase()) {
            "spawn" -> {
                val modelId = args.getOrNull(3) ?: run { sender.sendMessage("§c用法:/hanatoki admin model spawn <modelId> [scale]"); return }
                val scale = args.getOrNull(4)?.toFloatOrNull()
                core.bossModels.closeOwner(owner)
                debugModels.remove(player.uniqueId)
                // 3 格前、面對玩家(所以模型的正面轉過來對著他,不是背對)。
                val dir = player.location.direction.clone().normalize()
                val front = player.location.clone().add(dir.multiply(3.0))
                front.yaw = player.location.yaw + 180f
                front.pitch = 0f
                // 2026-09-22 修正:DummyTracker(原本的 spawnStatic)不會自動追蹤任何觀察者,
                // 兩機器人封包測試證實沒有任何 client 收到過任何一個 item_display。真正的 Boss
                // 一律綁在活的 base entity 上(EntityTracker,那條路徑才會自動追蹤觀察者),
                // debug 指令改成生一隻專用的暫時載體再 bind——跟內建 `/bettermodel spawn`
                // 用 Husk 的作法一樣(那條路徑兩個 bot 都真的收到了 65 個 item_display)。
                // ActorController 需要一個真的在跑的 dungeon session 才會生 actor(見它的
                // `sessionActive` 檢查),這裡沒有 session,不能借用那條路,所以自己生一隻
                // 最小號的載體:不隱形不重要(BetterModel 會蓋掉外觀),但要不會亂動、不會被
                // 打死、不會被自然消失規則清掉。
                val world = front.world ?: run { sender.sendMessage("§c目前世界不存在"); return }
                val carrier = world.spawn(front, org.bukkit.entity.Husk::class.java) { husk ->
                    husk.setAI(false)
                    husk.isCollidable = false
                    husk.isInvulnerable = true
                    husk.isSilent = true
                    husk.isPersistent = false
                    husk.setGravity(false)
                    husk.setRemoveWhenFarAway(false)
                }
                val handle = core.bossModels.bind(owner, carrier, modelId)
                if (handle == null) {
                    carrier.remove() // bind 失敗:不留下沒人管的載體
                    sender.sendMessage("§c生成失敗:$modelId 不是已知的模型 id(見 /hanatoki admin model list 前先 spawn 一個能用的)")
                    return
                }
                scale?.let(handle::scale)
                if (handle.play("idle_hover", true, null)) handle.setBase("idle_hover")
                debugModels[player.uniqueId] = handle
                sender.sendMessage("§a已生成 $modelId" + (scale?.let { " scale=$it" } ?: ""))
            }
            "play" -> withDebugModel(sender, player) { handle ->
                val anim = args.getOrNull(3) ?: run { sender.sendMessage("§c用法:/hanatoki admin model play <animation>"); return@withDebugModel }
                sender.sendMessage(if (handle.play(anim)) "§a播放 $anim" else "§c這個模型沒有叫 $anim 的動畫")
            }
            "base" -> withDebugModel(sender, player) { handle ->
                val anim = args.getOrNull(3) ?: run { sender.sendMessage("§c用法:/hanatoki admin model base <animation>"); return@withDebugModel }
                handle.setBase(anim)
                sender.sendMessage("§a基底動畫設為 $anim")
            }
            "stop" -> withDebugModel(sender, player) { handle ->
                val anim = args.getOrNull(3) ?: run { sender.sendMessage("§c用法:/hanatoki admin model stop <animation>"); return@withDebugModel }
                handle.stop(anim)
                sender.sendMessage("§a已停止 $anim")
            }
            "locators" -> withDebugModel(sender, player) { handle ->
                sender.sendMessage("§7=== ${handle.modelId} locators ===")
                DEBUG_LOCATOR_NAMES.forEach { name ->
                    val loc = handle.locator(name)
                    sender.sendMessage(
                        if (loc != null) "§7  $name: " + "%.2f, %.2f, %.2f".format(loc.x, loc.y, loc.z) else "§7  $name: (無)",
                    )
                }
            }
            "list" -> withDebugModel(sender, player) { handle ->
                val names = core.bossModels.animationNames(handle.modelId)
                sender.sendMessage("§7${handle.modelId} 的動畫(" + names.size + "):" + names.joinToString(", ").ifEmpty { "(無)" })
            }
            "clear" -> {
                core.bossModels.closeOwner(owner)
                debugModels.remove(player.uniqueId)
                sender.sendMessage("§a已清除 debug 模型")
            }
            else -> sender.sendMessage("§7/hanatoki admin model <spawn <modelId> [scale]|play <anim>|base <anim>|stop <anim>|locators|list|clear>")
        }
    }

    private fun withDebugModel(sender: CommandSender, player: Player, action: (com.tinyyana.hanatoki.presentation.BossModelHandle) -> Unit) {
        val handle = debugModels[player.uniqueId] ?: run { sender.sendMessage("§c沒有生成中的 debug 模型,先 /hanatoki admin model spawn <modelId>"); return }
        action(handle)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = when (args.size) {
        1 -> listOf("enter", "leave", "admin").filter { it.startsWith(args[0].lowercase()) }
        2 -> when (args[0].lowercase()) {
            "enter" -> core.registry.definitions.keys.toList()
            "admin" -> listOf("list", "kick", "reset", "debug", "poses", "journal", "restore", "content-disable", "content-reload", "mapprobe", "model")
                .filter { it.startsWith(args[1].lowercase()) }
            else -> emptyList()
        }
        3 -> when {
            args[0].equals("admin", true) && args[1].equals("reset", true) -> core.slotPool.slotIds()
            args[0].equals("admin", true) && args[1].equals("kick", true) ->
                Bukkit.getOnlinePlayers().map { it.name }
            args[0].equals("admin", true) && args[1].equals("restore", true) ->
                core.instanceInventory.snapshotRecords().map { it.instanceId.toString() }
            args[0].equals("admin", true) && args[1].equals("model", true) ->
                listOf("spawn", "play", "base", "stop", "locators", "list", "clear")
            else -> emptyList()
        }
        4 -> when {
            args[0].equals("admin", true) && args[1].equals("model", true) && args[2].equals("spawn", true) ->
                core.bossModels.modelIds().toList()
            args[0].equals("admin", true) && args[1].equals("model", true) &&
                (args[2].equals("play", true) || args[2].equals("base", true) || args[2].equals("stop", true)) -> {
                val handle = (sender as? Player)?.let { p -> debugModels[p.uniqueId] }
                handle?.let { core.bossModels.animationNames(it.modelId).toList() } ?: emptyList()
            }
            else -> emptyList()
        }
        else -> emptyList()
    }
}
