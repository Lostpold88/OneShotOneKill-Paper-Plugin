package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import java.util.UUID
import org.bukkit.Sound as BukkitSound

/**
 * Zwei bewegungsabhaengige Aufgaben, die dieselbe Datenquelle nutzen:
 *
 * - **Anti-Camping** - wer [campSeconds] Sekunden im Umkreis von [campRadius] Bloecken bleibt,
 *   leuchtet fuer alle auf, bis er sich bewegt.
 * - **Streckenmessung** - zurueckgelegte Bloecke pro Spieler fuer die Match-Zusammenfassung.
 *
 * Die Streckenmessung haengt am Paper-Event `PlayerMoveEvent#hasChangedPosition()`, damit reine
 * Blickrichtungsaenderungen gar nicht erst weiterverarbeitet werden. Spruenge ueber
 * [MAX_STEP_DISTANCE] gelten als Teleport (Respawn, Rauchbombe, Teleport-Granate) und werden nicht
 * mitgezaehlt - sonst wuerde ein Respawn quer durch die Arena die Statistik verfaelschen.
 */
class AntiCampManager(private val plugin: OneShotOneKill) : Listener {

    /**
     * Schaltet die Camper-Markierung um. Die **Streckenmessung fuer die Match-Zusammenfassung
     * laeuft unabhaengig davon weiter** - sie ist eine eigene Aufgabe und haengt nicht an dieser
     * Einstellung.
     */
    var isEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) reset()
        }

    /** Einstellbar ueber /osok camper. */
    var campRadius: Double = DEFAULT_CAMP_RADIUS
        set(value) {
            field = value.coerceIn(MIN_CAMP_RADIUS, MAX_CAMP_RADIUS)
            // Laufende Zaehler gelten fuer den alten Radius und waeren jetzt falsch
            reset()
        }

    var campSeconds: Int = DEFAULT_CAMP_SECONDS
        set(value) {
            field = value.coerceIn(MIN_CAMP_SECONDS, MAX_CAMP_SECONDS)
            reset()
        }

    private val anchors = mutableMapOf<UUID, Location>()
    private val stationarySeconds = mutableMapOf<UUID, Int>()
    private val flagged = mutableSetOf<UUID>()
    private var checkTask: ScheduledTask? = null

    /** Startet die Dauerpruefung. Laeuft ueber die gesamte Plugin-Laufzeit. */
    fun start() {
        if (checkTask != null) return
        checkTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin, { tick() }, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS,
        )
    }

    fun stop() {
        checkTask?.cancel()
        checkTask = null
        reset()
    }

    /** Setzt alle Zaehler und Markierungen zurueck (Match-Start, Match-Ende, Map-Wechsel). */
    fun reset() {
        flagged.toList().forEach { flaggedId ->
            Bukkit.getPlayer(flaggedId)?.let { plugin.glowManager.remove(it, GlowManager.GlowReason.CAMPING) }
        }
        flagged.clear()
        anchors.clear()
        stationarySeconds.clear()
    }

    /** Gibt die Daten eines Spielers frei (bei Quit aufzurufen). */
    fun removePlayer(uuid: UUID) {
        anchors.remove(uuid)
        stationarySeconds.remove(uuid)
        flagged.remove(uuid)
    }

    /**
     * Streckenmessung. Laeuft auf `MONITOR`, weil hier nichts entschieden, sondern nur gezaehlt
     * wird - abgebrochene Bewegungen sollen gar nicht erst ankommen.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        // Paper: filtert reine Blickrichtungsaenderungen heraus, bevor irgendetwas gerechnet wird
        if (!event.hasChangedPosition()) return

        val match = plugin.matchManager
        if (!match.isMatchStarted || match.isMatchPaused || match.isMatchEnded || match.isStatsPaused) return

        val from = event.from
        val to = event.to
        if (from.world == null || from.world != to.world) return
        if (!plugin.arenaManager.isInArenaArea(to)) return

        val distance = from.distance(to)
        if (distance <= 0.0 || distance > MAX_STEP_DISTANCE) return

        plugin.scoreboardManager.addDistance(event.player.uniqueId, distance)
    }

    private fun tick() {
        if (!isEnabled) return

        val match = plugin.matchManager
        val matchRunning = match.isMatchStarted && !match.isMatchPaused && !match.isMatchEnded
        val radiusSquared = campRadius * campRadius

        for (player in Bukkit.getOnlinePlayers()) {
            val playerId = player.uniqueId

            if (!matchRunning || !plugin.arenaManager.isInArenaArea(player.location)) {
                release(player)
                continue
            }

            val anchor = anchors[playerId]
            val moved = anchor == null ||
                anchor.world == null ||
                anchor.world != player.world ||
                anchor.distanceSquared(player.location) > radiusSquared

            if (moved) {
                anchors[playerId] = player.location.clone()
                stationarySeconds[playerId] = 0
                if (flagged.remove(playerId)) {
                    plugin.glowManager.remove(player, GlowManager.GlowReason.CAMPING)
                    player.sendMessage("<green>[OSOK] 👟 Du bist nicht mehr als Camper markiert.</green>".mini())
                }
                continue
            }

            val seconds = (stationarySeconds[playerId] ?: 0) + 1
            stationarySeconds[playerId] = seconds

            if (seconds == campSeconds - CAMP_PREWARN_SECONDS) {
                player.sendActionBar(
                    "<yellow><b>⚠ Beweg dich! In ${CAMP_PREWARN_SECONDS}s leuchtest du auf.</b></yellow>".mini()
                )
                player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 1.0f, 1.4f))
                continue
            }

            if (seconds >= campSeconds && flagged.add(playerId)) {
                plugin.glowManager.add(player, GlowManager.GlowReason.CAMPING)
                player.sendMessage(
                    ("<red>[OSOK] 🏕 <b>CAMPER!</b> <gray>Du stehst zu lange an derselben Stelle " +
                        "und leuchtest jetzt für alle.</gray></red>").mini()
                )
                player.playSound(
                    Sound.sound(BukkitSound.ENTITY_ELDER_GUARDIAN_CURSE, Sound.Source.MASTER, 0.7f, 1.0f)
                )
            }
        }
    }

    /** Nimmt Markierung und Zaehler eines Spielers zurueck. */
    private fun release(player: Player) {
        val playerId = player.uniqueId
        anchors.remove(playerId)
        stationarySeconds.remove(playerId)
        if (flagged.remove(playerId)) {
            plugin.glowManager.remove(player, GlowManager.GlowReason.CAMPING)
        }
    }

    companion object {
        /** Standardradius, innerhalb dessen ein Spieler als "steht noch immer da" gilt. */
        const val DEFAULT_CAMP_RADIUS = 5.0

        /** Standardzeit auf der Stelle bis zur Markierung. */
        const val DEFAULT_CAMP_SECONDS = 20

        const val MIN_CAMP_RADIUS = 1.0
        const val MAX_CAMP_RADIUS = 64.0
        const val MIN_CAMP_SECONDS = 3
        const val MAX_CAMP_SECONDS = 600

        /** So viele Sekunden vorher gibt es eine Vorwarnung. */
        private const val CAMP_PREWARN_SECONDS = 5

        /** Groessere Spruenge sind Teleports und zaehlen nicht zur Strecke. */
        private const val MAX_STEP_DISTANCE = 8.0

        private const val CHECK_PERIOD_TICKS = 20L
    }
}
