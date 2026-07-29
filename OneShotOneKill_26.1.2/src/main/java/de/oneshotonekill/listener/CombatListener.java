package de.oneshotonekill.listener;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillstreakManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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

        if (!plugin.getMatchManager().isMatchStarted() || plugin.getMatchManager().isMatchPaused() || plugin.getMatchManager().isMatchEnded()) {
            event.setCancelled(true);
            Player damager = null;
            if (event.getDamager() instanceof Player attacker) {
                damager = attacker;
            } else if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player shooter) {
                damager = shooter;
            }
            if (damager != null) {
                if (plugin.getMatchManager().isMatchPaused()) {
                    damager.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ⏸ Das Match ist aktuell pausiert! Kämpfen ist deaktiviert.</red>"));
                } else if (!plugin.getMatchManager().isMatchStarted()) {
                    damager.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Das Spiel wurde noch nicht gestartet! Kämpfen ist deaktiviert. Warte auf /start.</red>"));
                }
                damager.playSound(damager.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 1.0f, 1.0f);
            }
            return;
        }

        Player damager = null;
        boolean isMelee = false;

        if (event.getDamager() instanceof Player attacker) {
            damager = attacker;
            isMelee = true;
        } else if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player shooter) {
            damager = shooter;
        }

        if (damager != null) {
            // Prüfung: Nahkampfschlag -> NUR mit dem Eisenschwert (OneShot Dolch)! Andere Items (Wolle, Bogen, Fäuste etc.) verursachen normalen Schaden.
            if (isMelee) {
                ItemStack mainHand = damager.getInventory().getItemInMainHand();
                if (mainHand == null || mainHand.getType() != Material.IRON_SWORD) {
                    return; // Normaler Faust-, Blöcke- oder Bogenschlagschaden
                }
            }

            // Reflektor-Schild Prüfung
            if (plugin.getKillstreakManager().hasShield(target.getUniqueId())) {
                plugin.getKillstreakManager().removeShield(target.getUniqueId());
                event.setCancelled(true);
                target.playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, SoundCategory.MASTER, 1.0f, 1.0f);
                target.sendMessage(MiniMessage.miniMessage().deserialize("<aqua>[OSOK] [🛡] Dein Reflektor-Schild hat den tödlichen Treffer abgewehrt!</aqua>"));
                damager.playSound(damager.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.MASTER, 1.0f, 0.8f);
                damager.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] [🛡] Treffer abgeprallt! " + target.getName() + " hatte ein Reflektor-Schild!</red>"));
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

        // Kopfgeld-Prüfung & Auszahlung vor Stats-Reset
        boolean wasBounty = plugin.getScoreboardManager().removeBountyTarget(victim.getUniqueId());

        // Deaths erhöhen
        int d = plugin.getScoreboardManager().addDeath(victim.getUniqueId());
        plugin.getScoreboardManager().resetStreak(victim.getUniqueId());

        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            int k = plugin.getScoreboardManager().addKill(killer.getUniqueId());
            int s = plugin.getScoreboardManager().addStreak(killer.getUniqueId());

            // Standard Blut-Splash Killeffekt beim Opfer abspielen
            plugin.getKillEffectManager().playKillEffect(victim.getLocation());

            killer.playSound(killer.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.MASTER, 1.0f, 1.2f);
            killer.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] Du hast <yellow>" + victim.getName() + "</yellow> eliminiert! <gray>(Streak: <yellow>" + s + "</yellow>)</gray></green>"));

            // Kopfgeld Belohnung: 2 Spezial-Items für den Killer!
            if (wasBounty) {
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, 0);
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, 0);
                killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 1.0f, 1.5f);
                Component bcMsg = MiniMessage.miniMessage().deserialize("<green>[OSOK] 💰 KOPFGELD KASSIERT! <white>" + killer.getName() + "</white> <gray>hat das Kopfgeld auf <yellow>" + victim.getName() + "</yellow> geholt und 2 Spezial-Items kassiert!</gray></green>");
                Bukkit.broadcast(bcMsg);
            }

            // Alle 3er Streaks zufälliges Spezial-Item verleihen (im STREAK- oder BOTH-Modus)
            KillstreakManager.ItemMode mode = plugin.getKillstreakManager().getItemMode();
            if (s > 0 && s % 3 == 0 && (mode == KillstreakManager.ItemMode.STREAK || mode == KillstreakManager.ItemMode.BOTH)) {
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, s);
            }

            event.deathMessage(MiniMessage.miniMessage().deserialize("<red>🎯 " + victim.getName() + " <gray>wurde von <yellow>" + killer.getName() + "</yellow> ausgeschaltet!</gray></red>"));

            // Prüfen, ob dieser Kill den Match-Sieg auslöst
            plugin.getMatchManager().checkKillWinner(killer, k);
        } else {
            event.deathMessage(MiniMessage.miniMessage().deserialize("<red>☠ " + victim.getName() + " <gray>ist gestorben.</gray></red>"));
        }

        // Live-Scoreboard und Tab-Liste für ALLE Online-Spieler aktualisieren
        plugin.getScoreboardManager().updateAllScoreboards();

        // Paper Native Entity Scheduler: Sofortiger Auto-Respawn ohne Wiederbeleben-Button!
        victim.getScheduler().runDelayed(plugin, task -> {
            if (victim.isOnline() && victim.isDead()) {
                victim.spigot().respawn();
            }
        }, null, 1L);
    }

    @EventHandler
    public void onCreatureSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
        }
    }
}
