package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

public class ArenaManager {

    private final OneShotOneKill plugin;
    private final Random random = new Random();

    public static final double MIN_X = 221.5;
    public static final double MAX_X = 287.5;
    public static final double MIN_Z = -106.5;
    public static final double MAX_Z = -50.5;

    public ArenaManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public Location getRandomArenaLocation() {
        World osokWorld = plugin.getWorldManager().getOsokWorld();
        if (osokWorld == null) return null;

        for (int attempts = 0; attempts < 100; attempts++) {
            double randomX = MIN_X + (random.nextDouble() * (MAX_X - MIN_X));
            double randomZ = MIN_Z + (random.nextDouble() * (MAX_Z - MIN_Z));
            int blockX = (int) Math.floor(randomX);
            int blockZ = (int) Math.floor(randomZ);

            // Scanne strikt auf Höhe Y=57 bis Y=60
            for (int y = 57; y <= 60; y++) {
                Block ground = osokWorld.getBlockAt(blockX, y, blockZ);
                Block feet = osokWorld.getBlockAt(blockX, y + 1, blockZ);
                Block head = osokWorld.getBlockAt(blockX, y + 2, blockZ);

                String matName = ground.getType().name();
                boolean isBlackWool = matName.contains("BLACK_WOOL");
                boolean isBrick = matName.contains("BRICK");

                // Prüfe: Fester Boden, KEINE schwarze Wolle, KEINE Ziegelsteine, Steh-Blöcke frei
                if (ground.getType().isSolid() && 
                    !isBlackWool &&
                    !isBrick &&
                    !ground.isLiquid() &&
                    ground.getType() != Material.LAVA &&
                    ground.getType() != Material.FIRE &&
                    !feet.getType().isSolid() &&
                    !head.getType().isSolid() &&
                    !feet.isLiquid() &&
                    !head.isLiquid()) {

                    float randomYaw = random.nextFloat() * 360f - 180f;
                    return new Location(osokWorld, blockX + 0.5, y + 1.0, blockZ + 0.5, randomYaw, 0f);
                }
            }
        }

        // Fallback: Arena-Boden auf Höhe Y=57
        Location spawnLoc = plugin.getWorldManager().getSpawnLocation();
        return spawnLoc != null ? spawnLoc : new Location(osokWorld, 223.5, 57.0, -78.0);
    }

    public boolean isInArenaArea(Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().getName().equalsIgnoreCase("OSOK")) {
            return false;
        }
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        double minX = 221.0;
        double maxX = 288.0;
        double minY = 50.0;
        double maxY = 120.0;
        double minZ = -107.0;
        double maxZ = -50.0;

        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
