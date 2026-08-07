package de.oneshotonekill.listener

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.manager.GlowManager
import de.oneshotonekill.manager.KillstreakManager
import de.oneshotonekill.manager.NukeManager
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID
import org.bukkit.Sound as BukkitSound

class SpecialItemListener(private val plugin: OneShotOneKill) : Listener {

    private val activeBearTraps = mutableSetOf<Location>()

    /** Von einer Frost-Trap festgehaltene Spieler. */
    private val frozenPlayers = mutableSetOf<UUID>()

    private val vanishedPlayers = mutableSetOf<UUID>()

    /** Zaehler pro Spieler, damit ein neuer Radar-Puls das Leuchten des vorherigen verlaengert. */
    private val radarGlowGeneration = mutableMapOf<UUID, Int>()

    /**
     * Identifiziert Spezial-Items ausschliesslich ueber den PersistentDataContainer. Jedes
     * Spezial-Item erhaelt seinen Typ in `KillstreakManager#createSpecialItem` per NamespacedKey,
     * daher sind Anzeigenamen-Vergleiche weder noetig noch zulaessig.
     */
    private fun getSpecialItemType(item: ItemStack?): String? {
        if (item == null || !item.hasItemMeta()) return null
        // Paper: PersistentDataContainerView direkt am ItemStack - liest ohne ItemMeta-Kopie. Diese
        // Methode laeuft bei jedem Interact- und Drop-Event, die Kopie war hier messbar teuer.
        return item.persistentDataContainer
            .get(plugin.killstreakManager.specialItemKey, PersistentDataType.STRING)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val joiner = event.player
        vanishedPlayers
            .mapNotNull { Bukkit.getPlayer(it) }
            .filter { it.isOnline }
            .forEach { joiner.hidePlayer(plugin, it) }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val leaver = event.player
        radarGlowGeneration.remove(leaver.uniqueId)
        frozenPlayers.remove(leaver.uniqueId)
        revealPlayer(leaver)
    }

    /**
     * Beendet den Unsichtbarkeits-Mantel eines Spielers sofort.
     *
     * Der Mantel haengt nicht am Potion-Effekt, sondern an `hidePlayer`. Wuerde er beim Eliminieren
     * oder beim Match-Ende nicht ausdruecklich beendet, bliebe der Spieler bis zum Ablauf seines
     * Timers fuer alle unsichtbar - auch in der Lobby.
     */
    fun revealPlayer(player: Player) {
        if (!vanishedPlayers.remove(player.uniqueId)) return

        Bukkit.getOnlinePlayers().forEach { it.showPlayer(plugin, player) }
    }

    /** Beendet alle laufenden Unsichtbarkeiten (Match-Ende, Map-Wechsel, Plugin-Stop). */
    fun clearAllVanish() {
        vanishedPlayers.toList()
            .mapNotNull { Bukkit.getPlayer(it) }
            .forEach { revealPlayer(it) }
        vanishedPlayers.clear()
    }

    /**
     * Entfernt alle noch liegenden Frost-Trap-Druckplatten aus der Welt und taut alle
     * eingefrorenen Spieler auf.
     *
     * Wird bei Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop gerufen. Da eine Trap von sich
     * aus nicht mehr verfaellt, ist das die einzige Stelle, an der ungenutzte Platten wieder
     * verschwinden.
     */
    fun clearAllTraps() {
        activeBearTraps.toList().forEach { removeTrapBlock(it) }
        activeBearTraps.clear()

        frozenPlayers.toList().forEach { frozenId -> Bukkit.getPlayer(frozenId)?.let { unfreezePlayer(it) } }
        frozenPlayers.clear()
    }

    /**
     * Friert einen Spieler tatsaechlich fest.
     *
     * SLOWNESS allein reicht nicht: Der Effekt senkt nur die Laufgeschwindigkeit, ein Sprung trug
     * den Spieler weiterhin mehrere Bloecke weit. Deshalb wird zusaetzlich die Bewegung in
     * [onFrozenMove] unterbunden und die laufende Bewegung sofort auf null gesetzt, damit auch ein
     * bereits begonnener Sprung abbricht.
     */
    private fun freezePlayer(player: Player) {
        val playerId = player.uniqueId
        frozenPlayers.add(playerId)

        player.addPotionEffect(
            PotionEffect(PotionEffectType.SLOWNESS, FROST_TRAP_FREEZE_TICKS.toInt(), 10, false, false)
        )
        player.freezeTicks = FROST_TRAP_FREEZE_TICKS.toInt()
        player.velocity = Vector(0.0, 0.0, 0.0)

        // Paper Entity Scheduler: an den Tick des Spielers gebunden
        player.scheduler.runDelayed(
            plugin,
            {
                if (frozenPlayers.remove(playerId) && player.isOnline) {
                    player.freezeTicks = 0
                    player.sendMessage("<aqua>[OSOK] ❄ Du bist wieder aufgetaut.</aqua>".mini())
                    player.playSound(Sound.sound(BukkitSound.BLOCK_GLASS_BREAK, Sound.Source.MASTER, 0.7f, 1.6f))
                }
            },
            null,
            FROST_TRAP_FREEZE_TICKS,
        )
    }

    /** Hebt die Vereisung sofort auf - bei Eliminierung, Quit und beim Aufraeumen. */
    fun unfreezePlayer(player: Player) {
        if (frozenPlayers.remove(player.uniqueId) && player.isOnline) {
            player.freezeTicks = 0
        }
    }

    /**
     * Haelt eingefrorene Spieler an Ort und Stelle.
     *
     * Umsehen bleibt erlaubt - nur die Position wird auf den Stand vor der Bewegung
     * zurueckgesetzt. Ohne das konnte man sich per Sprung aus der Falle heraustragen lassen.
     *
     * Teleports sind nicht betroffen: `PlayerTeleportEvent` hat in Paper eine eigene HandlerList,
     * ein `PlayerMoveEvent`-Handler sieht sie also gar nicht. Ein Respawn waehrend der Vereisung
     * funktioniert damit normal.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFrozenMove(event: PlayerMoveEvent) {
        if (event.player.uniqueId !in frozenPlayers) return
        // Paper: reine Blickrichtungsaenderungen gar nicht erst weiterverarbeiten
        if (!event.hasChangedPosition()) return

        event.setTo(
            event.from.clone().apply {
                yaw = event.to.yaw
                pitch = event.to.pitch
            }
        )
    }

    /** Nimmt die Druckplatte zurueck, sofern an der Stelle noch eine liegt. */
    private fun removeTrapBlock(trapLoc: Location) {
        val world = trapLoc.world ?: return

        val block = trapLoc.block
        if (Tag.PRESSURE_PLATES.isTagged(block.type)) {
            block.type = Material.AIR
            world.spawnParticle(
                Particle.SNOWFLAKE, trapLoc.clone().add(0.5, 0.2, 0.5), 15, 0.2, 0.2, 0.2, 0.05,
            )
        }
    }

    @EventHandler
    fun onItemPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val item = event.item
        if (!item.persistentDataContainer.has(KillstreakManager.KEY_GROUND_SPECIAL_PDC, PersistentDataType.BYTE)) {
            return
        }

        val itemName = item.itemStack.getData(DataComponentTypes.CUSTOM_NAME) ?: Component.text("Spezial-Item")

        // Zaehlt fuer die Match-Zusammenfassung - bei eingefrorener Wertung nicht
        if (!plugin.matchManager.isStatsPaused) {
            plugin.scoreboardManager.addItemsCollected(player.uniqueId, 1)
        }

        player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.8f))

        val loc = item.location
        loc.world?.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 30, 0.3, 0.3, 0.3, 0.1)

        player.sendMessage(
            "<yellow>[OSOK] 🎁 <b>ITEM-BOX GEÖFFNET!</b> <gray>Du hast </gray></yellow>".mini()
                .append(itemName)
                .append("<gray> erhalten!</gray>".mini())
        )
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (getSpecialItemType(event.itemDrop.itemStack) == null) return

        event.isCancelled = true
        val player = event.player
        player.sendMessage("<red>[OSOK] ❌ Spezial-Items können nicht weggeworfen werden!</red>".mini())
        player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player

        // Druckplatte betreten (physische Aktion)
        if (event.action == Action.PHYSICAL) {
            val trapLoc = event.clickedBlock?.location
            if (trapLoc != null && activeBearTraps.remove(trapLoc)) {
                freezePlayer(player)
                player.playSound(Sound.sound(BukkitSound.BLOCK_GLASS_BREAK, Sound.Source.MASTER, 1.0f, 0.5f))
                player.sendMessage(
                    "<red>[OSOK] ❄ Du bist in eine Frost-Trap getreten und für 7s eingefroren!</red>".mini()
                )

                // Nach 7 Sekunden verschwindet die Druckplatte
                Bukkit.getGlobalRegionScheduler().runDelayed(
                    plugin, { removeTrapBlock(trapLoc) }, FROST_TRAP_FREEZE_TICKS,
                )
                return
            }
        }

        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item ?: return
        val typeId = getSpecialItemType(item) ?: return

        val match = plugin.matchManager
        if (!match.isMatchStarted || match.isMatchPaused || match.isMatchEnded) {
            event.isCancelled = true
            player.sendMessage(
                if (match.isMatchPaused) {
                    "<red>[OSOK] ⏸ Das Match ist aktuell pausiert!</red>".mini()
                } else {
                    "<red>[OSOK] ❌ Das Spiel wurde noch nicht gestartet! Warte auf /start.</red>".mini()
                }
            )
            player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            return
        }

        if (!plugin.arenaManager.isInArenaArea(player.location)) {
            event.isCancelled = true
            player.sendMessage(
                "<red>[OSOK] ❌ Spezial-Items können nur innerhalb der Arena genutzt werden!</red>".mini()
            )
            player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            return
        }

        when (typeId) {
            KillstreakManager.KEY_RADAR -> {
                event.isCancelled = true
                item.subtract(1)
                useRadarPulse(player)
            }

            KillstreakManager.KEY_EXPLOSIVE -> {
                event.isCancelled = true
                item.subtract(1)
                plugin.killstreakManager.addExplosiveShot(player.uniqueId)
                player.playSound(Sound.sound(BukkitSound.ENTITY_TNT_PRIMED, Sound.Source.MASTER, 1.0f, 1.2f))
                player.sendMessage(
                    ("<green>[OSOK] ★ Explosiv-Schuss geladen! Dein nächster Schuss erzeugt eine " +
                        "große Explosion.</green>").mini()
                )
            }

            KillstreakManager.KEY_REFLECTOR -> {
                event.isCancelled = true
                item.subtract(1)
                plugin.killstreakManager.addShield(player.uniqueId)
                player.playSound(Sound.sound(BukkitSound.ITEM_SHIELD_BLOCK, Sound.Source.MASTER, 1.0f, 1.2f))
                player.sendMessage(
                    ("<green>[OSOK] 🛡 Reflektor-Schild ist aktiv! Blockiert deinen nächsten " +
                        "Treffer.</green>").mini()
                )
            }

            KillstreakManager.KEY_MINIGUN -> {
                event.isCancelled = true
                item.subtract(1)
                plugin.killstreakManager.activateMinigun(player)
            }

            KillstreakManager.KEY_SMOKE -> {
                event.isCancelled = true
                item.subtract(1)
                useSmokeBomb(player)
            }

            KillstreakManager.KEY_FROST -> {
                val clicked = event.clickedBlock ?: return
                event.isCancelled = true

                val targetBlock = clicked.getRelative(BlockFace.UP)
                if (targetBlock.type != Material.AIR) return

                targetBlock.type = Material.HEAVY_WEIGHTED_PRESSURE_PLATE
                activeBearTraps.add(targetBlock.location)
                item.subtract(1)
                player.playSound(Sound.sound(BukkitSound.BLOCK_GLASS_PLACE, Sound.Source.MASTER, 1.0f, 1.0f))
                player.sendMessage(
                    ("<green>[OSOK] ❄ Frost-Trap platziert! <gray>(bleibt liegen, bis jemand " +
                        "hineintritt)</gray></green>").mini()
                )
            }

            KillstreakManager.KEY_TELEPORT -> {
                event.isCancelled = true
                item.subtract(1)

                val pearl = player.launchProjectile(
                    EnderPearl::class.java, player.eyeLocation.direction.multiply(1.8),
                )
                pearl.persistentDataContainer
                    .set(KillstreakManager.KEY_TP_GRENADE_PDC, PersistentDataType.BYTE, 1.toByte())
                player.playSound(Sound.sound(BukkitSound.ENTITY_ENDER_PEARL_THROW, Sound.Source.MASTER, 1.0f, 1.0f))
                player.sendMessage("<green>[OSOK] 🌀 Teleport-Granate geworfen!</green>".mini())
            }

            KillstreakManager.KEY_INVISIBILITY -> {
                event.isCancelled = true
                item.subtract(1)
                useInvisibilityCloak(player)
            }

            KillstreakManager.KEY_MAGNET -> {
                event.isCancelled = true
                item.subtract(1)
                plugin.killstreakManager.activateArrowMagnet(player)
            }

            KillstreakManager.KEY_CHAIN_LIGHTNING -> {
                event.isCancelled = true
                item.subtract(1)
                plugin.killstreakManager.addChainLightningShot(player.uniqueId)
                player.playSound(Sound.sound(BukkitSound.ITEM_TRIDENT_THUNDER, Sound.Source.MASTER, 1.0f, 1.5f))
                player.sendMessage(
                    ("<green>[OSOK] ⚡ Kettenblitz-Schuss geladen! Dein nächster Treffer beschwört " +
                        "Blitze.</green>").mini()
                )
            }

            // Air-Strike: oeffnet die Arena-Karte. Verbrauch erst bei der Zielauswahl im Menue.
            KillstreakManager.KEY_AIRSTRIKE -> {
                event.isCancelled = true
                plugin.explosivesManager.openAirStrikeMap(player)
            }

            // C4: wird auf einen Block platziert, der Fernzuender kommt automatisch dazu
            KillstreakManager.KEY_C4 -> {
                event.isCancelled = true
                val clicked = event.clickedBlock
                if (clicked == null) {
                    player.sendMessage(
                        "<red>[OSOK] 💥 Ziele auf einen Block, um die C4 zu platzieren!</red>".mini()
                    )
                    return
                }
                if (plugin.explosivesManager.placeC4(player, clicked)) {
                    item.subtract(1)
                }
            }

            // Tarnkappenbomber: oeffnet nur die Zielauswahl. Der Verbrauch erfolgt erst bei der
            // Auswahl des Ziels im Menue.
            KillstreakManager.KEY_STEALTH_BOMBER -> {
                event.isCancelled = true
                plugin.stealthBomberManager.openTargetMenu(player)
            }

            // Railgun: feuert sofort einen Hitscan-Strahl, ohne Ladephase
            KillstreakManager.KEY_RAILGUN -> {
                event.isCancelled = true
                item.subtract(1)
                plugin.tacticalItemsManager.fireRailgun(player)
            }

            // Singularitaet: Wurfgeschoss, das beim Einschlag alles zusammenreisst
            KillstreakManager.KEY_SINGULARITY -> {
                event.isCancelled = true
                item.subtract(1)
                plugin.tacticalItemsManager.throwSingularity(player)
            }

            // Gleitflug: Verbrauch erst, wenn wirklich ein Flug startet
            KillstreakManager.KEY_GLIDER -> {
                event.isCancelled = true
                if (plugin.tacticalItemsManager.startGlide(player)) {
                    item.subtract(1)
                }
            }

            // Nuke: oeffnet das Freigabemenue. Der Auslöser wird erst beim Abschuss verbraucht.
            NukeManager.KEY_NUKE -> {
                event.isCancelled = true
                plugin.nukeManager.openCodeGui(player)
            }

            // Geschuetzturm: auf Block platzieren
            KillstreakManager.KEY_SENTRY_TURRET -> {
                event.isCancelled = true
                val clicked = event.clickedBlock
                if (clicked == null) {
                    player.sendMessage(
                        "<red>[OSOK] 🤖 Ziele auf einen Block, um den Geschützturm zu platzieren!</red>".mini()
                    )
                    return
                }
                val targetLoc = clicked.getRelative(event.blockFace).location
                if (plugin.tacticalItemsManager.placeSentryTurret(player, targetLoc)) {
                    item.subtract(1)
                }
            }
        }
    }

    private fun useRadarPulse(player: Player) {
        player.playSound(Sound.sound(BukkitSound.ENTITY_EVOKER_CAST_SPELL, Sound.Source.MASTER, 1.0f, 1.5f))
        player.world.spawnParticle(
            Particle.END_ROD, player.location.add(0.0, 1.0, 0.0), 50, 0.5, 0.5, 0.5, 0.1,
        )

        val revealed = player.location.getNearbyPlayers(RADAR_RANGE)
            .filter { it.uniqueId != player.uniqueId }
            .onEach { applyRadarGlow(it, RADAR_GLOW_TICKS) }
            .size

        player.sendMessage(
            ("<green>[OSOK] ✦ Radar-Puls ausgeführt! <yellow>$revealed</yellow> Gegner in der " +
                "Arena für 30s enthüllt!</green>").mini()
        )
    }

    private fun useSmokeBomb(player: Player) {
        val currentLoc = player.location
        currentLoc.world?.let { world ->
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, currentLoc, 200, 1.5, 1.0, 1.5, 0.05)
            world.playSound(
                Sound.sound(BukkitSound.BLOCK_FIRE_EXTINGUISH, Sound.Source.MASTER, 1.0f, 0.8f),
                currentLoc.x(), currentLoc.y(), currentLoc.z(),
            )
        }

        val randomLoc = plugin.arenaManager.getRandomArenaLocation() ?: return
        player.teleportAsync(randomLoc).thenAccept { success ->
            if (!success || !player.isOnline) return@thenAccept

            plugin.equipmentManager.giveOneShotEquipment(player)
            plugin.scoreboardManager.updateAllScoreboards()
            player.playSound(Sound.sound(BukkitSound.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 1.0f, 1.2f))
        }
    }

    /** Echter Vanish ueber hidePlayer - 15 Sekunden. */
    private fun useInvisibilityCloak(player: Player) {
        vanishedPlayers.add(player.uniqueId)
        player.addPotionEffect(
            PotionEffect(PotionEffectType.INVISIBILITY, VANISH_DURATION_TICKS.toInt(), 0, false, false)
        )

        Bukkit.getOnlinePlayers()
            .filter { it != player }
            .forEach { it.hidePlayer(plugin, player) }

        player.playSound(Sound.sound(BukkitSound.ENTITY_PHANTOM_FLAP, Sound.Source.MASTER, 1.0f, 1.5f))
        player.sendMessage(
            ("<green>[OSOK] ✦ Unsichtbarkeits-Mantel aktiviert! Du bist für 15s komplett " +
                "unsichtbar (Vanish).</green>").mini()
        )

        player.scheduler.runDelayed(
            plugin,
            {
                if (player.uniqueId in vanishedPlayers) {
                    revealPlayer(player)
                    if (player.isOnline) {
                        player.sendMessage("<red>[OSOK] ✦ Unsichtbarkeits-Mantel abgelaufen.</red>".mini())
                    }
                }
            },
            null,
            VANISH_DURATION_TICKS,
        )
    }

    /**
     * Markiert einen Gegner fuer den Radar-Puls.
     *
     * Bewusst ueber das Glow-Flag der Entity statt ueber `PotionEffectType.GLOWING`: Ein
     * Potion-Effekt taucht beim Betroffenen immer im Effekt-Fenster des Inventars auf, selbst mit
     * `icon=false`. Ohne Potion-Effekt gibt es fuer ihn nichts zu sehen - nur die Gegner sehen den
     * Leuchtrahmen.
     */
    private fun applyRadarGlow(target: Player, durationTicks: Long) {
        val targetId = target.uniqueId
        val generation = (radarGlowGeneration[targetId] ?: 0) + 1
        radarGlowGeneration[targetId] = generation

        // Ueber den GlowManager, damit die Anti-Camping-Markierung das Radar-Leuchten nicht
        // versehentlich wieder abschaltet (und umgekehrt)
        plugin.glowManager.add(target, GlowManager.GlowReason.RADAR)

        // Paper Entity Scheduler: an den Tick des Ziels gebunden
        target.scheduler.runDelayed(
            plugin,
            {
                // Nur zuruecksetzen, wenn seither kein neuer Radar-Puls das Ziel erfasst hat
                if (radarGlowGeneration[targetId] == generation) {
                    radarGlowGeneration.remove(targetId)
                    if (target.isOnline) {
                        plugin.glowManager.remove(target, GlowManager.GlowReason.RADAR)
                    }
                }
            },
            null,
            durationTicks,
        )
    }

    @EventHandler
    fun onBowShoot(event: EntityShootBowEvent) {
        val shooter = event.entity as? Player ?: return

        (event.projectile as? AbstractArrow)?.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED

        val killstreak = plugin.killstreakManager

        // Kettenblitz-Schuss
        if (killstreak.hasChainLightningShot(shooter.uniqueId)) {
            killstreak.removeChainLightningShot(shooter.uniqueId)
            event.projectile.persistentDataContainer
                .set(KillstreakManager.KEY_CHAIN_LIGHTNING_PDC, PersistentDataType.BYTE, 1.toByte())
            return
        }

        // Explosiv-Schuss
        if (killstreak.hasExplosiveShot(shooter.uniqueId)) {
            killstreak.removeExplosiveShot(shooter.uniqueId)
            event.projectile.persistentDataContainer
                .set(KillstreakManager.KEY_EXPLOSIVE_PDC, PersistentDataType.BYTE, 1.toByte())
        }
    }

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        when (val projectile = event.entity) {
            is EnderPearl -> {
                if (projectile.persistentDataContainer
                        .has(KillstreakManager.KEY_TP_GRENADE_PDC, PersistentDataType.BYTE)
                ) {
                    handleTeleportGrenade(projectile)
                }
            }

            is Arrow -> {
                val pdc = projectile.persistentDataContainer
                if (pdc.has(KillstreakManager.KEY_CHAIN_LIGHTNING_PDC, PersistentDataType.BYTE)) {
                    handleChainLightningArrow(projectile)
                }
                if (pdc.has(KillstreakManager.KEY_EXPLOSIVE_PDC, PersistentDataType.BYTE)) {
                    handleExplosiveArrow(projectile)
                }
            }
        }
    }

    private fun handleTeleportGrenade(pearl: EnderPearl) {
        val loc = pearl.location
        val world = loc.world ?: return

        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2)
        world.playSound(
            Sound.sound(BukkitSound.ENTITY_GENERIC_EXPLODE, Sound.Source.MASTER, 1.0f, 1.5f),
            loc.x(), loc.y(), loc.z(),
        )

        val shooter = pearl.shooter as? Player ?: return

        // Paper Spatial Entity Index: direkte Spieler-Abfrage statt Entity-Box + instanceof
        loc.getNearbyPlayers(TP_GRENADE_RADIUS)
            .filter { it != shooter }
            .forEach { victim ->
                victim.velocity = victim.location.toVector()
                    .subtract(loc.toVector())
                    .normalize()
                    .multiply(1.5)
                    .setY(0.5)
                victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 2))
                victim.sendMessage(
                    "<red>[OSOK] 🌀 Du wurdest von einer Teleport-Granate weggeschleudert!</red>".mini()
                )
            }
    }

    private fun handleChainLightningArrow(arrow: Arrow) {
        val loc = arrow.location
        val world = loc.world ?: return
        world.strikeLightningEffect(loc)

        val shooter = arrow.shooter as? Player ?: return

        loc.getNearbyPlayers(CHAIN_LIGHTNING_RADIUS)
            .filter { it.uniqueId != shooter.uniqueId }
            .take(CHAIN_LIGHTNING_MAX_TARGETS)
            .forEach { victim ->
                plugin.eliminationManager.eliminate(victim, shooter)
                victim.world.strikeLightningEffect(victim.location)
            }
    }

    private fun handleExplosiveArrow(arrow: Arrow) {
        val loc = arrow.location
        val world = loc.world ?: return

        world.createExplosion(loc, 0.0f, false, false)
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 3)
        world.playSound(
            Sound.sound(BukkitSound.ENTITY_GENERIC_EXPLODE, Sound.Source.MASTER, 1.0f, 1.0f),
            loc.x(), loc.y(), loc.z(),
        )

        val shooter = arrow.shooter as? Player ?: return

        loc.getNearbyPlayers(EXPLOSIVE_ARROW_RADIUS)
            .filter { it.uniqueId != shooter.uniqueId }
            .forEach { plugin.eliminationManager.eliminate(it, shooter) }
    }

    private companion object {
        /** Dauer des Radar-Puls-Leuchtens (30 Sekunden). */
        const val RADAR_GLOW_TICKS = 600L

        /** Reichweite des Radar-Pulses - deckt die gesamte Arena ab. */
        const val RADAR_RANGE = 200.0

        /**
         * Dauer der Vereisung nach dem Ausloesen (7 Sekunden).
         *
         * Eine platzierte Frost-Trap laeuft bewusst **nicht** von selbst ab - sie liegt, bis jemand
         * hineintritt. Dass sich keine Platten ansammeln, stellt [clearAllTraps] sicher, das bei
         * Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop laeuft.
         */
        const val FROST_TRAP_FREEZE_TICKS = 140L

        /** Unsichtbarkeits-Mantel: 15 Sekunden. */
        const val VANISH_DURATION_TICKS = 300L

        const val TP_GRENADE_RADIUS = 5.0
        const val CHAIN_LIGHTNING_RADIUS = 8.0
        const val CHAIN_LIGHTNING_MAX_TARGETS = 2
        const val EXPLOSIVE_ARROW_RADIUS = 7.0
    }
}
