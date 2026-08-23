package com.tinyyana.hanatoki.command

import com.tinyyana.hanatoki.HanaTokiCore
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

    private fun handleEnter(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run { sender.sendMessage("§c只有玩家能進場"); return }
        if (!player.hasPermission("hanatoki.enter")) { sender.sendMessage("§c沒有權限"); return }
        val dungeonId = args.getOrNull(1) ?: run { sender.sendMessage("§c用法:/hanatoki enter <dungeonId>"); return }
        when (val result = core.enter(dungeonId, listOf(player))) {
            is EnterResult.NoSlot -> sender.sendMessage("§c副本 $dungeonId 目前客滿或不存在,稍後再試")
            is EnterResult.Entered -> {
                player.teleportAsync(result.anchor).thenAccept { ok ->
                    if (ok) {
                        player.sendMessage("§a已進入副本 $dungeonId(slot=${result.session.slotId}),限時 ${result.session.timeLimitMs / 1000} 秒")
                    } else {
                        sender.sendMessage("§c傳送失敗,已從 session 移除")
                        core.kick(player.uniqueId)
                    }
                }
            }
        }
    }

    private fun handleLeave(sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage("§c只有玩家能離開"); return }
        core.kick(player.uniqueId)
        sender.sendMessage("§a已離開副本")
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
            else -> sender.sendMessage("§7/hanatoki admin <list|kick <player>|reset <slotId>|debug|difftest <slotId> <count>|diffrollback <slotId>>")
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
            "admin" -> listOf("list", "kick", "reset", "debug").filter { it.startsWith(args[1].lowercase()) }
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
