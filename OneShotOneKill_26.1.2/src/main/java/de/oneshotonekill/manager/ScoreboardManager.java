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

/**
 * Verwaltet Statistiken, Sidebar und Tabliste.
 * <p>
 * <b>Performance:</b> Das Scoreboard wird pro Spieler <b>einmal</b> angelegt und danach nur noch
 * inhaltlich aktualisiert. Ein kompletter Neuaufbau pro Update (neues {@code Scoreboard}, neues
 * Objective, alle Zeilen neu geparst, alle Team-Entries neu gesetzt) war der teuerste Pfad im
 * Plugin - er lief bis zu fuenfmal pro Kill fuer jeden Online-Spieler.
 * <p>
 * Statische Zeilen liegen als vorgeparste {@link Component}-Konstanten bereit, damit MiniMessage
 * nicht bei jedem Update erneut denselben String zerlegt.
 */
public class ScoreboardManager {

    private static final String OBJECTIVE_NAME = "oneshot";
    private static final String NAMETAG_TEAM = "no_nametag";
    /** Feste Entry-Schluessel; nie sichtbar, da die Zeile ueber Score#customName gerendert wird. */
    private static final String LINE_ENTRY_PREFIX = "osok_line_";
    private static final int MAX_LINES = 16;
    private static final int MAX_RANKING_ENTRIES = 10;

    // Vorgeparste Komponenten - MiniMessage laeuft dafuer nur einmal beim Klassenladen.
    private static final Component TITLE =
            MiniMessage.miniMessage().deserialize("<red><b>🎯 OSOK</b></red>");
    private static final Component SEPARATOR =
            MiniMessage.miniMessage().deserialize("<gray>-------------------</gray>");
    private static final Component HEADING_RANKING =
            MiniMessage.miniMessage().deserialize("<yellow><b>🏆 TOP RANKING:</b></yellow>");
    private static final Component NO_PLAYERS =
            MiniMessage.miniMessage().deserialize("<gray>Keine Spieler online</gray>");
    private static final Component TAB_HEADER =
            MiniMessage.miniMessage().deserialize("\n<red><b>🎯 OSOK</b></red> <gray>|</gray> <red>MATCH STATS</red>\n");
    private static final Component TAB_FOOTER =
            MiniMessage.miniMessage().deserialize("\n<gray>Scoreboard & Leaderboard</gray>\n");

    private static final String[] RANK_COLORS = {"yellow", "gray", "red"};

    private final OneShotOneKill plugin;
    private final Map<UUID, Integer> killsMap = new HashMap<>();
    private final Map<UUID, Integer> deathsMap = new HashMap<>();
    private final Map<UUID, Integer> streakMap = new HashMap<>();
    private final Map<UUID, Integer> highestStreakMap = new HashMap<>();
    /** Eingesammelte Spezial-Items (Boden-Boxen, Killstreak- und Kopfgeld-Belohnungen). */
    private final Map<UUID, Integer> itemsCollectedMap = new HashMap<>();
    /** Zurueckgelegte Strecke in Bloecken, gemessen vom AntiCampManager. */
    private final Map<UUID, Double> distanceMap = new HashMap<>();
    private final Set<UUID> bountyTargets = new HashSet<>();

    /** Pro Spieler ein dauerhaft wiederverwendetes Board. */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public ScoreboardManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public void updateAllScoreboards() {
        MatchManager match = plugin.getMatchManager();
        boolean frozen = match != null && match.isStatsPaused();

        // Rangliste einmal berechnen statt pro Spieler erneut
        List<Player> ranking = Bukkit.getOnlinePlayers().stream()
                .sorted(Comparator.comparingInt((Player p) -> getKills(p.getUniqueId())).reversed())
                .collect(Collectors.toList());

        for (Player p : Bukkit.getOnlinePlayers()) {
            // Eingefroren (/osok pausestats): bestehende Boards bleiben unangetastet.
            // Wer erst jetzt verbindet, bekommt aber eines - sonst haette er keine Sidebar.
            if (frozen && boards.containsKey(p.getUniqueId())) {
                continue;
            }
            updateScoreboard(p, ranking);
            updateTabListName(p);
            p.sendPlayerListHeaderAndFooter(TAB_HEADER, TAB_FOOTER);
        }
    }

    /** Gibt das gecachte Board eines Spielers frei (bei Quit aufzurufen). */
    public void removePlayer(UUID uuid) {
        boards.remove(uuid);
    }

    public boolean isBountyTarget(UUID uuid) {
        return bountyTargets.contains(uuid);
    }

    public boolean removeBountyTarget(UUID uuid) {
        return bountyTargets.remove(uuid);
    }

    public void updateScoreboard(Player player) {
        updateScoreboard(player, null);
    }

    private void updateScoreboard(Player player, List<Player> presortedRanking) {
        Objective objective = obtainObjective(player);
        if (objective == null) return;

        List<Player> ranking = (presortedRanking != null) ? presortedRanking
                : Bukkit.getOnlinePlayers().stream()
                        .sorted(Comparator.comparingInt((Player p) -> getKills(p.getUniqueId())).reversed())
                        .collect(Collectors.toList());

        List<Component> lines = buildLines(player, ranking);
        applyLines(objective, lines);
        syncNameTagTeam(objective.getScoreboard());
    }

    /**
     * Holt das Board des Spielers oder legt es einmalig an. Danach wird es nur noch befuellt,
     * nicht mehr neu erzeugt.
     */
    private Objective obtainObjective(Player player) {
        org.bukkit.scoreboard.ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return null;

        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            board = mgr.getNewScoreboard();
            boards.put(player.getUniqueId(), board);
        }

        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, TITLE);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(NumberFormat.blank());
        }

        // Nur setzen, wenn der Spieler noch ein anderes Board hat - sonst unnoetige Pakete
        if (!board.equals(player.getScoreboard())) {
            player.setScoreboard(board);
        }
        return objective;
    }

    private List<Component> buildLines(Player player, List<Player> ranking) {
        List<Component> lines = new ArrayList<>(MAX_LINES);
        lines.add(SEPARATOR);

        MatchManager match = plugin.getMatchManager();
        if (match != null && !match.isMatchEnded()) {
            if (match.hasKillLimit()) {
                lines.add(MiniMessage.miniMessage().deserialize(
                        "<yellow><b>🎯 MATCH ZIEL:</b></yellow> <white>" + match.getKillLimit() + " Kills</white>"));
                lines.add(SEPARATOR);
            } else if (match.getTimeLimitSeconds() > 0) {
                int shownSeconds = match.isMatchStarted() ? match.getRemainingSeconds() : match.getTimeLimitSeconds();
                lines.add(MiniMessage.miniMessage().deserialize(
                        "<yellow><b>⏱ VERBLEIBEND:</b></yellow> <white>" + match.formatTime(shownSeconds) + "</white>"));
                lines.add(SEPARATOR);
            }

            SuddenDeathManager suddenDeath = plugin.getSuddenDeathManager();
            if (suddenDeath != null && suddenDeath.isActive()) {
                lines.add(suddenDeath.sidebarLine());
                lines.add(SEPARATOR);
            }
        }

        lines.add(HEADING_RANKING);

        int shown = Math.min(MAX_RANKING_ENTRIES, ranking.size());
        for (int i = 0; i < shown && lines.size() < MAX_LINES - 1; i++) {
            lines.add(rankingLine(i, ranking.get(i)));
        }
        if (ranking.isEmpty()) {
            lines.add(NO_PLAYERS);
        }

        lines.add(SEPARATOR);
        return lines;
    }

    private Component rankingLine(int index, Player p) {
        int kills = getKills(p.getUniqueId());
        int streak = getStreak(p.getUniqueId());
        int highest = getHighestStreak(p.getUniqueId());
        String kd = getKDRatio(p.getUniqueId());

        String color = (index < RANK_COLORS.length) ? RANK_COLORS[index] : "white";
        String bountyTag = isBountyTarget(p.getUniqueId()) ? "<yellow>[👑] </yellow>" : "";

        return MiniMessage.miniMessage().deserialize(
                "<" + color + ">#" + (index + 1) + " </" + color + ">" + bountyTag
                        + "<white>" + p.getName() + "</white> <gray>»</gray> <green>" + kills + "K</green> <gray>|</gray> <aqua>" + kd
                        + "</aqua> <gray>|</gray> <yellow>⚡" + streak + "</yellow> <gold>(★" + highest + ")</gold>");
    }

    /**
     * Schreibt die Zeilen in feste Entry-Schluessel. Ueberzaehlige Zeilen aus einem
     * vorherigen Update werden entfernt, statt das Board zu verwerfen.
     */
    private void applyLines(Objective objective, List<Component> lines) {
        Scoreboard board = objective.getScoreboard();

        for (int i = 0; i < lines.size(); i++) {
            Score score = objective.getScore(LINE_ENTRY_PREFIX + i);
            score.setScore(lines.size() - i);
            score.customName(lines.get(i));
            score.numberFormat(NumberFormat.blank());
        }

        for (int i = lines.size(); i < MAX_LINES; i++) {
            String entry = LINE_ENTRY_PREFIX + i;
            if (objective.getScore(entry).isScoreSet()) {
                board.resetScores(entry);
            }
        }
    }

    /** Blendet Nametags aus. Entries werden nur bei Aenderung angefasst, nicht bei jedem Update. */
    private void syncNameTagTeam(Scoreboard board) {
        Team team = board.getTeam(NAMETAG_TEAM);
        if (team == null) {
            team = board.registerNewTeam(NAMETAG_TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }

        Set<String> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getName());
            if (!team.hasEntry(p.getName())) {
                team.addEntry(p.getName());
            }
        }
        for (String entry : new HashSet<>(team.getEntries())) {
            if (!online.contains(entry)) {
                team.removeEntry(entry);
            }
        }
    }

    public void resetAllStats() {
        killsMap.clear();
        deathsMap.clear();
        streakMap.clear();
        highestStreakMap.clear();
        itemsCollectedMap.clear();
        distanceMap.clear();
        bountyTargets.clear();
        updateAllScoreboards();
    }

    /** Kyori Component Tabliste: Anzeigename des Spielers inkl. Live-Stats. */
    public void updateTabListName(Player player) {
        int k = getKills(player.getUniqueId());
        int d = getDeaths(player.getUniqueId());
        int s = getStreak(player.getUniqueId());
        int hs = getHighestStreak(player.getUniqueId());
        String kd = getKDRatio(player.getUniqueId());

        String bountyTag = isBountyTarget(player.getUniqueId()) ? "<yellow>[👑] </yellow>" : "";
        player.playerListName(MiniMessage.miniMessage().deserialize(
                bountyTag + "<white>" + player.getName() + "</white>"
                        + " <gray>|</gray> <green>K: " + k + "</green> <gray>|</gray> <red>D: " + d + "</red>"
                        + " <gray>|</gray> <aqua>K/D: " + kd + "</aqua> <gray>|</gray> <yellow>⚡" + s + "</yellow> <gold>(★" + hs + ")</gold>"));
    }

    public void updateTabListHeaderFooter(Player player) {
        player.sendPlayerListHeaderAndFooter(TAB_HEADER, TAB_FOOTER);
    }

    // ------------------------------------------------------------------
    // Statistiken. Bewusst OHNE internes updateAllScoreboards: Der Aufrufer
    // aktualisiert einmal, nachdem alle Werte gesetzt sind.
    // ------------------------------------------------------------------

    public int addKill(UUID uuid) {
        int k = killsMap.getOrDefault(uuid, 0) + 1;
        killsMap.put(uuid, k);
        return k;
    }

    public int addDeath(UUID uuid) {
        int d = deathsMap.getOrDefault(uuid, 0) + 1;
        deathsMap.put(uuid, d);
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
            Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                    "<red><b>[👑 KOPFGELD]</b> <yellow><b>" + name + "</b> hat eine <b>5er Streak!</b> Wer ihn tötet erhält 2 Bonus-Items!</yellow></red>"));
            if (p != null) {
                p.getWorld().strikeLightningEffect(p.getLocation());
                p.playSound(Sound.sound(org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, Sound.Source.MASTER, 0.6f, 1.8f));
            }
        }
        return s;
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

    /** Zaehlt eingesammelte Spezial-Items fuer die Match-Zusammenfassung. */
    public int addItemsCollected(UUID uuid, int amount) {
        int total = itemsCollectedMap.getOrDefault(uuid, 0) + amount;
        itemsCollectedMap.put(uuid, total);
        return total;
    }

    public int getItemsCollected(UUID uuid) {
        return itemsCollectedMap.getOrDefault(uuid, 0);
    }

    /** Summiert zurueckgelegte Bloecke fuer die Match-Zusammenfassung. */
    public void addDistance(UUID uuid, double blocks) {
        distanceMap.merge(uuid, blocks, Double::sum);
    }

    public double getDistance(UUID uuid) {
        return distanceMap.getOrDefault(uuid, 0.0);
    }

    public String getKDRatio(UUID uuid) {
        return String.format(Locale.US, "%.1f", getKDRatioValue(uuid));
    }

    /** K/D als Zahl - fuer Vergleiche in der Match-Zusammenfassung. */
    public double getKDRatioValue(UUID uuid) {
        int k = getKills(uuid);
        int d = getDeaths(uuid);
        if (d == 0) return k;
        return (double) k / (double) d;
    }
}
