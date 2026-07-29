package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;
import java.util.stream.Collectors;

public class ScoreboardManager {

    private final OneShotOneKill plugin;
    private final Map<UUID, Integer> killsMap = new HashMap<>();
    private final Map<UUID, Integer> deathsMap = new HashMap<>();
    private final Map<UUID, Integer> streakMap = new HashMap<>();
    private final Map<UUID, Integer> highestStreakMap = new HashMap<>();
    private final Set<UUID> bountyTargets = new HashSet<>();

    private static final Component SEPARATOR = MiniMessage.miniMessage().deserialize("<gray>-------------------</gray>");

    public ScoreboardManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public void updateAllScoreboards() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateScoreboard(p);
            updateTabListName(p);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateTabListHeaderFooter(p);
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
        Component titleComponent = MiniMessage.miniMessage().deserialize("<red><b>🎯 OSOK</b></red>");

        // Paper 26.1.2 Scoreboard API: Native Criteria & Kyori Component Titel (0% deprecated String-Criteria)
        Objective obj = board.registerNewObjective("oneshot", Criteria.DUMMY, titleComponent);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.numberFormat(NumberFormat.blank());

        int scorePos = 15;
        int lineId = 0;

        lineId = addScoreLine(obj, SEPARATOR, scorePos--, lineId);

        // Match-Ziel: wird angezeigt, sobald ein Limit konfiguriert ist - nicht erst ab /osok start.
        // Solange das Match nicht laeuft, weist ein Zusatz darauf hin.
        MatchManager match = (plugin != null) ? plugin.getMatchManager() : null;
        if (match != null && !match.isMatchEnded()) {
            boolean running = match.isMatchStarted();
            String pendingHint = running ? "" : " <dark_gray>(ab /osok start)</dark_gray>";

            if (match.hasKillLimit()) {
                lineId = addScoreLine(obj, MiniMessage.miniMessage().deserialize(
                        "<yellow><b>🎯 MATCH ZIEL:</b></yellow> <white>" + match.getKillLimit() + " Kills</white>" + pendingHint), scorePos--, lineId);

                if (running) {
                    int remaining = Math.max(0, match.getKillLimit() - getKills(player.getUniqueId()));
                    lineId = addScoreLine(obj, MiniMessage.miniMessage().deserialize(
                            "<gold>➜ Du brauchst noch <yellow><b>" + remaining + "</b></yellow> "
                                    + (remaining == 1 ? "Kill" : "Kills") + "</gold>"), scorePos--, lineId);
                }
                lineId = addScoreLine(obj, SEPARATOR, scorePos--, lineId);

            } else if (match.getTimeLimitSeconds() > 0) {
                int shownSeconds = running ? match.getRemainingSeconds() : match.getTimeLimitSeconds();
                lineId = addScoreLine(obj, MiniMessage.miniMessage().deserialize(
                        "<yellow><b>⏱ VERBLEIBEND:</b></yellow> <white>" + match.formatTime(shownSeconds) + "</white>" + pendingHint), scorePos--, lineId);
                lineId = addScoreLine(obj, SEPARATOR, scorePos--, lineId);
            }
        }

        lineId = addScoreLine(obj, MiniMessage.miniMessage().deserialize("<yellow><b>🏆 TOP RANKING:</b></yellow>"), scorePos--, lineId);

        // Alle Online-Spieler nach Kills sortieren
        List<Player> sortedPlayers = Bukkit.getOnlinePlayers().stream()
                .sorted((p1, p2) -> Integer.compare(getKills(p2.getUniqueId()), getKills(p1.getUniqueId())))
                .collect(Collectors.toList());

        String[] rankColors = new String[]{"yellow", "gray", "red"};

        for (int i = 0; i < Math.min(10, sortedPlayers.size()); i++) {
            Player p = sortedPlayers.get(i);
            int k = getKills(p.getUniqueId());
            int s = getStreak(p.getUniqueId());
            int hs = getHighestStreak(p.getUniqueId());
            String kd = getKDRatio(p.getUniqueId());

            String rankColor = (i < rankColors.length) ? rankColors[i] : "white";
            String bountyTag = isBountyTarget(p.getUniqueId()) ? "<yellow>[👑] </yellow>" : "";
            Component playerLine = MiniMessage.miniMessage().deserialize(
                    "<" + rankColor + ">#" + (i + 1) + " </" + rankColor + ">" + bountyTag
                            + "<white>" + p.getName() + "</white> <gray>»</gray> <green>" + k + "K</green> <gray>|</gray> <aqua>" + kd
                            + "</aqua> <gray>|</gray> <yellow>⚡" + s + "</yellow> <gold>(★" + hs + ")</gold>");

            lineId = addScoreLine(obj, playerLine, scorePos--, lineId);
        }

        if (sortedPlayers.isEmpty()) {
            lineId = addScoreLine(obj, MiniMessage.miniMessage().deserialize("<gray>Keine Spieler online</gray>"), scorePos--, lineId);
        }

        addScoreLine(obj, SEPARATOR, 0, lineId);

        // Nametags über den Köpfen aller Spieler komplett ausblenden
        Team noTagTeam = board.getTeam("no_nametag");
        if (noTagTeam == null) {
            noTagTeam = board.registerNewTeam("no_nametag");
        }
        noTagTeam.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);

        for (Player p : Bukkit.getOnlinePlayers()) {
            noTagTeam.addEntry(p.getName());
        }

        player.setScoreboard(board);
    }

    /**
     * Paper Native Scoreboard API: Die Zeile wird ausschliesslich ueber {@link Score#customName(Component)}
     * als Kyori Component gerendert. Der Entry-String dient nur als eindeutiger, nie sichtbarer Schluessel.
     */
    private int addScoreLine(Objective obj, Component text, int scoreVal, int lineId) {
        Score score = obj.getScore("osok_line_" + lineId);
        score.setScore(scoreVal);
        score.customName(text);
        score.numberFormat(NumberFormat.blank());
        return lineId + 1;
    }

    public void resetAllStats() {
        killsMap.clear();
        deathsMap.clear();
        streakMap.clear();
        highestStreakMap.clear();
        bountyTargets.clear();
        updateAllScoreboards();
    }

    /**
     * Kyori Component Tabliste: Setzt den Anzeigenamen des Spielers inkl. Live-Stats.
     */
    public void updateTabListName(Player player) {
        int k = getKills(player.getUniqueId());
        int d = getDeaths(player.getUniqueId());
        int s = getStreak(player.getUniqueId());
        int hs = getHighestStreak(player.getUniqueId());
        String kd = getKDRatio(player.getUniqueId());

        String bountyTag = isBountyTarget(player.getUniqueId()) ? "<yellow>[👑] </yellow>" : "";
        Component tabName = MiniMessage.miniMessage().deserialize(
                bountyTag + "<white>" + player.getName() + "</white>"
                        + " <gray>|</gray> <green>K: " + k + "</green> <gray>|</gray> <red>D: " + d + "</red>"
                        + " <gray>|</gray> <aqua>K/D: " + kd + "</aqua> <gray>|</gray> <yellow>⚡" + s + "</yellow> <gold>(★" + hs + ")</gold>");

        player.playerListName(tabName);
    }

    public void updateTabListHeaderFooter(Player player) {
        Component header = MiniMessage.miniMessage().deserialize("\n<red><b>🎯 OSOK</b></red> <gray>|</gray> <red>MATCH STATS</red>\n");
        Component footer = MiniMessage.miniMessage().deserialize("\n<gray>Scoreboard & Leaderboard</gray>\n");

        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    public int addKill(UUID uuid) {
        int k = killsMap.getOrDefault(uuid, 0) + 1;
        killsMap.put(uuid, k);
        updateAllScoreboards();
        return k;
    }

    public int addDeath(UUID uuid) {
        int d = deathsMap.getOrDefault(uuid, 0) + 1;
        deathsMap.put(uuid, d);
        updateAllScoreboards();
        return d;
    }

    public int addStreak(UUID uuid) {
        int s = streakMap.getOrDefault(uuid, 0) + 1;
        streakMap.put(uuid, s);

        int hs = highestStreakMap.getOrDefault(uuid, 0);
        if (s > hs) {
            highestStreakMap.put(uuid, s);
        }

        if (s == 5) {
            bountyTargets.add(uuid);
            Player p = Bukkit.getPlayer(uuid);
            String name = (p != null) ? p.getName() : "Ein Spieler";
            Component msg = MiniMessage.miniMessage().deserialize("<red><b>[👑 KOPFGELD]</b> <yellow><b>" + name + "</b> hat eine <b>5er Streak!</b> Wer ihn tötet erhält 2 Bonus-Items!</yellow></red>");
            Bukkit.broadcast(msg);
            if (p != null) {
                p.getWorld().strikeLightningEffect(p.getLocation());
                p.playSound(Sound.sound(org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, Sound.Source.MASTER, 0.6f, 1.8f));
            }
        }

        updateAllScoreboards();
        return s;
    }

    public void resetStreak(UUID uuid) {
        streakMap.put(uuid, 0);
        bountyTargets.remove(uuid);
        updateAllScoreboards();
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

    public String getKDRatio(UUID uuid) {
        int k = getKills(uuid);
        int d = getDeaths(uuid);
        if (d == 0) return String.format(Locale.US, "%.1f", (double) k);
        return String.format(Locale.US, "%.1f", (double) k / (double) d);
    }
}
