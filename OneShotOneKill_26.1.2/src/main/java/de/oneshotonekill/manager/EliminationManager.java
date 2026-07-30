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
 * {@code PlayerDeathEvent} bleibt als Auffangnetz fuer echte Tode bestehen (z. B. /kill). Diese
 * gehen ueber {@link #handleRealDeath(Player, Player)} und teilen sich mit der regulaeren
 * Eliminierung dieselbe Buchfuehrung. Frueher fuehrte der Death-Handler eine eigene, parallele
 * Statistik - dabei wurden weder {@code /osok pausestats} noch das Kill-Limit beachtet.
 */
public class EliminationManager {

    /** Ab so vielen verbleibenden Kills wird der Spieler an sein Match-Ziel erinnert. */
    private static final int KILL_LIMIT_WARN_THRESHOLD = 5;

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

        // Reflektor-Schild: faengt JEDE Eliminierung ab, nicht nur direkte Treffer.
        // Die Pruefung sitzt bewusst hier und nicht im CombatListener, denn Kettenblitz,
        // Explosiv-Pfeil, Bomber-TNT, Air-Strike, C4, Railgun und Sturzschaden erreichen den
        // CombatListener-Nahkampfzweig nie und wuerden das Schild sonst umgehen.
        // Steht vor der inProgress-Sperre, damit ein zweiter Treffer im selben Tick
        // korrekt toetet, statt ebenfalls blockiert zu werden.
        if (plugin.getKillstreakManager().hasShield(victimId)) {
            plugin.getKillstreakManager().removeShield(victimId);

            victim.playSound(Sound.sound(org.bukkit.Sound.ITEM_SHIELD_BREAK, Sound.Source.MASTER, 1.0f, 1.0f));
            victim.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<aqua>[OSOK] [🛡] Dein Reflektor-Schild hat den tödlichen Treffer abgewehrt!</aqua>"));

            if (killer != null && killer.isOnline() && !killer.getUniqueId().equals(victimId)) {
                killer.playSound(Sound.sound(org.bukkit.Sound.ITEM_SHIELD_BLOCK, Sound.Source.MASTER, 1.0f, 0.8f));
                killer.sendMessage(MiniMessage.miniMessage().deserialize(
                        "<red>[OSOK] [🛡] Treffer abgeprallt! " + victim.getName() + " hatte ein Reflektor-Schild!</red>"));
            }
            return;
        }

        if (!inProgress.add(victimId)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> inProgress.remove(victimId), 2L);

        Location deathLoc = victim.getLocation().clone();
        plugin.getKillEffectManager().playKillEffect(deathLoc);

        registerKill(victim, killer);
        returnToPlay(victim);
        plugin.getScoreboardManager().updateAllScoreboards();
    }

    /**
     * Auffangnetz fuer einen <b>echten</b> Tod (z. B. {@code /kill}).
     * <p>
     * Hier wird ausschliesslich gebucht: Kein Reflektor-Schild (der Tod laesst sich in
     * {@code PlayerDeathEvent} nicht mehr verhindern, das Schild duerfte also auch nicht
     * verbraucht werden) und kein Teleport - den Respawn erledigt der
     * {@code PlayerRespawnEvent}.
     */
    public void handleRealDeath(Player victim, Player killer) {
        if (victim == null) return;

        plugin.getKillEffectManager().playKillEffect(victim.getLocation());
        registerKill(victim, killer);
        cleanupEffects(victim);
        plugin.getScoreboardManager().updateAllScoreboards();
    }

    /**
     * Gemeinsame Buchfuehrung von Eliminierung und echtem Tod: Statistik, Kopfgeld,
     * Killstreak-Belohnung, Todesnachricht und Match-Ziel.
     * <p>
     * Bei eingefrorener Wertung ({@code /osok pausestats}) wirkt der Treffer normal, wird aber
     * nicht gezaehlt.
     */
    private void registerKill(Player victim, Player killer) {
        UUID victimId = victim.getUniqueId();
        ScoreboardManager scoreboard = plugin.getScoreboardManager();

        boolean hasKiller = killer != null && killer.isOnline() && !killer.getUniqueId().equals(victimId);

        if (plugin.getMatchManager().isStatsPaused()) {
            if (hasKiller) {
                killer.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ARROW_HIT_PLAYER, Sound.Source.MASTER, 1.0f, 1.2f));
                killer.sendMessage(MiniMessage.miniMessage().deserialize(
                        "<gray>[OSOK] Du hast <yellow>" + victim.getName() + "</yellow> eliminiert - <b>wird aktuell nicht gewertet</b> (Statistik eingefroren).</gray>"));
            }
            return;
        }

        boolean wasBounty = scoreboard.removeBountyTarget(victimId);
        scoreboard.addDeath(victimId);
        scoreboard.resetStreak(victimId);

        if (!hasKiller) {
            Bukkit.broadcast(MiniMessage.miniMessage().deserialize(
                    "<red>☠ " + victim.getName() + " <gray>ist gestorben.</gray></red>"));
            return;
        }

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

        notifyKillLimitProgress(killer, kills);
        plugin.getMatchManager().checkKillWinner(killer, kills);
    }

    /**
     * Erinnert den Killer daran, wie viele Kills ihm noch zum Sieg fehlen.
     * Greift nur, wenn Kills das aktive Match-Limit sind, und ab
     * {@link #KILL_LIMIT_WARN_THRESHOLD} verbleibenden Kills abwaerts.
     */
    private void notifyKillLimitProgress(Player killer, int kills) {
        MatchManager match = plugin.getMatchManager();
        if (match == null || !match.hasKillLimit()) return;

        int remaining = match.getKillLimit() - kills;
        if (remaining <= 0 || remaining > KILL_LIMIT_WARN_THRESHOLD) return;

        String killWord = (remaining == 1) ? "Kill" : "Kills";
        Component message = MiniMessage.miniMessage().deserialize(
                "<gold>[OSOK] 🎯 Nur noch <yellow><b>" + remaining + "</b></yellow> " + killWord + " bis zum <b>Sieg</b>!</gold>");

        killer.sendMessage(message);
        killer.sendActionBar(MiniMessage.miniMessage().deserialize(
                "<gold><b>" + remaining + " " + killWord + " bis zum Sieg!</b></gold>"));
        killer.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, Sound.Source.MASTER, 1.0f, 1.6f));
    }

    /**
     * Setzt den Spieler zurueck ins Spiel: frische Position, volle Gesundheit, saubere Effekte.
     * Ohne echten Tod gibt es keinen Respawn-Bildschirm.
     */
    private void returnToPlay(Player victim) {
        cleanupEffects(victim);

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
                // Sterbe-Sound statt Teleport-Sound: Der Spieler wurde eliminiert,
                // nicht teleportiert - auch wenn technisch ein Teleport dahintersteckt.
                victim.playSound(Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_DEATH, Sound.Source.MASTER, 1.0f, 1.0f));
            }
        });
    }

    /**
     * Raeumt alle laufenden Item-Wirkungen des Opfers ab.
     * <p>
     * Wichtig: Der Unsichtbarkeits-Mantel und der Gleitflug haengen nicht an einem Potion-Effekt,
     * sondern an {@code hidePlayer} bzw. an angelegten Schwingen. Wuerden sie hier fehlen,
     * bliebe ein eliminierter Spieler bis zum Ablauf seines Timers unsichtbar - oder truege
     * weiter eine Elytra.
     */
    private void cleanupEffects(Player victim) {
        victim.setFireTicks(0);
        victim.setFreezeTicks(0);
        plugin.getGlowManager().clear(victim);
        new ArrayList<>(victim.getActivePotionEffects()).forEach(effect -> victim.removePotionEffect(effect.getType()));

        if (plugin.getSpecialItemListener() != null) {
            plugin.getSpecialItemListener().revealPlayer(victim);
        }
        if (plugin.getTacticalItemsManager() != null) {
            plugin.getTacticalItemsManager().stopGlide(victim, false);
            // Nach dem Respawn darf keine noch laufende Singularitaet erneut zugreifen
            plugin.getTacticalItemsManager().excludeFromSingularities(victim.getUniqueId());
        }
    }
}
