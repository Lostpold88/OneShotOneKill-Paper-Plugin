package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillstreakManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import net.kyori.adventure.sound.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class OsokCommand implements BasicCommand {

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
    public boolean canUse(CommandSender sender) {
        return sender.isOp() || plugin.getAccessManager().isPrivileged(sender);
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (!sender.isOp() && !plugin.getAccessManager().isPrivileged(sender)) {
            msg(sender, "<red>[OSOK] Dazu hast du keine Rechte. Nur Operators (OP) dürfen /osok Befehle ausführen.</red>");
            return;
        }

        // Spieler ist optional: Nur itemtest und setspawn brauchen zwingend einen.
        Player player = (sender instanceof Player p) ? p : null;

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            msg(sender, "<yellow><b>=======================================</b></yellow>");
            msg(sender, "<green><b>🎯 OSOK COMMANDS (/osok <befehl>)</b></green>");
            msg(sender, "<yellow><b>=======================================</b></yellow>");
            msg(sender, "<gray>/osok start <dark_gray>-</dark_gray> <white>Match starten, Scoreboard zurücksetzen & alle zufällig in die Arena spawnen (Admin)</white></gray>");
            msg(sender, "<gray>/osok stop <dark_gray>-</dark_gray> <white>Spiel beenden, Scoreboard zurücksetzen & alle in die Lobby teleportieren (Admin)</white></gray>");
            msg(sender, "<gray>/osok pause <dark_gray>-</dark_gray> <white>Match pausieren / fortsetzen (Admin)</white></gray>");
            msg(sender, "<gray>/osok pausestats <dark_gray>-</dark_gray> <white>Kill- und Zeitwertung einfrieren / fortsetzen, Scoreboard bleibt stehen (Admin)</white></gray>");
            msg(sender, "<gray>/osok map <Standard|DustPvP> <dark_gray>-</dark_gray> <white>Dynamisch zwischen Arenen wechseln (Admin)</white></gray>");
            msg(sender, "<gray>/osok dauer [kills|minuten|sekunden|off] [Anzahl] <dark_gray>-</dark_gray> <white>Match-Dauer/Ziel festlegen (Admin)</white></gray>");
            msg(sender, "<gray>/osok itemmode [streak|spawn|both] <dark_gray>-</dark_gray> <white>Item-Modus umschalten (Admin)</white></gray>");
            msg(sender, "<gray>/osok itemgewichtung <dark_gray>-</dark_gray> <white>Menü: Spawnwahrscheinlichkeit je Spezial-Item (Admin)</white></gray>");
            msg(sender, "<gray>/osok camper <dark_gray>-</dark_gray> <white>Menü: Anti-Camping ein/aus, Zeit & Radius (Admin)</white></gray>");
            msg(sender, "<gray>/osok itemtest <dark_gray>-</dark_gray> <white>Spezial-Item Testmenü öffnen (Admin)</white></gray>");
            msg(sender, "<gray>/osok clearpfeile <dark_gray>-</dark_gray> <white>Alle Pfeile aus der Welt löschen (Admin)</white></gray>");
            msg(sender, "<gray>/osok setspawn <dark_gray>-</dark_gray> <white>Spawnpunkt auf der Map setzen (Admin)</white></gray>");
            msg(sender, "<gray>/osok resetstats <dark_gray>-</dark_gray> <white>Scoreboard & Statistiken zurücksetzen (Admin)</white></gray>");
            msg(sender, "<gray>/osok resetmap <dark_gray>-</dark_gray> <white>Map frisch aus der JAR wiederherstellen (Admin)</white></gray>");
            msg(sender, "<yellow><b>=======================================</b></yellow>");
            return;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("start")) {
            new StartCommand(plugin).start(player);
            return;
        }

        if (sub.equals("stop") || sub.equals("beenden")) {
            plugin.getMatchManager().stopGame(player);
            return;
        }

        if (sub.equals("pausestats") || sub.equals("statspause")) {
            plugin.getMatchManager().toggleStatsPause(sender);
            return;
        }

        if (sub.equals("pause")) {
            plugin.getMatchManager().togglePause(player);
            return;
        }

        if (sub.equals("map") || sub.equals("arena") || sub.equals("welt")) {
            if (args.length < 2) {
                String activeName = plugin.getWorldManager().getActiveMapConfig() != null ? plugin.getWorldManager().getActiveMapConfig().getName() : "Unbekannt";
                msg(sender, "<yellow>[OSOK] Aktuelle Map: <green><b>" + activeName + "</b></green></yellow>");
                msg(sender, "<gray>Verwendung: /osok map <Standard|DustPvP></gray>");
                return;
            }
            String targetMap = args[1];
            boolean switched = plugin.getWorldManager().switchMap(targetMap);
            if (!switched) {
                msg(sender, "<red>[OSOK] Map '" + targetMap + "' wurde nicht gefunden! Verfügbar: Standard, DustPvP</red>");
            }
            return;
        }

        if (sub.equals("dauer") || sub.equals("limit") || sub.equals("timer")) {
            String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
            handleDauerCommand(sender, subArgs);
            return;
        }

        if (sub.equals("itemmode") || sub.equals("itemmodus") || sub.equals("mode")) {
            String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
            handleItemModeCommand(sender, subArgs);
            return;
        }

        if (sub.equals("itemgewichtung") || sub.equals("gewichtung") || sub.equals("itemweight")) {
            if (player == null) {
                msg(sender, "<red>[OSOK] Die Item-Gewichtung läuft über ein Menü und kann nur von einem Spieler geöffnet werden.</red>");
                return;
            }
            plugin.getItemWeightGui().openGui(player);
            return;
        }

        if (sub.equals("camper") || sub.equals("anticamp") || sub.equals("camping")) {
            if (player == null) {
                msg(sender, "<red>[OSOK] Die Camper-Einstellungen laufen über ein Menü und können nur von einem Spieler geöffnet werden.</red>");
                return;
            }
            plugin.getCamperGui().openGui(player);
            return;
        }

        if (sub.equals("itemtest") || sub.equals("testgui")) {
            if (player == null) {
                msg(sender, "<red>[OSOK] Das Testmenü kann nur ein Spieler öffnen.</red>");
                return;
            }
            new ItemTestCommand(plugin).openTestGui(player);
            return;
        }

        if (sub.equals("clearpfeile") || sub.equals("cleararrows")) {
            new ClearPfeileCommand().clearArrows(sender);
            return;
        }

        if (sub.equals("setspawn")) {
            if (player == null) {
                msg(sender, "<red>[OSOK] Der Spawnpunkt kann nur von einem Spieler gesetzt werden (er nutzt dessen Position).</red>");
                return;
            }
            plugin.getWorldManager().setSpawnLocation(player.getLocation());
            msg(sender, "<green>[OSOK] Neuer Arena-Spawnpunkt gesetzt!</green>");
            player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 2.0f));
            return;
        }

        if (sub.equals("resetstats") || sub.equals("resetboard")) {
            plugin.getScoreboardManager().resetAllStats();
            broadcast("<yellow>[OSOK] 🔄 Die Statistiken und das Scoreboard wurden zurückgesetzt!</yellow>");
            Bukkit.getServer().playSound(Sound.sound(org.bukkit.Sound.UI_BUTTON_CLICK, Sound.Source.MASTER, 1.0f, 1.0f));
            return;
        }

        if (sub.equals("resetmap")) {
            broadcast("<yellow>[OSOK] Die Arena-Map wird zurückgesetzt! Server startet neu...</yellow>");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.kick(MiniMessage.miniMessage().deserialize("<green>[OSOK] Arena-Map wird zurückgesetzt!</green>\n<gray>Der Server startet jetzt neu...</gray>"));
            }
            Bukkit.shutdown();
            return;
        }

        msg(sender, "<red>[OSOK] Unbekannter Unterbefehl. Nutze /osok help für eine Liste aller Befehle.</red>");
    }

    private void handleDauerCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            msg(sender, "<yellow>[OSOK] Aktuelles Limit: <green>" +
                    (plugin.getMatchManager().hasKillLimit() ? plugin.getMatchManager().getKillLimit() + " Kills"
                            : plugin.getMatchManager().hasTimeLimit()
                                    ? plugin.getMatchManager()
                                            .formatTime(plugin.getMatchManager().getTimeLimitSeconds()) + " (Zeit)"
                                    : "Kein Limit")
                    + "</green></yellow>");
            msg(sender, "<gray>Verwendung: /osok dauer [kills|minuten|sekunden|off] [wert]</gray>");
            return;
        }

        String type = args[0].toLowerCase();
        if (type.equals("off") || type.equals("none") || type.equals("disable")) {
            plugin.getMatchManager().resetLimits();
            return;
        }

        if (args.length >= 2) {
            String valueStr = args[1];
            try {
                int val = Integer.parseInt(valueStr);
                if (type.equals("kills") || type.equals("kill") || type.equals("k")) {
                    plugin.getMatchManager().setKillLimit(val);
                    return;
                } else if (type.equals("minuten") || type.equals("minut") || type.equals("m")) {
                    plugin.getMatchManager().setTimeLimitMinutes(val);
                    return;
                } else if (type.equals("sekunden") || type.equals("sekunde") || type.equals("s")) {
                    plugin.getMatchManager().setTimeLimitSeconds(val);
                    return;
                }
            } catch (NumberFormatException e) {
                msg(sender, "<red>[OSOK] Ungültiger Zahlenwert: " + valueStr + "</red>");
                return;
            }
        }

        msg(sender, "<red>[OSOK] Ungültiger Parameter. Verwende: /osok dauer [kills|minuten|sekunden|off] [wert]</red>");
    }

    private void handleItemModeCommand(CommandSender sender, String[] args) {
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
        Bukkit.getServer().playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.5f));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (!stack.getSender().isOp() && !plugin.getAccessManager().isPrivileged(stack.getSender())) {
            return Collections.emptyList();
        }
        if (args.length <= 1) {
            String current = args.length == 1 ? args[0] : "";
            List<String> subCommands = Arrays.asList(
                    "start", "stop", "pause", "pausestats", "map", "dauer", "limit", "itemmode",
                    "itemgewichtung", "camper", "itemtest", "clearpfeile", "setspawn",
                    "resetstats", "resetmap", "help"
            );
            return StringUtil.copyPartialMatches(current, subCommands, new ArrayList<>());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("map") || args[0].equalsIgnoreCase("arena") || args[0].equalsIgnoreCase("welt"))) {
            return StringUtil.copyPartialMatches(args[1], Arrays.asList("Standard", "DustPvP"), new ArrayList<>());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("dauer") || args[0].equalsIgnoreCase("limit") || args[0].equalsIgnoreCase("timer"))) {
            return StringUtil.copyPartialMatches(args[1], Arrays.asList("kills", "minuten", "sekunden", "off"), new ArrayList<>());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("itemmode") || args[0].equalsIgnoreCase("itemmodus") || args[0].equalsIgnoreCase("mode"))) {
            return StringUtil.copyPartialMatches(args[1], Arrays.asList("streak", "spawn", "both", "kombi"), new ArrayList<>());
        }

        // itemgewichtung und camper nehmen keine Argumente mehr - sie oeffnen direkt ihr Menue
        return Collections.emptyList();
    }
}
