package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Endphase eines Matches mit Zeitlimit.
 * <p>
 * Ab {@link #SUDDEN_DEATH_SECONDS} verbleibenden Sekunden passiert dreierlei:
 * <ol>
 *   <li><b>Jeder leuchtet</b> - Verstecken ist vorbei (ueber den {@link GlowManager}, damit
 *       Radar-Puls und Anti-Camping sich nicht gegenseitig ausschalten).</li>
 *   <li><b>Der Ring schrumpft</b> - eine Zone um die Arena-Mitte zieht sich von 100 % auf
 *       {@link #MIN_RING_FACTOR} zusammen. Wer laenger als {@link #OUTSIDE_GRACE_SECONDS}
 *       draussen bleibt, wird eliminiert.</li>
 *   <li><b>Doppelte Item-Rate</b> - Boden-Item-Boxen spawnen alle 15 statt alle 30 Sekunden
 *       (siehe {@code KillstreakManager#startGroundSpawnTask}).</li>
 * </ol>
 * <b>Wichtig:</b> Die Arena-Grenzen der {@link MapConfig} bleiben <b>unangetastet</b>. Sie
 * steuern Kampfzone, Pausensperre und Item-Spawns; sie zu verkleinern wuerde ausserhalb des
 * Rings jeden Kampf deaktivieren. Der Ring ist deshalb eine eigene, zusaetzliche Grenze.
 */
public class SuddenDeathManager {

    /** Ab so vielen verbleibenden Sekunden startet die Endphase. */
    public static final int SUDDEN_DEATH_SECONDS = 60;
    /** Kleinste Ringgroesse als Anteil der Arena-Kantenlaenge. */
    private static final double MIN_RING_FACTOR = 0.45;
    /** So lange darf man ausserhalb des Rings ueberleben. */
    private static final int OUTSIDE_GRACE_SECONDS = 5;
    /** Takt der Ringpruefung: eine Sekunde. */
    private static final long RING_PERIOD_TICKS = 20L;
    /** Abstand der Partikel entlang der Ringkante. */
    private static final double RING_PARTICLE_STEP = 2.0;

    private static final Particle.DustOptions RING_DUST =
            new Particle.DustOptions(Color.fromRGB(255, 40, 40), 2.0f);

    private final OneShotOneKill plugin;
    private final Map<UUID, Integer> secondsOutside = new HashMap<>();
    private ScheduledTask ringTask;
    private boolean active;

    public SuddenDeathManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Aktueller Schrumpffaktor des Rings. Ausserhalb der Endphase {@code 1.0}, damit
     * Spieler- und Item-Spawns die volle Arena nutzen.
     */
    public double getRingFactor() {
        if (!active) return 1.0;

        int remaining = Math.max(0, Math.min(plugin.getMatchManager().getRemainingSeconds(), SUDDEN_DEATH_SECONDS));
        double progress = 1.0 - (remaining / (double) SUDDEN_DEATH_SECONDS);
        return 1.0 - progress * (1.0 - MIN_RING_FACTOR);
    }

    /** Liegt die Position innerhalb des aktuellen Rings? Ausserhalb der Endphase immer {@code true}. */
    public boolean isInsideRing(Location loc) {
        if (!active || loc == null) return true;

        MapConfig map = plugin.getWorldManager().getActiveMapConfig();
        if (map == null) return true;

        double factor = getRingFactor();
        double centerX = (map.getMinX() + map.getMaxX()) / 2.0;
        double centerZ = (map.getMinZ() + map.getMaxZ()) / 2.0;
        double halfX = (map.getMaxX() - map.getMinX()) / 2.0 * factor;
        double halfZ = (map.getMaxZ() - map.getMinZ()) / 2.0 * factor;

        return Math.abs(loc.getX() - centerX) <= halfX && Math.abs(loc.getZ() - centerZ) <= halfZ;
    }

    /** Startet die Endphase. Ein zweiter Aufruf ist wirkungslos. */
    public void start() {
        if (active) return;
        active = true;
        secondsOutside.clear();

        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(" "));
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                "<dark_red><b>=======================================</b></dark_red>"));
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                "<red><b>   ☠ SUDDEN DEATH!   </b></red>"));
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                "<gray>  Alle Spieler leuchten. Der Ring schrumpft. Doppelt so viele Item-Boxen.</gray>"));
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                "<gray>  Bleib in der Zone - draußen überlebst du nur <yellow>" + OUTSIDE_GRACE_SECONDS + " Sekunden</yellow>!</gray>"));
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                "<dark_red><b>=======================================</b></dark_red>"));
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(" "));

        Title.Times times = Title.Times.times(Ticks.duration(5), Ticks.duration(45), Ticks.duration(15));
        Bukkit.getServer().showTitle(Title.title(
                MiniMessage.miniMessage().deserialize("<red><b>☠ SUDDEN DEATH</b></red>"),
                MiniMessage.miniMessage().deserialize("<gray>Der Ring schließt sich!</gray>"),
                times));
        Bukkit.getServer().playSound(Sound.sound(org.bukkit.Sound.ENTITY_WITHER_SPAWN, Sound.Source.MASTER, 1.0f, 0.8f));

        ringTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(), RING_PERIOD_TICKS, RING_PERIOD_TICKS);
    }

    /** Beendet die Endphase und nimmt Leuchten und Zaehler zurueck. */
    public void stop() {
        if (ringTask != null) {
            ringTask.cancel();
            ringTask = null;
        }
        if (!active) return;

        active = false;
        secondsOutside.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getGlowManager().remove(player, GlowManager.GlowReason.SUDDEN_DEATH);
        }
    }

    private void tick() {
        MatchManager match = plugin.getMatchManager();
        if (!match.isMatchStarted() || match.isMatchEnded()) {
            stop();
            return;
        }
        // Bei /osok pause und /osok pausestats friert der Timer ein - der Ring ebenfalls
        if (match.isMatchPaused() || match.isStatsPaused()) {
            return;
        }

        drawRing();

        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean inArena = plugin.getArenaManager().isInArenaArea(player.getLocation());
            if (!inArena) {
                plugin.getGlowManager().remove(player, GlowManager.GlowReason.SUDDEN_DEATH);
                secondsOutside.remove(player.getUniqueId());
                continue;
            }

            plugin.getGlowManager().add(player, GlowManager.GlowReason.SUDDEN_DEATH);

            if (isInsideRing(player.getLocation())) {
                secondsOutside.remove(player.getUniqueId());
                continue;
            }

            int outside = secondsOutside.merge(player.getUniqueId(), 1, Integer::sum);
            int left = OUTSIDE_GRACE_SECONDS - outside;

            if (left <= 0) {
                secondsOutside.remove(player.getUniqueId());
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        "<dark_red>[OSOK] ☠ Du bist außerhalb der Zone gestorben!</dark_red>"));
                plugin.getEliminationManager().eliminate(player, null);
                continue;
            }

            player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red><b>☠ AUSSERHALB DER ZONE! <yellow>" + left + "s</yellow></b></red>"));
            player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 1.0f, 0.6f));
        }
    }

    /** Zeichnet die Ringkante als rote Partikelwand. */
    private void drawRing() {
        MapConfig map = plugin.getWorldManager().getActiveMapConfig();
        World world = plugin.getWorldManager().getOsokWorld();
        if (map == null || world == null) return;

        double factor = getRingFactor();
        double centerX = (map.getMinX() + map.getMaxX()) / 2.0;
        double centerZ = (map.getMinZ() + map.getMaxZ()) / 2.0;
        double halfX = (map.getMaxX() - map.getMinX()) / 2.0 * factor;
        double halfZ = (map.getMaxZ() - map.getMinZ()) / 2.0 * factor;
        double baseY = map.getMinY() + 1.0;

        for (double x = centerX - halfX; x <= centerX + halfX; x += RING_PARTICLE_STEP) {
            spawnRingColumn(world, x, baseY, centerZ - halfZ);
            spawnRingColumn(world, x, baseY, centerZ + halfZ);
        }
        for (double z = centerZ - halfZ; z <= centerZ + halfZ; z += RING_PARTICLE_STEP) {
            spawnRingColumn(world, centerX - halfX, baseY, z);
            spawnRingColumn(world, centerX + halfX, baseY, z);
        }
    }

    private void spawnRingColumn(World world, double x, double baseY, double z) {
        world.spawnParticle(Particle.DUST, x, baseY, z, 1, 0.0, 0.0, 0.0, 0.0, RING_DUST);
        world.spawnParticle(Particle.DUST, x, baseY + 2.5, z, 1, 0.0, 0.0, 0.0, 0.0, RING_DUST);
    }

    /** Verbleibende Sekunden als Komponente fuer das Scoreboard. */
    public Component sidebarLine() {
        int percent = (int) Math.round(getRingFactor() * 100.0);
        return MiniMessage.miniMessage().deserialize(
                "<red><b>☠ SUDDEN DEATH</b></red> <gray>Zone <white>" + percent + "%</white></gray>");
    }
}
