package de.oneshotonekill.model;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

public class MapConfig {

    /**
     * Spielraum ueber der oberen Arena-Kante, damit Spruenge und Knockback einen Spieler
     * nicht kurzzeitig aus der Arena "herausfallen" lassen (was PvP deaktivieren wuerde).
     * Bewusst klein gehalten: Die DustPvP-Lobby liegt bei Y=90 direkt ueber der Arena-Flaeche
     * und muss zuverlaessig ausserhalb der Arena bleiben.
     */
    private static final double ARENA_HEADROOM = 12.0;

    /** Toleranz unterhalb der unteren Kante (Bodenplatte, Stufen, leichte Senken). */
    private static final double ARENA_FLOOR_TOLERANCE = 2.0;

    /** Suchfenster fuer Spawnpunkte relativ zu den Arena-Grenzen. */
    private static final int SPAWN_SCAN_BELOW = 2;
    private static final int SPAWN_SCAN_ABOVE = 4;

    /**
     * Bloecke, auf denen nicht gespawnt werden darf (Dach-/Randmarkierungen der Maps).
     * Wird einmalig beim Klassenladen aufgebaut, damit der Spawn-Scan ohne String-Operationen auskommt.
     */
    private static final Set<Material> BLOCKED_SPAWN_GROUND;

    static {
        Set<Material> blocked = EnumSet.of(Material.BLACK_WOOL);
        for (Material material : Material.values()) {
            if (material.name().contains("BRICK")) {
                blocked.add(material);
            }
        }
        BLOCKED_SPAWN_GROUND = Collections.unmodifiableSet(blocked);
    }

    private final String name;
    private final String zipResource;
    /**
     * Maximale Y-Position, auf der eine Boden-Item-Box liegen darf.
     * Begrenzt den Boden-Scan zusaetzlich zu den Arena-Grenzen, damit Items wirklich nur
     * auf der Grundflaeche landen und nicht auf hoeher gelegenen Bereichen.
     */
    private double maxItemSpawnY;
    /**
     * Y-Hoehe der Arena-Decke. {@link Double#MAX_VALUE} bedeutet offener Himmel.
     * Fliegende Entities (z. B. der Tarnkappenbomber-Drache) muessen darunter bleiben.
     */
    private double ceilingY = Double.MAX_VALUE;
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
        setArenaBounds(minX, maxX, minY, maxY, minZ, maxZ);
        this.maxItemSpawnY = this.maxY + 1.0;
    }

    public double getMaxItemSpawnY() {
        return maxItemSpawnY;
    }

    public void setMaxItemSpawnY(double maxItemSpawnY) {
        this.maxItemSpawnY = maxItemSpawnY;
    }

    public double getCeilingY() {
        return ceilingY;
    }

    public void setCeilingY(double ceilingY) {
        this.ceilingY = ceilingY;
    }

    public boolean hasCeiling() {
        return ceilingY != Double.MAX_VALUE;
    }

    /**
     * Hoechste Y-Position, die eine fliegende Entity einnehmen darf: einen Block
     * unterhalb der Decke, damit sie nicht in ihr steckt. Ohne Decke unbegrenzt.
     */
    public double getMaxFlyY() {
        return hasCeiling() ? ceilingY - 1.0 : Double.MAX_VALUE;
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

    /** Normalisiert die beiden Eckpunkte, damit die Reihenfolge der Ecken egal ist. */
    public void setArenaBounds(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        this.minX = Math.min(minX, maxX);
        this.maxX = Math.max(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxZ = Math.max(minZ, maxZ);
    }

    /**
     * Prueft, ob eine Position innerhalb der Arena liegt. Die Welt-Zugehoerigkeit prueft
     * bewusst der ArenaManager, da nur der die aktive OSOK-Welt kennt.
     */
    public boolean isInArenaArea(Location loc) {
        if (loc == null) {
            return false;
        }
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        return x >= minX && x <= maxX
                && y >= minY - ARENA_FLOOR_TOLERANCE && y <= maxY + ARENA_HEADROOM
                && z >= minZ && z <= maxZ;
    }

    /**
     * Zufaelliger Spielerspawn: sucht von oben nach unten und liefert damit auch
     * erhoehte Plattformen und Bruecken der Arena.
     */
    public Location getRandomArenaLocation(World osokWorld) {
        Location loc = findRandomSpot(osokWorld, true);
        return loc != null ? loc : fallbackLocation(osokWorld);
    }

    /**
     * Zufaelliger Spawn fuer Boden-Items: sucht von unten nach oben und liefert damit
     * ausschliesslich den Arena-Boden - niemals Daecher, Bruecken oder Plattformen.
     * Liefert bewusst {@code null}, wenn kein Bodenplatz gefunden wurde, damit der Aufrufer
     * den Spawn ueberspringen kann statt ein Item in der Lobby abzulegen.
     */
    public Location getRandomFloorLocation(World osokWorld) {
        return findRandomSpot(osokWorld, false);
    }

    private Location findRandomSpot(World osokWorld, boolean topDown) {
        if (osokWorld == null) return null;

        int scanMinY = Math.max((int) Math.floor(minY) - SPAWN_SCAN_BELOW, osokWorld.getMinHeight());
        int scanMaxY = Math.min((int) Math.floor(maxY) + SPAWN_SCAN_ABOVE, osokWorld.getMaxHeight() - 3);

        if (!topDown) {
            // Boden-Items: Der Bodenblock liegt eine Position unter dem Item selbst.
            scanMaxY = Math.min(scanMaxY, (int) Math.floor(maxItemSpawnY) - 1);
        }
        if (scanMaxY < scanMinY) {
            return null;
        }

        for (int attempts = 0; attempts < 200; attempts++) {
            int blockX = (int) Math.floor(minX + (RANDOM.nextDouble() * (maxX - minX)));
            int blockZ = (int) Math.floor(minZ + (RANDOM.nextDouble() * (maxZ - minZ)));

            for (int step = 0; step <= scanMaxY - scanMinY; step++) {
                int y = topDown ? scanMaxY - step : scanMinY + step;

                if (isStandableAt(osokWorld, blockX, y, blockZ)) {
                    float randomYaw = RANDOM.nextFloat() * 360f - 180f;
                    return new Location(osokWorld, blockX + 0.5, y + 1.0, blockZ + 0.5, randomYaw, 0f);
                }
            }
        }
        return null;
    }

    /** Boden tragfaehig, Fuss- und Kopfhoehe begehbar und frei von Fluessigkeit. */
    private boolean isStandableAt(World world, int x, int y, int z) {
        Block ground = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);

        return ground.getType().isSolid()
                && !BLOCKED_SPAWN_GROUND.contains(ground.getType())
                && !ground.isLiquid()
                && ground.getType() != Material.LAVA
                && ground.getType() != Material.FIRE
                && feet.isPassable() && !feet.isLiquid()
                && head.isPassable() && !head.isLiquid();
    }

    private Location fallbackLocation(World osokWorld) {
        if (osokWorld == null) return null;
        if (lobbyLocation != null) {
            Location loc = lobbyLocation.clone();
            loc.setWorld(osokWorld);
            return loc;
        }
        return osokWorld.getSpawnLocation();
    }
}
