package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class StartCommand implements CommandExecutor, TabCompleter {

    private final OneShotOneKill plugin;

    public StartCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>Dieser Befehl ist nur für Spieler verfügbar.</red>"));
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Dazu hast du keine Rechte.</red>"));
            return true;
        }

        if (plugin.getMatchManager() != null) {
            plugin.getMatchManager().restartMatch(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
