package de.oneshotonekill.listener

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import net.kyori.adventure.sound.Sound
import org.bukkit.Material
import org.bukkit.damage.DamageType
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.Sound as BukkitSound

class CombatListener(private val plugin: OneShotOneKill) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return

        // Waehrend des Nuke-Finales ist JEDER Schaden abgeschaltet - auch /kill und Void. Das
        // TNT-Bombardement ist reine Kulisse: Sterben soll man ausschliesslich am Giftgas, und das
        // wickelt der NukeManager selbst ab. Ohne diese Sperre waere die halbe Runde tot, bevor das
        // Gas ueberhaupt austritt.
        if (plugin.nukeManager.isRunning) {
            event.isCancelled = true
            return
        }

        // /kill und Void-Sturz muessen als echte Tode durchgehen
        if (event.cause == EntityDamageEvent.DamageCause.KILL ||
            event.cause == EntityDamageEvent.DamageCause.VOID
        ) {
            return
        }

        // Ausserhalb der Arena ist JEGLICHER Schaden (Sturz, Angriff etc.) verboten
        if (!plugin.arenaManager.isInArenaArea(player.location)) {
            event.isCancelled = true
            return
        }

        // Toedlicher Schaden ohne direkten Nahkampftreffer (Sturz, Explosion) wuerde einen echten
        // Tod ausloesen und damit den Respawn-Ladebildschirm zeigen. Stattdessen regulaer
        // eliminieren.
        if (event.finalDamage >= player.health) {
            event.isCancelled = true
            plugin.eliminationManager.eliminate(player, resolveKiller(event, player))
        }
    }

    /**
     * Ermittelt den verantwortlichen Spieler fuer toedlichen Schaden, damit z. B. eine
     * Bomber-TNT-Explosion dem Ausloeser als Kill gutgeschrieben wird.
     */
    private fun resolveKiller(event: EntityDamageEvent, victim: Player): Player? {
        // Native Bukkit/Paper DamageSource: liefert direkt den ursaechlichen Spieler - beim Pfeil
        // den Schuetzen, beim TNT den per setSource gesetzten Ausloeser. Ersetzt die frueher
        // noetige manuelle Aufloesung ueber Arrow#getShooter und eine PDC-Suche.
        (event.damageSource.causingEntity as? Player)?.let { return it }

        // Air-Strike und C4 sprengen bewusst ohne Verursacher-Entity, damit auch der Ausloeser
        // Schaden nimmt. Dort gibt es also keine CausingEntity und die Zuordnung liefert der
        // ExplosivesManager.
        //
        // Unterschieden wird ueber den **DamageType**, nicht ueber `DamageCause`. Der Grund ist
        // eine Falle in der Ursachen-Zuordnung von CraftBukkit (gegen die Server-JAR geprueft,
        // `CraftEventFactory#handleEntityDamageEvent`): Sie fragt zuerst
        // `eventEntityDamager() ?: getDirectEntity()` ab. Ist beides null - und genau das ist eine
        // Sprengung ohne Quell-Entity - laeuft sie in den Zweig ohne Entity und ohne Block, und
        // dort wird `DamageTypes.EXPLOSION` **gar nicht geprueft**. Die Kette endet im
        // Sammelfall `DamageCause.CUSTOM`. Air-Strike- und C4-Kills waren damit weder
        // BLOCK_EXPLOSION noch ENTITY_EXPLOSION, fielen aus dem `when` und wurden als
        // "ist gestorben" ohne Killer gemeldet. Der DamageType haengt dagegen direkt an der
        // Schadensquelle und geht auf diesem Weg nicht verloren.
        //
        // FALL zaehlt bewusst mit: Eine C4 schleudert Getroffene weit nach oben, und wer den
        // Treffer knapp ueberlebt, stirbt Sekunden spaeter am Aufprall. Ohne diesen Zweig blieb so
        // ein Tod unzugeordnet - genau der Fall aus Issue #2.
        return when (event.damageSource.damageType) {
            DamageType.EXPLOSION, DamageType.PLAYER_EXPLOSION, DamageType.FALL ->
                plugin.explosivesManager.resolveBlastKiller(victim)

            else -> null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val target = event.entity as? Player ?: return

        val attacker = event.damager as? Player
        val isMelee = attacker != null
        val damager = attacker ?: (event.damager as? Arrow)?.shooter as? Player

        // 1. Match-Status pruefen (vor Start, Pause oder nach Ende)
        val match = plugin.matchManager
        if (!match.isMatchStarted || match.isMatchPaused || match.isMatchEnded) {
            event.isCancelled = true
            if (damager != null) {
                when {
                    match.isMatchPaused -> damager.sendMessage(
                        "<red>[OSOK] ⏸ Das Match ist aktuell pausiert! Kämpfen ist deaktiviert.</red>".mini()
                    )

                    !match.isMatchStarted -> damager.sendMessage(
                        ("<red>[OSOK] ❌ Das Spiel wurde noch nicht gestartet! Kämpfen ist " +
                            "deaktiviert. Warte auf /osok start.</red>").mini()
                    )
                }
                damager.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            }
            return
        }

        // 2. Arena-Bereich pruefen (ausserhalb der Arena ist Kaempfen & Toeten verboten!)
        val targetInArena = plugin.arenaManager.isInArenaArea(target.location)
        val damagerInArena = damager == null || plugin.arenaManager.isInArenaArea(damager.location)

        if (!targetInArena || !damagerInArena) {
            event.isCancelled = true
            damager?.let {
                it.sendMessage("<red>[OSOK] 🛡 Außerhalb der Arena ist Kämpfen deaktiviert!</red>".mini())
                it.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            }
            return
        }

        // Turmpfeile laufen nicht ueber den 1-Hit-Weg: Sie werden im TacticalItemsManager gezaehlt
        // und toeten erst beim dritten Treffer. Der Einschlag wird bereits im ProjectileHitEvent
        // abgefangen - dieser Zweig ist das Sicherheitsnetz, falls doch ein Schadensevent
        // durchkommt. Ohne ihn wuerde jeder Turmtreffer sofort eliminieren und dem Besitzer
        // nebenbei einen Pfeil ins Inventar nachfuellen.
        if ((event.damager as? Arrow)?.let { plugin.tacticalItemsManager.isTurretArrow(it) } == true) {
            event.isCancelled = true
            return
        }

        if (damager == null) return

        // Nahkampfschlag: NUR mit dem Eisenschwert (OneShot Dolch)! Andere Items verursachen
        // normalen Schaden.
        if (isMelee && damager.inventory.itemInMainHand.type != Material.IRON_SWORD) {
            // Normaler Faust-, Bloecke- oder Bogenschlagschaden
            return
        }

        // Das Reflektor-Schild wird zentral im EliminationManager geprueft, damit es auch gegen
        // Explosionen, Kettenblitz und Sturzschaden wirkt.

        // Pfeil beim Bogentreffer nachfuellen
        if (event.damager is Arrow) {
            damager.inventory.addItem(ItemStack.of(Material.ARROW, 1))
            damager.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.5f))
        }

        // Instant 1-Hit Kill: Schaden wird gecancelt, die Eliminierung uebernimmt der
        // EliminationManager. Ohne echten Tod entfaellt der Respawn-Ladebildschirm.
        event.isCancelled = true
        plugin.eliminationManager.eliminate(target, damager)
    }

    /**
     * Auffangnetz fuer echte Tode (z. B. `/kill`).
     *
     * Die Buchfuehrung uebernimmt vollstaendig der
     * [de.oneshotonekill.manager.EliminationManager] - dieselbe wie bei einer regulaeren
     * Eliminierung. Frueher lief hier eine eigene, parallele Statistik, die weder
     * `/osok pausestats` noch die Match-Ziel-Erinnerung kannte und damit stillschweigend von der
     * Hauptlogik abwich.
     *
     * Die Todesnachricht wird unterdrueckt, weil der EliminationManager sie selbst sendet.
     * Sofortiger Auto-Respawn ohne Wiederbeleben-Button laeuft rein ueber die Paper GameRule
     * IMMEDIATE_RESPAWN (siehe `WorldManager#applyPaperGameRules`).
     */
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.entity

        event.drops.clear()
        event.droppedExp = 0
        event.deathMessage(null)

        plugin.eliminationManager.handleRealDeath(victim, victim.killer)
    }

    @EventHandler
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        // Natuerliche Spawns unterbinden - vom Plugin selbst gesetzte Entities (z. B. der
        // Tarnkappenbomber-Drache) muessen aber erlaubt bleiben.
        if (event.spawnReason == CreatureSpawnEvent.SpawnReason.CUSTOM) return

        if (event.entity !is Player) {
            event.isCancelled = true
        }
    }
}
