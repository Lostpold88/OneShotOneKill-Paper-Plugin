package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.sound.Sound;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Consumer;

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
    public static final String KEY_STEALTH_BOMBER = "stealth_bomber";
    public static final String KEY_AIRSTRIKE = "air_strike";
    public static final String KEY_C4 = "c4_charge_item";

    /** Anzahl der verfuegbaren Spezial-Item-Typen (Indizes 0 bis SPECIAL_ITEM_COUNT-1). */
    public static final int SPECIAL_ITEM_COUNT = 13;

    public static final NamespacedKey KEY_EXPLOSIVE_PDC = new NamespacedKey("oneshotonekill", "explosive_arrow");
    public static final NamespacedKey KEY_CHAIN_LIGHTNING_PDC = new NamespacedKey("oneshotonekill", "chain_lightning_arrow");
    public static final NamespacedKey KEY_TP_GRENADE_PDC = new NamespacedKey("oneshotonekill", "tp_grenade");
    public static final NamespacedKey KEY_GROUND_SPECIAL_PDC = new NamespacedKey("oneshotonekill", "ground_special");

    private final OneShotOneKill plugin;
    private final NamespacedKey specialItemKey;
    private final Set<UUID> activeShields = new HashSet<>();
    private final Set<UUID> explosiveShots = new HashSet<>();
    private final Set<UUID> chainLightningShots = new HashSet<>();
    private final Set<UUID> activeMiniguns = new HashSet<>();
    private final Set<UUID> arrowMagnets = new HashSet<>();

    private final Set<Item> activeGroundItems = new HashSet<>();
    private ItemMode currentItemMode = ItemMode.BOTH;

    public KillstreakManager(OneShotOneKill plugin) {
        this.plugin = plugin;
        this.specialItemKey = new NamespacedKey(plugin, "special_item_type");

        startGroundSpawnTask();
        startMarioKartParticleAnimation();
    }

    public ItemMode getItemMode() {
        return currentItemMode;
    }

    public void setItemMode(ItemMode mode) {
        this.currentItemMode = mode;
    }

    public NamespacedKey getSpecialItemKey() {
        return specialItemKey;
    }

    public void clearAllGroundItems() {
        for (Item item : activeGroundItems) {
            if (item != null && item.isValid()) {
                if (item.getLocation() != null && item.getWorld() != null) {
                    item.getWorld().removePluginChunkTicket(item.getLocation().getBlockX() >> 4, item.getLocation().getBlockZ() >> 4, plugin);
                }
                item.remove();
            }
        }
        activeGroundItems.clear();
    }

    private void startGroundSpawnTask() {
        // Paper Native Global Region Scheduler: Spawnt alle 30 Sekunden Mario-Kart-Boxen
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!plugin.getMatchManager().isMatchStarted() || plugin.getMatchManager().isMatchEnded()) {
                return;
            }
            if (currentItemMode == ItemMode.SPAWN || currentItemMode == ItemMode.BOTH) {
                spawnGroundSpecialItem();
            }
        }, 600L, 600L);
    }

    private void startMarioKartParticleAnimation() {
        // Paper Native Global Region Scheduler: Läuft alle 2 Ticks für Partikel-Ringe
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            double angle = 0;

            @Override
            public void accept(ScheduledTask task) {
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

                    Location loc = item.getLocation().add(0, 0.5, 0);
                    World world = loc.getWorld();

                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 2, 0.1, 0.1, 0.1, 0.02);

                    double x = Math.cos(angle) * 0.6;
                    double z = Math.sin(angle) * 0.6;
                    Location ringLoc = loc.clone().add(x, 0, z);
                    world.spawnParticle(Particle.END_ROD, ringLoc, 1, 0, 0, 0, 0);
                }
            }
        }, 1L, 2L);
    }

    public void spawnGroundSpecialItem() {
        // Boden-Items ausschliesslich auf dem Arena-Boden - kein Dach, keine Plattform, keine Lobby
        Location spawnLoc = plugin.getArenaManager().getRandomFloorLocation();
        if (spawnLoc == null || spawnLoc.getWorld() == null) {
            plugin.getLogger().warning("[OSOK] Kein freier Arena-Bodenplatz fuer eine Item-Box gefunden - Spawn uebersprungen.");
            return;
        }

        Random random = new Random();
        int itemType = random.nextInt(SPECIAL_ITEM_COUNT);
        ItemStack itemStack = createSpecificSpecialItem(itemType);

        spawnLoc.getWorld().addPluginChunkTicket(spawnLoc.getBlockX() >> 4, spawnLoc.getBlockZ() >> 4, plugin);

        // Paper dropItem mit Consumer: Eigenschaften stehen fest, BEVOR das Item in der Welt erscheint.
        // Gravitation MUSS aktiv bleiben, sonst bleibt die Item-Box in der Luft haengen.
        Item dropped = spawnLoc.getWorld().dropItem(spawnLoc, itemStack, item -> {
            item.setCanMobPickup(false);
            item.setGravity(true);
            item.setVelocity(new Vector(0, 0, 0));
            // Deutlich sichtbarer Leuchtrahmen durch alle Waende hindurch
            item.setGlowing(true);
            item.getPersistentDataContainer().set(KEY_GROUND_SPECIAL_PDC, PersistentDataType.BYTE, (byte) 1);
        });
        activeGroundItems.add(dropped);

        spawnLoc.getWorld().spawnParticle(Particle.FIREWORK, spawnLoc.clone().add(0, 0.5, 0), 30, 0.4, 0.4, 0.4, 0.05);
        spawnLoc.getWorld().spawnParticle(Particle.END_ROD, spawnLoc.clone().add(0, 0.5, 0), 20, 0.3, 0.3, 0.3, 0.1);
        spawnLoc.getWorld().playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.5f), spawnLoc.x(), spawnLoc.y(), spawnLoc.z());

        // Paper Native Global Region Scheduler: Nach 60 Sekunden automatisch despawnen
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            if (dropped.isValid()) {
                dropped.getWorld().spawnParticle(Particle.SMOKE, dropped.getLocation(), 15, 0.2, 0.2, 0.2, 0.05);
                dropped.remove();
                activeGroundItems.remove(dropped);
            }
        }, 1200L);
    }

    public void awardRandomKillstreakItem(Player player, int streak) {
        Random random = new Random();
        int itemType = random.nextInt(SPECIAL_ITEM_COUNT);
        giveSpecificSpecialItem(player, itemType, streak);
    }

    public ItemStack createSpecificSpecialItem(int itemType) {
        return switch (itemType) {
            case 0 -> createSpecialItem(Material.ENDER_EYE, "<yellow><b>[✦] Radar-Puls (Rechtsklick)</b></yellow>", "<gray>Enthüllt alle Gegner in der Arena für 30 Sekunden!</gray>", KEY_RADAR);
            case 1 -> createSpecialItem(Material.TNT, "<red><b>[★] Explosiv-Schuss (Rechtsklick)</b></red>", "<gray>Dein nächster Pfeil erzeugt eine Explosion!</gray>", KEY_EXPLOSIVE);
            case 2 -> createSpecialItem(Material.NETHER_STAR, "<aqua><b>[🛡] Reflektor-Schild (Rechtsklick)</b></aqua>", "<gray>Blockiert den nächsten tödlichen Treffer!</gray>", KEY_REFLECTOR);
            case 3 -> createSpecialItem(Material.SNOWBALL, "<white><b>[☁] Rauchbombe (Werfen)</b></white>", "<gray>Erzeugt eine dichte Rauchwolke!</gray>", KEY_SMOKE);
            case 4 -> createSpecialItem(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, "<aqua><b>[❄] Frost-Trap (Plazieren)</b></aqua>", "<gray>Friert betretende Gegner für 7s fest!</gray>", KEY_FROST);
            case 5 -> createSpecialItem(Material.BLAZE_ROD, "<gold><b>[🔥] Krass Minigun (Rechtsklick)</b></gold>", "<gray>Feuert 8 Sekunden lang automatisch Pfeile ab!</gray>", KEY_MINIGUN);
            case 6 -> createSpecialItem(Material.ENDER_PEARL, "<light_purple><b>[🌀] Teleport-Granate (Werfen)</b></light_purple>", "<gray>Teleportiert & erzeugt eine Druckwelle!</gray>", KEY_TELEPORT);
            case 7 -> createSpecialItem(Material.PHANTOM_MEMBRANE, "<gray><b>[✦] Unsichtbarkeits-Mantel (Rechtsklick)</b></gray>", "<gray>Macht dich für 15s komplett unsichtbar!</gray>", KEY_INVISIBILITY);
            case 8 -> createSpecialItem(Material.HEART_OF_THE_SEA, "<blue><b>[⚓] Pfeil-Magnetfeld (Rechtsklick)</b></blue>", "<gray>Lenkt herannahende Pfeile für 15s ab!</gray>", KEY_MAGNET);
            case 9 -> createSpecialItem(Material.LIGHTNING_ROD, "<yellow><b>[⚡] Kettenblitz-Schuss (Rechtsklick)</b></yellow>", "<gray>Dein nächster Schuss erzeugt Blitze!</gray>", KEY_CHAIN_LIGHTNING);
            case 10 -> createSpecialItem(Material.DRAGON_HEAD, "<dark_purple><b>[🐉] Tarnkappenbomber (Rechtsklick)</b></dark_purple>", "<gray>Setzt 10s lang einen TNT-werfenden Drachen auf ein Ziel an!</gray>", KEY_STEALTH_BOMBER);
            case 11 -> createSpecialItem(Material.FILLED_MAP, "<red><b>[🛰] Air-Strike (Rechtsklick)</b></red>", "<gray>Arena-Karte öffnen und einen Bombenhagel anfordern!</gray>", KEY_AIRSTRIKE);
            default -> createSpecialItem(Material.TNT_MINECART, "<gold><b>[💥] C4 (Auf Block platzieren)</b></gold>", "<gray>Platzieren und per Fernzünder auslösen!</gray>", KEY_C4);
        };
    }

    public void giveSpecificSpecialItem(Player player, int itemType, int streak) {
        ItemStack item = createSpecificSpecialItem(itemType);
        ItemMeta meta = item.getItemMeta();
        Component itemNameComponent = meta != null && meta.hasDisplayName() ? meta.displayName() : Component.text("Spezial-Item");

        player.getInventory().addItem(item);
        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.8f));
        if (streak > 0) {
            Component msg = MiniMessage.miniMessage().deserialize("<green>[OSOK] 🎁 <b>" + streak + "er Killstreak!</b> <gray>Du hast den Spezial-Item erhalten: </gray></green>")
                    .append(itemNameComponent);
            player.sendMessage(msg);

            Component bc = MiniMessage.miniMessage().deserialize("<yellow>[OSOK] 🔥 <white>" + player.getName() + "</white> hat eine <b>" + streak + "er Killstreak</b> erreicht!</yellow>");
            Bukkit.broadcast(bc);
        } else {
            Component msg = MiniMessage.miniMessage().deserialize("<green>[OSOK] 🧪 Itemtest: </green>")
                    .append(itemNameComponent)
                    .append(MiniMessage.miniMessage().deserialize("<green> erhalten!</green>"));
            player.sendMessage(msg);
        }
    }

    public ItemStack createSpecialItem(Material mat, String miniMessageName, String miniMessageLore) {
        return createSpecialItem(mat, miniMessageName, miniMessageLore, null);
    }

    public ItemStack createSpecialItem(Material mat, String miniMessageName, String miniMessageLore, String itemTypeId) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(miniMessageName));
            meta.lore(Collections.singletonList(MiniMessage.miniMessage().deserialize(miniMessageLore)));
            if (itemTypeId != null) {
                meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, itemTypeId);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public void activateMinigun(Player player) {
        if (activeMiniguns.contains(player.getUniqueId())) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 🔥 Minigun ist bereits aktiv!</red>"));
            return;
        }

        activeMiniguns.add(player.getUniqueId());
        player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] 🔥 <b>MINIGUN AKTIVIERT!</b> <gray>8 Sekunden Dauerfeuer!</gray></green>"));
        player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 0.8f, 1.5f));

        List<Arrow> minigunArrows = new ArrayList<>();

        // Paper Native Entity Scheduler: Feuert alle 2 Ticks gebunden an den Player-Tick
        player.getScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int ticksLeft = 160;

            @Override
            public void accept(ScheduledTask task) {
                if (!player.isOnline() || player.isDead() || ticksLeft <= 0) {
                    activeMiniguns.remove(player.getUniqueId());
                    task.cancel();

                    for (Arrow arrow : minigunArrows) {
                        if (arrow.isValid()) {
                            arrow.remove();
                        }
                    }
                    minigunArrows.clear();

                    if (player.isOnline()) {
                        player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 🔥 Minigun abgelaufen. Pfeile wurden entfernt!</red>"));
                        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH, Sound.Source.MASTER, 1.0f, 1.0f));
                    }
                    return;
                }

                Arrow arrow = player.launchProjectile(Arrow.class, player.getEyeLocation().getDirection().multiply(2.5));
                arrow.setShooter(player);
                arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                minigunArrows.add(arrow);

                player.getWorld().playSound(Sound.sound(org.bukkit.Sound.ENTITY_ARROW_SHOOT, Sound.Source.MASTER, 0.8f, 2.0f), player.getLocation().x(), player.getLocation().y(), player.getLocation().z());
                player.getWorld().spawnParticle(Particle.FLAME, player.getEyeLocation().add(player.getEyeLocation().getDirection()), 3, 0.1, 0.1, 0.1, 0.05);

                ticksLeft -= 2;
            }
        }, null, 1L, 2L);
    }

    public void activateArrowMagnet(Player player) {
        if (arrowMagnets.contains(player.getUniqueId())) return;
        arrowMagnets.add(player.getUniqueId());
        player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ⚓ Pfeil-Magnetfeld für 15 Sekunden aktiv!</green>"));
        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, Sound.Source.MASTER, 1.0f, 1.5f));

        // Paper Native Entity Scheduler: Lenkt Pfeile ab gebunden an den Player-Tick
        player.getScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int ticksLeft = 300;

            @Override
            public void accept(ScheduledTask task) {
                if (!player.isOnline() || player.isDead() || ticksLeft <= 0) {
                    arrowMagnets.remove(player.getUniqueId());
                    task.cancel();
                    if (player.isOnline()) {
                        player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ⚓ Pfeil-Magnetfeld abgelaufen.</red>"));
                        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_BEACON_DEACTIVATE, Sound.Source.MASTER, 1.0f, 1.0f));
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
        }, null, 1L, 2L);
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
