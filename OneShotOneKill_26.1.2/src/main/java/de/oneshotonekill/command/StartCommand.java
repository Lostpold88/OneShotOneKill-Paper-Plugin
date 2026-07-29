package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.entity.Player;

/**
 * Startlogik fuer <code>/osok start</code>.
 * <p>
 * Bewusst ohne Bukkit {@code CommandExecutor}/{@code TabCompleter}: Die Registrierung erfolgt
 * ausschliesslich ueber die Paper Lifecycle Commands API (Brigadier {@code BasicCommand}) in
 * {@link OsokCommand}. Diese Klasse kapselt nur noch die eigentliche Aktion.
 */
public class StartCommand {

    private final OneShotOneKill plugin;

    public StartCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public void start(Player player) {
        if (plugin.getMatchManager() != null) {
            plugin.getMatchManager().restartMatch(player);
        }
    }
}
