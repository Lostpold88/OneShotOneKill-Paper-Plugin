package de.oneshotonekill.listener;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.sound.Sound;
import de.oneshotonekill.manager.GlowManager;
import de.oneshotonekill.manager.KillstreakManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SpecialItemListener implements Listener {

    /** Dauer des Radar-Puls Leuchtens (30 Sekunden). */
    private static final long RADAR_GLOW_TICKS = 600L;
    /**
     * Dauer der Vereisung nach dem Ausloesen (7 Sekunden).
     * <p>
     * Eine platzierte Frost-Trap laeuft bewusst <b>nicht</b> von selbst ab - sie liegt, bis
     * jemand hineintritt. Dass sich keine Platten ansammeln, stellt {@link #clearAllTraps()}
     * sicher, das bei Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop laeuft.
     */
    private static final long FROST_TRAP_FREEZE_TICKS = 140L;

    private final OneShotOneKill plugin;
    private final Set<Location> activeBearTraps = new HashSet<>();
    /** Von einer Frost-Trap festgehaltene Spieler. */
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Set<UUID> vanishedPlayers = new HashSet<>();
    /** Zaehler pro Spieler, damit ein neuer Radar-Puls das Leuchten des vorherigen verlaengert. */
    private final Map<UUID, Integer> radarGlowGeneration = new HashMap<>();

    public SpecialItemListener(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    /**
     * Identifiziert Spezial-Items ausschliesslich ueber den PersistentDataContainer.
     * Jedes Spezial-Item erhaelt seinen Typ in {@code KillstreakManager#createSpecialItem} per NamespacedKey,
     * daher sind Anzeigenamen-Vergleiche weder noetig noch zulaessig.
     */
    private String getSpecialItemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        // Paper: PersistentDataContainerView direkt am ItemStack - liest ohne ItemMeta-Kopie.
        // Diese Methode laeuft bei jedem Interact- und Drop-Event, die Kopie war hier messbar teuer.
        return item.getPersistentDataContainer()
                .get(plugin.getKillstreakManager().getSpecialItemKey(), PersistentDataType.STRING);
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player joiner = event.getPlayer();
        for (UUID uuid : vanishedPlayers) {
            Player v = Bukkit.getPlayer(uuid);
            if (v != null && v.isOnline()) {
                joiner.hidePlayer(plugin, v);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Player leaver = event.getPlayer();
        radarGlowGeneration.remove(leaver.getUniqueId());
        frozenPlayers.remove(leaver.getUniqueId());
        revealPlayer(leaver);
    }

    /**
     * Beendet den Unsichtbarkeits-Mantel eines Spielers sofort.
     * <p>
     * Der Mantel haengt nicht am Potion-Effekt, sondern an {@code hidePlayer}. Wuerde er beim
     * Eliminieren oder beim Match-Ende nicht ausdruecklich beendet, bliebe der Spieler bis zum
     * Ablauf seines Timers fuer alle unsichtbar - auch in der Lobby.
     */
    public void revealPlayer(Player player) {
        if (player == null || !vanishedPlayers.remove(player.getUniqueId())) return;

        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(plugin, player);
        }
    }

    /** Beendet alle laufenden Unsichtbarkeiten (Match-Ende, Map-Wechsel, Plugin-Stop). */
    public void clearAllVanish() {
        for (UUID vanishedId : new HashSet<>(vanishedPlayers)) {
            Player vanished = Bukkit.getPlayer(vanishedId);
            if (vanished != null) {
                revealPlayer(vanished);
            }
        }
        vanishedPlayers.clear();
    }

    /**
     * Entfernt alle noch liegenden Frost-Trap-Druckplatten aus der Welt und taut alle
     * eingefrorenen Spieler auf.
     * <p>
     * Wird bei Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop gerufen. Da eine Trap
     * von sich aus nicht mehr verfaellt, ist das die einzige Stelle, an der ungenutzte
     * Platten wieder verschwinden.
     */
    public void clearAllTraps() {
        for (Location trapLoc : new HashSet<>(activeBearTraps)) {
            removeTrapBlock(trapLoc);
        }
        activeBearTraps.clear();

        for (UUID frozenId : new HashSet<>(frozenPlayers)) {
            unfreezePlayer(Bukkit.getPlayer(frozenId));
        }
        frozenPlayers.clear();
    }

    /**
     * Friert einen Spieler tatsaechlich fest.
     * <p>
     * SLOWNESS allein reicht nicht: Der Effekt senkt nur die Laufgeschwindigkeit, ein Sprung
     * trug den Spieler weiterhin mehrere Bloecke weit. Deshalb wird zusaetzlich die Bewegung
     * in {@link #onFrozenMove(PlayerMoveEvent)} unterbunden und die laufende Bewegung sofort
     * auf null gesetzt, damit auch ein bereits begonnener Sprung abbricht.
     */
    private void freezePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        frozenPlayers.add(playerId);

        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) FROST_TRAP_FREEZE_TICKS, 10, false, false));
        player.setFreezeTicks((int) FROST_TRAP_FREEZE_TICKS);
        player.setVelocity(new Vector(0.0, 0.0, 0.0));

        // Paper Entity Scheduler: an den Tick des Spielers gebunden
        player.getScheduler().runDelayed(plugin, task -> {
            if (frozenPlayers.remove(playerId) && player.isOnline()) {
                player.setFreezeTicks(0);
                player.sendMessage(MiniMessage.miniMessage().deserialize(
                        "<aqua>[OSOK] ❄ Du bist wieder aufgetaut.</aqua>"));
                player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_GLASS_BREAK, Sound.Source.MASTER, 0.7f, 1.6f));
            }
        }, null, FROST_TRAP_FREEZE_TICKS);
    }

    /** Hebt die Vereisung sofort auf - bei Eliminierung, Quit und beim Aufraeumen. */
    public void unfreezePlayer(Player player) {
        if (player == null) return;
        if (frozenPlayers.remove(player.getUniqueId()) && player.isOnline()) {
            player.setFreezeTicks(0);
        }
    }

    /**
     * Haelt eingefrorene Spieler an Ort und Stelle.
     * <p>
     * Umsehen bleibt erlaubt - nur die Position wird auf den Stand vor der Bewegung
     * zurueckgesetzt. Ohne das konnte man sich per Sprung aus der Falle heraustragen lassen.
     * <p>
     * Teleports sind nicht betroffen: {@code PlayerTeleportEvent} hat in Paper eine eigene
     * HandlerList, ein {@code PlayerMoveEvent}-Handler sieht sie also gar nicht. Ein Respawn
     * waehrend der Vereisung funktioniert damit normal.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent event) {
        if (frozenPlayers.isEmpty()) return;
        if (!frozenPlayers.contains(event.getPlayer().getUniqueId())) return;
        // Paper: reine Blickrichtungsaenderungen gar nicht erst weiterverarbeiten
        if (!event.hasChangedPosition()) return;

        Location locked = event.getFrom().clone();
        locked.setYaw(event.getTo().getYaw());
        locked.setPitch(event.getTo().getPitch());
        event.setTo(locked);
    }

    /** Nimmt die Druckplatte zurueck, sofern an der Stelle noch eine liegt. */
    private void removeTrapBlock(Location trapLoc) {
        if (trapLoc == null || trapLoc.getWorld() == null) return;

        Block block = trapLoc.getBlock();
        if (Tag.PRESSURE_PLATES.isTagged(block.getType())) {
            block.setType(Material.AIR);
            block.getWorld().spawnParticle(Particle.SNOWFLAKE, trapLoc.clone().add(0.5, 0.2, 0.5),
                    15, 0.2, 0.2, 0.2, 0.05);
        }
    }

    @EventHandler
    public void onItemPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        if (item.getPersistentDataContainer().has(KillstreakManager.KEY_GROUND_SPECIAL_PDC, PersistentDataType.BYTE)) {
            ItemMeta meta = item.getItemStack().hasItemMeta() ? item.getItemStack().getItemMeta() : null;
            Component nameComp = meta != null && meta.hasDisplayName() ? meta.displayName() : Component.text("Spezial-Item");
            
            // Zaehlt fuer die Match-Zusammenfassung - bei eingefrorener Wertung nicht
            if (!plugin.getMatchManager().isStatsPaused()) {
                plugin.getScoreboardManager().addItemsCollected(player.getUniqueId(), 1);
            }

            player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.8f));

            Location loc = item.getLocation();
            loc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 30, 0.3, 0.3, 0.3, 0.1);

            Component msg = MiniMessage.miniMessage().deserialize("<yellow>[OSOK] 🎁 <b>ITEM-BOX GEÖFFNET!</b> <gray>Du hast </gray></yellow>")
                    .append(nameComp)
                    .append(MiniMessage.miniMessage().deserialize("<gray> erhalten!</gray>"));
            player.sendMessage(msg);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (getSpecialItemType(item) != null) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Spezial-Items können nicht weggeworfen werden!</red>"));
            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Druckplatten Betreten (Physical Action)
        if (event.getAction() == Action.PHYSICAL && event.getClickedBlock() != null) {
            Block block = event.getClickedBlock();
            Location trapLoc = block.getLocation();
            if (activeBearTraps.remove(trapLoc)) {
                freezePlayer(player);
                player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_GLASS_BREAK, Sound.Source.MASTER, 1.0f, 0.5f));
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❄ Du bist in eine Frost-Trap getreten und für 7s eingefroren!</red>"));

                // Nach 7 Sekunden verschwindet die Druckplatte
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> removeTrapBlock(trapLoc),
                        FROST_TRAP_FREEZE_TICKS);
                return;
            }
        }

        if (item == null) return;
        String typeId = getSpecialItemType(item);
        if (typeId == null) return;

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (!plugin.getMatchManager().isMatchStarted() || plugin.getMatchManager().isMatchPaused() || plugin.getMatchManager().isMatchEnded()) {
                event.setCancelled(true);
                if (plugin.getMatchManager().isMatchPaused()) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ⏸ Das Match ist aktuell pausiert!</red>"));
                } else {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Das Spiel wurde noch nicht gestartet! Warte auf /start.</red>"));
                }
                player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
                return;
            }

            if (!plugin.getArenaManager().isInArenaArea(player.getLocation())) {
                event.setCancelled(true);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Spezial-Items können nur innerhalb der Arena genutzt werden!</red>"));
                player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
                return;
            }
            // Radar-Puls
            if (KillstreakManager.KEY_RADAR.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_EVOKER_CAST_SPELL, Sound.Source.MASTER, 1.0f, 1.5f));
                player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 50, 0.5, 0.5, 0.5, 0.1);

                int count = 0;
                for (Player enemy : player.getLocation().getNearbyPlayers(200.0)) {
                    if (!enemy.getUniqueId().equals(player.getUniqueId())) {
                        applyRadarGlow(enemy, RADAR_GLOW_TICKS);
                        count++;
                    }
                }
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ✦ Radar-Puls ausgeführt! <yellow>" + count + "</yellow> Gegner in der Arena für 30s enthüllt!</green>"));
                return;
            }

            // Explosiv-Schuss
            if (KillstreakManager.KEY_EXPLOSIVE.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().addExplosiveShot(player.getUniqueId());
                player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_TNT_PRIMED, Sound.Source.MASTER, 1.0f, 1.2f));
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ★ Explosiv-Schuss geladen! Dein nächster Schuss erzeugt eine große Explosion.</green>"));
                return;
            }

            // Reflektor-Schild
            if (KillstreakManager.KEY_REFLECTOR.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().addShield(player.getUniqueId());
                player.playSound(Sound.sound(org.bukkit.Sound.ITEM_SHIELD_BLOCK, Sound.Source.MASTER, 1.0f, 1.2f));
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] 🛡 Reflektor-Schild ist aktiv! Blockiert deinen nächsten Treffer.</green>"));
                return;
            }

            // Krass Minigun
            if (KillstreakManager.KEY_MINIGUN.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().activateMinigun(player);
                return;
            }

            // Rauchbombe
            if (KillstreakManager.KEY_SMOKE.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                Location currentLoc = player.getLocation();
                currentLoc.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, currentLoc, 200, 1.5, 1.0, 1.5, 0.05);
                currentLoc.getWorld().playSound(Sound.sound(org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH, Sound.Source.MASTER, 1.0f, 0.8f), currentLoc.x(), currentLoc.y(), currentLoc.z());

                Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
                if (randomLoc != null) {
                    player.teleportAsync(randomLoc).thenAccept(success -> {
                        if (success && player.isOnline()) {
                            plugin.getEquipmentManager().giveOneShotEquipment(player);
                            plugin.getScoreboardManager().updateAllScoreboards();
                            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 1.0f, 1.2f));
                        }
                    });
                }
                return;
            }

            // Frost-Trap Platzieren
            if (KillstreakManager.KEY_FROST.equals(typeId) && event.getClickedBlock() != null) {
                event.setCancelled(true);
                Block targetBlock = event.getClickedBlock().getRelative(BlockFace.UP);
                if (targetBlock.getType() == Material.AIR) {
                    targetBlock.setType(Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
                    Location trapLoc = targetBlock.getLocation();
                    activeBearTraps.add(trapLoc);
                    consumeItem(player, item);
                    player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_GLASS_PLACE, Sound.Source.MASTER, 1.0f, 1.0f));
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ❄ Frost-Trap platziert! <gray>(bleibt liegen, bis jemand hineintritt)</gray></green>"));
                }
                return;
            }

            // Teleport-Granate
            if (KillstreakManager.KEY_TELEPORT.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                EnderPearl pearl = player.launchProjectile(EnderPearl.class, player.getEyeLocation().getDirection().multiply(1.8));
                pearl.getPersistentDataContainer().set(KillstreakManager.KEY_TP_GRENADE_PDC, PersistentDataType.BYTE, (byte) 1);
                player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ENDER_PEARL_THROW, Sound.Source.MASTER, 1.0f, 1.0f));
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] 🌀 Teleport-Granate geworfen!</green>"));
                return;
            }

            // Unsichtbarkeits-Mantel (Echter Vanish 15s)
            if (KillstreakManager.KEY_INVISIBILITY.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                vanishedPlayers.add(player.getUniqueId());
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 300, 0, false, false));

                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (!other.equals(player)) {
                        other.hidePlayer(plugin, player);
                    }
                }

                player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_PHANTOM_FLAP, Sound.Source.MASTER, 1.0f, 1.5f));
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ✦ Unsichtbarkeits-Mantel aktiviert! Du bist für 15s komplett unsichtbar (Vanish).</green>"));

                player.getScheduler().runDelayed(plugin, task -> {
                    if (vanishedPlayers.contains(player.getUniqueId())) {
                        revealPlayer(player);
                        if (player.isOnline()) {
                            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ✦ Unsichtbarkeits-Mantel abgelaufen.</red>"));
                        }
                    }
                }, null, 300L); // 15 Sekunden
                return;
            }

            // Pfeil-Magnetfeld
            if (KillstreakManager.KEY_MAGNET.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().activateArrowMagnet(player);
                return;
            }

            // Kettenblitz-Schuss
            if (KillstreakManager.KEY_CHAIN_LIGHTNING.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().addChainLightningShot(player.getUniqueId());
                player.playSound(Sound.sound(org.bukkit.Sound.ITEM_TRIDENT_THUNDER, Sound.Source.MASTER, 1.0f, 1.5f));
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ⚡ Kettenblitz-Schuss geladen! Dein nächster Treffer beschwört Blitze.</green>"));
                return;
            }

            // Air-Strike: oeffnet die Arena-Karte. Verbrauch erst bei der Zielauswahl im Menue.
            if (KillstreakManager.KEY_AIRSTRIKE.equals(typeId)) {
                event.setCancelled(true);
                plugin.getExplosivesManager().openAirStrikeMap(player);
                return;
            }

            // C4: wird auf einen Block platziert, der Fernzuender kommt automatisch dazu
            if (KillstreakManager.KEY_C4.equals(typeId)) {
                event.setCancelled(true);
                if (event.getClickedBlock() == null) {
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 💥 Ziele auf einen Block, um die C4 zu platzieren!</red>"));
                    return;
                }
                if (plugin.getExplosivesManager().placeC4(player, event.getClickedBlock())) {
                    consumeItem(player, item);
                }
                return;
            }

            // Tarnkappenbomber: oeffnet nur die Zielauswahl.
            // Der Verbrauch erfolgt erst bei der Auswahl des Ziels im Menue.
            if (KillstreakManager.KEY_STEALTH_BOMBER.equals(typeId)) {
                event.setCancelled(true);
                plugin.getStealthBomberManager().openTargetMenu(player);
                return;
            }

            // Railgun: feuert sofort einen Hitscan-Strahl, ohne Ladephase
            if (KillstreakManager.KEY_RAILGUN.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getTacticalItemsManager().fireRailgun(player);
                return;
            }

            // Singularitaet: Wurfgeschoss, das beim Einschlag alles zusammenreisst
            if (KillstreakManager.KEY_SINGULARITY.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getTacticalItemsManager().throwSingularity(player);
                return;
            }

            // Gleitflug: Verbrauch erst, wenn wirklich ein Flug startet
            if (KillstreakManager.KEY_GLIDER.equals(typeId)) {
                event.setCancelled(true);
                if (plugin.getTacticalItemsManager().startGlide(player)) {
                    consumeItem(player, item);
                }
                return;
            }

        }
    }

    private void consumeItem(Player player, ItemStack item) {
        item.subtract(1);
    }

    /**
     * Markiert einen Gegner fuer den Radar-Puls.
     * <p>
     * Bewusst ueber das Glow-Flag der Entity statt ueber {@code PotionEffectType.GLOWING}:
     * Ein Potion-Effekt taucht beim Betroffenen immer im Effekt-Fenster des Inventars auf,
     * selbst mit {@code icon=false}. Ohne Potion-Effekt gibt es fuer ihn nichts zu sehen -
     * nur die Gegner sehen den Leuchtrahmen.
     */
    private void applyRadarGlow(Player target, long durationTicks) {
        UUID targetId = target.getUniqueId();
        int generation = radarGlowGeneration.merge(targetId, 1, Integer::sum);
        // Ueber den GlowManager, damit die Anti-Camping-Markierung das Radar-Leuchten
        // nicht versehentlich wieder abschaltet (und umgekehrt)
        plugin.getGlowManager().add(target, GlowManager.GlowReason.RADAR);

        // Paper Entity Scheduler: an den Tick des Ziels gebunden
        target.getScheduler().runDelayed(plugin, task -> {
            // Nur zuruecksetzen, wenn seither kein neuer Radar-Puls das Ziel erfasst hat
            if (radarGlowGeneration.getOrDefault(targetId, 0) == generation) {
                radarGlowGeneration.remove(targetId);
                if (target.isOnline()) {
                    plugin.getGlowManager().remove(target, GlowManager.GlowReason.RADAR);
                }
            }
        }, null, durationTicks);
    }

    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter)) return;

        if (event.getProjectile() instanceof org.bukkit.entity.AbstractArrow arrow) {
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        }

        // Kettenblitz-Schuss
        if (plugin.getKillstreakManager().hasChainLightningShot(shooter.getUniqueId())) {
            plugin.getKillstreakManager().removeChainLightningShot(shooter.getUniqueId());
            event.getProjectile().getPersistentDataContainer().set(KillstreakManager.KEY_CHAIN_LIGHTNING_PDC, PersistentDataType.BYTE, (byte) 1);
            return;
        }

        // Explosiv-Schuss
        if (plugin.getKillstreakManager().hasExplosiveShot(shooter.getUniqueId())) {
            plugin.getKillstreakManager().removeExplosiveShot(shooter.getUniqueId());
            event.getProjectile().getPersistentDataContainer().set(KillstreakManager.KEY_EXPLOSIVE_PDC, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        // Teleport-Granate Einschlag
        if (event.getEntity() instanceof EnderPearl pearl && pearl.getPersistentDataContainer().has(KillstreakManager.KEY_TP_GRENADE_PDC, PersistentDataType.BYTE)) {
            Location loc = pearl.getLocation();
            loc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2);
            loc.getWorld().playSound(Sound.sound(org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, Sound.Source.MASTER, 1.0f, 1.5f), loc.x(), loc.y(), loc.z());

            if (pearl.getShooter() instanceof Player shooter) {
                // Paper Spatial Entity Index: direkte Spieler-Abfrage statt Entity-Box + instanceof
                for (Player victim : loc.getNearbyPlayers(5.0)) {
                    if (!victim.equals(shooter)) {
                        org.bukkit.util.Vector push = victim.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(1.5).setY(0.5);
                        victim.setVelocity(push);
                        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2));
                        victim.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 🌀 Du wurdest von einer Teleport-Granate weggeschleudert!</red>"));
                    }
                }
            }
        }

        // Kettenblitz-Pfeil
        if (event.getEntity() instanceof Arrow arrow && arrow.getPersistentDataContainer().has(KillstreakManager.KEY_CHAIN_LIGHTNING_PDC, PersistentDataType.BYTE)) {
            Location loc = arrow.getLocation();
            loc.getWorld().strikeLightningEffect(loc);

            if (arrow.getShooter() instanceof Player shooter) {
                int chained = 0;
                for (Player victim : loc.getNearbyPlayers(8.0)) {
                    if (!victim.getUniqueId().equals(shooter.getUniqueId())) {
                        plugin.getEliminationManager().eliminate(victim, shooter);
                        victim.getWorld().strikeLightningEffect(victim.getLocation());
                        chained++;
                        if (chained >= 2) break;
                    }
                }
            }
        }

        // Explosiv-Pfeil
        if (event.getEntity() instanceof Arrow arrow && arrow.getPersistentDataContainer().has(KillstreakManager.KEY_EXPLOSIVE_PDC, PersistentDataType.BYTE)) {
            Location loc = arrow.getLocation();
            loc.getWorld().createExplosion(loc, 0.0f, false, false);
            loc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3);
            loc.getWorld().playSound(Sound.sound(org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, Sound.Source.MASTER, 1.0f, 1.0f), loc.x(), loc.y(), loc.z());

            if (arrow.getShooter() instanceof Player shooter) {
                for (Player victim : loc.getNearbyPlayers(7.0)) {
                    if (!victim.getUniqueId().equals(shooter.getUniqueId())) {
                        plugin.getEliminationManager().eliminate(victim, shooter);
                    }
                }
            }
        }
    }
}
