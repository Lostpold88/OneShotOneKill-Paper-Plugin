package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class KillstreakManager {

    public enum ItemMode {
        STREAK, SPAWN, BOTH
    }

    public static final String KEY_RADAR = "radar_puls";
    public static final String KEY_EXPLOSIVE = "explosive_shot";
    public static final String KEY_REFLECTOR = "reflector_shield";
    public static final String KEY_SMOKE = "smoke_bomb";
    public static final String KEY_FROST = "frost_trap";
    public static final String KEY_MINIGUN = "minigun";
    public static final String KEY_TELEPORT = "teleport_grenade";
    public static final String KEY_INVISIBILITY = "invisibility_cloak";
    public static final String KEY_MAGNET = "arrow_magnet";
    public static final String KEY_CHAIN_LIGHTNING = "chain_lightning";
    public static final String KEY_ROCKET_JUMP = "rocket_jump";

    public static final NamespacedKey KEY_GROUND_SPECIAL_PDC = new NamespacedKey("oneshotonekill", "ground_special");
    public static final NamespacedKey KEY_TP_GRENADE_PDC = new NamespacedKey("oneshotonekill", "tp_grenade");
    public static final NamespacedKey KEY_CHAIN_LIGHTNING_PDC = new NamespacedKey("oneshotonekill", "chain_lightning");
    public static final NamespacedKey KEY_EXPLOSIVE_PDC = new NamespacedKey("oneshotonekill", "explosive");

    private final OneShotOneKill plugin;
    private final NamespacedKey specialItemKey;
    private ItemMode currentItemMode = ItemMode.BOTH;

    private final Set<UUID> activeShields = new HashSet<>();
    private final Set<UUID> explosiveShots = new HashSet<>();
    private final Set<UUID> chainLightningShots = new HashSet<>();
    private final Set<UUID> arrowMagnets = new HashSet<>();
    private final Set<UUID> activeMiniguns = new HashSet<>();
    private final Set<Item> activeGroundItems = new HashSet<>();

    public KillstreakManager(OneShotOneKill plugin) {
        this.plugin = plugin;
        this.specialItemKey = new NamespacedKey(plugin, "special_item_type");
        startGroundSpawnTask();
        startMarioKartParticleAnimation();
    }

    public NamespacedKey getSpecialItemKey() {
        return specialItemKey;
    }

    public ItemMode getItemMode() {
        return currentItemMode;
    }

    public void setItemMode(ItemMode mode) {
        this.currentItemMode = mode;
        if (mode == ItemMode.STREAK) {
            clearAllGroundItems();
        } else if (mode == ItemMode.SPAWN || mode == ItemMode.BOTH) {
            spawnGroundSpecialItem();
        }
    }

    public void clearAllGroundItems() {
        for (Item item : activeGroundItems) {
            if (item != null && item.isValid()) {
                item.remove();
            }
        }
        activeGroundItems.clear();
    }

    private void startGroundSpawnTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getMatchManager().isMatchStarted() || plugin.getMatchManager().isMatchEnded()) {
                    return;
                }
                if (currentItemMode == ItemMode.SPAWN || currentItemMode == ItemMode.BOTH) {
                    spawnGroundSpecialItem();
                }
            }
        }.runTaskTimer(plugin, 600L, 600L); // Alle 30 Sekunden
    }

    private void startMarioKartParticleAnimation() {
        new BukkitRunnable() {
            double angle = 0;

            @Override
            public void run() {
                if (activeGroundItems.isEmpty()) return;

                angle += 0.2;
                if (angle > Math.PI * 2) angle = 0;

                Iterator<Item> iterator = activeGroundItems.iterator();
                while (iterator.hasNext()) {
                    Item item = iterator.next();
                    if (item == null || !item.isValid()) {
                        if (item != null && item.getLocation() != null && item.getWorld() != null) {
                            item.getWorld().removePluginChunkTicket(item.getLocation().getBlockX() >> 4, item.getLocation().getBlockZ() >> 4, plugin);
                        }
                        iterator.remove();
                        continue;
                    }

                    Location loc = item.getLocation();
                    World world = loc.getWorld();
                    if (world == null) continue;

                    double x = Math.cos(angle) * 0.6;
                    double z = Math.sin(angle) * 0.6;
                    Location particleLoc = loc.clone().add(x, 0.2, z);

                    world.spawnParticle(Particle.END_ROD, particleLoc, 1, 0, 0, 0, 0);
                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0, 0.4, 0), 2, 0.2, 0.2, 0.2, 0.05);
                }
            }
        }.runTaskTimer(plugin, 0L, 3L);
    }

    public void spawnGroundSpecialItem() {
        World osokWorld = plugin.getWorldManager().getOsokWorld();
        if (osokWorld == null) return;

        activeGroundItems.removeIf(item -> item == null || !item.isValid());

        Location spawnLoc = null;
        for (int attempts = 0; attempts < 50; attempts++) {
            Location candidate = plugin.getArenaManager().getRandomArenaLocation();
            if (candidate == null) continue;

            boolean tooclose = false;
            for (Item groundItem : activeGroundItems) {
                if (groundItem != null && groundItem.isValid()) {
                    if (groundItem.getWorld().equals(candidate.getWorld()) && groundItem.getLocation().distance(candidate) < 10.0) {
                        tooclose = true;
                        break;
                    }
                }
            }

            if (!tooclose) {
                spawnLoc = candidate;
                break;
            }
        }

        if (spawnLoc == null) {
            spawnLoc = plugin.getArenaManager().getRandomArenaLocation();
        }

        if (spawnLoc == null) return;

        int chunkX = spawnLoc.getBlockX() >> 4;
        int chunkZ = spawnLoc.getBlockZ() >> 4;
        spawnLoc.getWorld().addPluginChunkTicket(chunkX, chunkZ, plugin);

        Random random = new Random();
        int itemType = random.nextInt(11);
        ItemStack specialItem = createSpecificSpecialItem(itemType);

        Item dropped = spawnLoc.getWorld().dropItem(spawnLoc, specialItem);
        dropped.setPickupDelay(5);
        dropped.setCanPlayerPickup(true);
        dropped.setCanMobPickup(false);
        dropped.getPersistentDataContainer().set(KEY_GROUND_SPECIAL_PDC, PersistentDataType.BYTE, (byte) 1);
        dropped.setPickupDelay(0);
        activeGroundItems.add(dropped);

        spawnLoc.getWorld().spawnParticle(Particle.FIREWORK, spawnLoc.clone().add(0, 0.5, 0), 30, 0.4, 0.4, 0.4, 0.05);
        spawnLoc.getWorld().spawnParticle(Particle.END_ROD, spawnLoc.clone().add(0, 0.5, 0), 20, 0.3, 0.3, 0.3, 0.1);
        spawnLoc.getWorld().playSound(spawnLoc, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.5f);

        // Nach 60 Sekunden (1 Minute) automatisch despawnen
        new BukkitRunnable() {
            @Override
            public void run() {
                if (dropped.isValid()) {
                    dropped.getWorld().spawnParticle(Particle.SMOKE, dropped.getLocation(), 15, 0.2, 0.2, 0.2, 0.05);
                    dropped.remove();
                    activeGroundItems.remove(dropped);
                }
            }
        }.runTaskLater(plugin, 1200L); // 60 Sekunden
    }

    public void awardRandomKillstreakItem(Player player, int streak) {
        Random random = new Random();
        int itemType = random.nextInt(11);
        giveSpecificSpecialItem(player, itemType, streak);
    }

    public ItemStack createSpecificSpecialItem(int itemType) {
        return switch (itemType) {
            case 0 -> createSpecialItem(Material.ENDER_EYE, "§e§l[✦] Radar-Puls (Rechtsklick)", "§7Enthüllt alle Gegner in der Arena für 30 Sekunden!", KEY_RADAR);
            case 1 -> createSpecialItem(Material.TNT, "§c§l[★] Explosiv-Schuss (Rechtsklick)", "§7Dein nächster Pfeil erzeugt eine Explosion!", KEY_EXPLOSIVE);
            case 2 -> createSpecialItem(Material.NETHER_STAR, "§b§l[🛡] Reflektor-Schild (Rechtsklick)", "§7Blockiert den nächsten tödlichen Treffer!", KEY_REFLECTOR);
            case 3 -> createSpecialItem(Material.SNOWBALL, "§f§l[☁] Rauchbombe (Werfen)", "§7Erzeugt eine dichte Rauchwolke!", KEY_SMOKE);
            case 4 -> createSpecialItem(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, "§b§l[❄] Frost-Trap (Plazieren)", "§7Friert betretende Gegner für 7s fest!", KEY_FROST);
            case 5 -> createSpecialItem(Material.BLAZE_ROD, "§6§l[🔥] Krass Minigun (Rechtsklick)", "§7Feuert 8 Sekunden lang automatisch Pfeile ab!", KEY_MINIGUN);
            case 6 -> createSpecialItem(Material.ENDER_PEARL, "§d§l[🌀] Teleport-Granate (Werfen)", "§7Teleportiert & erzeugt eine Druckwelle!", KEY_TELEPORT);
            case 7 -> createSpecialItem(Material.PHANTOM_MEMBRANE, "§7§l[✦] Unsichtbarkeits-Mantel (Rechtsklick)", "§7Macht dich für 15s komplett unsichtbar!", KEY_INVISIBILITY);
            case 8 -> createSpecialItem(Material.HEART_OF_THE_SEA, "§9§l[⚓] Pfeil-Magnetfeld (Rechtsklick)", "§7Lenkt herannahende Pfeile für 15s ab!", KEY_MAGNET);
            case 9 -> createSpecialItem(Material.LIGHTNING_ROD, "§e§l[⚡] Kettenblitz-Schuss (Rechtsklick)", "§7Dein nächster Schuss erzeugt Blitze!", KEY_CHAIN_LIGHTNING);
            default -> createSpecialItem(Material.FIREWORK_ROCKET, "§c§l[★] Raketen-Sprung (Rechtsklick)", "§7Schleudert dich 15 Blöcke in die Höhe!", KEY_ROCKET_JUMP);
        };
    }

    public void giveSpecificSpecialItem(Player player, int itemType, int streak) {
        ItemStack item = createSpecificSpecialItem(itemType);
        ItemMeta meta = item.getItemMeta();
        Component itemNameComponent = meta != null && meta.hasDisplayName() ? meta.displayName() : Component.text("Spezial-Item");

        player.getInventory().addItem(item);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.8f);
        if (streak > 0) {
            Component msg = LegacyComponentSerializer.legacySection().deserialize("§a[OSOK] 🎁 §l" + streak + "er Killstreak! §7Du hast den Spezial-Item erhalten: ")
                    .append(itemNameComponent);
            player.sendMessage(msg);

            Component bc = LegacyComponentSerializer.legacySection().deserialize("§e[OSOK] 🔥 §f" + player.getName() + " §ehat eine §l" + streak + "er Killstreak §eerreicht!");
            Bukkit.broadcast(bc);
        } else {
            Component msg = LegacyComponentSerializer.legacySection().deserialize("§a[OSOK] 🧪 Itemtest: ")
                    .append(itemNameComponent)
                    .append(LegacyComponentSerializer.legacySection().deserialize(" §aerhalten!"));
            player.sendMessage(msg);
        }
    }

    public ItemStack createSpecialItem(Material mat, String name, String loreLine) {
        return createSpecialItem(mat, name, loreLine, null);
    }

    public ItemStack createSpecialItem(Material mat, String name, String loreLine, String itemTypeId) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name));
            meta.lore(Collections.singletonList(LegacyComponentSerializer.legacySection().deserialize(loreLine)));
            if (itemTypeId != null) {
                meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, itemTypeId);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public void activateMinigun(Player player) {
        if (activeMiniguns.contains(player.getUniqueId())) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c[OSOK] 🔥 Minigun ist bereits aktiv!"));
            return;
        }

        activeMiniguns.add(player.getUniqueId());
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a[OSOK] 🔥 §lMINIGUN AKTIVIERT! §78 Sekunden Dauerfeuer!"));
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 0.8f, 1.5f);

        List<Arrow> minigunArrows = new ArrayList<>();

        new BukkitRunnable() {
            int ticksLeft = 160;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || ticksLeft <= 0) {
                    activeMiniguns.remove(player.getUniqueId());
                    cancel();

                    for (Arrow arrow : minigunArrows) {
                        if (arrow.isValid()) {
                            arrow.remove();
                        }
                    }
                    minigunArrows.clear();

                    if (player.isOnline()) {
                        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c[OSOK] 🔥 Minigun abgelaufen. Pfeile wurden entfernt!"));
                        player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.MASTER, 1.0f, 1.0f);
                    }
                    return;
                }

                Arrow arrow = player.launchProjectile(Arrow.class, player.getEyeLocation().getDirection().multiply(2.5));
                arrow.setShooter(player);
                arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                minigunArrows.add(arrow);

                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, SoundCategory.MASTER, 0.8f, 2.0f);
                player.getWorld().spawnParticle(Particle.FLAME, player.getEyeLocation().add(player.getEyeLocation().getDirection()), 3, 0.1, 0.1, 0.1, 0.05);

                ticksLeft -= 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    public void activateArrowMagnet(Player player) {
        if (arrowMagnets.contains(player.getUniqueId())) return;
        arrowMagnets.add(player.getUniqueId());
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a[OSOK] ⚓ Pfeil-Magnetfeld für 15 Sekunden aktiv!"));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.MASTER, 1.0f, 1.5f);

        new BukkitRunnable() {
            int ticksLeft = 300;

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead() || ticksLeft <= 0) {
                    arrowMagnets.remove(player.getUniqueId());
                    cancel();
                    if (player.isOnline()) {
                        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§c[OSOK] ⚓ Pfeil-Magnetfeld abgelaufen."));
                        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.MASTER, 1.0f, 1.0f);
                    }
                    return;
                }

                Location pLoc = player.getLocation().add(0, 1, 0);
                pLoc.getWorld().spawnParticle(Particle.END_ROD, pLoc, 8, 1.2, 1.2, 1.2, 0.05);

                for (Arrow arrow : pLoc.getNearbyEntitiesByType(Arrow.class, 8.0)) {
                    if (arrow.getShooter() != null && !arrow.getShooter().equals(player)) {
                        Vector pushAway = arrow.getLocation().toVector().subtract(pLoc.toVector()).normalize().multiply(1.8);
                        if (Double.isNaN(pushAway.getX())) pushAway = new Vector(0, 0.5, 0);
                        arrow.setVelocity(pushAway);
                        pLoc.getWorld().spawnParticle(Particle.CRIT, arrow.getLocation(), 5);
                    }
                }

                ticksLeft -= 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    public boolean isMinigunActive(UUID uuid) {
        return activeMiniguns.contains(uuid);
    }

    public boolean hasShield(UUID uuid) {
        return activeShields.contains(uuid);
    }

    public void addShield(UUID uuid) {
        activeShields.add(uuid);
    }

    public void removeShield(UUID uuid) {
        activeShields.remove(uuid);
    }

    public boolean hasExplosiveShot(UUID uuid) {
        return explosiveShots.contains(uuid);
    }

    public void addExplosiveShot(UUID uuid) {
        explosiveShots.add(uuid);
    }

    public void removeExplosiveShot(UUID uuid) {
        explosiveShots.remove(uuid);
    }

    public boolean hasChainLightningShot(UUID uuid) {
        return chainLightningShots.contains(uuid);
    }

    public void addChainLightningShot(UUID uuid) {
        chainLightningShots.add(uuid);
    }

    public void removeChainLightningShot(UUID uuid) {
        chainLightningShots.remove(uuid);
    }
}
