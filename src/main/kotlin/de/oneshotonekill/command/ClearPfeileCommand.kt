package de.oneshotonekill.command

import de.oneshotonekill.util.mini
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.AbstractArrow

/**
 * Aktion fuer `/osok clearpfeile`.
 *
 * Bewusst ohne Bukkit `CommandExecutor`: Die Registrierung erfolgt ausschliesslich ueber die Paper
 * Lifecycle Commands API (Brigadier `BasicCommand`) in [OsokCommand].
 */
class ClearPfeileCommand {

    fun clearArrows(sender: CommandSender) {
        // Paper Spatial Entity Index Engine: gezielte Klassen-Abfrage statt Iteration ueber alle
        // Entities
        val removed = Bukkit.getWorlds()
            .flatMap { it.getEntitiesByClass(AbstractArrow::class.java) }
            .onEach { it.remove() }
            .size

        sender.sendMessage(
            "<green>[OSOK] 🧹 Es wurden <yellow>$removed</yellow> Pfeile aus der Welt gelöscht!</green>".mini()
        )
    }
}
