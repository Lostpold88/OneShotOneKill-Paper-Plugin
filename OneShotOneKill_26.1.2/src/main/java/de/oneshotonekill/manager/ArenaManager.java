package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import org.bukkit.Location;
import org.bukkit.World;

public class ArenaManager {

    /** Mindestabstand zum Todespunkt beim Respawn, in Bloecken. */
    public static final double MIN_RESPAWN_DISTANCE = 16.0;

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
     * Zufaelliger Spawn mit Mindestabstand zu einem Punkt - beim Respawn also zum Todespunkt.
     * <p>
     * Ohne das landete man regelmaessig direkt neben seinem Killer und war beim naechsten
     * Treffer sofort wieder draussen. {@link #MIN_RESPAWN_DISTANCE} ist dabei ein Wunsch, keine
     * Zusage: Findet die Suche keinen ausreichend entfernten Platz, kommt der erstbeste
     * gueltige zurueck - gar kein Spawnpunkt waere schlimmer als ein etwas zu naher.
     */
    public Location getRandomArenaLocationAwayFrom(Location avoid) {
        World osokWorld = plugin.getWorldManager().getOsokWorld();
        if (osokWorld == null) return null;

        MapConfig activeMap = plugin.getWorldManager().getActiveMapConfig();
        if (activeMap != null) {
            Location loc = activeMap.getRandomArenaLocation(osokWorld, avoid, MIN_RESPAWN_DISTANCE);
            if (loc != null) return loc;
        }

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
