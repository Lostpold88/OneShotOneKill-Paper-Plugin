package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.sound.Sound;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
    public static final String KEY_RAILGUN = "railgun";
    public static final String KEY_SINGULARITY = "singularity";
    public static final String KEY_GLIDER = "glider_flight";
    public static final String KEY_SENTRY_TURRET = "sentry_turret";

    /**
     * Alle Spezial-Item-Typen in <b>Index-Reihenfolge</b>. Die Reihenfolge muss zu
     * {@link #createSpecificSpecialItem(int)} passen; {@link #SPECIAL_ITEM_COUNT} leitet sich
     * daraus ab, damit die Anzahl nirgends doppelt gepflegt werden muss.
     */
    public static final List<String> SPECIAL_ITEM_IDS = List.of(
            KEY_RADAR, KEY_EXPLOSIVE, KEY_REFLECTOR, KEY_SMOKE, KEY_FROST, KEY_MINIGUN,
            KEY_TELEPORT, KEY_INVISIBILITY, KEY_MAGNET, KEY_CHAIN_LIGHTNING, KEY_STEALTH_BOMBER,
            KEY_AIRSTRIKE, KEY_C4, KEY_RAILGUN, KEY_SINGULARITY, KEY_GLIDER, KEY_SENTRY_TURRET);

    /** Anzahl der verfuegbaren Spezial-Item-Typen (Indizes 0 bis SPECIAL_ITEM_COUNT-1). */
    public static final int SPECIAL_ITEM_COUNT = SPECIAL_ITEM_IDS.size();

    /** Startgewicht jedes Items. Bewusst nicht 1, damit sich Gewichte auch senken lassen. */
    public static final int DEFAULT_ITEM_WEIGHT = 10;
    /** Obergrenze eines Gewichts - verhindert absurde Werte per Tippfehler. */
    public static final int MAX_ITEM_WEIGHT = 1000;

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

    private static final Random RANDOM = new Random();

    /** Takt des Boden-Spawns: alle 30 Sekunden. */
    private static final long GROUND_SPAWN_PERIOD_TICKS = 600L;

    private final Set<Item> activeGroundItems = new HashSet<>();
    /** Spawngewicht je Item-Typ, einstellbar ueber /osok itemgewichtung. */
    private final Map<String, Integer> itemWeights = new HashMap<>();
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

    // ------------------------------------------------------------------
    // Spawngewichte (/osok itemgewichtung)
    // ------------------------------------------------------------------

    public int getItemWeight(String typeId) {
        return itemWeights.getOrDefault(typeId, DEFAULT_ITEM_WEIGHT);
    }

    /** Setzt das Gewicht eines Item-Typs. {@code 0} bedeutet: Das Item spawnt nie. */
    public void setItemWeight(String typeId, int weight) {
        itemWeights.put(typeId, Math.max(0, Math.min(weight, MAX_ITEM_WEIGHT)));
    }

    /** Setzt alle Gewichte auf {@link #DEFAULT_ITEM_WEIGHT} zurueck. */
    public void resetItemWeights() {
        itemWeights.clear();
    }

    /** Summe aller Gewichte. {@code 0} bedeutet: Es kann ueberhaupt kein Item mehr kommen. */
    public int getTotalItemWeight() {
        int total = 0;
        for (String typeId : SPECIAL_ITEM_IDS) {
            total += getItemWeight(typeId);
        }
        return total;
    }

    /** Spawnwahrscheinlichkeit eines Typs in Prozent. */
    public double getSpawnChance(String typeId) {
        int total = getTotalItemWeight();
        return (total <= 0) ? 0.0 : getItemWeight(typeId) * 100.0 / total;
    }

    /** Anzeigename eines Item-Typs - direkt vom erzeugten Item, damit nichts doppelt gepflegt wird. */
    public Component getItemDisplayName(String typeId) {
        int index = SPECIAL_ITEM_IDS.indexOf(typeId);
        if (index < 0) {
            return Component.text(typeId);
        }
        ItemStack sample = createSpecificSpecialItem(index);
        Component name = sample.getData(DataComponentTypes.CUSTOM_NAME);
        return name != null ? name : Component.text(typeId);
    }

    /**
     * Gewichteter Zufallszug ueber alle Item-Typen.
     *
     * @return Item-Index, oder {@code -1} wenn saemtliche Gewichte auf 0 stehen
     */
    private int rollItemIndex() {
        int total = getTotalItemWeight();
        if (total <= 0) {
            return -1;
        }

        int roll = RANDOM.nextInt(total);
        for (int index = 0; index < SPECIAL_ITEM_COUNT; index++) {
            roll -= getItemWeight(SPECIAL_ITEM_IDS.get(index));
            if (roll < 0) {
                return index;
            }
        }
        return SPECIAL_ITEM_COUNT - 1;
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

    /** Paper Native Global Region Scheduler: Spawnt alle 30 Sekunden Mario-Kart-Boxen. */
    private void startGroundSpawnTask() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!plugin.getMatchManager().isMatchStarted() || plugin.getMatchManager().isMatchEnded()) {
                return;
            }
            if (currentItemMode == ItemMode.SPAWN || currentItemMode == ItemMode.BOTH) {
                spawnGroundSpecialItem();
            }
        }, GROUND_SPAWN_PERIOD_TICKS, GROUND_SPAWN_PERIOD_TICKS);
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

        int itemType = rollItemIndex();
        if (itemType < 0) {
            // Alle Gewichte stehen auf 0 - dann soll auch keine Box erscheinen
            return;
        }
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

    /**
     * Vergibt ein zufaelliges Spezial-Item als Killstreak- oder Kopfgeld-Belohnung.
     * Zaehlt fuer die Match-Zusammenfassung mit; das Admin-Testmenue nutzt bewusst
     * {@link #giveSpecificSpecialItem(Player, int, int)} und zaehlt daher nicht.
     */
    public void awardRandomKillstreakItem(Player player, int streak) {
        int itemType = rollItemIndex();
        if (itemType < 0) {
            // Alle Gewichte stehen auf 0 - dann gibt es auch keine Belohnung
            return;
        }
        giveSpecificSpecialItem(player, itemType, streak);
        plugin.getScoreboardManager().addItemsCollected(player.getUniqueId(), 1);
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
            case 12 -> createSpecialItem(Material.TNT_MINECART, "<gold><b>[💥] C4 (Auf Block platzieren)</b></gold>", "<gray>Platzieren und per Fernzünder auslösen!</gray>", KEY_C4);
            case 13 -> createSpecialItem(Material.SPYGLASS, "<white><b>[🔭] Railgun (Rechtsklick)</b></white>", "<gray>Lädt 1s und tötet dann alles auf der Sichtlinie!</gray>", KEY_RAILGUN);
            case 14 -> createSpecialItem(Material.ECHO_SHARD, "<dark_purple><b>[🕳] Singularität (Werfen)</b></dark_purple>", "<gray>Reißt 4s lang alle Spieler im Umkreis zusammen!</gray>", KEY_SINGULARITY);
            case 15 -> createGliderItem();
            default -> createSentryTurretItem();
        };
    }

    private ItemStack createSentryTurretItem() {
        return createSpecialItem(Material.DISPENSER,
                "<gold><b>[🤖] Geschützturm (Auf Block platzieren)</b></gold>",
                "<gray>Platziere einen automatischen Geschützturm (20s Dauerfeuer)!</gray>", KEY_SENTRY_TURRET);
    }

    /**
     * Gleitflug-Item fuer die Hotbar.
     * <p>
     * Die Elytra darf ausdruecklich <b>nicht</b> angezogen werden koennen - sonst haette der
     * Spieler unbegrenzten Flug statt der acht Sekunden. Beide Faehigkeiten werden deshalb
     * ueber die Paper Data Components vom Item entfernt. Die eigentlichen Schwingen vergibt
     * fuer die Flugdauer der {@code TacticalItemsManager}.
     */
    private ItemStack createGliderItem() {
        ItemStack item = createSpecialItem(Material.ELYTRA,
                "<aqua><b>[🦅] Gleitflug (Rechtsklick)</b></aqua>",
                "<gray>8 Sekunden Flug mit Schubstößen!</gray>", KEY_GLIDER);
        item.unsetData(DataComponentTypes.EQUIPPABLE);
        item.unsetData(DataComponentTypes.GLIDER);
        return item;
    }

    public void giveSpecificSpecialItem(Player player, int itemType, int streak) {
        ItemStack item = createSpecificSpecialItem(itemType);
        Component customName = item.getData(DataComponentTypes.CUSTOM_NAME);
        Component itemNameComponent = customName != null ? customName : Component.text("Spezial-Item");

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
        ItemStack stack = ItemStack.of(mat);
        // Paper DataComponents statt ItemMeta: schreibt direkt in die Vanilla-Komponenten
        // custom_name und lore, ohne eine Meta-Kopie anzulegen.
        stack.setData(DataComponentTypes.CUSTOM_NAME, MiniMessage.miniMessage().deserialize(miniMessageName));
        stack.setData(DataComponentTypes.LORE,
                ItemLore.lore(Collections.singletonList(MiniMessage.miniMessage().deserialize(miniMessageLore))));
        if (itemTypeId != null) {
            // Der PDC haengt am Stack, nicht an der Meta - gleicher Speicher, ein Zugriff weniger
            stack.editPersistentDataContainer(pdc -> pdc.set(specialItemKey, PersistentDataType.STRING, itemTypeId));
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
