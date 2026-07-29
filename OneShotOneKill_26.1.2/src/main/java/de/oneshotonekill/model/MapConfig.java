package de.oneshotonekill.model;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

public class MapConfig {

    private final String name;
    private final String zipResource;
    private Location lobbyLocation;
    private double minX;
    private double maxX;
    private double minY;
    private double maxY;
    private double minZ;
    private double maxZ;

    private static final Random RANDOM = new Random();

    public MapConfig(String name, String zipResource, Location lobbyLocation,
                     double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        this.name = name;
        this.zipResource = zipResource;
        this.lobbyLocation = lobbyLocation;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    public String getName() {
        return name;
    }

    public String getZipResource() {
        return zipResource;
    }

    public Location getLobbyLocation() {
        return lobbyLocation;
    }

    public void setLobbyLocation(Location lobbyLocation) {
        this.lobbyLocation = lobbyLocation;
    }

    public double getMinX() {
        return minX;
    }

    public double getMaxX() {
        return maxX;
    }

    public double getMinY() {
        return minY;
    }

    public double getMaxY() {
        return maxY;
    }

    public double getMinZ() {
        return minZ;
    }

    public double getMaxZ() {
        return maxZ;
    }

    public void setArenaBounds(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    public boolean isInArenaArea(Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().getName().equalsIgnoreCase("OSOK")) {
            return false;
        }
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        double lowX = Math.min(minX, maxX);
        double highX = Math.max(minX, maxX);
        double lowY = Math.min(minY, maxY);
        double highY = Math.max(minY, maxY);
        double lowZ = Math.min(minZ, maxZ);
        double highZ = Math.max(minZ, maxZ);

        return x >= lowX && x <= highX && y >= lowY && y <= highY && z >= lowZ && z <= highZ;
    }

    public Location getRandomArenaLocation(World osokWorld) {
        if (osokWorld == null) return null;

        double lowX = Math.min(minX, maxX);
        double highX = Math.max(minX, maxX);
        double lowZ = Math.min(minZ, maxZ);
        double highZ = Math.max(minZ, maxZ);

        for (int attempts = 0; attempts < 100; attempts++) {
            double randomX = lowX + (RANDOM.nextDouble() * (highX - lowX));
            double randomZ = lowZ + (RANDOM.nextDouble() * (highZ - lowZ));
            int blockX = (int) Math.floor(randomX);
            int blockZ = (int) Math.floor(randomZ);

            int scanMinY = Math.max((int) Math.floor(minY), 50);
            int scanMaxY = Math.min((int) Math.floor(maxY), 100);

            for (int y = scanMinY; y <= scanMaxY; y++) {
                Block ground = osokWorld.getBlockAt(blockX, y, blockZ);
                Block feet = osokWorld.getBlockAt(blockX, y + 1, blockZ);
                Block head = osokWorld.getBlockAt(blockX, y + 2, blockZ);

                String matName = ground.getType().name();
                boolean isBlackWool = matName.contains("BLACK_WOOL");
                boolean isBrick = matName.contains("BRICK");

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

                    float randomYaw = RANDOM.nextFloat() * 360f - 180f;
                    return new Location(osokWorld, blockX + 0.5, y + 1.0, blockZ + 0.5, randomYaw, 0f);
                }
            }
        }

        // Fallback: Lobby / Spawnpunkt
        if (lobbyLocation != null) {
            Location loc = lobbyLocation.clone();
            loc.setWorld(osokWorld);
            return loc;
        }
        return osokWorld.getSpawnLocation();
    }
}
