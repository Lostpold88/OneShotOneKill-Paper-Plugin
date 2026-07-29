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

    public boolean isInArenaArea(Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().getName().equalsIgnoreCase("OSOK")) {
            return false;
        }
        MapConfig activeMap = plugin.getWorldManager().getActiveMapConfig();
        if (activeMap != null) {
            return activeMap.isInArenaArea(loc);
        }
        return false;
    }
}
