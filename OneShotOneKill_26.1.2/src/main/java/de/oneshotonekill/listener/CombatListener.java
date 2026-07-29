package de.oneshotonekill.listener;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillstreakManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import net.kyori.adventure.sound.Sound;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

public class CombatListener implements Listener {

    private final OneShotOneKill plugin;

    public CombatListener(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Außerhalb der Arena ist JEGLICHER Schaden (Sturz, Angriff etc.) verboten
        if (!plugin.getArenaManager().isInArenaArea(player.getLocation())) {
            event.setCancelled(true);
            return;
        }

        // Toedlicher Schaden ohne direkten Nahkampftreffer (Sturz, Explosion) wuerde einen echten
        // Tod ausloesen und damit den Respawn-Ladebildschirm zeigen. Stattdessen regulaer eliminieren.
        if (event.getFinalDamage() >= player.getHealth()) {
            event.setCancelled(true);
            plugin.getEliminationManager().eliminate(player, resolveKiller(event));
        }
    }

    /**
     * Ermittelt den verantwortlichen Spieler fuer toedlichen Schaden, damit z. B. eine
     * Bomber-TNT-Explosion dem Ausloeser als Kill gutgeschrieben wird.
     */
    private Player resolveKiller(EntityDamageEvent event) {
        // Native Bukkit/Paper DamageSource: liefert direkt den ursaechlichen Spieler - beim
        // Pfeil den Schuetzen, beim TNT den per setSource gesetzten Ausloeser. Ersetzt die
        // frueher noetige manuelle Aufloesung ueber Arrow#getShooter und eine PDC-Suche.
        DamageSource source = event.getDamageSource();
        if (source != null && source.getCausingEntity() instanceof Player causing) {
            return causing;
        }

        // Air-Strike und C4 sprengen bewusst ohne Verursacher-Entity, damit auch der
        // Ausloeser Schaden nimmt. Dort gibt es also keine CausingEntity und die
        // Zuordnung liefert der ExplosivesManager.
        if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return plugin.getExplosivesManager().getCurrentBlastOwner();
        }
        return null;
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

        // 1. Match-Status Prüfung (Vor Start, Pause oder nach Ende)
        if (!plugin.getMatchManager().isMatchStarted() || plugin.getMatchManager().isMatchPaused() || plugin.getMatchManager().isMatchEnded()) {
            event.setCancelled(true);
            if (damager != null) {
                if (plugin.getMatchManager().isMatchPaused()) {
                    damager.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ⏸ Das Match ist aktuell pausiert! Kämpfen ist deaktiviert.</red>"));
                } else if (!plugin.getMatchManager().isMatchStarted()) {
                    damager.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Das Spiel wurde noch nicht gestartet! Kämpfen ist deaktiviert. Warte auf /osok start.</red>"));
                }
                damager.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            }
            return;
        }

        // 2. Arena-Bereichs Prüfung (Außerhalb der Arena ist Kämpfen & Töten verboten!)
        boolean targetInArena = plugin.getArenaManager().isInArenaArea(target.getLocation());
        boolean damagerInArena = (damager == null) || plugin.getArenaManager().isInArenaArea(damager.getLocation());

        if (!targetInArena || !damagerInArena) {
            event.setCancelled(true);
            if (damager != null) {
                damager.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] 🛡 Außerhalb der Arena ist Kämpfen deaktiviert!</red>"));
                damager.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            }
            return;
        }

        if (damager != null) {
            // Nahkampfschlag: NUR mit dem Eisenschwert (OneShot Dolch)! Andere Items verursachen normalen Schaden.
            if (isMelee) {
                ItemStack mainHand = damager.getInventory().getItemInMainHand();
                if (mainHand == null || mainHand.getType() != Material.IRON_SWORD) {
                    return; // Normaler Faust-, Blöcke- oder Bogenschlagschaden
                }
            }

            // Das Reflektor-Schild wird zentral im EliminationManager geprueft, damit es
            // auch gegen Explosionen, Kettenblitz und Sturzschaden wirkt.

            // Refill Arrow on Bow Hit
            if (event.getDamager() instanceof Arrow) {
                damager.getInventory().addItem(ItemStack.of(Material.ARROW, 1));
                damager.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.5f));
            }

            // Instant 1-Hit Kill: Schaden wird gecancelt, die Eliminierung uebernimmt der
            // EliminationManager. Ohne echten Tod entfaellt der Respawn-Ladebildschirm.
            event.setCancelled(true);
            plugin.getEliminationManager().eliminate(target, damager);
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

            killer.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ARROW_HIT_PLAYER, Sound.Source.MASTER, 1.0f, 1.2f));
            killer.sendMessage(MiniMessage.miniMessage().deserialize("<green>[OSOK] Du hast <yellow>" + victim.getName() + "</yellow> eliminiert! <gray>(Streak: <yellow>" + s + "</yellow>)</gray></green>"));

            // Kopfgeld Belohnung: 2 Spezial-Items für den Killer!
            if (wasBounty) {
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, 0);
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, 0);
                killer.playSound(Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1.0f, 1.5f));
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

        // Sofortiger Auto-Respawn ohne Wiederbeleben-Button laeuft rein ueber die
        // Paper GameRule DO_IMMEDIATE_RESPAWN (siehe WorldManager#applyPaperGameRules).
    }

    @EventHandler
    public void onCreatureSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        // Natuerliche Spawns unterbinden - vom Plugin selbst gesetzte Entities
        // (z. B. der Tarnkappenbomber-Drache) muessen aber erlaubt bleiben.
        if (event.getSpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
        }
    }
}
