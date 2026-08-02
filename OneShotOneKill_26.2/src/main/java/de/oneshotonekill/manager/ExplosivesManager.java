package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
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
 * Die C4 sprengt dabei mit groesserer Reichweite als eine Air-Strike-Bombe.
 * <p>
 * Getroffen wird <b>jeder</b> Spieler in Reichweite, auch der Ausloeser - Details siehe
 * {@link #blast(Location, Player, float)}.
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
    /** Auf welchen Spieler ein Sektor zeigt - erlaubt das Zielen auf die Live-Position. */
    private static final NamespacedKey KEY_TARGET_PLAYER = new NamespacedKey("oneshotonekill", "airstrike_target_player");

    /** Sprengkraft einer Air-Strike-Bombe. Vanilla-TNT liegt bei 4.0. */
    private static final float AIRSTRIKE_BLAST_POWER = 8.0f;
    /** Sprengkraft einer C4-Ladung - bewusst groesser, da sie gezielt platziert wird. */
    private static final float C4_BLAST_POWER = 12.0f;

    private static final int GUI_COLS = 9;
    private static final int GUI_ROWS = 6;

    /** Vorwarnzeit zwischen Zielmarkierung und Einschlag. */
    private static final long AIRSTRIKE_DELAY_TICKS = 45L;
    private static final int AIRSTRIKE_BOMB_COUNT = 8;
    /**
     * Streuradius der Bomben um den Zielpunkt.
     * <p>
     * Bewusst eng: Das Raster kann die Arena nur auf 9x6 Truhenfelder abbilden, ein Sektor ist
     * also mehrere Bloecke breit. Zusammen mit dem Zielen auf die Live-Position (siehe
     * {@link #onAirStrikeMapClick}) landet der Abwurf dadurch dicht am Gegner statt irgendwo im
     * Sektor - das war der Kern von Issue #8, Punkt 3.
     */
    private static final double AIRSTRIKE_SPREAD = 2.0;
    /** Abwurfhoehe ueber der Arena-Oberkante, sofern keine Decke im Weg ist. */
    private static final double AIRSTRIKE_HEIGHT_ABOVE_ARENA = 15.0;
    private static final int BOMB_SAFETY_FUSE_TICKS = 400;
    /**
     * Startgeschwindigkeit der fallenden Bombe nach unten.
     * <p>
     * Die Schwerkraft allein liess die Bombe traege heruntertrudeln - auf der Standard-Map
     * sind es wegen der Decke ohnehin nur rund zehn Bloecke Fallhoehe. Der Anschub verkuerzt
     * die Wartezeit zwischen Zielmarkierung und Einschlag spuerbar.
     */
    private static final double BOMB_DROP_VELOCITY = -0.9;

    private static final Random RANDOM = new Random();

    /**
     * So lange nach einer Sprengung zaehlt ein Tod noch als deren Kill.
     * <p>
     * Bewusst grosszuegig: Eine C4 mit Staerke 12 schleudert Getroffene dutzende Bloecke hoch.
     * Steigen und Fallen zusammen dauern regelmaessig laenger als die frueheren 6 Sekunden -
     * lief das Fenster mitten im Flug ab, wurde der Aufprall als "ist gestorben" gemeldet und
     * der Zuender ging leer aus (Issue #8).
     */
    private static final long BLAST_CREDIT_MILLIS = 12000L;

    /**
     * Verhaeltnis von Schadensreichweite zu Sprengkraft.
     * <p>
     * {@code ServerExplosion#hurtEntities} sucht die Opfer im Umkreis {@code radius * 2.0} -
     * eine C4 mit Staerke 12 trifft also noch auf 24 Bloecke. Der Merkzettel wurde frueher nur
     * mit {@code power} gefuellt und liess damit die aeussere Haelfte der Druckwelle aus: Wer
     * dort hochgeschleudert wurde, starb ohne Zuordnung.
     */
    private static final double EXPLOSION_DAMAGE_RADIUS_FACTOR = 2.0;

    /** Merkzettel: Wer hat die Sprengung ausgeloest, die diesen Spieler erwischt hat? */
    private record BlastCredit(UUID owner, long expiresAt) { }

    private final OneShotOneKill plugin;
    private final Set<TNTPrimed> activeBombs = new HashSet<>();
    private final Map<UUID, List<BlockDisplay>> c4Charges = new HashMap<>();
    /** Ausloeser der aktuell laufenden Sprengung, nur waehrend {@code createExplosion} gesetzt. */
    private Player currentBlastOwner = null;
    /** Nachwirkende Zuordnung fuer Folgeschaeden, siehe resolveBlastKiller. */
    private final Map<UUID, BlastCredit> blastCredits = new HashMap<>();

    public ExplosivesManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    /**
     * Ermittelt, wem der Tod eines Spielers als Sprengungs-Kill zuzuschreiben ist.
     * <p>
     * Reicht {@link #currentBlastOwner} nicht aus, greift ein Kurzzeitgedaechtnis: Bei jeder
     * Sprengung wird fuer alle Spieler in Reichweite {@link #BLAST_CREDIT_MILLIS} lang
     * vermerkt, wer gezuendet hat.
     * <p>
     * Das ist noetig, weil eine Sprengung nicht nur direkt toetet. Eine C4 mit Staerke 12
     * schleudert Getroffene weit nach oben - wer den Treffer knapp ueberlebt, stirbt Sekunden
     * spaeter am Sturz. Der Schaden traegt dann die Ursache {@code FALL} und gar keine
     * Verursacher-Entity, der Kill blieb also unzugeordnet und wurde als "ist gestorben"
     * gemeldet. Mit dem Zeitfenster bekommt ihn der Zuender.
     *
     * @return der Ausloeser, oder {@code null} wenn keine Sprengung in Frage kommt
     */
    public Player resolveBlastKiller(Player victim) {
        if (currentBlastOwner != null) {
            return currentBlastOwner;
        }
        if (victim == null) return null;

        BlastCredit credit = blastCredits.get(victim.getUniqueId());
        if (credit == null || System.currentTimeMillis() > credit.expiresAt()) {
            blastCredits.remove(victim.getUniqueId());
            return null;
        }

        Player owner = Bukkit.getPlayer(credit.owner());
        return (owner != null && owner.isOnline()) ? owner : null;
    }

    /**
     * Vermerkt fuer alle Spieler in Reichweite, wer die Sprengung ausgeloest hat.
     * <p>
     * Der Radius muss der <b>tatsaechlichen</b> Schadensreichweite entsprechen, nicht der
     * Sprengkraft - siehe {@link #EXPLOSION_DAMAGE_RADIUS_FACTOR}.
     */
    private void rememberBlastCredit(Location center, Player owner, float power) {
        if (owner == null) return;

        long expiresAt = System.currentTimeMillis() + BLAST_CREDIT_MILLIS;
        // Paper Spatial Entity Index: direkte Spieler-Abfrage statt Entity-Box + instanceof
        for (Player affected : center.getNearbyPlayers(power * EXPLOSION_DAMAGE_RADIUS_FACTOR)) {
            if (affected.getUniqueId().equals(owner.getUniqueId())) continue;
            blastCredits.put(affected.getUniqueId(), new BlastCredit(owner.getUniqueId(), expiresAt));
        }

        // Abgelaufene Eintraege bei der Gelegenheit wegraeumen
        blastCredits.values().removeIf(entry -> System.currentTimeMillis() > entry.expiresAt());
    }

    /**
     * Loescht den Merkzettel-Eintrag eines Spielers.
     * <p>
     * Wird aufgerufen, sobald seine Eliminierung abgewickelt ist. Ohne das bliebe der Eintrag
     * bis zum Ablauf des Zeitfensters stehen und ein voellig unabhaengiger Sturz kurz nach dem
     * Respawn wuerde faelschlich noch dem Zuender gutgeschrieben.
     */
    public void clearBlastCredit(UUID victimId) {
        blastCredits.remove(victimId);
    }

    // ==================================================================
    // Gemeinsame Sprengung
    // ==================================================================

    /**
     * Gewaltige Explosion ohne jede Blockveraenderung, die <b>jeden</b> Spieler in Reichweite
     * trifft - auch den Ausloeser selbst.
     * <p>
     * Wichtig: Es wird bewusst <b>keine</b> Verursacher-Entity uebergeben. Minecraft ermittelt
     * die Explosionsopfer ueber {@code getEntities(source, box)}, und diese Abfrage schliesst
     * die Quell-Entity aus. Wuerde der Ausloeser als Quelle uebergeben, waere er von seiner
     * eigenen Sprengung ausgenommen.
     * <p>
     * Fuer die Kill-Zuordnung wird der Ausloeser stattdessen fuer die Dauer der Sprengung in
     * {@link #currentBlastOwner} hinterlegt. Das ist zuverlaessig, weil
     * {@code createExplosion} synchron laeuft und die Schadensevents unmittelbar ausloest.
     */
    private void blast(Location loc, Player owner, float power) {
        World world = loc.getWorld();
        if (world == null) return;

        // Zuordnung VOR der Sprengung merken - danach koennen Getroffene noch sekundenlang
        // an Folgeschaden (vor allem Sturz) sterben, siehe resolveBlastKiller
        rememberBlastCredit(loc, owner, power);

        // breakBlocks = false -> die Map kann nicht beschaedigt werden. setFire = false -> kein Feuer.
        this.currentBlastOwner = (owner != null && owner.isOnline()) ? owner : null;
        try {
            world.createExplosion(loc, power, false, false);
        } finally {
            this.currentBlastOwner = null;
        }

        // Partikelradius skaliert mit der Sprengkraft, damit die Optik zur Reichweite passt
        double spread = power / 3.0;
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 8, spread, spread * 0.6, spread, 0.0);
        world.spawnParticle(Particle.FLAME, loc, 160, spread * 1.4, 2.0, spread * 1.4, 0.12);
        world.spawnParticle(Particle.LARGE_SMOKE, loc, 90, spread * 1.2, 2.0, spread * 1.2, 0.08);
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
            gui.setItem(slot, createPlayerCell(shown, self));
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
        ItemStack item = ItemStack.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        writeCellData(item, MiniMessage.miniMessage().deserialize("<gray>Sektor</gray>"), cell, null);
        return item;
    }

    /**
     * Kopf-Feld eines Spielers. Der Zielpunkt ist seine <b>eigene</b> Position, nicht die
     * Sektormitte - ein Sektor ist mehrere Bloecke breit, die Mitte lag also regelmaessig
     * neben dem Gegner.
     */
    private ItemStack createPlayerCell(Player shown, boolean self) {
        ItemStack item = ItemStack.of(Material.PLAYER_HEAD);
        Component name = self
                ? MiniMessage.miniMessage().deserialize("<aqua><b>" + shown.getName() + "</b> <gray>(du)</gray></aqua>")
                : MiniMessage.miniMessage().deserialize("<red><b>" + shown.getName() + "</b></red>");

        // PROFILE ersetzt SkullMeta#setOwningPlayer - kein SkullMeta und keine Meta-Kopie
        // mehr, was bei 54 Feldern pro Menue spuerbar ist.
        item.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(shown.getPlayerProfile()));
        writeCellData(item, name, shown.getLocation(), shown.getUniqueId());
        return item;
    }

    /**
     * Schreibt Name, Lore und Zielkoordinaten eines Rasterfeldes als Paper DataComponents
     * bzw. in den PDC des Stacks.
     *
     * @param aim          Zielpunkt des Feldes - Sektormitte, oder die Position des Gegners
     * @param targetPlayer markierter Gegner, oder {@code null} fuer ein leeres Feld
     */
    private void writeCellData(ItemStack item, Component displayName, Location aim, UUID targetPlayer) {
        item.setData(DataComponentTypes.CUSTOM_NAME, displayName);
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                MiniMessage.miniMessage().deserialize(String.format(java.util.Locale.US,
                        "<gray>X <white>%.0f</white>   Z <white>%.0f</white></gray>", aim.getX(), aim.getZ())),
                MiniMessage.miniMessage().deserialize(targetPlayer != null
                        ? "<red>Gegner in diesem Sektor!</red>"
                        : "<dark_gray>leer</dark_gray>"),
                MiniMessage.miniMessage().deserialize("<yellow>Klicken, um den Air-Strike anzufordern</yellow>"))));
        item.editPersistentDataContainer(pdc -> {
            pdc.set(KEY_TARGET_X, PersistentDataType.DOUBLE, aim.getX());
            pdc.set(KEY_TARGET_Z, PersistentDataType.DOUBLE, aim.getZ());
            if (targetPlayer != null) {
                pdc.set(KEY_TARGET_PLAYER, PersistentDataType.STRING, targetPlayer.toString());
            }
        });
    }

    @EventHandler
    public void onAirStrikeMapClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(AIRSTRIKE_GUI_TITLE)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player user)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        Double targetX = clicked.getPersistentDataContainer().get(KEY_TARGET_X, PersistentDataType.DOUBLE);
        Double targetZ = clicked.getPersistentDataContainer().get(KEY_TARGET_Z, PersistentDataType.DOUBLE);
        if (targetX == null || targetZ == null) return;

        // Auf einen markierten Gegner wird seine Position im Moment des Klicks angepeilt, nicht
        // die beim Oeffnen des Menues gespeicherte. Die Karte ist eine Momentaufnahme - bis zum
        // Klick ist der Gegner laengst weitergelaufen, und der Abwurf ging ins Leere.
        // Die Vorwarnzeit bis zum Einschlag bleibt unangetastet: Ausweichen ist weiter moeglich.
        String markedId = clicked.getPersistentDataContainer().get(KEY_TARGET_PLAYER, PersistentDataType.STRING);
        if (markedId != null) {
            Player marked = Bukkit.getPlayer(UUID.fromString(markedId));
            if (marked != null && marked.isOnline()
                    && plugin.getArenaManager().isInArenaArea(marked.getLocation())) {
                targetX = marked.getLocation().getX();
                targetZ = marked.getLocation().getZ();
            }
        }

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
            spawned.setVelocity(new Vector(0, BOMB_DROP_VELOCITY, 0));
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
                blast(impact, owner, AIRSTRIKE_BLAST_POWER);
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
            // Bewusst ohne Leuchtrahmen: Die Ladung soll nicht durch Waende sichtbar sein.
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
            if (isDetonator(stack)) {
                return; // hat schon einen
            }
        }

        ItemStack detonator = ItemStack.of(Material.LEVER);
        // Paper DataComponents statt ItemMeta
        detonator.setData(DataComponentTypes.CUSTOM_NAME,
                MiniMessage.miniMessage().deserialize("<red><b>[💥] Fernzünder (Rechtsklick)</b></red>"));
        detonator.setData(DataComponentTypes.LORE,
                ItemLore.lore(List.of(MiniMessage.miniMessage().deserialize("<gray>Zündet alle deine C4-Ladungen!</gray>"))));
        detonator.editPersistentDataContainer(pdc -> pdc.set(KEY_DETONATOR, PersistentDataType.BYTE, (byte) 1));
        owner.getInventory().addItem(detonator);
    }

    /**
     * Aufheben einer platzierten C4: Rechtsklick auf den Block, auf dem die Ladung liegt.
     * <p>
     * Die Ladung ist ein {@link BlockDisplay} und damit nicht anklickbar - deshalb wird der
     * <b>Traegerblock</b> angeklickt und geprueft, ob direkt darueber eine eigene Ladung sitzt.
     * Mit einer C4 oder dem Fernzuender in der Hand greift das bewusst nicht: Damit wird
     * platziert bzw. gezuendet.
     * <p>
     * Es lassen sich nur <b>eigene</b> Ladungen aufnehmen. Mit der letzten Ladung verschwindet
     * auch der Fernzuender - ohne Ladung hat er keine Funktion mehr.
     * <p>
     * <b>{@code LOWEST} ist Pflicht.</b> Der {@code SpecialItemListener} platziert die C4 auf
     * {@code NORMAL} und verbraucht sie danach per {@code subtract(1)}. Bei genau einer C4 im
     * Stapel ist {@code event.getItem()} anschliessend leer - die Pruefung unten haette den
     * Platzierungsvorgang dann nicht mehr als solchen erkannt und die eben gesetzte Ladung
     * im selben Klick wieder eingesammelt.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onC4Pickup(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

        ItemStack held = event.getItem();
        if (isC4Item(held) || isDetonator(held)) return;

        Player owner = event.getPlayer();
        List<BlockDisplay> charges = c4Charges.get(owner.getUniqueId());
        if (charges == null || charges.isEmpty()) return;

        Location above = event.getClickedBlock().getRelative(BlockFace.UP).getLocation();
        BlockDisplay found = null;
        for (BlockDisplay charge : charges) {
            if (charge.isValid() && isSameBlock(charge.getLocation(), above) && isOwnedBy(charge, owner)) {
                found = charge;
                break;
            }
        }
        if (found == null) return;

        event.setCancelled(true);
        charges.remove(found);
        Location chargeLoc = found.getLocation().clone();
        found.remove();

        // Ladung zurueck ins Inventar
        int c4Index = KillstreakManager.SPECIAL_ITEM_IDS.indexOf(KillstreakManager.KEY_C4);
        owner.getInventory().addItem(plugin.getKillstreakManager().createSpecificSpecialItem(c4Index));

        owner.playSound(Sound.sound(org.bukkit.Sound.BLOCK_STONE_BREAK, Sound.Source.MASTER, 1.0f, 1.4f));
        if (chargeLoc.getWorld() != null) {
            chargeLoc.getWorld().spawnParticle(Particle.SMOKE, chargeLoc.clone().add(0.5, 0.5, 0.5),
                    12, 0.2, 0.2, 0.2, 0.02);
        }

        if (charges.isEmpty()) {
            c4Charges.remove(owner.getUniqueId());
            removeDetonator(owner);
            owner.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<green>[OSOK] 💥 C4 wieder aufgenommen. <gray>Es war deine letzte Ladung - der Fernzünder ist weg.</gray></green>"));
        } else {
            owner.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<green>[OSOK] 💥 C4 wieder aufgenommen. <gray>Noch <yellow>" + charges.size()
                            + "</yellow> Ladung(en) platziert.</gray></green>"));
        }
    }

    /**
     * Gehoert die Ladung diesem Spieler?
     * <p>
     * Die Zuordnung steckt zusaetzlich im PersistentDataContainer der Ladung selbst und nicht
     * nur in {@code c4Charges}. Damit haengt die Regel "nur der Platzierer darf aufheben" am
     * Objekt und nicht allein am Nachschlagepfad - eine falsch einsortierte Ladung koennte
     * sonst von jemand anderem aufgenommen werden.
     */
    private boolean isOwnedBy(BlockDisplay charge, Player player) {
        String ownerId = charge.getPersistentDataContainer().get(KEY_OWNER, PersistentDataType.STRING);
        return ownerId != null && ownerId.equals(player.getUniqueId().toString());
    }

    private boolean isSameBlock(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private boolean isC4Item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String type = stack.getPersistentDataContainer()
                .get(plugin.getKillstreakManager().getSpecialItemKey(), PersistentDataType.STRING);
        return KillstreakManager.KEY_C4.equals(type);
    }

    private boolean isDetonator(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getPersistentDataContainer().has(KEY_DETONATOR, PersistentDataType.BYTE);
    }

    /** Nimmt dem Spieler den Fernzuender ab - er ist ohne platzierte Ladung wirkungslos. */
    private void removeDetonator(Player owner) {
        ItemStack[] contents = owner.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isDetonator(contents[slot])) {
                owner.getInventory().setItem(slot, null);
            }
        }
    }

    /** Fernzünder: zündet alle Ladungen des Spielers. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDetonatorUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!item.getPersistentDataContainer().has(KEY_DETONATOR, PersistentDataType.BYTE)) return;

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
            blast(loc, owner, C4_BLAST_POWER);
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
            String type = stack.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
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
        blastCredits.clear();

        // Ohne Ladung ist der Fernzuender wirkungslos - er darf nicht im Inventar zurueckbleiben
        for (Player online : Bukkit.getOnlinePlayers()) {
            removeDetonator(online);
        }

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
