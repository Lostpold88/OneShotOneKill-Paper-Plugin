package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Locale
import org.bukkit.Sound as BukkitSound

/**
 * Match-Zusammenfassung am Spielende.
 *
 * Ausgewertet werden ausschliesslich **aktuell verbundene** Spieler. Das ist bewusst so: Die
 * Statistiken liegen nach UUID vor, und einen Namen zu einer UUID aufzuloesen, deren Spieler nicht
 * mehr online ist, wuerde eine blockierende Profilabfrage ausloesen. Die Live-Rangliste im
 * Scoreboard arbeitet aus demselben Grund ebenfalls nur mit Online-Spielern.
 */
class MatchSummaryManager(private val plugin: OneShotOneKill) {

    private val stats: ScoreboardManager
        get() = plugin.scoreboardManager

    /**
     * Sendet die Zusammenfassung an alle Spieler. Ohne jede Wertung (kein Kill, keine Strecke)
     * wird nichts ausgegeben - ein abgebrochenes Match soll keinen leeren Kasten erzeugen.
     */
    fun broadcastSummary() {
        val players = Bukkit.getOnlinePlayers().toList()
        if (players.isEmpty() || !hasAnyResult(players)) return

        broadcast(" ")
        broadcast("<gold><b>=======================================</b></gold>")
        broadcast("<yellow><b>   📋 MATCH-ZUSAMMENFASSUNG   </b></yellow>")
        broadcast("<gold><b>=======================================</b></gold>")

        line("🏅", "MVP", players.best { mvpScore(it) }) {
            "<gray>Wertung <white>${mvpScore(it).toInt()}</white></gray>"
        }

        line("🎯", "Meiste Kills", players.best { stats.getKills(it.uniqueId).toDouble() }) {
            "<green>${stats.getKills(it.uniqueId)} Kills</green>"
        }

        line("⚖", "Beste K/D", players.best { stats.getKDRatioValue(it.uniqueId) }) {
            "<aqua>${stats.getKDRatio(it.uniqueId)}</aqua>"
        }

        line("💀", "Meiste Tode", players.best { stats.getDeaths(it.uniqueId).toDouble() }) {
            "<red>${stats.getDeaths(it.uniqueId)} Tode</red>"
        }

        line("🎁", "Meiste Items", players.best { stats.getItemsCollected(it.uniqueId).toDouble() }) {
            "<light_purple>${stats.getItemsCollected(it.uniqueId)} Items</light_purple>"
        }

        line("👟", "Längste Strecke", players.best { stats.getDistance(it.uniqueId) }) {
            "<yellow>${formatDistance(stats.getDistance(it.uniqueId))}</yellow>"
        }

        broadcast("<gold><b>=======================================</b></gold>")
        broadcast(" ")

        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_CHIME, Sound.Source.MASTER, 1.0f, 1.2f)
        )
    }

    /** Schreibt eine Zeile, oder einen Platzhalter, wenn niemand gewertet wurde. */
    private inline fun line(icon: String, label: String, winner: Player?, value: (Player) -> String) {
        if (winner == null) {
            broadcast("<gray>  $icon $label: <dark_gray>—</dark_gray></gray>")
            return
        }
        broadcast(
            "<gray>  $icon $label: <white><b>${winner.name}</b></white> " +
                "<dark_gray>»</dark_gray> ${value(winner)}</gray>"
        )
    }

    /** Spieler mit dem hoechsten Wert, oder `null`, wenn alle Werte bei 0 liegen. */
    private inline fun List<Player>.best(scorer: (Player) -> Double): Player? =
        maxByOrNull(scorer)?.takeIf { scorer(it) > 0.0 }

    /**
     * Gewichtung der MVP-Wertung: Kills zaehlen dreifach, die beste Streak doppelt, Tode ziehen ab.
     */
    private fun mvpScore(player: Player): Double {
        val playerId = player.uniqueId
        return stats.getKills(playerId) * MVP_KILL_WEIGHT.toDouble() +
            stats.getHighestStreak(playerId) * MVP_STREAK_WEIGHT.toDouble() -
            stats.getDeaths(playerId)
    }

    private fun hasAnyResult(players: List<Player>): Boolean = players.any {
        val playerId = it.uniqueId
        stats.getKills(playerId) > 0 || stats.getDeaths(playerId) > 0 ||
            stats.getItemsCollected(playerId) > 0 || stats.getDistance(playerId) > 0.0
    }

    private fun formatDistance(blocks: Double): String = when {
        blocks >= 1000.0 -> String.format(Locale.GERMANY, "%.2f km", blocks / 1000.0)
        else -> String.format(Locale.GERMANY, "%.0f Blöcke", blocks)
    }

    private fun broadcast(miniMessage: String) {
        Bukkit.broadcast(miniMessage.mini())
    }

    private companion object {
        const val MVP_KILL_WEIGHT = 3
        const val MVP_STREAK_WEIGHT = 2
    }
}
