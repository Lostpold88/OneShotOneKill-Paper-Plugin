package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;

public class ClearPfeileCommand implements CommandExecutor {

    private final OneShotOneKill plugin;

    public ClearPfeileCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§cDazu hast du keine Rechte.");
            return true;
        }

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Arrow || entity instanceof AbstractArrow) {
                    entity.remove();
                    removed++;
                }
            }
        }

        sender.sendMessage("§a[OSOK] 🧹 Es wurden §e" + removed + " §aPfeile aus der Welt gelöscht!");
        return true;
    }
}
