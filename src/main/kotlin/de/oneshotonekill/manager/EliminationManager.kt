package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import org.bukkit.Sound as BukkitSound

/**
 * Zentrale Eliminierungs-Logik.
 *
 * Ein Treffer toetet den Spieler bewusst **nicht** im Sinne von Minecraft: Der Schaden wird
 * gecancelt und Statistik, Effekte und Respawn werden hier selbst abgewickelt. Ein echter Tod
 * loest beim Client einen Respawn-Paketwechsel aus, der den Ladebildschirm ("Welt wird geladen")
 * zeigt - genau das entfaellt dadurch vollstaendig.
 *
 * `PlayerDeathEvent` bleibt als Auffangnetz fuer echte Tode bestehen (z. B. /kill). Diese gehen
 * ueber [handleRealDeath] und teilen sich mit der regulaeren Eliminierung dieselbe Buchfuehrung.
 * Frueher fuehrte der Death-Handler eine eigene, parallele Statistik - dabei wurden weder
 * `/osok pausestats` noch das Kill-Limit beachtet.
 */
class EliminationManager(private val plugin: OneShotOneKill) {

    /** Verhindert Mehrfach-Eliminierung im selben Tick (z. B. Explosion + Pfeil gleichzeitig). */
    private val inProgress = mutableSetOf<UUID>()

    /**
     * Eliminiert einen Spieler ohne echten Tod.
     *
     * @param killer der Verursacher, oder `null` bei Selbstverschulden
     */
    fun eliminate(victim: Player?, killer: Player?) {
        if (victim == null || !victim.isOnline) return

        val victimId = victim.uniqueId

        // Reflektor-Schild: faengt JEDE Eliminierung ab, nicht nur direkte Treffer.
        // Die Pruefung sitzt bewusst hier und nicht im CombatListener, denn Kettenblitz,
        // Explosiv-Pfeil, Bomber-TNT, Air-Strike, C4, Railgun und Sturzschaden erreichen den
        // CombatListener-Nahkampfzweig nie und wuerden das Schild sonst umgehen.
        // Steht vor der inProgress-Sperre, damit ein zweiter Treffer im selben Tick
        // korrekt toetet, statt ebenfalls blockiert zu werden.
        if (plugin.killstreakManager.hasShield(victimId)) {
            plugin.killstreakManager.removeShield(victimId)

            victim.playSound(Sound.sound(BukkitSound.ITEM_SHIELD_BREAK, Sound.Source.MASTER, 1.0f, 1.0f))
            victim.sendMessage(
                "<aqua>[OSOK] [🛡] Dein Reflektor-Schild hat den tödlichen Treffer abgewehrt!</aqua>".mini()
            )

            if (killer != null && killer.isOnline && killer.uniqueId != victimId) {
                killer.playSound(Sound.sound(BukkitSound.ITEM_SHIELD_BLOCK, Sound.Source.MASTER, 1.0f, 0.8f))
                killer.sendMessage(
                    "<red>[OSOK] [🛡] Treffer abgeprallt! ${victim.name} hatte ein Reflektor-Schild!</red>".mini()
                )
            }
            return
        }

        if (!inProgress.add(victimId)) return
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { inProgress.remove(victimId) }, 2L)

        val deathLoc = victim.location.clone()
        plugin.killEffectManager.playKillEffect(deathLoc)

        registerKill(victim, killer)
        returnToPlay(victim, deathLoc)
        plugin.scoreboardManager.updateAllScoreboards()
    }

    /**
     * Auffangnetz fuer einen **echten** Tod (z. B. `/kill`).
     *
     * Hier wird ausschliesslich gebucht: Kein Reflektor-Schild (der Tod laesst sich in
     * `PlayerDeathEvent` nicht mehr verhindern, das Schild duerfte also auch nicht verbraucht
     * werden) und kein Teleport - den Respawn erledigt der `PlayerRespawnEvent`.
     */
    fun handleRealDeath(victim: Player?, killer: Player?) {
        if (victim == null) return

        plugin.killEffectManager.playKillEffect(victim.location)
        registerKill(victim, killer)
        cleanupEffects(victim)
        plugin.scoreboardManager.updateAllScoreboards()
    }

    /**
     * Gemeinsame Buchfuehrung von Eliminierung und echtem Tod: Statistik, Kopfgeld,
     * Killstreak-Belohnung, Todesnachricht und Match-Ziel.
     *
     * Bei eingefrorener Wertung (`/osok pausestats`) wirkt der Treffer normal, wird aber nicht
     * gezaehlt.
     */
    private fun registerKill(victim: Player, killer: Player?) {
        val victimId = victim.uniqueId
        val scoreboard = plugin.scoreboardManager

        val realKiller = killer?.takeIf { it.isOnline && it.uniqueId != victimId }

        if (plugin.matchManager.isStatsPaused) {
            realKiller?.let {
                it.playSound(Sound.sound(BukkitSound.ENTITY_ARROW_HIT_PLAYER, Sound.Source.MASTER, 1.0f, 1.2f))
                it.sendMessage(
                    ("<gray>[OSOK] Du hast <yellow>${victim.name}</yellow> eliminiert - " +
                        "<b>wird aktuell nicht gewertet</b> (Statistik eingefroren).</gray>").mini()
                )
            }
            return
        }

        val wasBounty = scoreboard.removeBountyTarget(victimId)
        scoreboard.addDeath(victimId)
        scoreboard.resetStreak(victimId)

        if (realKiller == null) {
            Bukkit.broadcast("<red>☠ ${victim.name} <gray>ist gestorben.</gray></red>".mini())
            return
        }

        val kills = scoreboard.addKill(realKiller.uniqueId)
        val streak = scoreboard.addStreak(realKiller.uniqueId)

        realKiller.playSound(Sound.sound(BukkitSound.ENTITY_ARROW_HIT_PLAYER, Sound.Source.MASTER, 1.0f, 1.2f))
        realKiller.sendMessage(
            ("<green>[OSOK] Du hast <yellow>${victim.name}</yellow> eliminiert! " +
                "<gray>(Streak: <yellow>$streak</yellow>)</gray></green>").mini()
        )

        if (wasBounty) {
            plugin.killstreakManager.awardRandomKillstreakItem(realKiller, 0)
            plugin.killstreakManager.awardRandomKillstreakItem(realKiller, 0)
            realKiller.playSound(Sound.sound(BukkitSound.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1.0f, 1.5f))
            Bukkit.broadcast(
                ("<green>[OSOK] 💰 KOPFGELD KASSIERT! <white>${realKiller.name}</white> " +
                    "<gray>hat das Kopfgeld auf <yellow>${victim.name}</yellow> geholt und " +
                    "2 Spezial-Items kassiert!</gray></green>").mini()
            )
        }

        val mode = plugin.killstreakManager.itemMode
        if (streak > 0 && streak % 3 == 0 &&
            (mode == KillstreakManager.ItemMode.STREAK || mode == KillstreakManager.ItemMode.BOTH)
        ) {
            plugin.killstreakManager.awardRandomKillstreakItem(realKiller, streak)
        }

        Bukkit.broadcast(
            ("<red>🎯 ${victim.name} <gray>wurde von <yellow>${realKiller.name}</yellow> " +
                "ausgeschaltet!</gray></red>").mini()
        )

        notifyKillLimitProgress(realKiller, kills)
        plugin.matchManager.checkKillWinner(realKiller, kills)
    }

    /**
     * Erinnert den Killer daran, wie viele Kills ihm noch zum Sieg fehlen. Greift nur, wenn Kills
     * das aktive Match-Limit sind, und ab [KILL_LIMIT_WARN_THRESHOLD] verbleibenden Kills abwaerts.
     */
    private fun notifyKillLimitProgress(killer: Player, kills: Int) {
        val match = plugin.matchManager
        if (!match.hasKillLimit()) return

        val remaining = match.killLimit - kills
        if (remaining <= 0 || remaining > KILL_LIMIT_WARN_THRESHOLD) return

        val killWord = if (remaining == 1) "Kill" else "Kills"

        killer.sendMessage(
            "<gold>[OSOK] 🎯 Nur noch <yellow><b>$remaining</b></yellow> $killWord bis zum <b>Sieg</b>!</gold>".mini()
        )
        killer.sendActionBar("<gold><b>$remaining $killWord bis zum Sieg!</b></gold>".mini())
        killer.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_BELL, Sound.Source.MASTER, 1.0f, 1.6f))
    }

    /**
     * Setzt den Spieler zurueck ins Spiel: frische Position, volle Gesundheit, saubere Effekte.
     * Ohne echten Tod gibt es keinen Respawn-Bildschirm.
     *
     * Der Respawn sucht bewusst den Punkt mit dem groessten Abstand zu Todespunkt **und** Gegnern -
     * sonst landet man regelmaessig direkt neben seinem Killer und ist beim naechsten Treffer
     * sofort wieder draussen.
     */
    private fun returnToPlay(victim: Player, deathLoc: Location) {
        cleanupEffects(victim)
        victim.health = 20.0
        victim.foodLevel = 20

        val match = plugin.matchManager
        val matchRunning = match.isMatchStarted && !match.isMatchPaused && !match.isMatchEnded

        val target = (if (matchRunning) plugin.arenaManager.getSafestArenaLocation(victim, deathLoc) else null)
            ?: plugin.worldManager.spawnLocation
            ?: return

        victim.teleportAsync(target).thenAccept { success ->
            if (!success || !victim.isOnline) return@thenAccept

            if (matchRunning) {
                plugin.equipmentManager.giveOneShotEquipment(victim)
            } else {
                plugin.equipmentManager.clearBaseEquipment(victim)
            }
            // Sterbe-Sound statt Teleport-Sound: Der Spieler wurde eliminiert, nicht
            // teleportiert - auch wenn technisch ein Teleport dahintersteckt.
            victim.playSound(Sound.sound(BukkitSound.ENTITY_PLAYER_DEATH, Sound.Source.MASTER, 1.0f, 1.0f))
        }
    }

    /**
     * Raeumt alle laufenden Item-Wirkungen des Opfers ab.
     *
     * Wichtig: Der Unsichtbarkeits-Mantel und der Gleitflug haengen nicht an einem Potion-Effekt,
     * sondern an `hidePlayer` bzw. an angelegten Schwingen. Wuerden sie hier fehlen, bliebe ein
     * eliminierter Spieler bis zum Ablauf seines Timers unsichtbar - oder truege weiter eine
     * Elytra.
     */
    private fun cleanupEffects(victim: Player) {
        victim.fireTicks = 0
        victim.freezeTicks = 0
        plugin.glowManager.clear(victim)
        victim.activePotionEffects.toList().forEach { victim.removePotionEffect(it.type) }

        plugin.specialItemListener.revealPlayer(victim)
        // Sonst bliebe ein in der Frost-Trap Erwischter auch nach dem Respawn festgenagelt
        plugin.specialItemListener.unfreezePlayer(victim)

        plugin.tacticalItemsManager.stopGlide(victim, false)
        // Nach dem Respawn darf keine noch laufende Singularitaet erneut zugreifen
        plugin.tacticalItemsManager.excludeFromSingularities(victim.uniqueId)

        // Die Sprengung ist mit dieser Eliminierung abgegolten. Bliebe der Merkzettel stehen,
        // wuerde ein unabhaengiger Sturz kurz nach dem Respawn noch dem Zuender gutgeschrieben.
        // Laeuft nach registerKill - der Kill ist da bereits gebucht.
        plugin.explosivesManager.clearBlastCredit(victim.uniqueId)
    }

    private companion object {
        /** Ab so vielen verbleibenden Kills wird der Spieler an sein Match-Ziel erinnert. */
        const val KILL_LIMIT_WARN_THRESHOLD = 5
    }
}
