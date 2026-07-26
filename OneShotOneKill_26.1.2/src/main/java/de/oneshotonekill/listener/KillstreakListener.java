package de.oneshotonekill.listener;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class KillstreakListener implements Listener {

    private final OneShotOneKill plugin;
    private final Set<Location> activeBearTraps = new HashSet<>();
    private final Set<UUID> noFallPlayers = new HashSet<>();
    private final Set<UUID> vanishedPlayers = new HashSet<>();

    public KillstreakListener(OneShotOneKill plugin) {
        this.plugin = plugin;
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
        if (item.hasMetadata("osok_ground_special")) {
            String name = item.getItemStack().hasItemMeta() ? item.getItemStack().getItemMeta().getDisplayName() : "Spezial-Item";
            
            // Nur noch Bling-Sound!
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.8f);
            
            Location loc = item.getLocation();
            loc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 30, 0.3, 0.3, 0.3, 0.1);

            player.sendMessage("§e[OneShot] 🎁 §lITEM-BOX GEÖFFNET! §7Du hast " + name + " §7erhalten!");
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item != null && item.hasItemMeta() && item.getItemMeta().getDisplayName() != null) {
            String name = item.getItemMeta().getDisplayName();
            if (isSpecialItemName(name)) {
                event.setCancelled(true);
                Player player = event.getPlayer();
                player.sendMessage("§c[OneShot] ❌ Spezial-Items können nicht weggeworfen werden!");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 1.0f, 1.0f);
            }
        }
    }

    private boolean isSpecialItemName(String name) {
        return name.contains("Radar-Puls") || name.contains("Explosiv-Schuss") || name.contains("Reflektor-Schild") ||
               name.contains("Rauchbombe") || name.contains("Frost-Trap") || name.contains("Bärenfalle") || name.contains("Minigun") ||
               name.contains("Teleport-Granate") || name.contains("Unsichtbarkeits-Mantel") ||
               name.contains("Pfeil-Magnetfeld") || name.contains("Kettenblitz-Schuss") || name.contains("Raketen-Sprung");
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
                player.sendMessage("§c[OneShot] ❄ Du bist in eine Frost-Trap getreten und für 7s eingefroren!");

                // Nach 7 Sekunden (140 Ticks) verschwindet die Druckplatte
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType() == Material.HEAVY_WEIGHTED_PRESSURE_PLATE || block.getType().name().contains("PRESSURE_PLATE")) {
                        block.setType(Material.AIR);
                        block.getWorld().spawnParticle(Particle.SNOWFLAKE, block.getLocation().add(0.5, 0.2, 0.5), 15, 0.2, 0.2, 0.2, 0.05);
                    }
                }, 140L);
                return;
            }
        }

        if (item == null || item.getItemMeta() == null) return;
        String name = item.getItemMeta().getDisplayName();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            // Radar-Puls
            if (name.contains("Radar-Puls")) {
                event.setCancelled(true);
                consumeItem(player, item);

                player.playSound(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, SoundCategory.MASTER, 1.0f, 1.5f);
                player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 50, 0.5, 0.5, 0.5, 0.1);

                int count = 0;
                for (Player enemy : Bukkit.getOnlinePlayers()) {
                    if (!enemy.getUniqueId().equals(player.getUniqueId()) && enemy.getWorld().equals(player.getWorld())) {
                        if (enemy.getLocation().distance(player.getLocation()) <= 30.0) {
                            enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0));
                            count++;
                        }
                    }
                }
                player.sendMessage("§a[OneShot] ✦ Radar-Puls ausgeführt! §e" + count + " §aGegner geortet!");
                return;
            }

            // Explosiv-Schuss
            if (name.contains("Explosiv-Schuss")) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().addExplosiveShot(player.getUniqueId());
                player.playSound(player.getLocation(), Sound.ENTITY_TNT_PRIMED, SoundCategory.MASTER, 1.0f, 1.2f);
                player.sendMessage("§a[OneShot] ★ Explosiv-Schuss geladen! Dein nächster Schuss erzeugt eine große Explosion.");
                return;
            }

            // Reflektor-Schild
            if (name.contains("Reflektor-Schild")) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().addShield(player.getUniqueId());
                player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.MASTER, 1.0f, 1.2f);
                player.sendMessage("§a[OneShot] 🛡 Reflektor-Schild ist aktiv! Blockiert deinen nächsten Treffer.");
                return;
            }

            // Krass Minigun
            if (name.contains("Minigun")) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().activateMinigun(player);
                return;
            }

            // Rauchbombe
            if (name.contains("Rauchbombe")) {
                event.setCancelled(true);
                consumeItem(player, item);

                Location currentLoc = player.getLocation();
                currentLoc.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, currentLoc, 200, 1.5, 1.0, 1.5, 0.05);
                currentLoc.getWorld().playSound(currentLoc, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.MASTER, 1.0f, 0.8f);

                Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
                if (randomLoc != null) {
                    player.teleport(randomLoc);
                    plugin.getEquipmentManager().giveOneShotEquipment(player);
                    plugin.getScoreboardManager().updateAllScoreboards();
                    player.sendMessage("§a[OneShot] ☁ Rauchbombe gezündet! Ninja-Escape Random-TP ausgeführt!");
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 1.0f, 1.2f);
                }
                return;
            }

            // Frost-Trap Platzieren
            if ((name.contains("Frost-Trap") || name.contains("Bärenfalle")) && event.getClickedBlock() != null) {
                event.setCancelled(true);
                Block targetBlock = event.getClickedBlock().getRelative(BlockFace.UP);
                if (targetBlock.getType() == Material.AIR) {
                    targetBlock.setType(Material.HEAVY_WEIGHTED_PRESSURE_PLATE);
                    activeBearTraps.add(targetBlock.getLocation());
                    consumeItem(player, item);
                    player.playSound(player.getLocation(), Sound.BLOCK_GLASS_PLACE, SoundCategory.MASTER, 1.0f, 1.0f);
                    player.sendMessage("§a[OneShot] ❄ Frost-Trap platziert!");
                }
                return;
            }

            // Teleport-Granate
            if (name.contains("Teleport-Granate")) {
                event.setCancelled(true);
                consumeItem(player, item);

                EnderPearl pearl = player.launchProjectile(EnderPearl.class, player.getEyeLocation().getDirection().multiply(1.8));
                pearl.setMetadata("osok_tp_grenade", new FixedMetadataValue(plugin, true));
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, SoundCategory.MASTER, 1.0f, 1.0f);
                player.sendMessage("§a[OneShot] 🌀 Teleport-Granate geworfen!");
                return;
            }

            // Unsichtbarkeits-Mantel (Echter Vanish 15s)
            if (name.contains("Unsichtbarkeits-Mantel")) {
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
                player.sendMessage("§a[OneShot] ✦ Unsichtbarkeits-Mantel aktiviert! Du bist für 15s komplett unsichtbar (Vanish).");

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (vanishedPlayers.remove(player.getUniqueId())) {
                        if (player.isOnline()) {
                            for (Player other : Bukkit.getOnlinePlayers()) {
                                other.showPlayer(plugin, player);
                            }
                            player.sendMessage("§c[OneShot] ✦ Unsichtbarkeits-Mantel abgelaufen.");
                        }
                    }
                }, 300L); // 15 Sekunden
                return;
            }

            // Pfeil-Magnetfeld
            if (name.contains("Pfeil-Magnetfeld")) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().activateArrowMagnet(player);
                return;
            }

            // Kettenblitz-Schuss
            if (name.contains("Kettenblitz-Schuss")) {
                event.setCancelled(true);
                consumeItem(player, item);

                plugin.getKillstreakManager().addChainLightningShot(player.getUniqueId());
                player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THUNDER, SoundCategory.MASTER, 1.0f, 1.5f);
                player.sendMessage("§a[OneShot] ⚡ Kettenblitz-Schuss geladen! Dein nächster Treffer beschwört Blitze.");
                return;
            }

            // Raketen-Sprung (20 Sekunden Fallschutz & Air-Sprint Geschwindigkeit)
            if (name.contains("Raketen-Sprung")) {
                event.setCancelled(true);
                consumeItem(player, item);

                player.setVelocity(new Vector(0, 1.8, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 2, false, false));
                player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation(), 30, 0.5, 0.5, 0.5, 0.1);
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.MASTER, 1.0f, 1.2f);
                player.sendMessage("§a[OneShot] ★ Raketen-Sprung! Du hast 20 Sekunden Air-Sprint & Fallschutz.");

                noFallPlayers.add(player.getUniqueId());
                Bukkit.getScheduler().runTaskLater(plugin, () -> noFallPlayers.remove(player.getUniqueId()), 400L); // 20 Sekunden
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
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().removeItem(item);
        }
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
            event.getProjectile().setMetadata("osok_chain_lightning", new FixedMetadataValue(plugin, true));
            return;
        }

        // Explosiv-Schuss
        if (plugin.getKillstreakManager().hasExplosiveShot(shooter.getUniqueId())) {
            plugin.getKillstreakManager().removeExplosiveShot(shooter.getUniqueId());
            event.getProjectile().setMetadata("osok_explosive", new FixedMetadataValue(plugin, true));
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        // Teleport-Granate Einschlag
        if (event.getEntity() instanceof EnderPearl pearl && pearl.hasMetadata("osok_tp_grenade")) {
            Location loc = pearl.getLocation();
            loc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2);
            loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 1.0f, 1.5f);

            if (pearl.getShooter() instanceof Player shooter) {
                for (Entity entity : loc.getWorld().getNearbyEntities(loc, 5.0, 5.0, 5.0)) {
                    if (entity instanceof Player victim && !victim.equals(shooter)) {
                        Vector push = victim.getLocation().toVector().subtract(loc.toVector()).normalize().multiply(1.5).setY(0.5);
                        victim.setVelocity(push);
                        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2));
                        victim.sendMessage("§c[OneShot] 🌀 Du wurdest von einer Teleport-Granate weggeschleudert!");
                    }
                }
            }
        }

        // Kettenblitz-Pfeil
        if (event.getEntity() instanceof Arrow arrow && arrow.hasMetadata("osok_chain_lightning")) {
            Location loc = arrow.getLocation();
            loc.getWorld().strikeLightningEffect(loc);

            if (arrow.getShooter() instanceof Player shooter) {
                int chained = 0;
                for (Player victim : Bukkit.getOnlinePlayers()) {
                    if (victim.getWorld().equals(loc.getWorld()) && victim.getLocation().distance(loc) <= 8.0) {
                        if (!victim.getUniqueId().equals(shooter.getUniqueId())) {
                            victim.damage(1000.0, shooter);
                            victim.getWorld().strikeLightningEffect(victim.getLocation());
                            chained++;
                            if (chained >= 2) break;
                        }
                    }
                }
            }
        }

        // Explosiv-Pfeil
        if (event.getEntity() instanceof Arrow arrow && arrow.hasMetadata("osok_explosive")) {
            Location loc = arrow.getLocation();
            loc.getWorld().createExplosion(loc, 0.0f, false, false);
            loc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3);
            loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 1.0f, 1.0f);

            if (arrow.getShooter() instanceof Player shooter) {
                for (Player victim : Bukkit.getOnlinePlayers()) {
                    if (victim.getWorld().equals(loc.getWorld()) && victim.getLocation().distance(loc) <= 7.0) {
                        if (!victim.getUniqueId().equals(shooter.getUniqueId())) {
                            victim.damage(1000.0, shooter);
                        }
                    }
                }
            }
        }
    }
}
