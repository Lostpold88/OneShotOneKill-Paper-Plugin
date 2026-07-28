package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillstreakManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    private void broadcast(String message) {
        Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "§cDieser Befehl ist nur für Spieler verfügbar.");
            return true;
        }

        String cmdName = command.getName().toLowerCase();
        if (cmdName.equals("itemmode") || cmdName.equals("itemmodus") || cmdName.equals("mode")) {
            return handleItemModeCommand(player, args);
        }

        if (args.length == 0) {
            msg(player, "§e§l=======================================");
            msg(player, "§a§l🎯 OSOK SYSTEM");
            msg(player, "§e§l=======================================");
            msg(player, "§7/start §8- §fMatch starten & alle zufällig in die Arena spawnen");
            msg(player, "§7/osok dauer [kills|zeit|off] [Anzahl] §8- §fMatch-Dauer/Ziel festlegen");
            msg(player, "§7/itemmode [streak|spawn] §8- §fItem-Modus umschalten (Streak vs 30s Map-Spawn)");
            msg(player, "§7/itemtest §8- §fSpezial-Item Testmenü öffnen (Admin)");
            msg(player, "§7/clearpfeile §8- §fAlle Pfeile aus der Welt löschen (Admin)");
            msg(player, "§7/osok setspawn §8- §fSpawnpunkt auf der Map setzen (Admin)");
            msg(player, "§7/osok resetstats §8- §fScoreboard & Statistiken zurücksetzen (Admin)");
            msg(player, "§7/osok resetmap §8- §fMap frisch aus der JAR wiederherstellen (Admin)");
            msg(player, "§e§l=======================================");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("dauer") || sub.equals("limit") || sub.equals("timer")) {
            String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
            return handleDauerCommand(player, subArgs);
        }

        if (sub.equals("start")) {
            return new StartCommand(plugin).onCommand(sender, command, label, args);
        }

        if (sub.equals("itemmode") || sub.equals("itemmodus") || sub.equals("mode")) {
            String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
            return handleItemModeCommand(player, subArgs);
        }

        if (sub.equals("itemtest") || sub.equals("testgui")) {
            if (!player.isOp()) {
                msg(player, "§cDazu hast du keine Rechte.");
                return true;
            }
            new ItemTestCommand(plugin).openTestGui(player);
            return true;
        }

        if (sub.equals("setspawn")) {
            if (!player.isOp()) {
                msg(player, "§cDazu hast du keine Rechte.");
                return true;
            }
            plugin.getWorldManager().setSpawnLocation(player.getLocation());
            msg(player, "§a[OSOK] Neuer Arena-Spawnpunkt gesetzt!");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 2.0f);
            return true;
        }

        if (sub.equals("resetstats") || sub.equals("resetboard")) {
            if (!player.isOp()) {
                msg(player, "§cDazu hast du keine Rechte.");
                return true;
            }
            plugin.getScoreboardManager().resetAllStats();
            broadcast("§e[OSOK] 🔄 Die Statistiken und das Scoreboard wurden zurückgesetzt!");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 1.0f, 1.0f);
            }
            return true;
        }

        if (sub.equals("resetmap")) {
            if (!player.isOp()) {
                msg(player, "§cDazu hast du keine Rechte.");
                return true;
            }
            broadcast("§e[OSOK] Die Arena-Map wird zurückgesetzt! Server startet neu...");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.kick(LegacyComponentSerializer.legacySection().deserialize("§a[OSOK] Arena-Map wird zurückgesetzt!\n§7Der Server startet jetzt neu..."));
            }
            Bukkit.shutdown();
            return true;
        }

        return false;
    }

    private boolean handleDauerCommand(Player player, String[] args) {
        if (!player.isOp()) {
            msg(player, "§cDazu hast du keine Rechte.");
            return true;
        }

        if (args.length == 0) {
            msg(player, "§e[OSOK] Verwende: §f/osok dauer [kills|zeit|off] [Wert]");
            msg(player, "§7Beispiel: §f/osok dauer kills 20 §7(Ziel: 20 Kills)");
            msg(player, "§7Beispiel: §f/osok dauer zeit 10 §7(Ziel: 10 Minuten)");
            msg(player, "§7Beispiel: §f/osok dauer off §7(Limits zurücksetzen)");
            return true;
        }

        String type = args[0].toLowerCase();
        if (type.equals("off") || type.equals("reset") || type.equals("aus") || type.equals("none")) {
            plugin.getMatchManager().resetLimits();
            return true;
        }

        if (type.equals("kills") || type.equals("kill") || type.equals("k")) {
            if (args.length < 2) {
                msg(player, "§c[OSOK] Bitte gib die Anzahl der Kills an (z. B. /osok dauer kills 20).");
                return true;
            }
            try {
                int kills = Integer.parseInt(args[1]);
                plugin.getMatchManager().setKillLimit(kills);
            } catch (NumberFormatException e) {
                msg(player, "§c[OSOK] Ungültige Zahl: " + args[1]);
            }
            return true;
        }

        if (type.equals("zeit") || type.equals("time") || type.equals("minuten") || type.equals("m")) {
            if (args.length < 2) {
                msg(player, "§c[OSOK] Bitte gib die Dauer in Minuten an (z. B. /osok dauer zeit 10).");
                return true;
            }
            try {
                int minutes = Integer.parseInt(args[1]);
                plugin.getMatchManager().setTimeLimitMinutes(minutes);
            } catch (NumberFormatException e) {
                msg(player, "§c[OSOK] Ungültige Zahl: " + args[1]);
            }
            return true;
        }

        if (type.endsWith("k")) {
            try {
                int kills = Integer.parseInt(type.substring(0, type.length() - 1));
                plugin.getMatchManager().setKillLimit(kills);
                return true;
            } catch (NumberFormatException ignored) {}
        }
        if (type.endsWith("m")) {
            try {
                int minutes = Integer.parseInt(type.substring(0, type.length() - 1));
                plugin.getMatchManager().setTimeLimitMinutes(minutes);
                return true;
            } catch (NumberFormatException ignored) {}
        }
        try {
            int val = Integer.parseInt(type);
            plugin.getMatchManager().setKillLimit(val);
            return true;
        } catch (NumberFormatException ignored) {}

        msg(player, "§c[OSOK] Ungültiger Parameter. Verwende: /osok dauer [kills|zeit|off] [Wert]");
        return true;
    }

    private boolean handleItemModeCommand(Player player, String[] args) {
        if (!player.isOp()) {
            msg(player, "§cDazu hast du keine Rechte.");
            return true;
        }

        if (args.length >= 1) {
            String modeArg = args[0].toLowerCase();
            if (modeArg.equals("spawn") || modeArg.equals("map") || modeArg.equals("ground")) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.SPAWN);
                broadcast("§e[OSOK] ⚙ Spezial-Item Modus gewechselt zu: §a§lMAP-SPAWN §7(Items spawnen alle 30s als Mario Kart Boxen!)");
            } else if (modeArg.equals("both") || modeArg.equals("kombi") || modeArg.equals("all")) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.BOTH);
                broadcast("§e[OSOK] ⚙ Spezial-Item Modus gewechselt zu: §a§lKOMBI-MODUS §7(Streaks + 30s Map-Spawns gleichzeitig!)");
            } else {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.STREAK);
                broadcast("§e[OSOK] ⚙ Spezial-Item Modus gewechselt zu: §a§lKILLSTREAK §7(Items nur alle 3 Kills!)");
            }
        } else {
            KillstreakManager.ItemMode current = plugin.getKillstreakManager().getItemMode();
            if (current == KillstreakManager.ItemMode.STREAK) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.SPAWN);
                broadcast("§e[OSOK] ⚙ Spezial-Item Modus gewechselt zu: §a§lMAP-SPAWN §7(Items spawnen alle 30s als Mario Kart Boxen!)");
            } else if (current == KillstreakManager.ItemMode.SPAWN) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.BOTH);
                broadcast("§e[OSOK] ⚙ Spezial-Item Modus gewechselt zu: §a§lKOMBI-MODUS §7(Streaks + 30s Map-Spawns gleichzeitig!)");
            } else {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.STREAK);
                broadcast("§e[OSOK] ⚙ Spezial-Item Modus gewechselt zu: §a§lKILLSTREAK §7(Items nur alle 3 Kills!)");
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.5f);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command != null ? command.getName().toLowerCase() : "";
        if (cmdName.equals("itemmode") || cmdName.equals("itemmodus") || cmdName.equals("mode")) {
            if (args.length == 1) {
                return StringUtil.copyPartialMatches(args[0], Arrays.asList("streak", "spawn", "both", "kombi"), new ArrayList<>());
            }
            return Collections.emptyList();
        }
        if (cmdName.equals("resetstats") || cmdName.equals("resetboard")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], Arrays.asList("start", "dauer", "limit", "setspawn", "resetmap", "resetstats", "itemtest", "itemmode"), new ArrayList<>());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("dauer") || args[0].equalsIgnoreCase("limit"))) {
            return StringUtil.copyPartialMatches(args[1], Arrays.asList("kills", "zeit", "off"), new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("itemmode")) {
            return StringUtil.copyPartialMatches(args[1], Arrays.asList("streak", "spawn", "both", "kombi"), new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
