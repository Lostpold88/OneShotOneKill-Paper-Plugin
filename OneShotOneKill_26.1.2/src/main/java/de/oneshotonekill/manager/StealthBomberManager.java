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
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tarnkappenbomber: Der Nutzer waehlt in einem Menue einen Spieler aus. Ueber diesem erscheint
 * ein Ender-Drache, der ihm 10 Sekunden lang folgt und dabei durchgehend TNT abwirft.
 * <p>
 * Der Drache greift niemanden an (AI und Wahrnehmung deaktiviert, Schaden durch ihn wird gecancelt).
 * Das TNT zerstoert keine Bloecke (Blockliste wird geleert) und zuendet bei Bodenkontakt sofort.
 */
public class StealthBomberManager implements Listener {

    public static final Component GUI_TITLE =
            MiniMessage.miniMessage().deserialize("<dark_purple><b>🐉 Tarnkappenbomber - Ziel waehlen</b></dark_purple>");

    private static final NamespacedKey KEY_BOMBER_DRAGON = new NamespacedKey("oneshotonekill", "bomber_dragon");
    private static final NamespacedKey KEY_BOMBER_TNT = new NamespacedKey("oneshotonekill", "bomber_tnt");
    private static final NamespacedKey KEY_BOMBER_OWNER = new NamespacedKey("oneshotonekill", "bomber_owner");
    private static final NamespacedKey KEY_GUI_TARGET = new NamespacedKey("oneshotonekill", "bomber_gui_target");

    /** Gesamtdauer des Angriffs (10 Sekunden). */
    private static final int BOMBER_DURATION_TICKS = 200;
    /** Takt der Verfolgung - jeden Tick, damit der Drache eng am Ziel bleibt. */
    private static final long FOLLOW_PERIOD_TICKS = 1L;
    /** Abstand zwischen zwei TNT-Abwuerfen. */
    private static final int TNT_DROP_INTERVAL_TICKS = 10;
    /** Flughoehe des Drachen ueber dem Ziel. */
    private static final double DRAGON_HEIGHT = 12.0;
    /** Sicherheits-Fuse, falls das TNT nie den Boden beruehrt. */
    private static final int TNT_MAX_FUSE_TICKS = 120;
    /**
     * Maximaler Schaden einer Bombe (3 Herzen). Ohne diesen Deckel toetet eine
     * Vanilla-TNT-Explosion aus naechster Naehe sofort - der Bomber soll aber
     * ausdruecklich nicht mit einem Treffer toeten.
     */
    private static final double BOMB_MAX_DAMAGE = 6.0;

    private final OneShotOneKill plugin;
    private final Set<UUID> activeDragons = new HashSet<>();
    private final Set<TNTPrimed> activeBombs = new HashSet<>();

    public StealthBomberManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Ziel-Auswahl GUI
    // ------------------------------------------------------------------

    /**
     * Oeffnet das Auswahlmenue. Liefert {@code false}, wenn es kein gueltiges Ziel gibt -
     * dann darf das Item nicht verbraucht werden.
     */
    public boolean openTargetMenu(Player user) {
        List<Player> targets = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getUniqueId().equals(user.getUniqueId())) {
                targets.add(online);
            }
        }

        if (targets.isEmpty()) {
            user.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>[OSOK] 🐉 Kein Ziel verfuegbar - es ist kein anderer Spieler online!</red>"));
            user.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            return false;
        }

        int size = Math.min(54, ((targets.size() - 1) / 9 + 1) * 9);
        Inventory gui = Bukkit.createInventory(null, size, GUI_TITLE);

        for (int i = 0; i < Math.min(targets.size(), size); i++) {
            gui.setItem(i, createTargetHead(targets.get(i)));
        }

        user.openInventory(gui);
        user.playSound(Sound.sound(org.bukkit.Sound.BLOCK_ENDER_CHEST_OPEN, Sound.Source.MASTER, 1.0f, 1.2f));
        return true;
    }

    private ItemStack createTargetHead(Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(target);
        }
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize("<light_purple><b>" + target.getName() + "</b></light_purple>"));
            meta.lore(List.of(MiniMessage.miniMessage().deserialize("<gray>Klicken, um den Bomber zu starten</gray>")));
            meta.getPersistentDataContainer().set(KEY_GUI_TARGET, PersistentDataType.STRING, target.getUniqueId().toString());
            head.setItemMeta(meta);
        }
        return head;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(GUI_TITLE)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player user)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String targetId = clicked.getItemMeta().getPersistentDataContainer()
                .get(KEY_GUI_TARGET, PersistentDataType.STRING);
        if (targetId == null) return;

        user.closeInventory();

        Player target = Bukkit.getPlayer(UUID.fromString(targetId));
        if (target == null || !target.isOnline()) {
            user.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 🐉 Das Ziel ist nicht mehr online!</red>"));
            return;
        }

        // Verbrauch erst bei der Auswahl - wer das Menue schliesst, behaelt sein Item
        if (!consumeBomberItem(user)) {
            user.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 🐉 Du hast keinen Tarnkappenbomber mehr im Inventar!</red>"));
            return;
        }

        launchBomber(user, target);
    }

    /** Entfernt genau einen Tarnkappenbomber aus dem Inventar. */
    private boolean consumeBomberItem(Player user) {
        NamespacedKey typeKey = plugin.getKillstreakManager().getSpecialItemKey();
        for (ItemStack stack : user.getInventory().getContents()) {
            if (stack == null || !stack.hasItemMeta()) continue;
            String type = stack.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
            if (KillstreakManager.KEY_STEALTH_BOMBER.equals(type)) {
                stack.subtract(1);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Drache & TNT
    // ------------------------------------------------------------------

    /**
     * Flugposition des Drachen ueber dem Ziel, begrenzt durch die Decke der aktiven Map.
     * Die Standard-Arena ist ueberdacht - ohne diese Begrenzung wuerde der Drache in der
     * Decke stecken oder darueber schweben.
     */
    private Location dragonPositionAbove(Player target) {
        Location loc = target.getLocation().clone().add(0, DRAGON_HEIGHT, 0);

        MapConfig activeMap = plugin.getWorldManager().getActiveMapConfig();
        if (activeMap != null) {
            double maxY = activeMap.getMaxFlyY();
            if (loc.getY() > maxY) {
                loc.setY(maxY);
            }
        }
        return loc;
    }

    public void launchBomber(Player owner, Player target) {
        Location spawnLoc = dragonPositionAbove(target);

        EnderDragon dragon = target.getWorld().spawn(spawnLoc, EnderDragon.class, spawned -> {
            spawned.setPhase(EnderDragon.Phase.HOVER);
            // Bewusst KEIN setAI(false): Das NoAI-Flag wird zum Client synchronisiert und
            // der Drache ist ein mehrteiliges Modell, dessen Segmente clientseitig in
            // aiStep() nachgefuehrt werden. Mit NoAI bleibt das Modell optisch stehen,
            // obwohl die Entity serverseitig nachweislich mitwandert.
            // Aggression wird stattdessen ueber setAware(false), Unverwundbarkeit und das
            // Cancelling saemtlichen Drachenschadens unterbunden.
            spawned.setAware(false);
            spawned.setInvulnerable(true);
            spawned.setGravity(false);
            spawned.setSilent(false);
            spawned.getPersistentDataContainer().set(KEY_BOMBER_DRAGON, PersistentDataType.BYTE, (byte) 1);
        });
        // Sofort registrieren, damit der Drache auch bei einem spaeteren Fehler aufraeumbar bleibt
        activeDragons.add(dragon.getUniqueId());

        // Achtung: getBossBar() ist null, solange der Drache nicht in einer End-Welt mit
        // Drachenkampf lebt. In der Arena ist das immer der Fall - hier gibt es also
        // schlicht keine Leiste, die ausgeblendet werden muesste.
        BossBar bossBar = dragon.getBossBar();
        if (bossBar != null) {
            bossBar.setVisible(false);
        }

        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                "<dark_purple>[OSOK] 🐉 <white>" + owner.getName() + "</white> hat den <b>Tarnkappenbomber</b> auf <yellow>"
                        + target.getName() + "</yellow> angesetzt!</dark_purple>"));
        target.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1.0f, 0.8f));

        UUID ownerId = owner.getUniqueId();

        // Paper Global Region Scheduler: Verfolgung und TNT-Abwurf
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int elapsed = 0;

            @Override
            public void accept(ScheduledTask task) {
                boolean expired = elapsed >= BOMBER_DURATION_TICKS;
                if (expired || !dragon.isValid() || !target.isOnline()) {
                    task.cancel();
                    removeDragon(dragon);
                    return;
                }

                Location above = dragonPositionAbove(target);
                // Blickrichtung des Ziels uebernehmen, damit der Drache mitdreht
                above.setYaw(target.getLocation().getYaw());
                above.setPitch(0f);

                // Bewusst synchron: Wir laufen bereits im GlobalRegionScheduler auf dem
                // Main-Thread und der Zielchunk ist geladen, weil dort der Zielspieler steht.
                dragon.teleport(above);
                dragon.setVelocity(new Vector(0, 0, 0));

                // Die HOVER-Phase verankert den Drachen an einem festen Schwebepunkt.
                // Der Phasenwechsel setzt diesen Punkt auf die neue Position, damit der
                // Drache nicht gegen den Teleport zurueckfliegt.
                dragon.setPhase(EnderDragon.Phase.CIRCLING);
                dragon.setPhase(EnderDragon.Phase.HOVER);

                if (elapsed % TNT_DROP_INTERVAL_TICKS == 0) {
                    dropBomb(above.clone().subtract(0, 2.0, 0), ownerId);
                }

                elapsed += FOLLOW_PERIOD_TICKS;
            }
        }, 1L, FOLLOW_PERIOD_TICKS);
    }

    private void dropBomb(Location loc, UUID ownerId) {
        if (loc.getWorld() == null) return;

        TNTPrimed tnt = loc.getWorld().spawn(loc, TNTPrimed.class, spawned -> {
            // Lange Zuendschnur: Gezuendet wird beim Bodenkontakt, nicht per Timer.
            spawned.setFuseTicks(TNT_MAX_FUSE_TICKS);
            spawned.setIsIncendiary(false);
            spawned.setVelocity(new Vector(0, -0.2, 0));
            spawned.getPersistentDataContainer().set(KEY_BOMBER_TNT, PersistentDataType.BYTE, (byte) 1);
            spawned.getPersistentDataContainer().set(KEY_BOMBER_OWNER, PersistentDataType.STRING, ownerId.toString());
        });
        activeBombs.add(tnt);

        // Bei Bodenkontakt sofort zuenden
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!tnt.isValid()) {
                task.cancel();
                activeBombs.remove(tnt);
                return;
            }
            if (tnt.isOnGround()) {
                task.cancel();
                tnt.setFuseTicks(0);
            }
        }, 1L, 1L);
    }

    private void removeDragon(EnderDragon dragon) {
        activeDragons.remove(dragon.getUniqueId());
        if (dragon.isValid()) {
            dragon.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, dragon.getLocation(), 1);
            dragon.remove();
        }
    }

    /**
     * Entfernt alle aktiven Drachen und Bomben (Plugin-Enable/Disable, Map-Wechsel,
     * /osok start und /osok stop).
     * <p>
     * Zusaetzlich zu den selbst verwalteten Referenzen werden alle Welten nach
     * PDC-markierten Bomber-Entities durchsucht. So verschwinden auch Drachen, die durch
     * einen Fehler oder einen Serverabsturz nie registriert wurden.
     */
    public void clearAll() {
        for (UUID dragonId : new HashSet<>(activeDragons)) {
            Entity entity = Bukkit.getEntity(dragonId);
            if (entity != null) {
                entity.remove();
            }
        }
        activeDragons.clear();

        for (TNTPrimed tnt : new HashSet<>(activeBombs)) {
            if (tnt.isValid()) {
                tnt.remove();
            }
        }
        activeBombs.clear();

        int orphans = 0;
        for (World world : Bukkit.getWorlds()) {
            for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) {
                if (dragon.getPersistentDataContainer().has(KEY_BOMBER_DRAGON, PersistentDataType.BYTE)) {
                    dragon.remove();
                    orphans++;
                }
            }
            for (TNTPrimed tnt : world.getEntitiesByClass(TNTPrimed.class)) {
                if (tnt.getPersistentDataContainer().has(KEY_BOMBER_TNT, PersistentDataType.BYTE)) {
                    tnt.remove();
                    orphans++;
                }
            }
        }
        if (orphans > 0) {
            plugin.getLogger().info("[OSOK] " + orphans + " verwaiste Tarnkappenbomber-Entities entfernt.");
        }
    }

    // ------------------------------------------------------------------
    // Schutz-Listener
    // ------------------------------------------------------------------

    /** Das Bomber-TNT und der Drache duerfen die Map niemals beschaedigen. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isBomberEntity(event.getEntity())) {
            event.blockList().clear();
            event.setYield(0.0f);
        }
    }

    /**
     * Schaden durch den Drachen selbst wird komplett unterbunden - er soll niemanden angreifen.
     * <p>
     * Das Bomber-TNT bleibt bewusst unangetastet: Es richtet regulaeren Explosionsschaden an
     * und toetet ausdruecklich <b>nicht</b> mit einem Treffer. Wird der Schaden toedlich,
     * uebernimmt {@code CombatListener#onEntityDamage} die Eliminierung und holt sich ueber
     * {@link #resolveBombOwner(Entity)} den Verursacher fuer die Kill-Gutschrift.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBomberDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();

        if (damager instanceof EnderDragon
                && damager.getPersistentDataContainer().has(KEY_BOMBER_DRAGON, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getEntity() instanceof Player)) return;
        if (!damager.getPersistentDataContainer().has(KEY_BOMBER_TNT, PersistentDataType.BYTE)) return;

        // Kein One-Shot: Vanilla-TNT richtet aus naechster Naehe fast vollen Schaden an.
        // Der Deckel sorgt dafuer, dass aus voller Gesundheit immer mehrere Treffer noetig sind.
        // Laeuft auf HIGH, also bevor CombatListener auf HIGHEST den toedlichen Schaden prueft.
        event.setDamage(Math.min(event.getDamage(), BOMB_MAX_DAMAGE));
    }

    /** Liefert den Spieler, der das Bomber-TNT ausgeloest hat, oder {@code null}. */
    public Player resolveBombOwner(Entity entity) {
        if (!entity.getPersistentDataContainer().has(KEY_BOMBER_TNT, PersistentDataType.BYTE)) {
            return null;
        }
        String ownerId = entity.getPersistentDataContainer().get(KEY_BOMBER_OWNER, PersistentDataType.STRING);
        return ownerId != null ? Bukkit.getPlayer(UUID.fromString(ownerId)) : null;
    }

    private boolean isBomberEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(KEY_BOMBER_TNT, PersistentDataType.BYTE)
                || entity.getPersistentDataContainer().has(KEY_BOMBER_DRAGON, PersistentDataType.BYTE);
    }
}
