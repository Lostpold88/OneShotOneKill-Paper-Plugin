package de.oneshotonekill.command

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.manager.KillstreakManager
import de.oneshotonekill.util.mini
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.Sound as BukkitSound

/**
 * Der einzige Befehl des Plugins. Registriert wird er ueber die Paper Lifecycle Commands API als
 * Brigadier [BasicCommand] - kein `CommandExecutor`, kein `TabCompleter`.
 */
class OsokCommand(private val plugin: OneShotOneKill) : BasicCommand {

    override fun canUse(sender: CommandSender): Boolean = sender.isAdmin

    override fun execute(stack: CommandSourceStack, args: Array<String>) {
        val sender = stack.sender
        if (!sender.isAdmin) {
            sender.msg(
                "<red>[OSOK] Dazu hast du keine Rechte. Nur Operators (OP) dürfen /osok Befehle " +
                    "ausführen.</red>"
            )
            return
        }

        // Spieler ist optional: Nur die Menue-Befehle und setspawn brauchen zwingend einen.
        val player = sender as? Player

        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            sendHelp(sender)
            return
        }

        val subArgs = args.drop(1)

        when (args[0].lowercase()) {
            "start" -> plugin.matchManager.restartMatch()

            "stop", "beenden" -> plugin.matchManager.stopGame(player)

            "pausestats", "statspause" -> plugin.matchManager.toggleStatsPause(sender)

            "pause" -> plugin.matchManager.togglePause(player)

            "map", "arena", "welt" -> handleMapCommand(sender, subArgs)

            "dauer", "limit", "timer" -> handleDauerCommand(sender, subArgs)

            "itemmode", "itemmodus", "mode" -> handleItemModeCommand(subArgs)

            "itemgewichtung", "gewichtung", "itemweight" -> requirePlayer(
                sender,
                player,
                "<red>[OSOK] Die Item-Gewichtung läuft über ein Menü und kann nur von einem " +
                    "Spieler geöffnet werden.</red>",
            ) { plugin.itemWeightGui.openGui(it) }

            "camper", "anticamp", "camping" -> requirePlayer(
                sender,
                player,
                "<red>[OSOK] Die Camper-Einstellungen laufen über ein Menü und können nur von " +
                    "einem Spieler geöffnet werden.</red>",
            ) { plugin.camperGui.openGui(it) }

            "itemtest", "testgui" -> requirePlayer(
                sender,
                player,
                "<red>[OSOK] Das Testmenü kann nur ein Spieler öffnen.</red>",
            ) { ItemTestCommand(plugin).openTestGui(it) }

            "clearpfeile", "cleararrows" -> ClearPfeileCommand().clearArrows(sender)

            "setspawn" -> requirePlayer(
                sender,
                player,
                "<red>[OSOK] Der Spawnpunkt kann nur von einem Spieler gesetzt werden (er nutzt " +
                    "dessen Position).</red>",
            ) { setSpawn(it) }

            "resetstats", "resetboard" -> resetStats()

            "resetmap" -> resetMap()

            else -> sender.msg(
                "<red>[OSOK] Unbekannter Unterbefehl. Nutze /osok help für eine Liste aller " +
                    "Befehle.</red>"
            )
        }
    }

    override fun suggest(stack: CommandSourceStack, args: Array<String>): Collection<String> {
        if (!stack.sender.isAdmin) return emptyList()

        if (args.size <= 1) {
            return SUB_COMMANDS.matching(args.firstOrNull() ?: "")
        }
        if (args.size != 2) return emptyList()

        return when (args[0].lowercase()) {
            "map", "arena", "welt" -> MAP_NAMES.matching(args[1])
            "dauer", "limit", "timer" -> LIMIT_TYPES.matching(args[1])
            "itemmode", "itemmodus", "mode" -> ITEM_MODES.matching(args[1])
            // itemgewichtung und camper nehmen keine Argumente - sie oeffnen direkt ihr Menue
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Unterbefehle
    // ------------------------------------------------------------------

    private fun handleMapCommand(sender: CommandSender, args: List<String>) {
        val targetMap = args.firstOrNull()
        if (targetMap == null) {
            sender.msg(
                "<yellow>[OSOK] Aktuelle Map: " +
                    "<green><b>${plugin.worldManager.activeMapConfig.name}</b></green></yellow>"
            )
            sender.msg("<gray>Verwendung: /osok map <Standard|DustPvP></gray>")
            return
        }

        if (!plugin.worldManager.switchMap(targetMap)) {
            sender.msg(
                "<red>[OSOK] Map '$targetMap' wurde nicht gefunden! Verfügbar: Standard, DustPvP</red>"
            )
        }
    }

    private fun handleDauerCommand(sender: CommandSender, args: List<String>) {
        val match = plugin.matchManager

        if (args.isEmpty()) {
            val current = when {
                match.hasKillLimit() -> "${match.killLimit} Kills"
                match.hasTimeLimit() -> "${match.formatTime(match.timeLimitSeconds)} (Zeit)"
                else -> "Kein Limit"
            }
            sender.msg("<yellow>[OSOK] Aktuelles Limit: <green>$current</green></yellow>")
            sender.msg("<gray>Verwendung: /osok dauer [kills|minuten|sekunden|off] [wert]</gray>")
            return
        }

        val type = args[0].lowercase()
        if (type in LIMIT_OFF_ALIASES) {
            match.resetLimits()
            return
        }

        val valueStr = args.getOrNull(1)
        if (valueStr != null) {
            val value = valueStr.toIntOrNull()
            if (value == null) {
                sender.msg("<red>[OSOK] Ungültiger Zahlenwert: $valueStr</red>")
                return
            }
            when (type) {
                "kills", "kill", "k" -> {
                    match.setKillLimit(value)
                    return
                }

                "minuten", "minut", "m" -> {
                    match.setTimeLimitMinutes(value)
                    return
                }

                "sekunden", "sekunde", "s" -> {
                    match.setTimeLimitSeconds(value)
                    return
                }
            }
        }

        sender.msg(
            "<red>[OSOK] Ungültiger Parameter. Verwende: /osok dauer [kills|minuten|sekunden|off] [wert]</red>"
        )
    }

    /** Ohne Argument wird durchgeschaltet: STREAK → SPAWN → BOTH → STREAK. */
    private fun handleItemModeCommand(args: List<String>) {
        val killstreak = plugin.killstreakManager

        val newMode = when (args.firstOrNull()?.lowercase()) {
            "spawn", "map", "ground" -> KillstreakManager.ItemMode.SPAWN
            "both", "kombi", "all" -> KillstreakManager.ItemMode.BOTH
            null -> when (killstreak.itemMode) {
                KillstreakManager.ItemMode.STREAK -> KillstreakManager.ItemMode.SPAWN
                KillstreakManager.ItemMode.SPAWN -> KillstreakManager.ItemMode.BOTH
                KillstreakManager.ItemMode.BOTH -> KillstreakManager.ItemMode.STREAK
            }

            else -> KillstreakManager.ItemMode.STREAK
        }

        killstreak.itemMode = newMode
        broadcast(
            when (newMode) {
                KillstreakManager.ItemMode.SPAWN ->
                    "<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>MAP-SPAWN</b></green> " +
                        "<gray>(Items spawnen alle 30s als Mario Kart Boxen!)</gray></yellow>"

                KillstreakManager.ItemMode.BOTH ->
                    "<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>KOMBI-MODUS</b></green> " +
                        "<gray>(Streaks + 30s Map-Spawns gleichzeitig!)</gray></yellow>"

                KillstreakManager.ItemMode.STREAK ->
                    "<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>KILLSTREAK</b></green> " +
                        "<gray>(Items nur alle 3 Kills!)</gray></yellow>"
            }
        )
        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.5f)
        )
    }

    private fun setSpawn(player: Player) {
        plugin.worldManager.spawnLocation = player.location
        player.msg("<green>[OSOK] Neuer Arena-Spawnpunkt gesetzt!</green>")
        player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 2.0f))
    }

    /** Der Absender sieht den Broadcast mit - eine gesonderte Rueckmeldung gibt es bewusst nicht. */
    private fun resetStats() {
        plugin.scoreboardManager.resetAllStats()
        broadcast("<yellow>[OSOK] 🔄 Die Statistiken und das Scoreboard wurden zurückgesetzt!</yellow>")
        Bukkit.getServer().playSound(Sound.sound(BukkitSound.UI_BUTTON_CLICK, Sound.Source.MASTER, 1.0f, 1.0f))
    }

    private fun resetMap() {
        broadcast("<yellow>[OSOK] Die Arena-Map wird zurückgesetzt! Server startet neu...</yellow>")
        Bukkit.getOnlinePlayers().forEach {
            it.kick(
                ("<green>[OSOK] Arena-Map wird zurückgesetzt!</green>\n" +
                    "<gray>Der Server startet jetzt neu...</gray>").mini()
            )
        }
        Bukkit.shutdown()
    }

    private fun sendHelp(sender: CommandSender) {
        sender.msg("<yellow><b>=======================================</b></yellow>")
        sender.msg("<green><b>🎯 OSOK COMMANDS (/osok <befehl>)</b></green>")
        sender.msg("<yellow><b>=======================================</b></yellow>")
        HELP_LINES.forEach { sender.msg(it) }
        sender.msg("<yellow><b>=======================================</b></yellow>")
    }

    // ------------------------------------------------------------------
    // Hilfsmittel
    // ------------------------------------------------------------------

    /** OP oder privilegiertes Konto. */
    private val CommandSender.isAdmin: Boolean
        get() = isOp || plugin.accessManager.isPrivileged(this)

    private fun CommandSender.msg(message: String) = sendMessage(message.mini())

    private fun broadcast(message: String) {
        Bukkit.broadcast(message.mini())
    }

    /** Fuehrt [action] nur aus, wenn der Absender ein Spieler ist. */
    private inline fun requirePlayer(
        sender: CommandSender,
        player: Player?,
        errorMessage: String,
        action: (Player) -> Unit,
    ) {
        if (player == null) {
            sender.msg(errorMessage)
            return
        }
        action(player)
    }

    private companion object {
        val SUB_COMMANDS = listOf(
            "start", "stop", "pause", "pausestats", "map", "dauer", "limit", "itemmode",
            "itemgewichtung", "camper", "itemtest", "clearpfeile", "setspawn",
            "resetstats", "resetmap", "help",
        )

        val MAP_NAMES = listOf("Standard", "DustPvP")
        val LIMIT_TYPES = listOf("kills", "minuten", "sekunden", "off")
        val ITEM_MODES = listOf("streak", "spawn", "both", "kombi")
        val LIMIT_OFF_ALIASES = setOf("off", "none", "disable")

        val HELP_LINES = listOf(
            "<gray>/osok start <dark_gray>-</dark_gray> <white>Match starten, Scoreboard zurücksetzen & alle zufällig in die Arena spawnen (Admin)</white></gray>",
            "<gray>/osok stop <dark_gray>-</dark_gray> <white>Spiel beenden, Scoreboard zurücksetzen & alle in die Lobby teleportieren (Admin)</white></gray>",
            "<gray>/osok pause <dark_gray>-</dark_gray> <white>Match pausieren / fortsetzen (Admin)</white></gray>",
            "<gray>/osok pausestats <dark_gray>-</dark_gray> <white>Kill- und Zeitwertung einfrieren / fortsetzen, Scoreboard bleibt stehen (Admin)</white></gray>",
            "<gray>/osok map <Standard|DustPvP> <dark_gray>-</dark_gray> <white>Dynamisch zwischen Arenen wechseln (Admin)</white></gray>",
            "<gray>/osok dauer [kills|minuten|sekunden|off] [Anzahl] <dark_gray>-</dark_gray> <white>Match-Dauer/Ziel festlegen (Admin)</white></gray>",
            "<gray>/osok itemmode [streak|spawn|both] <dark_gray>-</dark_gray> <white>Item-Modus umschalten (Admin)</white></gray>",
            "<gray>/osok itemgewichtung <dark_gray>-</dark_gray> <white>Menü: Spawnwahrscheinlichkeit je Spezial-Item (Admin)</white></gray>",
            "<gray>/osok camper <dark_gray>-</dark_gray> <white>Menü: Anti-Camping ein/aus, Zeit & Radius (Admin)</white></gray>",
            "<gray>/osok itemtest <dark_gray>-</dark_gray> <white>Spezial-Item Testmenü öffnen (Admin)</white></gray>",
            "<gray>/osok clearpfeile <dark_gray>-</dark_gray> <white>Alle Pfeile aus der Welt löschen (Admin)</white></gray>",
            "<gray>/osok setspawn <dark_gray>-</dark_gray> <white>Spawnpunkt auf der Map setzen (Admin)</white></gray>",
            "<gray>/osok resetstats <dark_gray>-</dark_gray> <white>Scoreboard & Statistiken zurücksetzen (Admin)</white></gray>",
            "<gray>/osok resetmap <dark_gray>-</dark_gray> <white>Map frisch aus der JAR wiederherstellen (Admin)</white></gray>",
        )

        /** Praefix-Filter fuer die Autovervollstaendigung. */
        fun List<String>.matching(prefix: String): List<String> =
            filter { it.startsWith(prefix, ignoreCase = true) }
    }
}
