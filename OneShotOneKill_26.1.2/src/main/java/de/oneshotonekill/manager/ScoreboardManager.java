package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;
import java.util.stream.Collectors;

public class ScoreboardManager {

    private final OneShotOneKill plugin;
    private final Map<UUID, Integer> killsMap = new HashMap<>();
    private final Map<UUID, Integer> deathsMap = new HashMap<>();
    private final Map<UUID, Integer> streakMap = new HashMap<>();
    private final Map<UUID, Integer> highestStreakMap = new HashMap<>();
    private final Set<UUID> bountyTargets = new HashSet<>();

    public ScoreboardManager() {
        this.plugin = null;
    }

    public ScoreboardManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public void updateAllScoreboards() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateScoreboard(p);
            updateTabList(p);
        }
    }

    public boolean isBountyTarget(UUID uuid) {
        return bountyTargets.contains(uuid);
    }

    public boolean removeBountyTarget(UUID uuid) {
        return bountyTargets.remove(uuid);
    }

    public void updateScoreboard(Player player) {
        org.bukkit.scoreboard.ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        Scoreboard board = mgr.getNewScoreboard();
        Component titleComponent = MiniMessage.miniMessage().deserialize("<yellow><b>🎯 OSOK</b></yellow>");
        Objective obj = board.registerNewObjective("oneshot", "dummy", titleComponent);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Paper 26.1.2 Scoreboard API: Native numberFormat(blank()) without any NMS reflection!
        obj.numberFormat(NumberFormat.blank());

        int scorePos = 15;
        addScoreLine(obj, "§7-------------------", scorePos--);

        if (plugin != null && plugin.getMatchManager() != null && plugin.getMatchManager().isMatchStarted() && !plugin.getMatchManager().isMatchEnded()) {
            if (plugin.getMatchManager().hasKillLimit()) {
                addScoreLine(obj, "§e§l🎯 MATCH ZIEL: §f" + plugin.getMatchManager().getKillLimit() + " Kills", scorePos--);
                addScoreLine(obj, "§7------------------- ", scorePos--);
            } else if (plugin.getMatchManager().hasTimeLimit()) {
                addScoreLine(obj, "§e§l⏱ VERBLEIBEND: §f" + plugin.getMatchManager().formatTime(plugin.getMatchManager().getRemainingSeconds()), scorePos--);
                addScoreLine(obj, "§7------------------- ", scorePos--);
            }
        }

        addScoreLine(obj, "§e§l🏆 TOP RANKING:", scorePos--);

        // Alle Online-Spieler nach Kills sortieren
        List<Player> sortedPlayers = Bukkit.getOnlinePlayers().stream()
                .sorted((p1, p2) -> Integer.compare(getKills(p2.getUniqueId()), getKills(p1.getUniqueId())))
                .collect(Collectors.toList());

        String[] rankColors = new String[]{"§e#1 ", "§7#2 ", "§c#3 ", "§f#4 ", "§f#5 ", "§f#6 ", "§f#7 ", "§f#8 ", "§f#9 ", "§f#10 "};

        for (int i = 0; i < Math.min(10, sortedPlayers.size()); i++) {
            Player p = sortedPlayers.get(i);
            int k = getKills(p.getUniqueId());
            int s = getStreak(p.getUniqueId());
            int hs = getHighestStreak(p.getUniqueId());
            String kd = getKDRatio(p.getUniqueId());

            String prefix = (i < rankColors.length) ? rankColors[i] : "§f#" + (i + 1) + " ";
            String bountyTag = isBountyTarget(p.getUniqueId()) ? "§e[👑] " : "";
            String playerLine = prefix + bountyTag + "§f" + p.getName() + " §7» §a" + k + "K §7| §b" + kd + " §7| §e⚡" + s + " §6(★" + hs + ")";

            addScoreLine(obj, playerLine, scorePos--);
        }

        if (sortedPlayers.isEmpty()) {
            addScoreLine(obj, "§7Keine Spieler online", scorePos--);
        }

        addScoreLine(obj, "§7--------------------", 0);

        // Nametags über den Köpfen aller Spieler komplett ausblenden
        org.bukkit.scoreboard.Team noNametagTeam = board.getTeam("nonametags");
        if (noNametagTeam == null) {
            noNametagTeam = board.registerNewTeam("nonametags");
        }
        noNametagTeam.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
        for (Player p : Bukkit.getOnlinePlayers()) {
            noNametagTeam.addEntry(p.getName());
        }

        player.setScoreboard(board);
    }

    private void addScoreLine(Objective obj, String text, int scoreVal) {
        org.bukkit.scoreboard.Score score = obj.getScore(text);
        score.setScore(scoreVal);
        score.numberFormat(NumberFormat.blank());
    }

    public void resetAllStats() {
        killsMap.clear();
        deathsMap.clear();
        streakMap.clear();
        highestStreakMap.clear();
        bountyTargets.clear();
        updateAllScoreboards();
    }

    public void updateTabList(Player player) {
        Component header = MiniMessage.miniMessage().deserialize("\n<yellow><b>🎯 OSOK</b></yellow> <gray>|</gray> <red>MATCH STATS</red>\n");
        Component footer = MiniMessage.miniMessage().deserialize("\n<gray>Scoreboard & Leaderboard</gray>\n");
        
        player.sendPlayerListHeaderAndFooter(header, footer);

        List<Player> sortedPlayers = Bukkit.getOnlinePlayers().stream()
                .sorted((p1, p2) -> Integer.compare(getKills(p2.getUniqueId()), getKills(p1.getUniqueId())))
                .collect(Collectors.toList());

        for (int rank = 0; rank < sortedPlayers.size(); rank++) {
            Player p = sortedPlayers.get(rank);
            int k = getKills(p.getUniqueId());
            int d = getDeaths(p.getUniqueId());
            int s = getStreak(p.getUniqueId());
            int hs = getHighestStreak(p.getUniqueId());
            String kd = getKDRatio(p.getUniqueId());

            String rankPrefix = "<yellow>#" + (rank + 1) + " </yellow>";
            String bountyTag = isBountyTarget(p.getUniqueId()) ? "<yellow>[👑] </yellow>" : "";
            String nameText = "<white>" + p.getName() + "</white>";
            String statsText = " <gray>|</gray> <green>K: " + k + "</green> <gray>|</gray> <red>D: " + d + "</red> <gray>|</gray> <aqua>K/D: " + kd + "</aqua> <gray>|</gray> <yellow>⚡" + s + "</yellow> <gold>(★" + hs + ")</gold>";

            Component listName = MiniMessage.miniMessage().deserialize(rankPrefix + bountyTag + nameText + statsText);
            p.playerListName(listName);
        }
    }

    public String getKDRatio(UUID uuid) {
        int k = getKills(uuid);
        int d = getDeaths(uuid);
        if (d == 0) return String.format("%.2f", (double) k);
        return String.format("%.2f", (double) k / d);
    }

    public int addKill(UUID uuid) {
        int k = getKills(uuid) + 1;
        killsMap.put(uuid, k);
        return k;
    }

    public int addDeath(UUID uuid) {
        int d = getDeaths(uuid) + 1;
        deathsMap.put(uuid, d);
        return d;
    }

    public int addStreak(UUID uuid) {
        int streak = getStreak(uuid) + 1;
        streakMap.put(uuid, streak);

        int highscore = highestStreakMap.getOrDefault(uuid, 0);
        if (streak > highscore) {
            highestStreakMap.put(uuid, streak);
        }

        // Kopfgeld aussetzen ab 5er Killstreak!
        if (streak == 5) {
            bountyTargets.add(uuid);
            Player p = Bukkit.getPlayer(uuid);
            String pName = p != null ? p.getName() : "Ein Spieler";
            Component msg = MiniMessage.miniMessage().deserialize("<yellow>[OSOK] 👑 KOPFGELD AUSGESETZT! <white>" + pName + "</white> <gray>hat eine <b>5er Killstreak</b> erreicht! Eliminiere ihn für 2 Spezial-Items!</gray></yellow>");
            Bukkit.broadcast(msg);
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.MASTER, 0.6f, 1.8f);
            }
        }

        return streak;
    }

    public void resetStreak(UUID uuid) {
        streakMap.put(uuid, 0);
        bountyTargets.remove(uuid);
    }

    public int getKills(UUID uuid) {
        return killsMap.getOrDefault(uuid, 0);
    }

    public int getDeaths(UUID uuid) {
        return deathsMap.getOrDefault(uuid, 0);
    }

    public int getStreak(UUID uuid) {
        return streakMap.getOrDefault(uuid, 0);
    }

    public int getHighestStreak(UUID uuid) {
        return highestStreakMap.getOrDefault(uuid, 0);
    }
}
