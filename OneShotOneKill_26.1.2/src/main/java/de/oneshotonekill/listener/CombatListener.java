package de.oneshotonekill.listener;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.ItemMode;
import de.oneshotonekill.manager.KillstreakManager;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

public class CombatListener implements Listener {

    private final OneShotOneKill plugin;

    public CombatListener(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)) return;

        Player damager = null;
        boolean isMelee = false;

        if (event.getDamager() instanceof Player attacker) {
            damager = attacker;
            isMelee = true;
        } else if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player shooter) {
            damager = shooter;
        }

        if (damager != null) {
            // Prüfung: Nahkampfschlaf mit leerer Hand oder Bogen -> KEIN One-Shot 1000.0 Schaden! (Dafür ist der Dolch da)
            if (isMelee) {
                ItemStack mainHand = damager.getInventory().getItemInMainHand();
                if (mainHand == null || mainHand.getType() == Material.AIR || mainHand.getType() == Material.BOW) {
                    return; // Normaler Faust-/Bogenschlagschaden
                }
            }

            // Reflektor-Schild Prüfung
            if (plugin.getKillstreakManager().hasShield(target.getUniqueId())) {
                plugin.getKillstreakManager().removeShield(target.getUniqueId());
                event.setCancelled(true);
                target.playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, SoundCategory.MASTER, 1.0f, 1.0f);
                target.sendMessage("§b[OneShot] [🛡] Dein Reflektor-Schild hat den tödlichen Treffer abgewehrt!");
                damager.playSound(damager.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.MASTER, 1.0f, 0.8f);
                damager.sendMessage("§c[OneShot] [🛡] Treffer abgeprallt! " + target.getName() + " hatte ein Reflektor-Schild!");
                return;
            }

            // Instant 1-Hit Kill per Waffe / Pfeil / Item
            event.setDamage(1000.0);

            // Refill Arrow on Bow Hit
            if (event.getDamager() instanceof Arrow) {
                damager.getInventory().addItem(new ItemStack(Material.ARROW, 1));
                damager.playSound(damager.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.5f);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        event.getDrops().clear();
        event.setDroppedExp(0);

        // Deaths erhöhen
        int d = plugin.getScoreboardManager().incrementDeaths(victim.getUniqueId());
        plugin.getScoreboardManager().resetStreak(victim.getUniqueId());

        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            int k = plugin.getScoreboardManager().incrementKills(killer.getUniqueId());
            int s = plugin.getScoreboardManager().incrementStreak(killer.getUniqueId());

            killer.playSound(killer.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.MASTER, 1.0f, 1.2f);
            killer.sendMessage("§a[OneShot] Du hast §e" + victim.getName() + " §aeliminiert! §7(Streak: §e" + s + "§7)");

            // Alle 3er Streaks zufälliges Spezial-Item verleihen (im STREAK- oder BOTH-Modus)
            ItemMode mode = plugin.getKillstreakManager().getItemMode();
            if (s > 0 && s % 3 == 0 && (mode == ItemMode.STREAK || mode == ItemMode.BOTH)) {
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, s);
            }

            event.setDeathMessage("§c🎯 " + victim.getName() + " §7wurde von §e" + killer.getName() + " §7ausgeschaltet!");
        } else {
            event.setDeathMessage("§c☠ " + victim.getName() + " §7ist gestorben.");
        }

        // Live-Scoreboard und Tab-Liste für ALLE Online-Spieler aktualisieren
        plugin.getScoreboardManager().updateAllScoreboards();
    }

    @EventHandler
    public void onCreatureSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
        }
    }
}
