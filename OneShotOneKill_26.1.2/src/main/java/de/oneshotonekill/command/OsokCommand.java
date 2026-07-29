package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillstreakManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
        sender.sendMessage(MiniMessage.miniMessage().deserialize(message));
    }

    private void broadcast(String message) {
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(message));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "<red>Dieser Befehl ist nur für Spieler verfügbar.</red>");
            return true;
        }

        String cmdName = command.getName().toLowerCase();
        if (cmdName.equals("pause")) {
            if (!player.isOp()) {
                msg(player, "<red>Dazu hast du keine Rechte.</red>");
                return true;
            }
            plugin.getMatchManager().togglePause(player);
            return true;
        }

        if (cmdName.equals("itemmode") || cmdName.equals("itemmodus") || cmdName.equals("mode")) {
            return handleItemModeCommand(player, args);
        }

        if (args.length == 0) {
            msg(player, "<yellow><b>=======================================</b></yellow>");
            msg(player, "<green><b>🎯 OSOK SYSTEM</b></green>");
            msg(player, "<yellow><b>=======================================</b></yellow>");
            msg(player, "<gray>/start <dark_gray>-</dark_gray> <white>Match starten & alle zufällig in die Arena spawnen</white></gray>");
            msg(player, "<gray>/osok pause <dark_gray>-</dark_gray> <white>Match pausieren / fortsetzen (Admin)</white></gray>");
            msg(player, "<gray>/osok dauer [kills|minuten|sekunden|off] [Anzahl] <dark_gray>-</dark_gray> <white>Match-Dauer/Ziel festlegen</white></gray>");
            msg(player, "<gray>/itemmode [streak|spawn] <dark_gray>-</dark_gray> <white>Item-Modus umschalten (Streak vs 30s Map-Spawn)</white></gray>");
            msg(player, "<gray>/itemtest <dark_gray>-</dark_gray> <white>Spezial-Item Testmenü öffnen (Admin)</white></gray>");
            msg(player, "<gray>/clearpfeile <dark_gray>-</dark_gray> <white>Alle Pfeile aus der Welt löschen (Admin)</white></gray>");
            msg(player, "<gray>/osok setspawn <dark_gray>-</dark_gray> <white>Spawnpunkt auf der Map setzen (Admin)</white></gray>");
            msg(player, "<gray>/osok resetstats <dark_gray>-</dark_gray> <white>Scoreboard & Statistiken zurücksetzen (Admin)</white></gray>");
            msg(player, "<gray>/osok resetmap <dark_gray>-</dark_gray> <white>Map frisch aus der JAR wiederherstellen (Admin)</white></gray>");
            msg(player, "<yellow><b>=======================================</b></yellow>");
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("pause")) {
            if (!player.isOp()) {
                msg(player, "<red>Dazu hast du keine Rechte.</red>");
                return true;
            }
            plugin.getMatchManager().togglePause(player);
            return true;
        }

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
                msg(player, "<red>Dazu hast du keine Rechte.</red>");
                return true;
            }
            new ItemTestCommand(plugin).openTestGui(player);
            return true;
        }

        if (sub.equals("setspawn")) {
            if (!player.isOp()) {
                msg(player, "<red>Dazu hast du keine Rechte.</red>");
                return true;
            }
            plugin.getWorldManager().setSpawnLocation(player.getLocation());
            msg(player, "<green>[OSOK] Neuer Arena-Spawnpunkt gesetzt!</green>");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 2.0f);
            return true;
        }

        if (sub.equals("resetstats") || sub.equals("resetboard")) {
            if (!player.isOp()) {
                msg(player, "<red>Dazu hast du keine Rechte.</red>");
                return true;
            }
            plugin.getScoreboardManager().resetAllStats();
            broadcast("<yellow>[OSOK] 🔄 Die Statistiken und das Scoreboard wurden zurückgesetzt!</yellow>");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 1.0f, 1.0f);
            }
            return true;
        }

        if (sub.equals("resetmap")) {
            if (!player.isOp()) {
                msg(player, "<red>Dazu hast du keine Rechte.</red>");
                return true;
            }
            broadcast("<yellow>[OSOK] Die Arena-Map wird zurückgesetzt! Server startet neu...</yellow>");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.kick(MiniMessage.miniMessage().deserialize("<green>[OSOK] Arena-Map wird zurückgesetzt!</green>\n<gray>Der Server startet jetzt neu...</gray>"));
            }
            Bukkit.shutdown();
            return true;
        }

        return false;
    }

    private boolean handleDauerCommand(Player player, String[] args) {
        if (!player.isOp()) {
            msg(player, "<red>Dazu hast du keine Rechte.</red>");
            return true;
        }

        if (args.length == 0) {
            msg(player, "<yellow>[OSOK] Verwende:</yellow>");
            msg(player, "<white>  /osok dauer kills [Anzahl] <gray>(z. B. 20 Kills)</gray></white>");
            msg(player, "<white>  /osok dauer minuten [Anzahl] <gray>(z. B. 10 Minuten)</gray></white>");
            msg(player, "<white>  /osok dauer sekunden [Anzahl] <gray>(z. B. 45 Sekunden)</gray></white>");
            msg(player, "<white>  /osok dauer off <gray>(Limits zurücksetzen)</gray></white>");
            msg(player, "<gray>Kurzformen: <white>/osok dauer 20k</white> | <white>5m</white> | <white>45s</white></gray>");
            return true;
        }

        String type = args[0].toLowerCase();
        if (type.equals("off") || type.equals("reset") || type.equals("aus") || type.equals("none")) {
            plugin.getMatchManager().resetLimits();
            return true;
        }

        if (type.equals("kills") || type.equals("kill") || type.equals("k")) {
            if (args.length < 2) {
                msg(player, "<red>[OSOK] Bitte gib die Anzahl der Kills an (z. B. /osok dauer kills 20).</red>");
                return true;
            }
            try {
                int kills = Integer.parseInt(args[1]);
                plugin.getMatchManager().setKillLimit(kills);
            } catch (NumberFormatException e) {
                msg(player, "<red>[OSOK] Ungültige Zahl: " + args[1] + "</red>");
            }
            return true;
        }

        if (type.equals("minuten") || type.equals("min") || type.equals("m") || type.equals("zeit") || type.equals("time")) {
            if (args.length < 2) {
                msg(player, "<red>[OSOK] Bitte gib die Dauer in Minuten an (z. B. /osok dauer minuten 10).</red>");
                return true;
            }
            try {
                int minutes = Integer.parseInt(args[1]);
                plugin.getMatchManager().setTimeLimitMinutes(minutes);
            } catch (NumberFormatException e) {
                msg(player, "<red>[OSOK] Ungültige Zahl: " + args[1] + "</red>");
            }
            return true;
        }

        if (type.equals("sekunden") || type.equals("sec") || type.equals("sek") || type.equals("s")) {
            if (args.length < 2) {
                msg(player, "<red>[OSOK] Bitte gib die Dauer in Sekunden an (z. B. /osok dauer sekunden 45).</red>");
                return true;
            }
            try {
                int seconds = Integer.parseInt(args[1]);
                plugin.getMatchManager().setTimeLimitSeconds(seconds);
            } catch (NumberFormatException e) {
                msg(player, "<red>[OSOK] Ungültige Zahl: " + args[1] + "</red>");
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
        if (type.endsWith("s")) {
            try {
                int seconds = Integer.parseInt(type.substring(0, type.length() - 1));
                plugin.getMatchManager().setTimeLimitSeconds(seconds);
                return true;
            } catch (NumberFormatException ignored) {}
        }
        try {
            int val = Integer.parseInt(type);
            plugin.getMatchManager().setKillLimit(val);
            return true;
        } catch (NumberFormatException ignored) {}

        msg(player, "<red>[OSOK] Ungültiger Parameter. Verwende: /osok dauer [kills|minuten|sekunden|off]</red>");
        return true;
    }

    private boolean handleItemModeCommand(Player player, String[] args) {
        if (!player.isOp()) {
            msg(player, "<red>Dazu hast du keine Rechte.</red>");
            return true;
        }

        if (args.length >= 1) {
            String modeArg = args[0].toLowerCase();
            if (modeArg.equals("spawn") || modeArg.equals("map") || modeArg.equals("ground")) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.SPAWN);
                broadcast("<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>MAP-SPAWN</b></green> <gray>(Items spawnen alle 30s als Mario Kart Boxen!)</gray></yellow>");
            } else if (modeArg.equals("both") || modeArg.equals("kombi") || modeArg.equals("all")) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.BOTH);
                broadcast("<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>KOMBI-MODUS</b></green> <gray>(Streaks + 30s Map-Spawns gleichzeitig!)</gray></yellow>");
            } else {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.STREAK);
                broadcast("<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>KILLSTREAK</b></green> <gray>(Items nur alle 3 Kills!)</gray></yellow>");
            }
        } else {
            KillstreakManager.ItemMode current = plugin.getKillstreakManager().getItemMode();
            if (current == KillstreakManager.ItemMode.STREAK) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.SPAWN);
                broadcast("<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>MAP-SPAWN</b></green> <gray>(Items spawnen alle 30s als Mario Kart Boxen!)</gray></yellow>");
            } else if (current == KillstreakManager.ItemMode.SPAWN) {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.BOTH);
                broadcast("<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>KOMBI-MODUS</b></green> <gray>(Streaks + 30s Map-Spawns gleichzeitig!)</gray></yellow>");
            } else {
                plugin.getKillstreakManager().setItemMode(KillstreakManager.ItemMode.STREAK);
                broadcast("<yellow>[OSOK] ⚙ Spezial-Item Modus gewechselt zu: <green><b>KILLSTREAK</b></green> <gray>(Items nur alle 3 Kills!)</gray></yellow>");
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
            return StringUtil.copyPartialMatches(args[0], Arrays.asList("start", "pause", "dauer", "limit", "setspawn", "resetmap", "resetstats", "itemtest", "itemmode"), new ArrayList<>());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("dauer") || args[0].equalsIgnoreCase("limit"))) {
            return StringUtil.copyPartialMatches(args[1], Arrays.asList("kills", "minuten", "sekunden", "off"), new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("itemmode")) {
            return StringUtil.copyPartialMatches(args[1], Arrays.asList("streak", "spawn", "both", "kombi"), new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
