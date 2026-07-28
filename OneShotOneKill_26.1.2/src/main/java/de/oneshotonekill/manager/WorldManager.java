package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.bukkit.entity.Player;

public class WorldManager {

    private final OneShotOneKill plugin;
    private World osokWorld;
    private Location spawnLocation;

    public WorldManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public void setupWorld() {
        File containerDir = Bukkit.getWorldContainer();
        File mapFolder = new File(containerDir, "OSOK");
        File migratedFolder = new File(containerDir, "world/dimensions/minecraft/osok");

        plugin.getLogger().info("Entpacke saubere OSOK Map aus der JAR...");
        try {
            if (mapFolder.exists()) {
                deleteDirectory(mapFolder);
            }
            if (migratedFolder.exists()) {
                deleteDirectory(migratedFolder);
            }
            extractEmbeddedMap(mapFolder);
            plugin.getLogger().info("OSOK Map erfolgreich entpackt!");
        } catch (Exception e) {
            plugin.getLogger().severe("Fehler beim Entpacken der OSOK Map: " + e.getMessage());
            e.printStackTrace();
        }

        WorldCreator creator = new WorldCreator("OSOK");
        osokWorld = Bukkit.createWorld(creator);

        if (osokWorld != null) {
            osokWorld.setGameRule(GameRule.KEEP_INVENTORY, true);
            osokWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            osokWorld.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
            osokWorld.setGameRule(GameRule.DO_TRADER_SPAWNING, false);

            osokWorld.setTime(6000); // Mittag

            for (org.bukkit.entity.Entity entity : osokWorld.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }

            spawnLocation = new Location(osokWorld, 223.5, 48.0, 55.5);
            osokWorld.setSpawnLocation(spawnLocation);
            plugin.getLogger().info("OSOK Map wurde perfekt ohne Chunk-Fehler geladen & Arena-Spawn auf (223.5, 48.0, 55.5) gesetzt!");
        }
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

    private void extractEmbeddedMap(File targetDir) throws IOException {
        InputStream is = plugin.getResource("map.zip");
        if (is == null) {
            plugin.getLogger().severe("map.zip wurde im Plugin-Resource Stream nicht gefunden!");
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
            spawnLocation = new Location(osokWorld, 223.5, 48.0, 55.5);
        }
        return spawnLocation;
    }

    public void setSpawnLocation(Location spawnLocation) {
        this.spawnLocation = spawnLocation;
        if (osokWorld != null) {
            osokWorld.setSpawnLocation(spawnLocation);
        }
    }
}
