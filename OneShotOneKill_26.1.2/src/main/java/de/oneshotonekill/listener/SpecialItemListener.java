package de.oneshotonekill.listener;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillstreakManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SpecialItemListener implements Listener {

    private final OneShotOneKill plugin;
    private final Set<Location> activeBearTraps = new HashSet<>();
    private final Set<UUID> noFallPlayers = new HashSet<>();
    private final Set<UUID> vanishedPlayers = new HashSet<>();

    public SpecialItemListener(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    private String getSpecialItemType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        String type = meta.getPersistentDataContainer().get(plugin.getKillstreakManager().getSpecialItemKey(), PersistentDataType.STRING);
        if (type != null) return type;

        if (meta.hasDisplayName()) {
            String name = LegacyComponentSerializer.legacySection().serialize(meta.displayName());
            if (name.contains("Radar-Puls")) return KillstreakManager.KEY_RADAR;
            if (name.contains("Explosiv-Schuss")) return KillstreakManager.KEY_EXPLOSIVE;
            if (name.contains("Reflektor-Schild")) return KillstreakManager.KEY_REFLECTOR;
            if (name.contains("Rauchbombe")) return KillstreakManager.KEY_SMOKE;
            if (name.contains("Frost-Trap") || name.contains("Bärenfalle")) return KillstreakManager.KEY_FROST;
            if (name.contains("Minigun")) return KillstreakManager.KEY_MINIGUN;
            if (name.contains("Teleport-Granate")) return KillstreakManager.KEY_TELEPORT;
            if (name.contains("Unsichtbarkeits-Mantel")) return KillstreakManager.KEY_INVISIBILITY;
            if (name.contains("Pfeil-Magnetfeld")) return KillstreakManager.KEY_MAGNET;
            if (name.contains("Kettenblitz-Schuss")) return KillstreakManager.KEY_CHAIN_LIGHTNING;
            if (name.contains("Raketen-Sprung")) return KillstreakManager.KEY_ROCKET_JUMP;
        }
        return null;
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
        if (vanishedPlayers.remove(leaver.getUniqueId())) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                other.showPlayer(plugin, leaver);
            }
        }
    }

    @EventHandler
    public void onItemPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        if (item.getPersistentDataContainer().has(KillstreakManager.KEY_GROUND_SPECIAL_PDC, PersistentDataType.BYTE)) {
            ItemMeta meta = item.getItemStack().hasItemMeta() ? item.getItemStack().getItemMeta() : null;
            Component nameComp = meta != null && meta.hasDisplayName() ? meta.displayName() : Component.text("Spezial-Item");
            
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.8f);
            
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
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Druckplatten Betreten (Physical Action)
        if (event.getAction() == Action.PHYSICAL && event.getClickedBlock() != null) {
            Block block = event.getClickedBlock();
            if (activeBearTraps.remove(block.getLocation())) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 140, 10));
                player.setFreezeTicks(140);
                player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, SoundCategory.MASTER, 1.0f, 0.5f);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❄ Du bist in eine Frost-Trap getreten und für 7s eingefroren!</red>"));

                // Nach 7 Sekunden (140 Ticks) verschwindet die Druckplatte
                Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                    if (block.getType() == Material.HEAVY_WEIGHTED_PRESSURE_PLATE || block.getType().name().contains("PRESSURE_PLATE")) {
                        block.setType(Material.AIR);
                        block.getWorld().spawnParticle(Particle.SNOWFLAKE, block.getLocation().add(0.5, 0.2, 0.5), 15, 0.2, 0.2, 0.2, 0.05);
                    }
                }, 140L);
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
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 1.0f, 1.0f);
                return;
            }

            if (!plugin.getArenaManager().isInArenaArea(player.getLocation())) {
                event.setCancelled(true);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Spezial-Items können nur innerhalb der Arena genutzt werden!</red>"));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 1.0f, 1.0f);
                return;
            }
            // Radar-Puls
            if (KillstreakManager.KEY_RADAR.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                player.playSound(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, SoundCategory.MASTER, 1.0f, 1.5f);
                player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 50, 0.5, 0.5, 0.5, 0.1);

                int count = 0;
                for (Player enemy : player.getLocation().getNearbyPlayers(200.0)) {
                    if (!enemy.getUniqueId().equals(player.getUniqueId())) {
                        // Paper API: particles=false, icon=false -> Das Opfer sieht WEDER Partikel NOCH ein Potion-Icon im HUD!
                        enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 600, 0, false, false, false));
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
                player.playSound(player.getLocation(), Sound.ENTITY_TNT_PRIMED, SoundCategory.MASTER, 1.0f, 1.2f);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ★ Explosiv-Schuss geladen! Dein nächster Schuss erzeugt eine große Explosion.</green>"));
                return;
            }

            // Reflektor-Schild
            if (KillstreakManager.KEY_REFLECTOR.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().addShield(player.getUniqueId());
                player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.MASTER, 1.0f, 1.2f);
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
                currentLoc.getWorld().playSound(currentLoc, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.MASTER, 1.0f, 0.8f);

                Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
                if (randomLoc != null) {
                    player.teleportAsync(randomLoc);
                    plugin.getEquipmentManager().giveOneShotEquipment(player);
                    plugin.getScoreboardManager().updateAllScoreboards();
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 1.0f, 1.2f);
                }
                return;
            }

            // Frost-Trap Platzieren
            if (KillstreakManager.KEY_FROST.equals(typeId) && event.getClickedBlock() != null) {
                event.setCancelled(true);
                Block targetBlock = event.getClickedBlock().getRelative(BlockFace.UP);
                if (targetBlock.getType() == Material.AIR) {
                    targetBlock.setType(Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
                    activeBearTraps.add(targetBlock.getLocation());
                    consumeItem(player, item);
                    player.playSound(player.getLocation(), Sound.BLOCK_GLASS_PLACE, SoundCategory.MASTER, 1.0f, 1.0f);
                    player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ❄ Frost-Trap platziert!</green>"));
                }
                return;
            }

            // Teleport-Granate
            if (KillstreakManager.KEY_TELEPORT.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                EnderPearl pearl = player.launchProjectile(EnderPearl.class, player.getEyeLocation().getDirection().multiply(1.8));
                pearl.getPersistentDataContainer().set(KillstreakManager.KEY_TP_GRENADE_PDC, PersistentDataType.BYTE, (byte) 1);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, SoundCategory.MASTER, 1.0f, 1.0f);
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

                player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, SoundCategory.MASTER, 1.0f, 1.5f);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ✦ Unsichtbarkeits-Mantel aktiviert! Du bist für 15s komplett unsichtbar (Vanish).</green>"));

                player.getScheduler().runDelayed(plugin, task -> {
                    if (vanishedPlayers.remove(player.getUniqueId())) {
                        if (player.isOnline()) {
                            for (Player other : Bukkit.getOnlinePlayers()) {
                                other.showPlayer(plugin, player);
                            }
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
                player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THUNDER, SoundCategory.MASTER, 1.0f, 1.5f);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ⚡ Kettenblitz-Schuss geladen! Dein nächster Treffer beschwört Blitze.</green>"));
                return;
            }

            // Raketen-Sprung (20 Sekunden Fallschutz & Air-Sprint Geschwindigkeit)
            if (KillstreakManager.KEY_ROCKET_JUMP.equals(typeId)) {
                event.setCancelled(true);
                consumeItem(player, item);

                player.setVelocity(new org.bukkit.util.Vector(0, 1.8, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 2, false, false));
                player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation(), 30, 0.5, 0.5, 0.5, 0.1);
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.MASTER, 1.0f, 1.2f);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] ★ Raketen-Sprung! Du hast 20 Sekunden Air-Sprint & Fallschutz.</green>"));

                noFallPlayers.add(player.getUniqueId());
                player.getScheduler().runDelayed(plugin, task -> noFallPlayers.remove(player.getUniqueId()), null, 400L); // 20 Sekunden
                return;
            }
        }
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
            if (noFallPlayers.contains(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    private void consumeItem(Player player, ItemStack item) {
        item.subtract(1);
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
            loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 1.0f, 1.5f);

            if (pearl.getShooter() instanceof Player shooter) {
                for (Entity entity : loc.getWorld().getNearbyEntities(loc, 5.0, 5.0, 5.0)) {
                    if (entity instanceof Player victim && !victim.equals(shooter)) {
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
                        victim.damage(1000.0, shooter);
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
            loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 1.0f, 1.0f);

            if (arrow.getShooter() instanceof Player shooter) {
                for (Player victim : loc.getNearbyPlayers(7.0)) {
                    if (!victim.getUniqueId().equals(shooter.getUniqueId())) {
                        victim.damage(1000.0, shooter);
                    }
                }
            }
        }
    }
}
