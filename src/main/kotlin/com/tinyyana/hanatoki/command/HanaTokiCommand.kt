package com.tinyyana.hanatoki.command

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.folia.PlayerOp
import com.tinyyana.hanatoki.instance.EnterResult
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
        // 進場前先記下每個人站在哪——副本在專屬世界裡,結束時要靠這個把人送回來
        // (見 HanaTokiCore.sendHome)。必須在 teleportAsync 之前記,而且要在確定 enter 成功之前
        //  記也無所謂:沒進場的話這筆登記會在下一次 sendHome 的 no-op 分支被清掉。
        party.forEach { core.returnPoints.remember(it) }
        val def = core.registry.definitions[dungeonId] ?: run {
            sender.sendMessage(core.texts.format("session.no-slot", mapOf("dungeon" to display)))
            return
        }
        when (val result = core.enter(dungeonId, party)) {
            is EnterResult.NoSlot -> sender.sendMessage(core.texts.format("session.no-slot", mapOf("dungeon" to display)))
            is EnterResult.Entered -> teleportParty(party, display, result.session, core.entryLocationFor(def, result.anchor))
            is EnterResult.Joined -> teleportParty(party, display, result.session, core.entryLocationFor(def, result.anchor))
        }
    }

    /** 進場落點在 [destination](anchor + 定義的 spawn 偏移,見 `DungeonDefinition.spawnOffsetX`)。 */
    private fun teleportParty(
        party: List<Player>,
        display: String,
        session: com.tinyyana.hanatoki.instance.Session,
        destination: org.bukkit.Location,
    ) {
        party.forEach { member ->
            // teleportAsync 的 callback 不保證在哪條執行緒——對玩家的訊息一律經 PlayerOp
            // 派回他自己的 EntityScheduler(ARCH §5.2 規則 2)。
            member.teleportAsync(destination).thenAccept { ok ->
                if (ok) {
                    PlayerOp.message(
                        core.plugin,
                        member.uniqueId,
                        core.texts.format(
                            "session.entered",
                            mapOf(
                                "dungeon" to display,
                                // 常駐 instance 沒有時限(timeLimitMs 是 Long.MAX_VALUE 的佔位值),
                                // 印出來會是一串沒有意義的數字。
                                "seconds" to if (session.persistent) "∞" else (session.timeLimitMs / 1000).toString(),
                            ),
                        ),
                    )
                } else {
                    PlayerOp.message(core.plugin, member.uniqueId, core.texts.format("session.teleport-failed"))
                    core.kick(member.uniqueId)
                }
            }
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
            }
            // 這台核心實際接受哪些 Mannequin 姿勢。`Mannequin.validPoses()` 是 runtime 橋接,
            // API jar 與 JavaDoc 都查不到內容,而且 Lecithin 是 Folia 分支不一定跟 Paper 一致
            // ——內容層要用哪個姿勢之前,在**實際跑的核心**上問這一句,不要憑文件推。
            "poses" -> {
                val poses = core.actorController.handleFor(java.util.UUID.randomUUID()).validPoseNames()
                sender.sendMessage("§7Mannequin 可用姿勢(${poses.size}):${poses.joinToString(", ").ifEmpty { "(核心未提供)" }}")
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
            else -> sender.sendMessage("§7/hanatoki admin <list|kick <player>|reset <slotId>|debug|poses|difftest <slotId> <count>|diffrollback <slotId>>")
        }
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
            "admin" -> listOf("list", "kick", "reset", "debug", "poses").filter { it.startsWith(args[1].lowercase()) }
            else -> emptyList()
        }
        3 -> when {
            args[0].equals("admin", true) && args[1].equals("reset", true) -> core.slotPool.slotIds()
            args[0].equals("admin", true) && args[1].equals("kick", true) ->
                Bukkit.getOnlinePlayers().map { it.name }
            else -> emptyList()
        }
        else -> emptyList()
    }
}
