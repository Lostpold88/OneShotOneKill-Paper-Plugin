package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WorldManager {

    private final OneShotOneKill plugin;
    private World osokWorld;
    private Location spawnLocation;
    private final Map<String, MapConfig> availableMaps = new HashMap<>();
    private MapConfig activeMapConfig;

    public WorldManager(OneShotOneKill plugin) {
        this.plugin = plugin;
        initDefaultMaps();
    }

    private void initDefaultMaps() {
        // Standard Map (Standards)
        MapConfig standardMap = new MapConfig(
                "Standard",
                "Standard.zip",
                new Location(null, 223.5, 48.0, 55.5),
                221.0, 288.0, 50.0, 120.0, -107.0, -50.0
        );
        availableMaps.put("standard", standardMap);

        // DustPvP Map (Platzhalter-Koordinaten bis exakte Werte angegeben werden)
        MapConfig dustPvPMap = new MapConfig(
                "DustPvP",
                "DustPvP.zip",
                new Location(null, 223.5, 48.0, 55.5),
                150.0, 350.0, 40.0, 130.0, -200.0, 0.0
        );
        availableMaps.put("dustpvp", dustPvPMap);

        this.activeMapConfig = standardMap;
    }

    public void setupWorld() {
        File containerDir = Bukkit.getWorldContainer();
        File mapFolder = new File(containerDir, "OSOK");
        File migratedFolder = new File(containerDir, "world/dimensions/minecraft/osok");

        plugin.getLogger().info("Entpacke initiale OSOK Map (" + activeMapConfig.getName() + ") aus der JAR...");
        try {
            if (mapFolder.exists()) {
                deleteDirectory(mapFolder);
            }
            if (migratedFolder.exists()) {
                deleteDirectory(migratedFolder);
            }
            extractEmbeddedMap(activeMapConfig.getZipResource(), mapFolder);
            plugin.getLogger().info("OSOK Map (" + activeMapConfig.getName() + ") erfolgreich entpackt!");
        } catch (Exception e) {
            plugin.getLogger().severe("Fehler beim Entpacken der OSOK Map: " + e.getMessage());
            e.printStackTrace();
        }

        WorldCreator creator = new WorldCreator("OSOK");
        osokWorld = Bukkit.createWorld(creator);

        if (osokWorld != null) {
            applyPaperGameRules(osokWorld);
            osokWorld.setTime(6000); // Mittag

            for (org.bukkit.entity.Entity entity : osokWorld.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }

            spawnLocation = activeMapConfig.getLobbyLocation().clone();
            spawnLocation.setWorld(osokWorld);
            osokWorld.setSpawnLocation(spawnLocation);
            plugin.getLogger().info("OSOK Map (" + activeMapConfig.getName() + ") geladen & Lobby-Spawn gesetzt!");
        }
    }

    public boolean switchMap(String mapName) {
        MapConfig targetConfig = availableMaps.get(mapName.toLowerCase());
        if (targetConfig == null) {
            return false;
        }

        plugin.getLogger().info("[OSOK] Starte dynamischen Map-Wechsel zu: " + targetConfig.getName());

        // 1. Pausiere aktives Match falls nötig
        if (plugin.getMatchManager() != null && plugin.getMatchManager().isMatchStarted()) {
            plugin.getMatchManager().resetLimits();
        }

        // 2. Paper Async Teleportation: Alle Spieler temporär in die Hauptwelt sichern
        World mainWorld = Bukkit.getWorlds().get(0);
        Location fallbackLoc = mainWorld.getSpawnLocation();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>[OSOK] 🔄 Wechsel auf Map <b>" + targetConfig.getName() + "</b>... Bitte warten!</yellow>"));
            p.teleportAsync(fallbackLoc);
        }

        // 3. OSOK Welt sicher entladen
        if (osokWorld != null) {
            Bukkit.unloadWorld(osokWorld, false);
            osokWorld = null;
        }
        Bukkit.unloadWorld("OSOK", false);

        // 4. Alte Ordner löschen
        File containerDir = Bukkit.getWorldContainer();
        File mapFolder = new File(containerDir, "OSOK");
        File migratedFolder = new File(containerDir, "world/dimensions/minecraft/osok");

        if (mapFolder.exists()) {
            deleteDirectory(mapFolder);
        }
        if (migratedFolder.exists()) {
            deleteDirectory(migratedFolder);
        }

        // 5. Gewählte Map-ZIP entpacken
        try {
            extractEmbeddedMap(targetConfig.getZipResource(), mapFolder);
        } catch (Exception e) {
            plugin.getLogger().severe("Fehler beim Entpacken von " + targetConfig.getZipResource() + ": " + e.getMessage());
            return false;
        }

        // 6. OSOK Welt neu laden & Paper GameRules anwenden
        WorldCreator creator = new WorldCreator("OSOK");
        osokWorld = Bukkit.createWorld(creator);

        if (osokWorld != null) {
            applyPaperGameRules(osokWorld);
            osokWorld.setTime(6000);

            for (org.bukkit.entity.Entity entity : osokWorld.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }

            this.activeMapConfig = targetConfig;
            this.spawnLocation = targetConfig.getLobbyLocation().clone();
            this.spawnLocation.setWorld(osokWorld);
            osokWorld.setSpawnLocation(spawnLocation);

            // 7. Paper Async Teleportation: Alle Spieler in die neue Map-Lobby bringen
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.teleportAsync(spawnLocation);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);
                p.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] 🗺 Map erfolgreich zu <b>" + targetConfig.getName() + "</b> gewechselt!</green>"));
            }

            // Scoreboards aktualisieren
            if (plugin.getScoreboardManager() != null) {
                plugin.getScoreboardManager().updateAllScoreboards();
            }
            return true;
        }
        return false;
    }

    private void applyPaperGameRules(World world) {
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
    }

    private boolean deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        return dir.delete();
    }

    private void extractEmbeddedMap(String zipResourceName, File targetDir) throws IOException {
        InputStream is = plugin.getResource(zipResourceName);
        if (is == null) {
            is = plugin.getResource("map.zip");
        }
        if (is == null) {
            plugin.getLogger().severe(zipResourceName + " wurde im Plugin-Resource Stream nicht gefunden!");
            return;
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        try (ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                File file = new File(targetDir, name);
                if (entry.isDirectory() || name.endsWith("/") || name.endsWith("\\")) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    if (entry.getSize() == 0 && !name.contains(".")) {
                        file.mkdirs();
                    } else {
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    public World getOsokWorld() {
        if (osokWorld == null) {
            osokWorld = Bukkit.getWorld("OSOK");
        }
        return osokWorld;
    }

    public Location getSpawnLocation() {
        if (spawnLocation == null && getOsokWorld() != null) {
            spawnLocation = activeMapConfig.getLobbyLocation().clone();
            spawnLocation.setWorld(osokWorld);
        }
        return spawnLocation;
    }

    public void setSpawnLocation(Location spawnLocation) {
        this.spawnLocation = spawnLocation;
        if (activeMapConfig != null) {
            activeMapConfig.setLobbyLocation(spawnLocation);
        }
        if (osokWorld != null) {
            osokWorld.setSpawnLocation(spawnLocation);
        }
    }

    public MapConfig getActiveMapConfig() {
        return activeMapConfig;
    }

    public Collection<MapConfig> getAvailableMaps() {
        return availableMaps.values();
    }
}
