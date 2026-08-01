package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Match-Zusammenfassung am Spielende.
 * <p>
 * Ausgewertet werden ausschliesslich <b>aktuell verbundene</b> Spieler. Das ist bewusst so:
 * Die Statistiken liegen nach UUID vor, und einen Namen zu einer UUID aufzuloesen, deren
 * Spieler nicht mehr online ist, wuerde eine blockierende Profilabfrage ausloesen. Die
 * Live-Rangliste im Scoreboard arbeitet aus demselben Grund ebenfalls nur mit Online-Spielern.
 */
public class MatchSummaryManager {

    /** Gewichtung der MVP-Wertung: Kills zaehlen dreifach, die beste Streak doppelt, Tode ziehen ab. */
    private static final int MVP_KILL_WEIGHT = 3;
    private static final int MVP_STREAK_WEIGHT = 2;

    private final OneShotOneKill plugin;

    public MatchSummaryManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    /**
     * Sendet die Zusammenfassung an alle Spieler. Ohne jede Wertung (kein Kill, keine Strecke)
     * wird nichts ausgegeben - ein abgebrochenes Match soll keinen leeren Kasten erzeugen.
     */
    public void broadcastSummary() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty() || !hasAnyResult(players)) {
            return;
        }

        ScoreboardManager stats = plugin.getScoreboardManager();

        broadcast(" ");
        broadcast("<gold><b>=======================================</b></gold>");
        broadcast("<yellow><b>   📋 MATCH-ZUSAMMENFASSUNG   </b></yellow>");
        broadcast("<gold><b>=======================================</b></gold>");

        line("🏅", "MVP", best(players, this::mvpScore),
                player -> "<gray>Wertung <white>" + (int) mvpScore(player) + "</white></gray>");

        line("🎯", "Meiste Kills", best(players, player -> stats.getKills(player.getUniqueId())),
                player -> "<green>" + stats.getKills(player.getUniqueId()) + " Kills</green>");

        line("⚖", "Beste K/D", best(players, player -> stats.getKDRatioValue(player.getUniqueId())),
                player -> "<aqua>" + stats.getKDRatio(player.getUniqueId()) + "</aqua>");

        line("💀", "Meiste Tode", best(players, player -> stats.getDeaths(player.getUniqueId())),
                player -> "<red>" + stats.getDeaths(player.getUniqueId()) + " Tode</red>");

        line("🎁", "Meiste Items", best(players, player -> stats.getItemsCollected(player.getUniqueId())),
                player -> "<light_purple>" + stats.getItemsCollected(player.getUniqueId()) + " Items</light_purple>");

        line("👟", "Längste Strecke", best(players, player -> stats.getDistance(player.getUniqueId())),
                player -> "<yellow>" + formatDistance(stats.getDistance(player.getUniqueId())) + "</yellow>");

        broadcast("<gold><b>=======================================</b></gold>");
        broadcast(" ");

        Bukkit.getServer().playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, Sound.Source.MASTER, 1.0f, 1.2f));
    }

    /** Schreibt eine Zeile, oder einen Platzhalter, wenn niemand gewertet wurde. */
    private void line(String icon, String label, Player winner, Function<Player, String> value) {
        if (winner == null) {
            broadcast("<gray>  " + icon + " " + label + ": <dark_gray>—</dark_gray></gray>");
            return;
        }
        broadcast("<gray>  " + icon + " " + label + ": <white><b>" + winner.getName() + "</b></white> <dark_gray>»</dark_gray> "
                + value.apply(winner) + "</gray>");
    }

    /** Spieler mit dem hoechsten Wert, oder {@code null}, wenn alle Werte bei 0 liegen. */
    private Player best(List<Player> players, ToDoubleFunction<Player> scorer) {
        Player leader = players.stream()
                .max(Comparator.comparingDouble(scorer))
                .orElse(null);
        if (leader == null || scorer.applyAsDouble(leader) <= 0.0) {
            return null;
        }
        return leader;
    }

    private double mvpScore(Player player) {
        UUID playerId = player.getUniqueId();
        ScoreboardManager stats = plugin.getScoreboardManager();
        return stats.getKills(playerId) * (double) MVP_KILL_WEIGHT
                + stats.getHighestStreak(playerId) * (double) MVP_STREAK_WEIGHT
                - stats.getDeaths(playerId);
    }

    private boolean hasAnyResult(List<Player> players) {
        ScoreboardManager stats = plugin.getScoreboardManager();
        for (Player player : players) {
            UUID playerId = player.getUniqueId();
            if (stats.getKills(playerId) > 0 || stats.getDeaths(playerId) > 0
                    || stats.getItemsCollected(playerId) > 0 || stats.getDistance(playerId) > 0.0) {
                return true;
            }
        }
        return false;
    }

    private String formatDistance(double blocks) {
        if (blocks >= 1000.0) {
            return String.format(Locale.GERMANY, "%.2f km", blocks / 1000.0);
        }
        return String.format(Locale.GERMANY, "%.0f Blöcke", blocks);
    }

    private void broadcast(String miniMessage) {
        Component component = MiniMessage.miniMessage().deserialize(miniMessage);
        Bukkit.broadcast(component);
    }
}
