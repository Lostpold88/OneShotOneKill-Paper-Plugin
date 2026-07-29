package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import net.kyori.adventure.sound.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WorldManager {

    private final OneShotOneKill plugin;
    private World osokWorld;
    private Location spawnLocation;
    private final Map<String, MapConfig> availableMaps = new HashMap<>();
    private MapConfig activeMapConfig;
    private volatile boolean switchInProgress = false;

    public WorldManager(OneShotOneKill plugin) {
        this.plugin = plugin;
        initDefaultMaps();
    }

    private void initDefaultMaps() {
        // Standard Map (OSOK_Standard) - Arena-Ecken: (221, 58, -50) / (287, 64, -106)
        MapConfig standardMap = new MapConfig(
                "Standard",
                "Standard.zip",
                new Location(null, 223.5, 48.0, 55.5),
                221.0, 287.0, 58.0, 64.0, -106.0, -50.0
        );
        // Boden-Item-Boxen duerfen auf Standard hoechstens auf Y=61 liegen
        standardMap.setMaxItemSpawnY(61.0);
        availableMaps.put("standard", standardMap);

        // DustPvP Map (OSOK_DustPvP) - Arena-Ecken: (-25, 70, 33) / (25, 70, -33)
        MapConfig dustPvPMap = new MapConfig(
                "DustPvP",
                "DustPvP.zip",
                new Location(null, 0.5, 90.0, 0.5),
                -25.0, 25.0, 70.0, 70.0, -33.0, 33.0
        );
        // DustPvP ist eine flache Ebene auf Y=70, die Box liegt also auf Y=71
        dustPvPMap.setMaxItemSpawnY(71.0);
        availableMaps.put("dustpvp", dustPvPMap);

        this.activeMapConfig = standardMap;
    }

    public void setupWorld() {
        String worldName = "OSOK_" + activeMapConfig.getName();
        File containerDir = Bukkit.getWorldContainer();
        File mapFolder = new File(containerDir, worldName);
        File migratedFolder = new File(containerDir, "world/dimensions/minecraft/" + worldName.toLowerCase());

        plugin.getLogger().info("Entpacke initiale Map " + worldName + " aus der JAR...");
        try {
            if (mapFolder.exists()) {
                deleteDirectory(mapFolder);
            }
            if (migratedFolder.exists()) {
                deleteDirectory(migratedFolder);
            }
            extractEmbeddedMap(activeMapConfig.getZipResource(), mapFolder);
            plugin.getLogger().info("Map " + worldName + " erfolgreich entpackt!");
        } catch (Exception e) {
            plugin.getLogger().severe("Fehler beim Entpacken der Map " + worldName + ": " + e.getMessage());
            e.printStackTrace();
        }

        WorldCreator creator = new WorldCreator(worldName);
        creator.environment(World.Environment.NORMAL);
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
            plugin.getLogger().info("Map " + worldName + " geladen & Lobby-Spawn gesetzt!");
        }
    }

    public boolean switchMap(String mapName) {
        MapConfig targetConfig = availableMaps.get(mapName.toLowerCase());
        if (targetConfig == null) {
            return false;
        }
        if (switchInProgress) {
            broadcast("<red>[OSOK] ⏳ Es laeuft bereits ein Map-Wechsel. Bitte warten!</red>");
            return true;
        }
        switchInProgress = true;

        plugin.getLogger().info("[OSOK] Starte dynamischen Map-Wechsel zu: OSOK_" + targetConfig.getName());

        // 1. Laufendes Match sauber beenden (kein Kampf waehrend/nach dem Wechsel)
        if (plugin.getMatchManager() != null) {
            plugin.getMatchManager().stopMatch();
        }

        // 2. Boden-Items der alten Map inkl. Chunk-Tickets freigeben
        if (plugin.getKillstreakManager() != null) {
            plugin.getKillstreakManager().clearAllGroundItems();
        }

        // 3. Paper Async Teleportation: Alle Spieler aus der OSOK-Welt heraus sichern.
        //    Die Welt darf erst entladen werden, wenn ALLE Teleports abgeschlossen sind.
        World mainWorld = Bukkit.getWorlds().get(0);
        Location fallbackLoc = mainWorld.getSpawnLocation();

        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>[OSOK] 🔄 Wechsel auf Map <b>" + targetConfig.getName() + "</b>... Bitte warten!</yellow>"));
            plugin.getEquipmentManager().clearBaseEquipment(p);
            teleports.add(p.teleportAsync(fallbackLoc));
        }

        // 4. Nach Abschluss aller Teleports zurueck auf den Main-Thread wechseln:
        //    Welt-Entladen, Loeschen, Entpacken und Laden sind nicht thread-safe.
        CompletableFuture.allOf(teleports.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, throwable) -> Bukkit.getGlobalRegionScheduler().run(plugin, task -> finishMapSwitch(targetConfig)));

        return true;
    }

    private void finishMapSwitch(MapConfig targetConfig) {
        String targetWorldName = "OSOK_" + targetConfig.getName();
        try {
            // Alte OSOK-Welt entladen (Spieler sind jetzt nachweislich draussen)
            if (osokWorld != null) {
                if (!Bukkit.unloadWorld(osokWorld, false)) {
                    plugin.getLogger().warning("[OSOK] Alte Welt " + osokWorld.getName() + " konnte nicht entladen werden.");
                }
                osokWorld = null;
            }
            Bukkit.unloadWorld(targetWorldName, false);

            // Ziel-Ordner & migrierte Dimension löschen (frische Map aus JAR)
            File containerDir = Bukkit.getWorldContainer();
            File mapFolder = new File(containerDir, targetWorldName);
            File migratedFolder = new File(containerDir, "world/dimensions/minecraft/" + targetWorldName.toLowerCase());

            if (mapFolder.exists()) {
                deleteDirectory(mapFolder);
            }
            if (migratedFolder.exists()) {
                deleteDirectory(migratedFolder);
            }

            extractEmbeddedMap(targetConfig.getZipResource(), mapFolder);

            WorldCreator creator = new WorldCreator(targetWorldName);
            creator.environment(World.Environment.NORMAL);
            osokWorld = Bukkit.createWorld(creator);

            if (osokWorld == null) {
                plugin.getLogger().severe("[OSOK] Welt " + targetWorldName + " konnte nicht geladen werden!");
                broadcast("<red>[OSOK] ❌ Map-Wechsel fehlgeschlagen: Welt konnte nicht geladen werden.</red>");
                return;
            }

            applyPaperGameRules(osokWorld);
            osokWorld.setTime(6000);

            for (org.bukkit.entity.Entity entity : osokWorld.getEntities()) {
                if (!(entity instanceof Player)) {
                    entity.remove();
                }
            }

            // Erst jetzt ist die neue Map aktiv - Arena-Grenzen wechseln synchron mit der Welt
            this.activeMapConfig = targetConfig;
            this.spawnLocation = targetConfig.getLobbyLocation().clone();
            this.spawnLocation.setWorld(osokWorld);
            osokWorld.setSpawnLocation(spawnLocation);

            for (Player p : Bukkit.getOnlinePlayers()) {
                p.teleportAsync(spawnLocation).thenAccept(success -> {
                    if (success && p.isOnline()) {
                        plugin.getEquipmentManager().clearBaseEquipment(p);
                        p.playSound(Sound.sound(org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, Sound.Source.MASTER, 1.0f, 1.0f));
                        p.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] 🗺 Map erfolgreich zu <b>" + targetConfig.getName() + "</b> gewechselt! <gray>Starte mit /osok start.</gray></green>"));
                    }
                });
            }

            if (plugin.getScoreboardManager() != null) {
                plugin.getScoreboardManager().updateAllScoreboards();
            }
            plugin.getLogger().info("[OSOK] Map-Wechsel zu " + targetWorldName + " abgeschlossen.");
        } catch (Exception e) {
            plugin.getLogger().severe("[OSOK] Map-Wechsel fehlgeschlagen: " + e.getMessage());
            broadcast("<red>[OSOK] ❌ Map-Wechsel fehlgeschlagen. Details siehe Server-Log.</red>");
        } finally {
            switchInProgress = false;
        }
    }

    private void broadcast(String miniMessage) {
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    private void applyPaperGameRules(World world) {
        // Moderne Paper GameRules-Registry (org.bukkit.GameRule ist deprecated for removal)
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        // Zusaetzlicher Schutz der Map: kein Entity soll Bloecke veraendern koennen
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        applyGlobalGameRules(world);
    }

    /**
     * GameRules, die auf JEDER Welt des Servers gelten muessen - unabhaengig davon,
     * ob es eine OSOK-Arena ist. Wird zusaetzlich beim Serverstart und bei
     * WorldLoadEvent angewendet (siehe WorldRuleListener).
     */
    public static void applyGlobalGameRules(World world) {
        world.setGameRule(GameRules.LOCATOR_BAR, false);
    }

    /** Wendet die globalen GameRules auf alle aktuell geladenen Welten an. */
    public static void applyGlobalGameRulesToAllWorlds() {
        for (World world : Bukkit.getWorlds()) {
            applyGlobalGameRules(world);
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

    private void extractEmbeddedMap(String zipResourceName, File targetDir) throws IOException {
        InputStream is = plugin.getResource(zipResourceName);
        if (is == null) {
            is = plugin.getResource("Standard.zip");
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
        if (osokWorld == null && activeMapConfig != null) {
            osokWorld = Bukkit.getWorld("OSOK_" + activeMapConfig.getName());
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
