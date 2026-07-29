package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractArrow;

public class ClearPfeileCommand implements CommandExecutor {

    private final OneShotOneKill plugin;

    public ClearPfeileCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>Dazu hast du keine Rechte.</red>"));
            return true;
        }

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (AbstractArrow arrow : world.getEntitiesByClass(AbstractArrow.class)) {
                arrow.remove();
                removed++;
            }
        }

        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] 🧹 Es wurden <yellow>" + removed + "</yellow> Pfeile aus der Welt gelöscht!</green>"));
        return true;
    }
}
