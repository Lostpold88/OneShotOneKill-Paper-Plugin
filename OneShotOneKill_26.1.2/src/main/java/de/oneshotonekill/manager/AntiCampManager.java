package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Zwei bewegungsabhaengige Aufgaben, die dieselbe Datenquelle nutzen:
 * <ul>
 *   <li><b>Anti-Camping</b> - wer {@link #CAMP_WARN_SECONDS} Sekunden im Umkreis von
 *       {@link #CAMP_RADIUS} Bloecken bleibt, leuchtet fuer alle auf, bis er sich bewegt.</li>
 *   <li><b>Streckenmessung</b> - zurueckgelegte Bloecke pro Spieler fuer die
 *       Match-Zusammenfassung.</li>
 * </ul>
 * Die Streckenmessung haengt am Paper-Event {@code PlayerMoveEvent#hasChangedPosition()}, damit
 * reine Blickrichtungsaenderungen gar nicht erst weiterverarbeitet werden. Spruenge ueber
 * {@link #MAX_STEP_DISTANCE} gelten als Teleport (Respawn, Rauchbombe, Teleport-Granate) und
 * werden nicht mitgezaehlt - sonst wuerde ein Respawn quer durch die Arena die Statistik
 * verfaelschen.
 */
public class AntiCampManager implements Listener {

    /** Radius, innerhalb dessen ein Spieler als "steht noch immer da" gilt. */
    private static final double CAMP_RADIUS = 5.0;
    private static final double CAMP_RADIUS_SQUARED = CAMP_RADIUS * CAMP_RADIUS;
    /** Nach so vielen Sekunden auf der Stelle wird markiert. */
    private static final int CAMP_WARN_SECONDS = 20;
    /** So viele Sekunden vorher gibt es eine Vorwarnung. */
    private static final int CAMP_PREWARN_SECONDS = 5;
    /** Groessere Spruenge sind Teleports und zaehlen nicht zur Strecke. */
    private static final double MAX_STEP_DISTANCE = 8.0;
    private static final long CHECK_PERIOD_TICKS = 20L;

    private final OneShotOneKill plugin;
    private final Map<UUID, Location> anchors = new HashMap<>();
    private final Map<UUID, Integer> stationarySeconds = new HashMap<>();
    private final Set<UUID> flagged = new HashSet<>();
    private ScheduledTask checkTask;

    public AntiCampManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    /** Startet die Dauerpruefung. Laeuft ueber die gesamte Plugin-Laufzeit. */
    public void start() {
        if (checkTask != null) return;
        checkTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(),
                CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        reset();
    }

    /** Setzt alle Zaehler und Markierungen zurueck (Match-Start, Match-Ende, Map-Wechsel). */
    public void reset() {
        for (UUID flaggedId : new HashSet<>(flagged)) {
            Player player = Bukkit.getPlayer(flaggedId);
            if (player != null) {
                plugin.getGlowManager().remove(player, GlowManager.GlowReason.CAMPING);
            }
        }
        flagged.clear();
        anchors.clear();
        stationarySeconds.clear();
    }

    /** Gibt die Daten eines Spielers frei (bei Quit aufzurufen). */
    public void removePlayer(UUID uuid) {
        anchors.remove(uuid);
        stationarySeconds.remove(uuid);
        flagged.remove(uuid);
    }

    /**
     * Streckenmessung. Laeuft auf {@code MONITOR}, weil hier nichts entschieden, sondern nur
     * gezaehlt wird - abgebrochene Bewegungen sollen gar nicht erst ankommen.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Paper: filtert reine Blickrichtungsaenderungen heraus, bevor irgendetwas gerechnet wird
        if (!event.hasChangedPosition()) return;

        MatchManager match = plugin.getMatchManager();
        if (!match.isMatchStarted() || match.isMatchPaused() || match.isMatchEnded() || match.isStatsPaused()) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;
        if (!plugin.getArenaManager().isInArenaArea(to)) return;

        double distance = from.distance(to);
        if (distance <= 0.0 || distance > MAX_STEP_DISTANCE) return;

        plugin.getScoreboardManager().addDistance(event.getPlayer().getUniqueId(), distance);
    }

    private void tick() {
        MatchManager match = plugin.getMatchManager();
        boolean matchRunning = match.isMatchStarted() && !match.isMatchPaused() && !match.isMatchEnded();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();

            if (!matchRunning || !plugin.getArenaManager().isInArenaArea(player.getLocation())) {
                release(player);
                continue;
            }

            Location anchor = anchors.get(playerId);
            boolean moved = anchor == null
                    || anchor.getWorld() == null
                    || !anchor.getWorld().equals(player.getWorld())
                    || anchor.distanceSquared(player.getLocation()) > CAMP_RADIUS_SQUARED;

            if (moved) {
                anchors.put(playerId, player.getLocation().clone());
                stationarySeconds.put(playerId, 0);
                if (flagged.remove(playerId)) {
                    plugin.getGlowManager().remove(player, GlowManager.GlowReason.CAMPING);
                    player.sendMessage(MiniMessage.miniMessage().deserialize(
                            "<green>[OSOK] 👟 Du bist nicht mehr als Camper markiert.</green>"));
                }
                continue;
            }

            int seconds = stationarySeconds.merge(playerId, 1, Integer::sum);

            if (seconds == CAMP_WARN_SECONDS - CAMP_PREWARN_SECONDS) {
                player.sendActionBar(MiniMessage.miniMessage().deserialize(
                        "<yellow><b>⚠ Beweg dich! In " + CAMP_PREWARN_SECONDS + "s leuchtest du auf.</b></yellow>"));
                player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 1.0f, 1.4f));
                continue;
            }

            if (seconds >= CAMP_WARN_SECONDS && flagged.add(playerId)) {
                plugin.getGlowManager().add(player, GlowManager.GlowReason.CAMPING);
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        "<red>[OSOK] 🏕 <b>CAMPER!</b> <gray>Du stehst zu lange an derselben Stelle und leuchtest jetzt für alle.</gray></red>"));
                player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ELDER_GUARDIAN_CURSE, Sound.Source.MASTER, 0.7f, 1.0f));
            }
        }
    }

    /** Nimmt Markierung und Zaehler eines Spielers zurueck. */
    private void release(Player player) {
        UUID playerId = player.getUniqueId();
        anchors.remove(playerId);
        stationarySeconds.remove(playerId);
        if (flagged.remove(playerId)) {
            plugin.getGlowManager().remove(player, GlowManager.GlowReason.CAMPING);
        }
    }
}
