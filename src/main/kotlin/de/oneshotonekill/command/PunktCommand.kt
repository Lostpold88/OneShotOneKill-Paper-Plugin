package de.oneshotonekill.command

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.sound.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File
import org.bukkit.Sound as BukkitSound

/**
 * `/punkt` - Werkzeug zum Abschreiben von Arena-Ecken.
 *
 * Haengt die Blockkoordinaten des Aufrufers als naechste Zeile an `punkte.txt` im Plugin-Ordner an;
 * `/punkt reset` loescht die Datei. Die laufende Nummer kommt aus der Zeilenzahl der Datei - so
 * braucht der Befehl keinen eigenen Zustand und ueberlebt jeden Neustart.
 *
 * Bewusst schlicht gehalten: Das ist ein Hilfsmittel zum Einmessen neuer Maps, kein Spielinhalt.
 */
class PunktCommand(private val plugin: OneShotOneKill) : BasicCommand {

    override fun canUse(sender: CommandSender): Boolean = sender.isAdmin

    override fun execute(stack: CommandSourceStack, args: Array<String>) {
        val sender = stack.sender
        if (!sender.isAdmin) {
            sender.sendMessage("<red>[OSOK] Dazu hast du keine Rechte.</red>".mini())
            return
        }

        if (args.firstOrNull()?.lowercase() in RESET_ALIASES) {
            reset(sender)
            return
        }

        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("<red>[OSOK] /punkt braucht einen Spieler - die Konsole steht nirgends.</red>".mini())
            return
        }

        savePoint(player)
    }

    override fun suggest(stack: CommandSourceStack, args: Array<String>): Collection<String> =
        if (stack.sender.isAdmin && args.size <= 1) listOf("reset") else emptyList()

    private fun savePoint(player: Player) {
        val loc = player.location
        val number = countPoints() + 1
        val line = "Punkt $number: ${loc.blockX} / ${loc.blockY} / ${loc.blockZ}   (${player.world.name})"

        // Eine Zeile anhaengen laeuft synchron: Es sind ein paar Bytes, und die laufende Nummer
        // haengt am Dateiinhalt - asynchron koennten zwei schnelle Aufrufe dieselbe Nummer ziehen.
        val file = pointsFile()
        file.parentFile?.mkdirs()
        file.appendText(line + System.lineSeparator())

        player.sendMessage("<green>[OSOK] 📍 <white>$line</white></green>".mini())
        player.sendMessage("<gray>Gespeichert in <yellow>${file.path}</yellow></gray>".mini())
        player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.6f))
    }

    private fun reset(sender: CommandSender) {
        val file = pointsFile()
        val removed = countPoints()
        if (file.exists() && !file.delete()) {
            sender.sendMessage("<red>[OSOK] 📍 ${file.name} liess sich nicht loeschen.</red>".mini())
            return
        }

        sender.sendMessage(
            "<yellow>[OSOK] 📍 Punkte zurückgesetzt <gray>($removed Einträge entfernt)</gray>.</yellow>".mini()
        )
    }

    /** Zeilen mit "Punkt " in der Datei - das ist zugleich die zuletzt vergebene Nummer. */
    private fun countPoints(): Int {
        val file = pointsFile()
        if (!file.isFile) return 0
        return file.readLines().count { it.startsWith("Punkt ") }
    }

    private fun pointsFile(): File = File(plugin.dataFolder, POINTS_FILE_NAME)

    private val CommandSender.isAdmin: Boolean
        get() = isOp || plugin.accessManager.isPrivileged(this)

    private companion object {
        const val POINTS_FILE_NAME = "punkte.txt"

        val RESET_ALIASES = setOf("reset", "clear", "leeren")
    }
}
