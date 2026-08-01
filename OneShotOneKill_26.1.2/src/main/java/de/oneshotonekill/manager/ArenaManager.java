package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class ArenaManager {

    /** So viele Spawnpunkte werden pro Respawn ausgewuerfelt und bewertet. */
    private static final int RESPAWN_CANDIDATES = 24;
    /** Ab diesem Abstand zum naechsten Gegner bringt mehr Abstand keinen Vorteil mehr. */
    private static final double ENEMY_DISTANCE_CAP = 32.0;
    /** Dasselbe fuer den Todespunkt - er wiegt weniger schwer als die Gegnerposition. */
    private static final double DEATH_DISTANCE_CAP = 24.0;
    private static final double ENEMY_WEIGHT = 0.7;
    private static final double DEATH_WEIGHT = 0.3;

    private final OneShotOneKill plugin;

    public ArenaManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public Location getRandomArenaLocation() {
        World osokWorld = plugin.getWorldManager().getOsokWorld();
        if (osokWorld == null) return null;

        MapConfig activeMap = plugin.getWorldManager().getActiveMapConfig();
        if (activeMap != null) {
            Location loc = activeMap.getRandomArenaLocation(osokWorld);
            if (loc != null) return loc;
        }

        Location spawnLoc = plugin.getWorldManager().getSpawnLocation();
        return spawnLoc != null ? spawnLoc : osokWorld.getSpawnLocation();
    }

    /**
     * Bestmoeglicher Respawn-Punkt: moeglichst weit weg vom Todespunkt <b>und</b> vom naechsten
     * Gegner.
     * <p>
     * Statt den erstbesten ausreichend entfernten Platz zu nehmen, werden
     * {@link #RESPAWN_CANDIDATES} zufaellige Kandidaten gesammelt und bewertet; der beste
     * gewinnt. Weil die Kandidatenmenge jedes Mal neu ausgewuerfelt wird, bleibt der Spawn
     * trotzdem unvorhersehbar - eine reine "maximaler Abstand"-Suche wuerde die Spieler
     * dagegen immer in dieselbe Ecke schicken, die man dann bequem zucampen kann.
     *
     * @param respawning der Spieler, der zurueckkommt - zaehlt nicht als eigener Gegner
     * @param deathLoc   Todespunkt, oder {@code null} wenn unbekannt
     */
    public Location getSafestArenaLocation(Player respawning, Location deathLoc) {
        World osokWorld = plugin.getWorldManager().getOsokWorld();
        if (osokWorld == null) return null;

        MapConfig activeMap = plugin.getWorldManager().getActiveMapConfig();
        if (activeMap == null) {
            return fallbackSpawn(osokWorld);
        }

        List<Location> candidates = activeMap.collectArenaSpots(osokWorld, RESPAWN_CANDIDATES);
        if (candidates.isEmpty()) {
            return fallbackSpawn(osokWorld);
        }

        List<Location> enemies = collectEnemyPositions(respawning, osokWorld);
        Location relevantDeathLoc = (deathLoc != null && osokWorld.equals(deathLoc.getWorld())) ? deathLoc : null;

        Location best = candidates.get(0);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Location candidate : candidates) {
            double score = rateSpawn(candidate, relevantDeathLoc, enemies);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Bewertet einen Spawnpunkt. Hoeher ist besser, das Ergebnis liegt zwischen 0 und 1.
     * <p>
     * Der Abstand zum naechsten Gegner wiegt schwerer als der zum Todespunkt: Der Todespunkt ist
     * nur ein Anhaltspunkt dafuer, wo der Killer stand - wo die Gegner <b>jetzt</b> stehen, ist
     * die genauere Information. Beide Abstaende werden gedeckelt, weil jenseits der Deckel kein
     * spuerbarer Sicherheitsgewinn mehr entsteht und sonst nur noch die Kartenecken gewinnen.
     */
    private double rateSpawn(Location candidate, Location deathLoc, List<Location> enemies) {
        double nearestEnemy = ENEMY_DISTANCE_CAP;
        for (Location enemy : enemies) {
            nearestEnemy = Math.min(nearestEnemy, candidate.distance(enemy));
        }
        double enemyScore = nearestEnemy / ENEMY_DISTANCE_CAP;

        double deathScore = 1.0;
        if (deathLoc != null) {
            deathScore = Math.min(candidate.distance(deathLoc), DEATH_DISTANCE_CAP) / DEATH_DISTANCE_CAP;
        }

        return enemyScore * ENEMY_WEIGHT + deathScore * DEATH_WEIGHT;
    }

    /**
     * Positionen aller Gegner in der Arena.
     * <p>
     * Bewusst ein einzelner Durchlauf ueber die Online-Spieler statt
     * {@code Location#getNearbyPlayers}: Gesucht sind nicht die Spieler nahe <b>einem</b> Punkt,
     * sondern alle - jeder Kandidat wird anschliessend gegen dieselbe Liste geprueft. Eine
     * Umkreissuche pro Kandidat waere hier der teurere Weg.
     */
    private List<Location> collectEnemyPositions(Player respawning, World osokWorld) {
        List<Location> enemies = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (respawning != null && online.getUniqueId().equals(respawning.getUniqueId())) continue;
            if (!osokWorld.equals(online.getWorld())) continue;
            if (!isInArenaArea(online.getLocation())) continue;
            enemies.add(online.getLocation());
        }
        return enemies;
    }

    private Location fallbackSpawn(World osokWorld) {
        Location spawnLoc = plugin.getWorldManager().getSpawnLocation();
        return spawnLoc != null ? spawnLoc : osokWorld.getSpawnLocation();
    }

    /**
     * Spawnpunkt fuer Boden-Items: ausschliesslich auf dem Arena-Boden.
     * Liefert {@code null}, wenn kein Bodenplatz gefunden wurde - dann wird kein Item gespawnt,
     * statt es an einem falschen Ort (z. B. in der Lobby) abzulegen.
     */
    public Location getRandomFloorLocation() {
        World osokWorld = plugin.getWorldManager().getOsokWorld();
        if (osokWorld == null) return null;

        MapConfig activeMap = plugin.getWorldManager().getActiveMapConfig();
        return activeMap != null ? activeMap.getRandomFloorLocation(osokWorld) : null;
    }

    /**
     * Ist der Spieler unter die Welt gefallen und muss gerettet werden?
     * <p>
     * Ausserhalb der Arena wird jeder Schaden gecancelt - auch Void-Schaden. Ohne diese
     * Pruefung faellt ein Spieler, der neben die Lobby-Plattform tritt, endlos weiter.
     */
    public boolean isBelowWorld(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        World osokWorld = plugin.getWorldManager().getOsokWorld();
        if (osokWorld == null || !loc.getWorld().equals(osokWorld)) return false;

        MapConfig activeMap = plugin.getWorldManager().getActiveMapConfig();
        if (activeMap == null) return false;

        return loc.getY() < activeMap.getVoidRescueY();
    }

    /**
     * Prueft die Welt-Zugehoerigkeit gegen die tatsaechlich aktive OSOK-Welt.
     * Ein Vergleich gegen einen festen Weltnamen ist nicht moeglich, da die Welten
     * je nach aktiver Map OSOK_Standard bzw. OSOK_DustPvP heissen.
     */
    public boolean isInArenaArea(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }

        World osokWorld = plugin.getWorldManager().getOsokWorld();
        if (osokWorld == null || !loc.getWorld().equals(osokWorld)) {
            return false;
        }

        MapConfig activeMap = plugin.getWorldManager().getActiveMapConfig();
        return activeMap != null && activeMap.isInArenaArea(loc);
    }
}
