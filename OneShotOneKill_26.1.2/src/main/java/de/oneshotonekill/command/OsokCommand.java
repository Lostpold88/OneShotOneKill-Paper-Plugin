package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillstreakManager;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OsokCommand implements CommandExecutor, TabCompleter {

    private final OneShotOneKill plugin;

    public OsokCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cDieser Befehl ist nur für Spieler verfügbar.");
            return true;
        }

        String cmdName = command.getName().toLowerCase();
        if (cmdName.equals("itemmode") || cmdName.equals("itemmodus") || cmdName.equals("mode")) {
            return handleItemModeCommand(player, args);
        }

        if (args.length == 0) {
            player.sendMessage("§e§l=======================================");
            player.sendMessage("§a§l🎯 ONESHOT-ONEKILL SYSTEM");
            player.sendMessage("§e§l=======================================");
            player.sendMessage("§7/start §8- §fMatch starten & alle zufällig in die Arena spawnen");
            player.sendMessage("§7/itemmode [streak|spawn] §8- §fItem-Modus umschalten (Streak vs 30s Map-Spawn)");
            player.sendMessage("§7/itemtest §8- §fSpezial-Item Testmenü öffnen (Admin)");
            player.sendMessage("§7/clearpfeile §8- §fAlle Pfeile aus der Welt löschen (Admin)");
            player.sendMessage("§7/osok setspawn §8- §fSpawnpunkt auf der Map setzen (Admin)");
            player.sendMessage("§7/osok resetstats §8- §fScoreboard & Statistiken zurücksetzen (Admin)");
            player.sendMessage("§7/osok resetmap §8- §fMap frisch aus der JAR wiederherstellen (Admin)");
            player.sendMessage("§e§l=======================================");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("start")) {
            return plugin.getCommand("start").getExecutor().onCommand(sender, command, label, args);
        }

        if (sub.equals("itemmode") || sub.equals("itemmodus") || sub.equals("mode")) {
            String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
            return handleItemModeCommand(player, subArgs);
        }

        if (sub.equals("itemtest") || sub.equals("testgui")) {
            if (!player.isOp()) {
                player.sendMessage("§cDazu hast du keine Rechte.");
                return true;
            }
            new ItemTestCommand(plugin).openTestGui(player);
            return true;
        }

        if (sub.equals("clearpfeile")) {
            return plugin.getCommand("clearpfeile").getExecutor().onCommand(sender, command, label, args);
        }

        if (sub.equals("setspawn")) {
            if (!player.isOp()) {
                player.sendMessage("§cDazu hast du keine Rechte.");
                return true;
            }
            plugin.getWorldManager().setSpawnLocation(player.getLocation());
            player.sendMessage("§a[OneShot] Neuer Arena-Spawnpunkt gesetzt!");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 2.0f);
            return true;
        }

        if (sub.equals("resetstats") || sub.equals("resetboard")) {
            if (!player.isOp()) {
                player.sendMessage("§cDazu hast du keine Rechte.");
                return true;
            }
            plugin.getScoreboardManager().resetAllStats();
            Bukkit.broadcastMessage("§e[OneShot] 🔄 Die Statistiken und das Scoreboard wurden zurückgesetzt!");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 1.0f, 1.0f);
            }
            return true;
        }

        if (sub.equals("resetmap")) {
            if (!player.isOp()) {
                player.sendMessage("§cDazu hast du keine Rechte.");
                return true;
            }
            Bukkit.broadcastMessage("§e[OneShot] Die Arena-Map wird zurückgesetzt! Server startet neu...");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.kickPlayer("§a[OneShot] Arena-Map wird zurückgesetzt!\n§7Der Server startet jetzt neu...");
            }
            Bukkit.shutdown();
            return true;
        }

        return false;
    }

    private boolean handleItemModeCommand(Player player, String[] args) {
        if (!player.isOp()) {
            player.sendMessage("§cDazu hast du keine Rechte.");
            return true;
        }

        if (args.length >= 1) {
            String modeArg = args[0].toLowerCase();
            if (modeArg.equals("spawn") || modeArg.equals("map") || modeArg.equals("ground")) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.SPAWN);
                Bukkit.broadcastMessage("§e[OneShot] ⚙ Spezial-Item Modus gewechselt zu: §a§lMAP-SPAWN §7(Items spawnen alle 30s als Mario Kart Boxen!)");
            } else if (modeArg.equals("both") || modeArg.equals("kombi") || modeArg.equals("all")) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.BOTH);
                Bukkit.broadcastMessage("§e[OneShot] ⚙ Spezial-Item Modus gewechselt zu: §a§lKOMBI-MODUS §7(Streaks + 30s Map-Spawns gleichzeitig!)");
            } else {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.STREAK);
                Bukkit.broadcastMessage("§e[OneShot] ⚙ Spezial-Item Modus gewechselt zu: §a§lKILLSTREAK §7(Items nur alle 3 Kills!)");
            }
        } else {
            KillstreakManager.ItemMode current = plugin.getKillstreakManager().getItemMode();
            if (current == KillstreakManager.ItemMode.STREAK) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.SPAWN);
                Bukkit.broadcastMessage("§e[OneShot] ⚙ Spezial-Item Modus gewechselt zu: §a§lMAP-SPAWN §7(Items spawnen alle 30s als Mario Kart Boxen!)");
            } else if (current == KillstreakManager.ItemMode.SPAWN) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.BOTH);
                Bukkit.broadcastMessage("§e[OneShot] ⚙ Spezial-Item Modus gewechselt zu: §a§lKOMBI-MODUS §7(Streaks + 30s Map-Spawns gleichzeitig!)");
            } else {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.STREAK);
                Bukkit.broadcastMessage("§e[OneShot] ⚙ Spezial-Item Modus gewechselt zu: §a§lKILLSTREAK §7(Items nur alle 3 Kills!)");
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.5f);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], Arrays.asList("start", "setspawn", "resetmap", "resetstats", "itemtest", "itemmode", "clearpfeile"), new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("itemmode")) {
            return StringUtil.copyPartialMatches(args[1], Arrays.asList("streak", "spawn", "both", "kombi"), new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
