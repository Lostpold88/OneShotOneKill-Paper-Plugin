package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
            sender.sendMessage("§cDieser Befehl ist nur für Spieler verfügbar.");
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage("§cDazu hast du keine Rechte.");
            return true;
        }

        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
            if (randomLoc != null) {
                p.teleport(randomLoc);
                plugin.getEquipmentManager().giveOneShotEquipment(p);
                p.sendTitle("§a§lLOS GEHT'S!", "§7OneShotOneKill Match gestartet", 10, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 0.5f, 1.5f);
                count++;
            }
        }

        plugin.getScoreboardManager().updateAllScoreboards();

        Bukkit.broadcastMessage(" ");
        Bukkit.broadcastMessage("§a§l=======================================");
        Bukkit.broadcastMessage("§e§l   🚀 MATCH GESTARTET!   ");
        Bukkit.broadcastMessage("§7" + count + " Spieler wurden zufällig in der Arena platziert!");
        Bukkit.broadcastMessage("§a§l=======================================");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
