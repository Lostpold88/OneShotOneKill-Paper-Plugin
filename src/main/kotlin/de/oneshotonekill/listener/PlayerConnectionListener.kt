package de.oneshotonekill.listener

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import java.util.UUID
import org.bukkit.Sound as BukkitSound

class PlayerConnectionListener(private val plugin: OneShotOneKill) : Listener {

    /** Laufende Void-Rettungen - verhindert, dass jeder Fall-Tick einen neuen Teleport ausloest. */
    private val voidRescues = mutableSetOf<UUID>()

    /** Kampf laeuft: gestartet, nicht pausiert, nicht beendet. */
    private val isMatchRunning: Boolean
        get() = plugin.matchManager.let { it.isMatchStarted && !it.isMatchPaused && !it.isMatchEnded }

    @EventHandler
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        event.isCancelled = true
        player.foodLevel = 20
        player.saturation = 20.0f
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        // Rechte des privilegierten Kontos sofort setzen - noch vor allem anderen, damit kein
        // Zeitfenster ohne Rechte entsteht (No-Op fuer alle uebrigen Spieler).
        plugin.accessManager.grant(player)

        event.joinMessage(
            ("<green>[✦] <white>${player.name}</white> <gray>hat <yellow><b>OSOK</b></yellow> " +
                "betreten!</gray></green>").mini()
        )

        // Definierter Startzustand: Ueberlebensmodus und komplett leeres Inventar. Laeuft sofort im
        // Event, nicht im verzoegerten Task, damit kein Zeitfenster entsteht, in dem der Spieler
        // noch alte Items oder einen anderen Modus hat.
        prepareCleanStart(player)

        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_CHIME, Sound.Source.MASTER, 1.0f, 1.5f)
        )

        player.scheduler.runDelayed(
            plugin,
            {
                val spawnLoc = plugin.worldManager.spawnLocation
                if (plugin.worldManager.osokWorld == null || spawnLoc == null || !player.isOnline) {
                    return@runDelayed
                }

                // Paper API: Asynchrones Teleportieren mit Pre-Loading
                player.teleportAsync(spawnLoc).thenAccept { success ->
                    if (!success || !player.isOnline) return@thenAccept

                    applyMatchEquipment(player)
                    plugin.scoreboardManager.updateAllScoreboards()
                    plugin.logger.info(
                        "Spieler ${player.name} wurde in die Lobby der Map " +
                            "${plugin.worldManager.activeMapConfig.name} teleportiert."
                    )
                }
            },
            null,
            5L,
        )
    }

    /**
     * Versetzt einen Spieler in einen sauberen Ausgangszustand: Ueberlebensmodus, leeres Inventar
     * (inkl. Ruestung, Zweithand und Cursor) und keine Alteffekte. Die eigentliche
     * Match-Ausruestung vergibt anschliessend der EquipmentManager.
     */
    private fun prepareCleanStart(player: Player) {
        player.gameMode = GameMode.SURVIVAL

        player.inventory.clear()
        // Vier leere Slots statt null: setArmorContents erwartet ein Array, keinen null-Wert
        player.inventory.armorContents = arrayOfNulls(ARMOR_SLOTS)
        player.inventory.setItemInOffHand(null)
        player.setItemOnCursor(null)

        player.activePotionEffects.toList().forEach { player.removePotionEffect(it.type) }
        player.isGlowing = false
        player.fireTicks = 0
        player.freezeTicks = 0
        player.level = 0
        player.exp = 0.0f
        player.health = 20.0
        player.foodLevel = 20
        player.saturation = 20.0f
    }

    /** Match-Ausruestung, wenn gekaempft wird - sonst nur die Grundausruestung einziehen. */
    private fun applyMatchEquipment(player: Player) {
        val match = plugin.matchManager
        if (match.isMatchStarted && !match.isMatchPaused) {
            plugin.equipmentManager.giveOneShotEquipment(player)
        } else {
            plugin.equipmentManager.clearBaseEquipment(player)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        event.quitMessage(
            ("<red>[❌] <white>${player.name}</white> <gray>hat <yellow><b>OSOK</b></yellow> " +
                "verlassen.</gray></red>").mini()
        )

        // Gecachtes Board und alle spielerbezogenen Zustaende freigeben, damit nichts bis zum
        // Serverstop im Speicher bleibt
        plugin.scoreboardManager.removePlayer(player.uniqueId)
        plugin.glowManager.removePlayer(player.uniqueId)
        plugin.antiCampManager.removePlayer(player.uniqueId)
        plugin.tacticalItemsManager.stopGlide(player, false)
        plugin.accessManager.remove(player.uniqueId)
        voidRescues.remove(player.uniqueId)

        Bukkit.getGlobalRegionScheduler().runDelayed(
            plugin,
            { plugin.scoreboardManager.updateAllScoreboards() },
            2L,
        )
    }

    /**
     * Respawn nach einem echten Tod.
     *
     * Laeuft ein Match, geht es direkt zurueck in die Arena - genau wie bei einer regulaeren
     * Eliminierung. Ohne diesen Gleichlauf landete man nach `/kill` in der Lobby, waehrend ein
     * normaler Treffer sofort wieder ins Spiel bringt.
     */
    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player

        // player.location zeigt hier noch auf den Todespunkt - der Respawn haelt Abstand dazu
        val respawnLoc = (if (isMatchRunning) {
            plugin.arenaManager.getSafestArenaLocation(player, player.location)
        } else {
            null
        }) ?: plugin.worldManager.spawnLocation

        if (plugin.worldManager.osokWorld != null && respawnLoc != null) {
            event.respawnLocation = respawnLoc
        }

        player.scheduler.runDelayed(
            plugin,
            {
                applyMatchEquipment(player)
                plugin.scoreboardManager.updateAllScoreboards()
            },
            null,
            2L,
        )
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        // Paper: reine Blickrichtungsaenderungen gar nicht erst weiterverarbeiten
        if (!event.hasChangedPosition()) return

        val player = event.player

        // Rettung aus dem Void - siehe rescueFromVoid
        if (plugin.arenaManager.isBelowWorld(event.to)) {
            rescueFromVoid(player)
            return
        }

        if (!plugin.matchManager.isMatchPaused) return

        val spawnLoc = plugin.worldManager.spawnLocation ?: return
        if (!plugin.arenaManager.isInArenaArea(event.to)) return

        player.teleportAsync(spawnLoc)
        player.sendMessage(
            "<red>[OSOK] ⏸ Das Match ist pausiert! Du kannst die Arena aktuell nicht betreten.</red>".mini()
        )
        player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
    }

    /**
     * Holt einen Spieler zurueck, der unter die Welt gefallen ist.
     *
     * Notwendig, weil der `CombatListener` jeden Schaden ausserhalb der Arena cancelt -
     * einschliesslich Void-Schaden. Die Lobby liegt bewusst ausserhalb der Arena-Grenzen; ein
     * Fehltritt neben die Plattform fuehrte deshalb in einen endlosen Fall, aus dem nur ein Rejoin
     * half. Der Sturz zaehlt bewusst **nicht** als Tod - ausserhalb der Arena wird ohnehin nicht
     * gewertet.
     */
    private fun rescueFromVoid(player: Player) {
        // Rettung laeuft bereits, der Spieler faellt nur noch bis zum Teleport
        if (!voidRescues.add(player.uniqueId)) return

        val matchRunning = isMatchRunning
        val target: Location? = (if (matchRunning) plugin.arenaManager.getRandomArenaLocation() else null)
            ?: plugin.worldManager.spawnLocation

        if (target == null) {
            voidRescues.remove(player.uniqueId)
            return
        }

        player.fallDistance = 0.0f
        player.teleportAsync(target).thenAccept { success ->
            voidRescues.remove(player.uniqueId)
            if (!success || !player.isOnline) return@thenAccept

            player.fallDistance = 0.0f
            if (matchRunning) {
                plugin.equipmentManager.giveOneShotEquipment(player)
            } else {
                plugin.equipmentManager.clearBaseEquipment(player)
            }
            player.sendMessage(
                "<yellow>[OSOK] 🪂 Du bist aus der Welt gefallen und wurdest zurückgeholt.</yellow>".mini()
            )
            player.playSound(Sound.sound(BukkitSound.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 1.0f, 0.8f))
        }
    }

    @EventHandler
    fun onPlayerSwapHandItems(event: PlayerSwapHandItemsEvent) {
        event.isCancelled = true
        val player = event.player
        val match = plugin.matchManager

        if (!match.isMatchStarted || match.isMatchEnded) {
            denied(player, "<red>[OSOK] ❌ Das Spiel wurde noch nicht gestartet! Warte auf /start.</red>")
            return
        }

        if (match.isMatchPaused) {
            denied(player, "<red>[OSOK] ⏸ Das Match ist aktuell pausiert!</red>")
            return
        }

        if (plugin.arenaManager.isInArenaArea(player.location)) {
            denied(player, "<red>[OSOK] ❌ Du bist bereits in der Arena!</red>")
            return
        }

        val randomLoc = plugin.arenaManager.getRandomArenaLocation()
        if (randomLoc == null) {
            player.sendMessage("<red>[OSOK] Arena-Welt ist aktuell nicht geladen.</red>".mini())
            return
        }

        // Paper API: Asynchrones Teleportieren ohne Main-Thread-Lags
        player.teleportAsync(randomLoc).thenAccept { success ->
            if (!success || !player.isOnline) return@thenAccept

            plugin.equipmentManager.giveOneShotEquipment(player)
            plugin.scoreboardManager.updateAllScoreboards()
            player.playSound(Sound.sound(BukkitSound.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 1.0f, 1.2f))
        }
    }

    /** Ablehnung mit Meldung und Verweigerungs-Ton. */
    private fun denied(player: Player, miniMessage: String) {
        player.sendMessage(miniMessage.mini())
        player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
    }

    private companion object {
        /** Helm, Brustplatte, Hose, Stiefel. */
        const val ARMOR_SLOTS = 4
    }
}
