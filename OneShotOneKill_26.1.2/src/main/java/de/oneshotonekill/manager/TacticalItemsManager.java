package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.model.MapConfig;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Drei taktische Spezial-Items, die sich Infrastruktur teilen (Partikelstrahl, Aufraeumen,
 * Arena-Grenzpruefung):
 * <ul>
 *   <li><b>🔭 Railgun</b> - Hitscan-Schuss per {@code World#rayTrace}. Sichtlinie = Kill.</li>
 *   <li><b>🕳 Singularitaet</b> - Wurfgeschoss, das 4s lang alle Spieler zum Zentrum zieht.</li>
 *   <li><b>🦅 Gleitflug</b> - 8s Elytra-Flug mit Schubstoessen, gedeckelt durch die Map-Decke.</li>
 * </ul>
 * Alle drei sind ueber {@link #clearAll()} restlos zurueckzunehmen, damit Match-Ende,
 * Map-Wechsel und Plugin-Stop keine Reste hinterlassen.
 */
public class TacticalItemsManager implements Listener {

    // ---------------- Railgun ----------------
    /** Reichweite des Strahls in Bloecken. */
    private static final double RAILGUN_RANGE = 64.0;
    /** Ladezeit, in der das Ziel den Zielstrahl sieht und ausweichen kann. */
    private static final int RAILGUN_CHARGE_TICKS = 20;
    /** Trefferradius des Strahls - etwas grosszuegiger als ein Pixelstrahl. */
    private static final double RAILGUN_RAY_SIZE = 0.6;

    // ---------------- Singularitaet ----------------
    private static final int SINGULARITY_DURATION_TICKS = 80;
    private static final long SINGULARITY_PERIOD_TICKS = 2L;
    private static final double SINGULARITY_RADIUS = 8.0;
    /** Grundstaerke des Sogs pro Impuls. */
    private static final double SINGULARITY_PULL = 0.75;

    // ---------------- Gleitflug ----------------
    private static final int GLIDE_DURATION_TICKS = 160;
    private static final long GLIDE_PERIOD_TICKS = 2L;
    /** Jeder wievielte Durchlauf einen Schubstoss gibt (alle 6 Ticks). */
    private static final int GLIDE_BOOST_EVERY = 3;
    private static final double GLIDE_BOOST = 0.28;
    /** Obergrenze der Fluggeschwindigkeit, damit niemand aus der Arena schiesst. */
    private static final double GLIDE_MAX_SPEED = 1.7;
    /** Spielraum ueber der Arena-Oberkante, den der Gleitflug nutzen darf. */
    private static final double GLIDE_HEADROOM = 8.0;

    private static final NamespacedKey KEY_SINGULARITY_ORB = new NamespacedKey("oneshotonekill", "singularity_orb");
    private static final NamespacedKey KEY_GLIDER_WINGS = new NamespacedKey("oneshotonekill", "glider_wings");

    private final OneShotOneKill plugin;
    /** Laufende Singularitaeten, damit sie beim Aufraeumen abgebrochen werden koennen. */
    private final Set<ScheduledTask> activeSingularities = new HashSet<>();
    private final Set<UUID> activeGliders = new HashSet<>();
    private final Set<UUID> chargingRailguns = new HashSet<>();

    public TacticalItemsManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    // ==================================================================
    // 🔭 Railgun
    // ==================================================================

    /**
     * Startet die Ladephase. Waehrend der Ladezeit zeichnet ein Partikelstrahl die aktuelle
     * Blickrichtung nach - der Schuetze darf weiter zielen, das Ziel kann aber ausweichen.
     *
     * @return {@code false}, wenn bereits ein Schuss geladen wird (Item nicht verbrauchen)
     */
    public boolean chargeRailgun(Player shooter) {
        UUID shooterId = shooter.getUniqueId();
        if (!chargingRailguns.add(shooterId)) {
            shooter.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>[OSOK] 🔭 Deine Railgun lädt bereits!</red>"));
            shooter.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            return false;
        }

        shooter.playSound(Sound.sound(org.bukkit.Sound.ITEM_SPYGLASS_USE, Sound.Source.MASTER, 1.0f, 0.7f));
        shooter.playSound(Sound.sound(org.bukkit.Sound.BLOCK_CONDUIT_ACTIVATE, Sound.Source.MASTER, 0.7f, 1.6f));
        shooter.sendMessage(MiniMessage.miniMessage().deserialize(
                "<yellow>[OSOK] 🔭 <b>Railgun lädt…</b> <gray>Halte dein Ziel im Visier!</gray></yellow>"));

        // Paper Entity Scheduler: an den Tick des Schuetzen gebunden
        shooter.getScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int elapsed = 0;

            @Override
            public void accept(ScheduledTask task) {
                if (!shooter.isOnline() || !isCombatActive(shooter)) {
                    task.cancel();
                    chargingRailguns.remove(shooterId);
                    return;
                }

                if (elapsed >= RAILGUN_CHARGE_TICKS) {
                    task.cancel();
                    chargingRailguns.remove(shooterId);
                    fireRailgun(shooter);
                    return;
                }

                // Vorwarnung: Zielstrahl fuer alle sichtbar
                drawBeam(shooter.getEyeLocation(), aimEnd(shooter), Particle.SMALL_FLAME, 1.0);
                shooter.playSound(Sound.sound(org.bukkit.Sound.BLOCK_BEACON_POWER_SELECT, Sound.Source.MASTER,
                        0.35f, 1.2f + elapsed * 0.04f));
                elapsed += 2;
            }
        }, null, 1L, 2L);

        return true;
    }

    /**
     * Feuert den Strahl ab. {@code World#rayTrace} liefert in <b>einem</b> Aufruf den naechsten
     * Treffer - egal ob Block oder Spieler. Damit blockt eine Wand den Schuss zuverlaessig,
     * ohne dass Block- und Entity-Raytrace von Hand verglichen werden muessen.
     */
    private void fireRailgun(Player shooter) {
        World world = shooter.getWorld();
        Location eye = shooter.getEyeLocation();
        Vector direction = eye.getDirection();
        UUID shooterId = shooter.getUniqueId();

        RayTraceResult result = world.rayTrace(
                eye, direction, RAILGUN_RANGE,
                FluidCollisionMode.NEVER,
                true,                       // durchlaessige Bloecke (Gras, Scheiben) ignorieren
                RAILGUN_RAY_SIZE,
                entity -> entity instanceof Player other
                        && !other.getUniqueId().equals(shooterId)
                        && plugin.getArenaManager().isInArenaArea(other.getLocation()));

        Location impact = (result != null)
                ? result.getHitPosition().toLocation(world)
                : eye.clone().add(direction.clone().multiply(RAILGUN_RANGE));

        drawBeam(eye, impact, Particle.ELECTRIC_SPARK, 0.4);
        drawBeam(eye, impact, Particle.END_ROD, 0.8);
        world.spawnParticle(Particle.FLASH, impact, 1);
        world.playSound(Sound.sound(org.bukkit.Sound.ITEM_TRIDENT_THUNDER, Sound.Source.MASTER, 1.0f, 1.4f),
                eye.x(), eye.y(), eye.z());
        world.playSound(Sound.sound(org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_IMPACT, Sound.Source.MASTER, 0.8f, 1.8f),
                impact.x(), impact.y(), impact.z());

        if (result != null && result.getHitEntity() instanceof Player victim) {
            shooter.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<green>[OSOK] 🔭 <b>VOLLTREFFER!</b> <gray>Die Railgun hat <yellow>"
                            + victim.getName() + "</yellow> durchschlagen.</gray></green>"));
            plugin.getEliminationManager().eliminate(victim, shooter);
        } else {
            shooter.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>[OSOK] 🔭 Fehlschuss! Der Strahl hat kein Ziel getroffen.</red>"));
            shooter.playSound(Sound.sound(org.bukkit.Sound.ITEM_SPYGLASS_STOP_USING, Sound.Source.MASTER, 1.0f, 0.8f));
        }
    }

    /** Endpunkt des Zielstrahls waehrend der Ladephase - stoppt an der ersten Wand. */
    private Location aimEnd(Player shooter) {
        Location eye = shooter.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult blocked = shooter.getWorld().rayTraceBlocks(
                eye, direction, RAILGUN_RANGE, FluidCollisionMode.NEVER, true);
        return (blocked != null)
                ? blocked.getHitPosition().toLocation(shooter.getWorld())
                : eye.clone().add(direction.multiply(RAILGUN_RANGE));
    }

    /** Zeichnet eine Partikellinie zwischen zwei Punkten. */
    private void drawBeam(Location from, Location to, Particle particle, double step) {
        World world = from.getWorld();
        if (world == null || to.getWorld() == null || !world.equals(to.getWorld())) return;

        Vector path = to.toVector().subtract(from.toVector());
        double length = path.length();
        if (length < 0.1) return;

        Vector unit = path.normalize().multiply(step);
        Location cursor = from.clone();
        for (double travelled = 0; travelled < length; travelled += step) {
            world.spawnParticle(particle, cursor, 1, 0.0, 0.0, 0.0, 0.0);
            cursor.add(unit);
        }
    }

    // ==================================================================
    // 🕳 Singularitaet
    // ==================================================================

    /** Wirft die Singularitaet als Geschoss. Gezuendet wird beim Einschlag. */
    public void throwSingularity(Player player) {
        Snowball orb = player.launchProjectile(Snowball.class,
                player.getEyeLocation().getDirection().multiply(1.4));
        // Optik des Geschosses: Echo-Scherbe statt Schneeball
        orb.setItem(ItemStack.of(Material.ECHO_SHARD));
        orb.getPersistentDataContainer().set(KEY_SINGULARITY_ORB, PersistentDataType.BYTE, (byte) 1);

        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, Sound.Source.MASTER, 0.8f, 1.4f));
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<dark_purple>[OSOK] 🕳 Singularität geworfen! <gray>Sie reißt alles in ihrer Nähe zusammen.</gray></dark_purple>"));
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball orb)) return;
        if (!orb.getPersistentDataContainer().has(KEY_SINGULARITY_ORB, PersistentDataType.BYTE)) return;

        Player owner = (orb.getShooter() instanceof Player shooter) ? shooter : null;
        openSingularity(orb.getLocation().clone(), owner);
    }

    /**
     * Oeffnet die Singularitaet: 4 Sekunden Sog auf alle Spieler im Umkreis.
     * <p>
     * Der Sog trifft bewusst <b>jeden</b> - auch den Werfer. Die Singularitaet richtet keinen
     * Schaden an; sie ist ein Aufbau-Item fuer Air-Strike, C4 und Bomber.
     */
    private void openSingularity(Location center, Player owner) {
        World world = center.getWorld();
        if (world == null) return;

        world.playSound(Sound.sound(org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, Sound.Source.MASTER, 1.0f, 1.3f),
                center.x(), center.y(), center.z());
        if (owner != null) {
            Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                    "<dark_purple>[OSOK] 🕳 <white>" + owner.getName()
                            + "</white> hat eine <b>SINGULARITÄT</b> geöffnet!</dark_purple>"));
        }

        ScheduledTask task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int ticksLeft = SINGULARITY_DURATION_TICKS;

            @Override
            public void accept(ScheduledTask self) {
                if (ticksLeft <= 0) {
                    self.cancel();
                    activeSingularities.remove(self);
                    collapse(center);
                    return;
                }

                drawVortex(center, ticksLeft);

                // Paper Spatial Entity Index: direkte Spieler-Abfrage statt Entity-Box + instanceof
                for (Player pulled : center.getNearbyPlayers(SINGULARITY_RADIUS)) {
                    if (!plugin.getArenaManager().isInArenaArea(pulled.getLocation())) continue;
                    applyPull(pulled, center);
                }

                ticksLeft -= SINGULARITY_PERIOD_TICKS;
            }
        }, 1L, SINGULARITY_PERIOD_TICKS);

        activeSingularities.add(task);
    }

    private void applyPull(Player player, Location center) {
        Vector delta = center.toVector().subtract(player.getLocation().toVector());
        double distance = delta.length();
        if (distance < 0.6) {
            player.setVelocity(new Vector(0.0, 0.22, 0.0));
            return;
        }

        // Naeher am Zentrum zieht es staerker, aber auch am Rand bleibt ein spuerbarer Sog
        double strength = SINGULARITY_PULL * (0.45 + 0.55 * (1.0 - Math.min(distance / SINGULARITY_RADIUS, 1.0)));
        Vector pull = delta.normalize().multiply(strength);
        // Leichter Auftrieb, damit die Bodenreibung den Sog nicht auffrisst
        pull.setY(Math.max(pull.getY(), 0.14));

        player.setVelocity(player.getVelocity().multiply(0.55).add(pull));
    }

    private void drawVortex(Location center, int ticksLeft) {
        World world = center.getWorld();
        if (world == null) return;

        double phase = (SINGULARITY_DURATION_TICKS - ticksLeft) * 0.35;
        double radius = SINGULARITY_RADIUS * (0.35 + 0.65 * (ticksLeft / (double) SINGULARITY_DURATION_TICKS));

        for (int arm = 0; arm < 4; arm++) {
            double angle = phase + arm * (Math.PI / 2.0);
            world.spawnParticle(Particle.REVERSE_PORTAL,
                    center.getX() + Math.cos(angle) * radius,
                    center.getY() + 0.6,
                    center.getZ() + Math.sin(angle) * radius,
                    3, 0.1, 0.4, 0.1, 0.02);
        }
        world.spawnParticle(Particle.SCULK_SOUL, center, 4, 0.3, 0.3, 0.3, 0.01);
        world.spawnParticle(Particle.PORTAL, center, 12, 0.4, 0.4, 0.4, 0.6);
    }

    private void collapse(Location center) {
        World world = center.getWorld();
        if (world == null) return;

        world.spawnParticle(Particle.SONIC_BOOM, center, 1);
        world.spawnParticle(Particle.SCULK_SOUL, center, 40, 0.2, 0.2, 0.2, 0.25);
        world.playSound(Sound.sound(org.bukkit.Sound.BLOCK_CONDUIT_DEACTIVATE, Sound.Source.MASTER, 1.0f, 0.6f),
                center.x(), center.y(), center.z());
    }

    // ==================================================================
    // 🦅 Gleitflug
    // ==================================================================

    /**
     * Startet den Gleitflug: Schwingen in den Brustslot, Startschub, danach 8 Sekunden
     * Gleiten mit regelmaessigen Schubstoessen.
     *
     * @return {@code false}, wenn bereits ein Gleitflug laeuft (Item nicht verbrauchen)
     */
    public boolean startGlide(Player player) {
        UUID playerId = player.getUniqueId();
        if (!activeGliders.add(playerId)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>[OSOK] 🦅 Dein Gleitflug läuft bereits!</red>"));
            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            return false;
        }

        player.getInventory().setChestplate(createGliderWings());
        player.setVelocity(player.getLocation().getDirection().normalize().multiply(0.7).setY(1.15));
        player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, Sound.Source.MASTER, 1.0f, 1.2f));
        player.sendMessage(MiniMessage.miniMessage().deserialize(
                "<aqua>[OSOK] 🦅 <b>GLEITFLUG AKTIV!</b> <gray>8 Sekunden - schau in die Richtung, in die du willst.</gray></aqua>"));

        // Paper Entity Scheduler: an den Tick des Spielers gebunden
        player.getScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int ticksLeft = GLIDE_DURATION_TICKS;
            int runs = 0;
            /** setGliding wurde angestossen. */
            boolean launched = false;
            /** Der Server hat den Gleitflug bestaetigt - erst danach zaehlt eine Landung. */
            boolean airborne = false;

            @Override
            public void accept(ScheduledTask task) {
                if (!player.isOnline() || !activeGliders.contains(playerId) || ticksLeft <= 0) {
                    task.cancel();
                    stopGlide(player, true);
                    return;
                }

                // Erst nach dem Startschub gleiten - sonst faellt der Spieler sofort wieder
                if (!launched && runs >= 2) {
                    player.setGliding(true);
                    launched = true;
                }

                boolean gliding = player.isGliding();
                if (launched && gliding) {
                    airborne = true;
                }

                // Landung: Der Flug endet sofort, nicht erst nach Ablauf der acht Sekunden.
                // Ohne diese Pruefung liefen Partikel und Flugsound am Boden weiter.
                if (airborne && !gliding) {
                    task.cancel();
                    stopGlide(player, true);
                    return;
                }

                if (gliding && runs % GLIDE_BOOST_EVERY == 0) {
                    applyGlideBoost(player);
                }

                Location trail = player.getLocation();
                trail.getWorld().spawnParticle(Particle.END_ROD, trail, 2, 0.2, 0.2, 0.2, 0.01);

                runs++;
                ticksLeft -= GLIDE_PERIOD_TICKS;
            }
        }, null, 2L, GLIDE_PERIOD_TICKS);

        return true;
    }

    /**
     * Schubstoss nach vorn, gedeckelt durch Hoechstgeschwindigkeit und Flughoehe.
     * <p>
     * Die Hoehenbegrenzung ist Pflicht: Auf der offenen DustPvP-Map koennte der Spieler sonst
     * ueber die Arena-Oberkante hinaussteigen - und ausserhalb der Arena ist jeder Kampf
     * deaktiviert.
     */
    private void applyGlideBoost(Player player) {
        MapConfig map = plugin.getWorldManager().getActiveMapConfig();
        Vector velocity = player.getVelocity().add(player.getLocation().getDirection().normalize().multiply(GLIDE_BOOST));

        if (velocity.length() > GLIDE_MAX_SPEED) {
            velocity = velocity.normalize().multiply(GLIDE_MAX_SPEED);
        }

        if (map != null) {
            double ceiling = Math.min(map.getMaxFlyY(), map.getMaxY() + GLIDE_HEADROOM);
            if (player.getLocation().getY() >= ceiling && velocity.getY() > 0.0) {
                velocity.setY(-0.2);
            }
        }

        player.setVelocity(velocity);
    }

    /**
     * Beendet einen laufenden Gleitflug. Mehrfachaufrufe sind unschaedlich.
     * <p>
     * Der Flugsound wird ausdruecklich per {@code stopSound} abgewuergt. Der Client spielt
     * {@code item.elytra.flying} als eigene, laufende Soundinstanz, solange er den Spieler fuer
     * gleitend haelt - ein blosses {@code setGliding(false)} liess ihn noch sekundenlang
     * nachklingen, sowohl bei der Landung als auch nach Ablauf der acht Sekunden.
     */
    public void stopGlide(Player player, boolean notify) {
        if (player == null || !activeGliders.remove(player.getUniqueId())) return;
        if (!player.isOnline()) return;

        player.setGliding(false);
        removeGliderWings(player);
        // Ohne Quellenangabe: stoppt den Flugsound auf jeder Sound-Kategorie
        player.stopSound(SoundStop.named(org.bukkit.Sound.ITEM_ELYTRA_FLYING));
        // Sanfte Landung: Sturzschaden waere in der Arena toedlich
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, false, false));

        if (notify) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>[OSOK] 🦅 Gleitflug beendet.</red>"));
            // Kurzer, abschliessender Ton statt einer weiteren Elytra-Schleife
            player.playSound(Sound.sound(org.bukkit.Sound.ITEM_ARMOR_EQUIP_ELYTRA, Sound.Source.MASTER, 0.8f, 0.8f));
        }
    }

    /**
     * Schwingen des Gleitflugs. Das {@code GLIDER}-Datenkomponent wird bewusst <b>explizit</b>
     * gesetzt, damit das Flugverhalten nicht von der Standardbelegung des Materials abhaengt.
     */
    private ItemStack createGliderWings() {
        ItemStack wings = ItemStack.of(Material.ELYTRA);
        wings.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize("<aqua><b>🦅 Gleitflug-Schwingen</b></aqua>"));
            meta.lore(List.of(MiniMessage.miniMessage().deserialize("<gray>Verschwinden am Ende des Fluges.</gray>")));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(KEY_GLIDER_WINGS, PersistentDataType.BYTE, (byte) 1);
        });
        // Paper Data Components: Gleit-Faehigkeit und Glanz direkt am Item
        wings.setData(DataComponentTypes.GLIDER);
        wings.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return wings;
    }

    /** Entfernt die Schwingen aus dem gesamten Inventar - auch wenn sie umgelagert wurden. */
    private void removeGliderWings(Player player) {
        if (isGliderWings(player.getInventory().getChestplate())) {
            player.getInventory().setChestplate(null);
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isGliderWings(contents[slot])) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private boolean isGliderWings(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getPersistentDataContainer().has(KEY_GLIDER_WINGS, PersistentDataType.BYTE);
    }

    /** Die Schwingen sind Leihgabe: Sie duerfen nicht im Inventar umgelagert werden. */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (isGliderWings(event.getCurrentItem()) || isGliderWings(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    // ==================================================================
    // Aufraeumen
    // ==================================================================

    /**
     * Nimmt alles zurueck: laufende Singularitaeten, Gleitfluege samt Schwingen und
     * ladende Railguns. Wird bei Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop gerufen.
     */
    public void clearAll() {
        for (ScheduledTask task : new HashSet<>(activeSingularities)) {
            task.cancel();
        }
        activeSingularities.clear();
        chargingRailguns.clear();

        for (UUID gliderId : new ArrayList<>(activeGliders)) {
            Player player = Bukkit.getPlayer(gliderId);
            if (player != null) {
                stopGlide(player, false);
            } else {
                activeGliders.remove(gliderId);
            }
        }
        activeGliders.clear();

        // Sicherheitsnetz: Schwingen einsammeln, deren Traeger den Task nie beendet hat
        for (Player online : Bukkit.getOnlinePlayers()) {
            removeGliderWings(online);
        }
    }

    /** Kampf laeuft und der Spieler steht in der Arena. */
    private boolean isCombatActive(Player player) {
        MatchManager match = plugin.getMatchManager();
        return match.isMatchStarted() && !match.isMatchPaused() && !match.isMatchEnded()
                && plugin.getArenaManager().isInArenaArea(player.getLocation());
    }
}
