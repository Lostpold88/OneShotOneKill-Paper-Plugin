package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
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
        Objective obj = board.registerNewObjective("oneshot", "dummy", "§e§l🎯 OSOK");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        hideRedNumbers(obj);

        int scorePos = 15;
        addScoreLine(obj, "§7-------------------", scorePos--);

        if (plugin != null && plugin.getMatchManager() != null) {
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
        hideScoreNumber(score);
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
        String header = "\n§e§l🎯 OSOK §7| §cMATCH STATS\n";
        String footer = "\n§7Scoreboard & Leaderboard\n";
        
        player.setPlayerListHeaderFooter(header, footer);

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

            String rankPrefix = "§e#" + (rank + 1) + " ";
            String bountyTag = isBountyTarget(p.getUniqueId()) ? "§e[👑] " : "";
            String nameText = "§f" + p.getName();
            String statsText = " §7| §aK: " + k + " §7| §cD: " + d + " §7| §bK/D: " + kd + " §7| §e⚡" + s + " §6(★" + hs + ")";

            p.setPlayerListName(rankPrefix + bountyTag + nameText + statsText);
        }
    }

    public String getKDRatio(UUID uuid) {
        int kills = getKills(uuid);
        int deaths = getDeaths(uuid);
        if (deaths == 0) {
            return String.valueOf(kills) + ".00";
        }
        double ratio = (double) kills / (double) deaths;
        return String.format(Locale.US, "%.2f", ratio);
    }

    public int incrementKills(UUID uuid) {
        int kills = killsMap.getOrDefault(uuid, 0) + 1;
        killsMap.put(uuid, kills);
        return kills;
    }

    public int incrementDeaths(UUID uuid) {
        int deaths = deathsMap.getOrDefault(uuid, 0) + 1;
        deathsMap.put(uuid, deaths);
        return deaths;
    }

    public int incrementStreak(UUID uuid) {
        int streak = streakMap.getOrDefault(uuid, 0) + 1;
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
            Bukkit.broadcastMessage("§e[OSOK] 👑 KOPFGELD AUSGESETZT! §f" + pName + " §7hat eine §l5er Killstreak §7erreicht! Eliminiere ihn für 2 Spezial-Items!");
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

    private void hideRedNumbers(Objective objective) {
        try {
            java.lang.reflect.Method getHandleMethod = objective.getClass().getDeclaredMethod("getHandle");
            getHandleMethod.setAccessible(true);
            Object handle = getHandleMethod.invoke(objective);

            Class<?> blankFormatClass = Class.forName("net.minecraft.network.chat.numbers.BlankFormat");
            Object blankInstance = blankFormatClass.getField("INSTANCE").get(null);

            Class<?> numberFormatClass = Class.forName("net.minecraft.network.chat.numbers.NumberFormat");
            java.lang.reflect.Method setNumberFormatMethod = handle.getClass().getMethod("setNumberFormat", numberFormatClass);
            setNumberFormatMethod.setAccessible(true);
            setNumberFormatMethod.invoke(handle, blankInstance);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void hideScoreNumber(org.bukkit.scoreboard.Score score) {
        try {
            org.bukkit.scoreboard.Objective obj = score.getObjective();
            java.lang.reflect.Method getHandleMethod = obj.getClass().getDeclaredMethod("getHandle");
            getHandleMethod.setAccessible(true);
            Object nmsObjective = getHandleMethod.invoke(obj);

            Object nmsScoreboard = nmsObjective.getClass().getMethod("getScoreboard").invoke(nmsObjective);

            Class<?> scoreHolderClass = Class.forName("net.minecraft.world.scores.ScoreHolder");
            java.lang.reflect.Method forNameOnlyMethod = scoreHolderClass.getMethod("forNameOnly", String.class);
            Object scoreHolder = forNameOnlyMethod.invoke(null, score.getEntry());

            Class<?> objectiveClass = Class.forName("net.minecraft.world.scores.Objective");
            java.lang.reflect.Method getOrCreatePlayerScoreMethod = nmsScoreboard.getClass().getMethod("getOrCreatePlayerScore", scoreHolderClass, objectiveClass);
            Object scoreAccess = getOrCreatePlayerScoreMethod.invoke(nmsScoreboard, scoreHolder, nmsObjective);

            Class<?> blankFormatClass = Class.forName("net.minecraft.network.chat.numbers.BlankFormat");
            Object blankInstance = blankFormatClass.getField("INSTANCE").get(null);

            Class<?> numberFormatClass = Class.forName("net.minecraft.network.chat.numbers.NumberFormat");
            java.lang.reflect.Method numberFormatOverrideMethod = scoreAccess.getClass().getMethod("numberFormatOverride", numberFormatClass);
            numberFormatOverrideMethod.setAccessible(true);
            numberFormatOverrideMethod.invoke(scoreAccess, blankInstance);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
