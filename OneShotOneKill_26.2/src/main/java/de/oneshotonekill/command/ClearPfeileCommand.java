package de.oneshotonekill.command;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;

/**
 * Aktion fuer <code>/osok clearpfeile</code>.
 * <p>
 * Bewusst ohne Bukkit {@code CommandExecutor}: Die Registrierung erfolgt ausschliesslich ueber die
 * Paper Lifecycle Commands API (Brigadier {@code BasicCommand}) in {@link OsokCommand}.
 */
public class ClearPfeileCommand {

    public void clearArrows(CommandSender sender) {
        int removed = 0;
        // Paper Spatial Entity Index Engine: gezielte Klassen-Abfrage statt Iteration ueber alle Entities
        for (World world : Bukkit.getWorlds()) {
            for (AbstractArrow arrow : world.getEntitiesByClass(AbstractArrow.class)) {
                arrow.remove();
                removed++;
            }
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] 🧹 Es wurden <yellow>" + removed + "</yellow> Pfeile aus der Welt gelöscht!</green>"));
    }
}
