package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import org.bukkit.Location;
import org.bukkit.World;

public class ArenaManager {

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
