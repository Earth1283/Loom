package io.github.earth1283.loom.command

import io.github.earth1283.loom.api.ScriptManager
import io.github.earth1283.loom.config.LoomConfig
import io.github.earth1283.loom.web.AuthManager
import io.github.earth1283.loom.web.WebServer
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class LoomCommand(
    private val scripts: ScriptManager,
    private val auth: AuthManager,
    private val config: LoomConfig,
    private val webServer: WebServer?,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (config.allowedOpsOnly && !sender.isOp) {
            sender.sendMessage("${ChatColor.RED}You don't have permission to use Loom.")
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "confirm" -> cmdConfirm(sender, args)
            "run", "load" -> cmdLoad(sender, args)
            "stop", "unload" -> cmdUnload(sender, args)
            "reload" -> cmdReload(sender, args)
            "list" -> cmdList(sender)
            "new" -> cmdNew(sender, args)
            "delete" -> cmdDelete(sender, args)
            "open", "editor" -> cmdOpenEditor(sender)
            "info" -> cmdInfo(sender, args)
            "revoke" -> cmdRevoke(sender, args)
            "reload-config" -> cmdReloadConfig(sender)
            "help" -> sendHelp(sender)
            else -> {
                sender.sendMessage("${ChatColor.RED}Unknown subcommand. Use /loom help.")
            }
        }
        return true
    }

    private fun cmdConfirm(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            sender.sendMessage("${ChatColor.RED}Usage: /loom confirm <code>")
            return
        }
        val code = args[1]
        val playerName = sender.name
        val sessionId = auth.confirm(code, playerName)
        if (sessionId == null) {
            sender.sendMessage("${ChatColor.RED}Invalid or expired confirmation code.")
        } else {
            sender.sendMessage("${ChatColor.GREEN}Editor session authenticated! You can now use the Loom web editor.")
            if (config.debugMode) sender.sendMessage("${ChatColor.GRAY}Session: $sessionId")
        }
    }

    private fun cmdLoad(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) { sender.sendMessage("${ChatColor.RED}Usage: /loom load <name>"); return }
        val name = args[1]
        sender.sendMessage("${ChatColor.YELLOW}Loading script '$name'...")
        val diags = scripts.load(name)
        val script = scripts.get(name)
        if (script == null) {
            sender.sendMessage("${ChatColor.RED}Script '$name' not found.")
            return
        }
        if (diags.isEmpty()) {
            sender.sendMessage("${ChatColor.GREEN}Script '$name' loaded successfully.")
        } else {
            sender.sendMessage("${ChatColor.RED}Script '$name' loaded with ${diags.size} error(s):")
            diags.forEach { d -> sender.sendMessage("  ${ChatColor.RED}[${d.line}:${d.col}] ${d.message}") }
        }
    }

    private fun cmdUnload(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) { sender.sendMessage("${ChatColor.RED}Usage: /loom unload <name>"); return }
        val name = args[1]
        scripts.unload(name)
        sender.sendMessage("${ChatColor.YELLOW}Script '$name' unloaded.")
    }

    private fun cmdReload(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            sender.sendMessage("${ChatColor.YELLOW}Reloading all scripts...")
            scripts.reloadAll()
            sender.sendMessage("${ChatColor.GREEN}All scripts reloaded.")
            return
        }
        val name = args[1]
        sender.sendMessage("${ChatColor.YELLOW}Reloading '$name'...")
        val diags = scripts.reload(name)
        if (diags.isEmpty()) sender.sendMessage("${ChatColor.GREEN}Script '$name' reloaded.")
        else {
            sender.sendMessage("${ChatColor.RED}Reloaded with ${diags.size} error(s):")
            diags.forEach { d -> sender.sendMessage("  ${ChatColor.RED}[${d.line}:${d.col}] ${d.message}") }
        }
    }

    private fun cmdList(sender: CommandSender) {
        val all = scripts.all()
        val disk = scripts.listScripts()
        sender.sendMessage("${ChatColor.AQUA}=== Loom Scripts ===")
        if (disk.isEmpty()) {
            sender.sendMessage("  ${ChatColor.GRAY}No scripts found.")
            return
        }
        for (name in disk) {
            val s = all[name]
            val stateColor = when (s?.state?.name) {
                "RUNNING" -> ChatColor.GREEN
                "ERROR" -> ChatColor.RED
                else -> ChatColor.GRAY
            }
            val stateLabel = s?.state?.name ?: "UNLOADED"
            sender.sendMessage("  ${ChatColor.WHITE}$name ${stateColor}[$stateLabel]")
        }
    }

    private fun cmdNew(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) { sender.sendMessage("${ChatColor.RED}Usage: /loom new <name>"); return }
        val name = args[1].replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        scripts.createScript(name)
        sender.sendMessage("${ChatColor.GREEN}Created script '$name'. Open the editor or use /loom load $name to run it.")
    }

    private fun cmdDelete(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) { sender.sendMessage("${ChatColor.RED}Usage: /loom delete <name>"); return }
        val name = args[1]
        scripts.deleteScript(name)
        sender.sendMessage("${ChatColor.YELLOW}Deleted script '$name'.")
    }

    private fun cmdOpenEditor(sender: CommandSender) {
        val url = "http://${if (config.webHost == "0.0.0.0") "localhost" else config.webHost}:${config.webPort}"
        if (sender is Player) {
            val msg = TextComponent("${ChatColor.GREEN}Open Loom Editor: ")
            val link = TextComponent("${ChatColor.AQUA}${ChatColor.UNDERLINE}$url")
            link.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, url)
            link.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text("Click to open the editor"))
            sender.spigot().sendMessage(msg, link)
            sender.sendMessage("${ChatColor.GRAY}Then run /loom confirm <code> shown in the browser to authenticate.")
        } else {
            sender.sendMessage("Editor URL: $url")
        }
    }

    private fun cmdInfo(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) { sender.sendMessage("${ChatColor.RED}Usage: /loom info <name>"); return }
        val name = args[1]
        val script = scripts.get(name) ?: run {
            sender.sendMessage("${ChatColor.RED}Script '$name' is not loaded.")
            return
        }
        sender.sendMessage("${ChatColor.AQUA}=== Script: $name ===")
        sender.sendMessage("  State: ${script.state}")
        sender.sendMessage("  Commands: ${script.getCommandNames().joinToString(", ").ifEmpty { "none" }}")
        sender.sendMessage("  Events: ${script.getEventNames().joinToString(", ").ifEmpty { "none" }}")
        script.lastError?.let { sender.sendMessage("  ${ChatColor.RED}Last error: $it") }
    }

    private fun cmdRevoke(sender: CommandSender, args: Array<String>) {
        if (args.size < 2 || args[1] == "all") {
            auth.revokeAll()
            sender.sendMessage("${ChatColor.YELLOW}All editor sessions revoked.")
        } else {
            auth.revokeSession(args[1])
            sender.sendMessage("${ChatColor.YELLOW}Session revoked.")
        }
    }

    private fun cmdReloadConfig(sender: CommandSender) {
        sender.sendMessage("${ChatColor.YELLOW}Use /reload or restart the server to reload the Loom config.")
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("${ChatColor.AQUA}=== Loom Commands ===")
        mapOf(
            "/loom confirm <code>" to "Authenticate web editor session",
            "/loom load <name>" to "Load and run a script",
            "/loom unload <name>" to "Unload a running script",
            "/loom reload [name]" to "Reload script(s)",
            "/loom list" to "List all scripts",
            "/loom new <name>" to "Create a new script file",
            "/loom delete <name>" to "Delete a script",
            "/loom editor" to "Get the editor URL",
            "/loom info <name>" to "Show script info",
            "/loom revoke [all|sessionId]" to "Revoke editor session(s)",
        ).forEach { (cmd, desc) ->
            sender.sendMessage("  ${ChatColor.GREEN}$cmd ${ChatColor.GRAY}- $desc")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            return listOf("confirm", "load", "unload", "reload", "list", "new", "delete", "editor", "info", "revoke", "help")
                .filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2) {
            return when (args[0].lowercase()) {
                "load", "unload", "reload", "delete", "info" -> scripts.listScripts().filter { it.startsWith(args[1]) }
                else -> emptyList()
            }
        }
        return emptyList()
    }
}
