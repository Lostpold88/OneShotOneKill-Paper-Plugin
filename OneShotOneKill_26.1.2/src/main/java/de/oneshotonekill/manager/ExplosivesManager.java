package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sprengstoff-Spezialitems: <b>Air-Strike</b> und <b>C4</b>.
 * <p>
 * Beide nutzen dieselbe Sprengung: {@code createExplosion(..., breakBlocks = false)} richtet
 * gewaltigen Schaden an, kann die Map aber grundsaetzlich nicht beschaedigen - es werden gar
 * keine Bloecke angetastet, statt eine Blockliste nachtraeglich zu leeren.
 * <p>
 * Als Verursacher wird der ausloesende Spieler uebergeben. Damit laeuft der Schaden durch die
 * normale Pipeline und {@code CombatListener#resolveKiller} schreibt einen toedlichen Treffer
 * korrekt dem Ausloeser zu.
 */
public class ExplosivesManager implements Listener {

    public static final Component AIRSTRIKE_GUI_TITLE =
            MiniMessage.miniMessage().deserialize("<red><b>🛰 Air-Strike - Ziel markieren</b></red>");

    private static final NamespacedKey KEY_AIRSTRIKE_BOMB = new NamespacedKey("oneshotonekill", "airstrike_bomb");
    private static final NamespacedKey KEY_C4_CHARGE = new NamespacedKey("oneshotonekill", "c4_charge");
    private static final NamespacedKey KEY_DETONATOR = new NamespacedKey("oneshotonekill", "c4_detonator");
    private static final NamespacedKey KEY_OWNER = new NamespacedKey("oneshotonekill", "explosive_owner");
    private static final NamespacedKey KEY_TARGET_X = new NamespacedKey("oneshotonekill", "airstrike_target_x");
    private static final NamespacedKey KEY_TARGET_Z = new NamespacedKey("oneshotonekill", "airstrike_target_z");

    /** Sprengkraft. Vanilla-TNT liegt bei 4.0 - das hier ist deutlich gewaltiger. */
    private static final float BLAST_POWER = 8.0f;

    private static final int GUI_COLS = 9;
    private static final int GUI_ROWS = 6;

    /** Vorwarnzeit zwischen Zielmarkierung und Einschlag. */
    private static final long AIRSTRIKE_DELAY_TICKS = 45L;
    private static final int AIRSTRIKE_BOMB_COUNT = 8;
    private static final double AIRSTRIKE_SPREAD = 3.5;
    /** Abwurfhoehe ueber der Arena-Oberkante, sofern keine Decke im Weg ist. */
    private static final double AIRSTRIKE_HEIGHT_ABOVE_ARENA = 15.0;
    private static final int BOMB_SAFETY_FUSE_TICKS = 400;

    private static final Random RANDOM = new Random();

    private final OneShotOneKill plugin;
    private final Set<TNTPrimed> activeBombs = new HashSet<>();
    private final Map<UUID, List<BlockDisplay>> c4Charges = new HashMap<>();

    public ExplosivesManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // Gemeinsame Sprengung
    // ==================================================================

    /**
     * Gewaltige Explosion ohne jede Blockveraenderung.
     * Der Schaden wird dem Ausloeser zugeschrieben.
     */
    private void blast(Location loc, Player owner) {
        World world = loc.getWorld();
        if (world == null) return;

        // breakBlocks = false -> die Map kann nicht beschaedigt werden. setFire = false -> kein Feuer.
        // Der Ausloeser wird als Verursacher uebergeben, damit ein toedlicher Treffer ihm
        // zugeschrieben wird. Ist er inzwischen offline, explodiert es ohne Verursacher.
        if (owner != null && owner.isOnline()) {
            world.createExplosion(owner, loc, BLAST_POWER, false, false);
        } else {
            world.createExplosion(loc, BLAST_POWER, false, false);
        }

        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 8, 2.5, 1.5, 2.5, 0.0);
        world.spawnParticle(Particle.FLAME, loc, 160, 3.5, 2.0, 3.5, 0.12);
        world.spawnParticle(Particle.LARGE_SMOKE, loc, 90, 3.0, 2.0, 3.0, 0.08);
        world.playSound(Sound.sound(org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, Sound.Source.MASTER, 1.0f, 0.55f),
                loc.x(), loc.y(), loc.z());
    }

    // ==================================================================
    // Air-Strike
    // ==================================================================

    /**
     * Oeffnet die Arena-Karte zur Zielauswahl. Das Raster bildet die XZ-Grenzen der aktiven Map
     * auf 9x6 Felder ab; Spieler in der Arena werden als Kopf auf ihrem Feld eingezeichnet.
     * Liefert {@code false}, wenn keine Karte aufgebaut werden konnte.
     */
    public boolean openAirStrikeMap(Player user) {
        MapConfig map = plugin.getWorldManager().getActiveMapConfig();
        World world = plugin.getWorldManager().getOsokWorld();
        if (map == null || world == null) {
            user.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 🛰 Die Arena ist aktuell nicht geladen!</red>"));
            return false;
        }

        Inventory gui = Bukkit.createInventory(null, GUI_COLS * GUI_ROWS, AIRSTRIKE_GUI_TITLE);

        for (int row = 0; row < GUI_ROWS; row++) {
            for (int col = 0; col < GUI_COLS; col++) {
                Location cell = cellCenter(map, world, col, row);
                gui.setItem(row * GUI_COLS + col, createTerrainCell(cell));
            }
        }

        // Spieler auf der Karte einzeichnen - inklusive des Nutzers selbst zur Orientierung
        for (Player shown : Bukkit.getOnlinePlayers()) {
            if (!plugin.getArenaManager().isInArenaArea(shown.getLocation())) continue;

            int slot = slotFor(map, shown.getLocation());
            if (slot < 0) continue;

            boolean self = shown.getUniqueId().equals(user.getUniqueId());
            gui.setItem(slot, createPlayerCell(shown, cellCenterOfSlot(map, world, slot), self));
        }

        user.openInventory(gui);
        user.playSound(Sound.sound(org.bukkit.Sound.ITEM_SPYGLASS_USE, Sound.Source.MASTER, 1.0f, 1.0f));
        return true;
    }

    private Location cellCenter(MapConfig map, World world, int col, int row) {
        double x = map.getMinX() + (map.getMaxX() - map.getMinX()) * ((col + 0.5) / GUI_COLS);
        double z = map.getMinZ() + (map.getMaxZ() - map.getMinZ()) * ((row + 0.5) / GUI_ROWS);
        return new Location(world, x, map.getMinY(), z);
    }

    private Location cellCenterOfSlot(MapConfig map, World world, int slot) {
        return cellCenter(map, world, slot % GUI_COLS, slot / GUI_COLS);
    }

    /** Rasterfeld einer Weltposition, oder -1 wenn ausserhalb. */
    private int slotFor(MapConfig map, Location loc) {
        double spanX = map.getMaxX() - map.getMinX();
        double spanZ = map.getMaxZ() - map.getMinZ();
        if (spanX <= 0 || spanZ <= 0) return -1;

        int col = (int) Math.floor((loc.getX() - map.getMinX()) / spanX * GUI_COLS);
        int row = (int) Math.floor((loc.getZ() - map.getMinZ()) / spanZ * GUI_ROWS);
        if (col < 0 || col >= GUI_COLS || row < 0 || row >= GUI_ROWS) return -1;

        return row * GUI_COLS + col;
    }

    private ItemStack createTerrainCell(Location cell) {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        applyCellMeta(item, MiniMessage.miniMessage().deserialize("<gray>Sektor</gray>"), cell, false);
        return item;
    }

    private ItemStack createPlayerCell(Player shown, Location cell, boolean self) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(shown);
        }
        item.setItemMeta(meta);

        Component name = self
                ? MiniMessage.miniMessage().deserialize("<aqua><b>" + shown.getName() + "</b> <gray>(du)</gray></aqua>")
                : MiniMessage.miniMessage().deserialize("<red><b>" + shown.getName() + "</b></red>");
        applyCellMeta(item, name, cell, true);
        return item;
    }

    private void applyCellMeta(ItemStack item, Component displayName, Location cell, boolean occupied) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.displayName(displayName);
        meta.lore(List.of(
                MiniMessage.miniMessage().deserialize(String.format(java.util.Locale.US,
                        "<gray>X <white>%.0f</white>   Z <white>%.0f</white></gray>", cell.getX(), cell.getZ())),
                MiniMessage.miniMessage().deserialize(occupied
                        ? "<red>Gegner in diesem Sektor!</red>"
                        : "<dark_gray>leer</dark_gray>"),
                MiniMessage.miniMessage().deserialize("<yellow>Klicken, um den Air-Strike anzufordern</yellow>")));
        meta.getPersistentDataContainer().set(KEY_TARGET_X, PersistentDataType.DOUBLE, cell.getX());
        meta.getPersistentDataContainer().set(KEY_TARGET_Z, PersistentDataType.DOUBLE, cell.getZ());
        item.setItemMeta(meta);
    }

    @EventHandler
    public void onAirStrikeMapClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(AIRSTRIKE_GUI_TITLE)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player user)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        Double targetX = clicked.getItemMeta().getPersistentDataContainer().get(KEY_TARGET_X, PersistentDataType.DOUBLE);
        Double targetZ = clicked.getItemMeta().getPersistentDataContainer().get(KEY_TARGET_Z, PersistentDataType.DOUBLE);
        if (targetX == null || targetZ == null) return;

        user.closeInventory();

        // Verbrauch erst bei der Auswahl - wer das Menue schliesst, behaelt sein Item
        if (!consumeSpecialItem(user, KillstreakManager.KEY_AIRSTRIKE)) {
            user.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 🛰 Du hast keinen Air-Strike mehr im Inventar!</red>"));
            return;
        }

        callAirStrike(user, targetX, targetZ);
    }

    public void callAirStrike(Player owner, double targetX, double targetZ) {
        World world = plugin.getWorldManager().getOsokWorld();
        MapConfig map = plugin.getWorldManager().getActiveMapConfig();
        if (world == null || map == null) return;

        // Abwurfhoehe: ueber der Arena, aber niemals durch die Decke (Standard-Map ist ueberdacht)
        double dropY = Math.min(map.getMaxY() + AIRSTRIKE_HEIGHT_ABOVE_ARENA, map.getMaxFlyY());

        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                "<red>[OSOK] 🛰 <white>" + owner.getName() + "</white> hat einen <b>AIR-STRIKE</b> angefordert! <gray>Einschlag in Kürze…</gray></red>"));

        Location marker = new Location(world, targetX, dropY, targetZ);
        world.playSound(Sound.sound(org.bukkit.Sound.ENTITY_WITHER_SPAWN, Sound.Source.MASTER, 1.0f, 1.4f),
                marker.x(), marker.y(), marker.z());

        // Zielmarkierung als Partikelsaeule, damit der Einschlag angekuendigt wird
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            long elapsed = 0;

            @Override
            public void accept(ScheduledTask task) {
                if (elapsed >= AIRSTRIKE_DELAY_TICKS) {
                    task.cancel();
                    return;
                }
                for (double y = map.getMinY(); y <= dropY; y += 1.5) {
                    world.spawnParticle(Particle.SMALL_FLAME, targetX, y, targetZ, 2, 0.15, 0.15, 0.15, 0.0);
                }
                elapsed += 5L;
            }
        }, 1L, 5L);

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            for (int i = 0; i < AIRSTRIKE_BOMB_COUNT; i++) {
                double offsetX = (RANDOM.nextDouble() - 0.5) * 2.0 * AIRSTRIKE_SPREAD;
                double offsetZ = (RANDOM.nextDouble() - 0.5) * 2.0 * AIRSTRIKE_SPREAD;
                dropBomb(new Location(world, targetX + offsetX, dropY, targetZ + offsetZ), owner);
            }
        }, AIRSTRIKE_DELAY_TICKS);
    }

    /** Fallende Bombe, die bei Bodenkontakt gezuendet wird. */
    private void dropBomb(Location loc, Player owner) {
        if (loc.getWorld() == null) return;

        TNTPrimed bomb = loc.getWorld().spawn(loc, TNTPrimed.class, spawned -> {
            // Lange Zuendschnur: Gezuendet wird bei Bodenkontakt, nicht per Timer.
            spawned.setFuseTicks(BOMB_SAFETY_FUSE_TICKS);
            spawned.setIsIncendiary(false);
            spawned.setVelocity(new Vector(0, -0.35, 0));
            spawned.getPersistentDataContainer().set(KEY_AIRSTRIKE_BOMB, PersistentDataType.BYTE, (byte) 1);
            spawned.getPersistentDataContainer().set(KEY_OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());
        });
        activeBombs.add(bomb);

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!bomb.isValid()) {
                task.cancel();
                activeBombs.remove(bomb);
                return;
            }
            if (bomb.isOnGround()) {
                task.cancel();
                Location impact = bomb.getLocation().clone();
                activeBombs.remove(bomb);
                // Eigene Sprengung statt der Vanilla-Explosion des TNT: garantiert ohne Blockschaden
                bomb.remove();
                blast(impact, owner);
            }
        }, 1L, 1L);
    }

    // ==================================================================
    // C4
    // ==================================================================

    /** Platziert eine C4-Ladung auf dem angeklickten Block und gibt dem Spieler den Fernzuender. */
    public boolean placeC4(Player owner, Block clickedBlock) {
        Block above = clickedBlock.getRelative(BlockFace.UP);
        if (!above.isPassable()) {
            owner.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 💥 Hier ist kein Platz für die Ladung!</red>"));
            return false;
        }

        Location loc = above.getLocation();
        // BlockDisplay statt echtem Block: Die Map bleibt voellig unberuehrt.
        BlockDisplay charge = loc.getWorld().spawn(loc, BlockDisplay.class, spawned -> {
            spawned.setBlock(Material.TNT.createBlockData());
            spawned.setGlowing(true);
            spawned.setPersistent(false);
            spawned.getPersistentDataContainer().set(KEY_C4_CHARGE, PersistentDataType.BYTE, (byte) 1);
            spawned.getPersistentDataContainer().set(KEY_OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());
        });

        c4Charges.computeIfAbsent(owner.getUniqueId(), id -> new ArrayList<>()).add(charge);

        owner.playSound(Sound.sound(org.bukkit.Sound.BLOCK_STONE_PLACE, Sound.Source.MASTER, 1.0f, 0.8f));
        owner.sendMessage(MiniMessage.miniMessage().deserialize(
                "<green>[OSOK] 💥 C4 platziert! <gray>Mit dem <yellow>Fernzünder</yellow> auslösen.</gray></green>"));

        giveDetonator(owner);
        return true;
    }

    private void giveDetonator(Player owner) {
        for (ItemStack stack : owner.getInventory().getContents()) {
            if (stack != null && stack.hasItemMeta()
                    && stack.getItemMeta().getPersistentDataContainer().has(KEY_DETONATOR, PersistentDataType.BYTE)) {
                return; // hat schon einen
            }
        }

        ItemStack detonator = new ItemStack(Material.LEVER);
        ItemMeta meta = detonator.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize("<red><b>[💥] Fernzünder (Rechtsklick)</b></red>"));
            meta.lore(List.of(MiniMessage.miniMessage().deserialize("<gray>Zündet alle deine C4-Ladungen!</gray>")));
            meta.getPersistentDataContainer().set(KEY_DETONATOR, PersistentDataType.BYTE, (byte) 1);
            detonator.setItemMeta(meta);
        }
        owner.getInventory().addItem(detonator);
    }

    /** Fernzünder: zündet alle Ladungen des Spielers. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDetonatorUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!item.getItemMeta().getPersistentDataContainer().has(KEY_DETONATOR, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player owner = event.getPlayer();

        List<BlockDisplay> charges = c4Charges.remove(owner.getUniqueId());
        if (charges == null || charges.isEmpty()) {
            owner.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 💥 Du hast keine C4-Ladung platziert!</red>"));
            owner.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            return;
        }

        int detonated = 0;
        for (BlockDisplay charge : charges) {
            if (!charge.isValid()) continue;
            Location loc = charge.getLocation().clone().add(0.5, 0.5, 0.5);
            charge.remove();
            blast(loc, owner);
            detonated++;
        }

        owner.sendMessage(MiniMessage.miniMessage().deserialize(
                "<green>[OSOK] 💥 <b>" + detonated + "</b> C4-Ladung(en) gezündet!</green>"));

        // Zuender verbrauchen, es gibt keine Ladungen mehr
        item.subtract(1);
    }

    // ==================================================================
    // Hilfsmittel & Aufraeumen
    // ==================================================================

    /** Entfernt genau ein Spezial-Item des angegebenen Typs aus dem Inventar. */
    private boolean consumeSpecialItem(Player user, String typeId) {
        NamespacedKey typeKey = plugin.getKillstreakManager().getSpecialItemKey();
        for (ItemStack stack : user.getInventory().getContents()) {
            if (stack == null || !stack.hasItemMeta()) continue;
            String type = stack.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
            if (typeId.equals(type)) {
                stack.subtract(1);
                return true;
            }
        }
        return false;
    }

    /**
     * Entfernt alle fallenden Bomben und platzierten C4-Ladungen.
     * Durchsucht zusaetzlich alle Welten nach PDC-markierten Resten, damit auch Objekte
     * verschwinden, die durch einen Fehler oder Serverabsturz nie registriert wurden.
     */
    public void clearAll() {
        for (TNTPrimed bomb : new HashSet<>(activeBombs)) {
            if (bomb.isValid()) {
                bomb.remove();
            }
        }
        activeBombs.clear();

        for (List<BlockDisplay> charges : c4Charges.values()) {
            for (BlockDisplay charge : charges) {
                if (charge.isValid()) {
                    charge.remove();
                }
            }
        }
        c4Charges.clear();

        int orphans = 0;
        for (World world : Bukkit.getWorlds()) {
            for (TNTPrimed bomb : world.getEntitiesByClass(TNTPrimed.class)) {
                if (bomb.getPersistentDataContainer().has(KEY_AIRSTRIKE_BOMB, PersistentDataType.BYTE)) {
                    bomb.remove();
                    orphans++;
                }
            }
            for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
                if (display.getPersistentDataContainer().has(KEY_C4_CHARGE, PersistentDataType.BYTE)) {
                    display.remove();
                    orphans++;
                }
            }
        }
        if (orphans > 0) {
            plugin.getLogger().info("[OSOK] " + orphans + " verwaiste Sprengstoff-Objekte entfernt.");
        }
    }

    /**
     * Sicherheitsnetz: Sollte eine Air-Strike-Bombe doch einmal regulaer explodieren
     * (z. B. weil die Zuendschnur abgelaufen ist), darf sie die Map nicht beschaedigen.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (entity.getPersistentDataContainer().has(KEY_AIRSTRIKE_BOMB, PersistentDataType.BYTE)) {
            event.blockList().clear();
            event.setYield(0.0f);
        }
    }
}
