package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Zentrale Eliminierungs-Logik.
 * <p>
 * Ein Treffer toetet den Spieler bewusst <b>nicht</b> im Sinne von Minecraft: Der Schaden wird
 * gecancelt und Statistik, Effekte und Respawn werden hier selbst abgewickelt. Ein echter Tod
 * loest beim Client einen Respawn-Paketwechsel aus, der den Ladebildschirm ("Welt wird geladen")
 * zeigt - genau das entfaellt dadurch vollstaendig.
 * <p>
 * {@code PlayerDeathEvent} bleibt als Auffangnetz fuer echte Tode bestehen (z. B. /kill oder Void).
 */
public class EliminationManager {

    private final OneShotOneKill plugin;
    /** Verhindert Mehrfach-Eliminierung im selben Tick (z. B. Explosion + Pfeil gleichzeitig). */
    private final Set<UUID> inProgress = new HashSet<>();

    public EliminationManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    /**
     * Eliminiert einen Spieler ohne echten Tod.
     *
     * @param killer der Verursacher, oder {@code null} bei Selbstverschulden
     */
    public void eliminate(Player victim, Player killer) {
        if (victim == null || !victim.isOnline()) return;

        UUID victimId = victim.getUniqueId();
        if (!inProgress.add(victimId)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> inProgress.remove(victimId), 2L);

        ScoreboardManager scoreboard = plugin.getScoreboardManager();
        Location deathLoc = victim.getLocation().clone();

        plugin.getKillEffectManager().playKillEffect(deathLoc);

        boolean wasBounty = scoreboard.removeBountyTarget(victimId);
        scoreboard.addDeath(victimId);
        scoreboard.resetStreak(victimId);

        boolean hasKiller = killer != null && killer.isOnline() && !killer.getUniqueId().equals(victimId);

        if (hasKiller) {
            int kills = scoreboard.addKill(killer.getUniqueId());
            int streak = scoreboard.addStreak(killer.getUniqueId());

            killer.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ARROW_HIT_PLAYER, Sound.Source.MASTER, 1.0f, 1.2f));
            killer.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<green>[OSOK] Du hast <yellow>" + victim.getName() + "</yellow> eliminiert! <gray>(Streak: <yellow>" + streak + "</yellow>)</gray></green>"));

            if (wasBounty) {
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, 0);
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, 0);
                killer.playSound(Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1.0f, 1.5f));
                Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                        "<green>[OSOK] 💰 KOPFGELD KASSIERT! <white>" + killer.getName() + "</white> <gray>hat das Kopfgeld auf <yellow>"
                                + victim.getName() + "</yellow> geholt und 2 Spezial-Items kassiert!</gray></green>"));
            }

            KillstreakManager.ItemMode mode = plugin.getKillstreakManager().getItemMode();
            if (streak > 0 && streak % 3 == 0
                    && (mode == KillstreakManager.ItemMode.STREAK || mode == KillstreakManager.ItemMode.BOTH)) {
                plugin.getKillstreakManager().awardRandomKillstreakItem(killer, streak);
            }

            Component deathMessage = MiniMessage.miniMessage().deserialize(
                    "<red>🎯 " + victim.getName() + " <gray>wurde von <yellow>" + killer.getName() + "</yellow> ausgeschaltet!</gray></red>");
            Bukkit.broadcast(deathMessage);

            plugin.getMatchManager().checkKillWinner(killer, kills);
        } else {
            Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                    "<red>☠ " + victim.getName() + " <gray>ist gestorben.</gray></red>"));
        }

        returnToPlay(victim);
        scoreboard.updateAllScoreboards();
    }

    /**
     * Setzt den Spieler zurueck ins Spiel: frische Position, volle Gesundheit, saubere Effekte.
     * Ohne echten Tod gibt es keinen Respawn-Bildschirm.
     */
    private void returnToPlay(Player victim) {
        victim.setFireTicks(0);
        victim.setFreezeTicks(0);
        victim.setGlowing(false);
        new ArrayList<>(victim.getActivePotionEffects()).forEach(effect -> victim.removePotionEffect(effect.getType()));

        boolean matchRunning = plugin.getMatchManager().isMatchStarted()
                && !plugin.getMatchManager().isMatchPaused()
                && !plugin.getMatchManager().isMatchEnded();

        Location target = matchRunning ? plugin.getArenaManager().getRandomArenaLocation() : null;
        if (target == null) {
            target = plugin.getWorldManager().getSpawnLocation();
        }
        if (target == null) {
            return;
        }

        victim.teleportAsync(target).thenAccept(success -> {
            if (success && victim.isOnline()) {
                if (matchRunning) {
                    plugin.getEquipmentManager().giveOneShotEquipment(victim);
                } else {
                    plugin.getEquipmentManager().clearBaseEquipment(victim);
                }
                victim.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 0.8f, 1.4f));
            }
        });
    }
}
