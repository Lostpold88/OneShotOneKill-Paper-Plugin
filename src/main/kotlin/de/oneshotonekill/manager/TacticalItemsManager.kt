package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.math.Angle
import io.papermc.paper.raytracing.RayTraceTarget
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.EulerAngle
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.Sound as BukkitSound

/**
 * Vier taktische Spezial-Items, die sich Infrastruktur teilen (Partikelstrahl, Aufraeumen,
 * Arena-Grenzpruefung):
 *
 * - **🔭 Railgun** - Hitscan-Schuss per `World#rayTrace`. Sichtlinie = Kill.
 * - **🕳 Singularitaet** - Wurfgeschoss, das 4s lang alle Spieler zum Zentrum zieht.
 * - **🦅 Gleitflug** - 8s Elytra-Flug mit Schubstoessen, gedeckelt durch die Map-Decke.
 * - **🤖 Geschuetzturm** - 20s Automat, der Gegner in Sichtlinie beschiesst. Als einzige Waffe im
 *   Spiel toetet er **nicht** mit einem Treffer, sondern erst mit dem dritten.
 *
 * Alle vier sind ueber [clearAll] restlos zurueckzunehmen, damit Match-Ende, Map-Wechsel und
 * Plugin-Stop keine Reste hinterlassen.
 */
class TacticalItemsManager(private val plugin: OneShotOneKill) : Listener {

    /**
     * Ein laufender Geschuetzturm.
     *
     * Der Besitzer steckt als UUID darin und **nicht** als [Player]-Referenz: Der Turm laeuft 20
     * Sekunden, und eine so lange festgehaltene Spieler-Instanz zeigt nach einem Rejoin auf eine
     * tote Verbindung.
     */
    private class ActiveTurret(val entity: ArmorStand, val ownerId: UUID, var ticksRemaining: Int) {
        var task: ScheduledTask? = null

        /** Aktuell anvisierter Gegner - siehe [acquireTarget]. */
        var targetId: UUID? = null

        /** Zielposition des vorigen Takts. Daraus entsteht der Vorhalt auf laufende Ziele. */
        var lastTargetPos: Vector? = null

        /** Zuletzt in den Namen geschriebene Restsekunde; verhindert unnoetiges Neu-Parsen. */
        var shownSeconds = -1
    }

    /** Trefferkonto eines Spielers gegenueber Geschuetztuermen. */
    private class TurretHits(var count: Int, var lastHitTick: Int)

    /**
     * Eine laufende Singularitaet. Jede fuehrt ihre **eigene** Ausschlussliste - zwei gleichzeitig
     * offene Singularitaeten duerfen sich nicht gegenseitig beeinflussen.
     */
    private class ActiveSingularity {
        val excluded = mutableSetOf<UUID>()
        var task: ScheduledTask? = null
    }

    /** Laufende Singularitaeten, damit sie beim Aufraeumen abgebrochen werden koennen. */
    private val activeSingularities = mutableSetOf<ActiveSingularity>()
    private val activeGliders = mutableSetOf<UUID>()
    private val activeTurrets = mutableSetOf<ActiveTurret>()

    /**
     * Turmtreffer je Opfer. Der Geschuetzturm ist die einzige Waffe im Spiel, die nicht sofort
     * toetet - deshalb ist er auch der einzige, der eine Buchfuehrung braucht.
     */
    private val turretHits = mutableMapOf<UUID, TurretHits>()

    // ==================================================================
    // 🔭 Railgun
    // ==================================================================

    /**
     * Feuert den Strahl **sofort** ab - ohne Ladephase.
     *
     * `World#rayTrace` liefert in **einem** Aufruf den naechsten Treffer - egal ob Block oder
     * Spieler. Damit blockt eine Wand den Schuss zuverlaessig, ohne dass Block- und Entity-Raytrace
     * von Hand verglichen werden muessen.
     */
    fun fireRailgun(shooter: Player) {
        val world = shooter.world
        val eye = shooter.eyeLocation
        val direction = eye.direction
        val shooterId = shooter.uniqueId

        val result = world.rayTrace(
            eye,
            direction,
            RAILGUN_RANGE,
            FluidCollisionMode.NEVER,
            // durchlaessige Bloecke (Gras, Scheiben) ignorieren
            true,
            RAILGUN_RAY_SIZE,
        ) { entity ->
            entity is Player &&
                entity.uniqueId != shooterId &&
                plugin.arenaManager.isInArenaArea(entity.location)
        }

        val impact = result?.hitPosition?.toLocation(world)
            ?: eye.clone().add(direction.clone().multiply(RAILGUN_RANGE))

        shooter.playSound(Sound.sound(BukkitSound.ITEM_SPYGLASS_USE, Sound.Source.MASTER, 1.0f, 1.8f))
        drawBeam(eye, impact, Particle.ELECTRIC_SPARK, 0.4)
        drawBeam(eye, impact, Particle.END_ROD, 0.8)
        // Particle.FLASH verlangt zwingend ein Color-Datenobjekt. Ohne das wirft CraftParticle
        // "missing required data class org.bukkit.Color" und der ganze Schuss bricht mittendrin ab -
        // der Strahl wurde gezeichnet, aber nie ausgewertet.
        world.spawnParticle(Particle.FLASH, impact, 1, 0.0, 0.0, 0.0, 0.0, Color.WHITE)
        world.playSound(
            Sound.sound(BukkitSound.ITEM_TRIDENT_THUNDER, Sound.Source.MASTER, 1.0f, 1.4f),
            eye.x(), eye.y(), eye.z(),
        )
        world.playSound(
            Sound.sound(BukkitSound.ENTITY_LIGHTNING_BOLT_IMPACT, Sound.Source.MASTER, 0.8f, 1.8f),
            impact.x(), impact.y(), impact.z(),
        )

        val victim = result?.hitEntity as? Player
        if (victim != null) {
            shooter.sendMessage(
                ("<green>[OSOK] 🔭 <b>VOLLTREFFER!</b> <gray>Die Railgun hat " +
                    "<yellow>${victim.name}</yellow> durchschlagen.</gray></green>").mini()
            )
            plugin.eliminationManager.eliminate(victim, shooter)
        } else {
            shooter.sendMessage("<red>[OSOK] 🔭 Fehlschuss! Der Strahl hat kein Ziel getroffen.</red>".mini())
            shooter.playSound(
                Sound.sound(BukkitSound.ITEM_SPYGLASS_STOP_USING, Sound.Source.MASTER, 1.0f, 0.8f)
            )
        }
    }

    /** Zeichnet eine Partikellinie zwischen zwei Punkten. */
    private fun drawBeam(from: Location, to: Location, particle: Particle, step: Double) {
        val world = from.world ?: return
        if (world != to.world) return

        val path = to.toVector().subtract(from.toVector())
        val length = path.length()
        if (length < 0.1) return

        val unit = path.normalize().multiply(step)
        val cursor = from.clone()
        var travelled = 0.0
        while (travelled < length) {
            world.spawnParticle(particle, cursor, 1, 0.0, 0.0, 0.0, 0.0)
            cursor.add(unit)
            travelled += step
        }
    }

    // ==================================================================
    // 🕳 Singularitaet
    // ==================================================================

    /** Wirft die Singularitaet als Geschoss. Gezuendet wird beim Einschlag. */
    fun throwSingularity(player: Player) {
        val orb = player.launchProjectile(Snowball::class.java, player.eyeLocation.direction.multiply(1.4))
        // Optik des Geschosses: Echo-Scherbe statt Schneeball
        orb.item = ItemStack.of(Material.ECHO_SHARD)
        orb.persistentDataContainer.set(KEY_SINGULARITY_ORB, PersistentDataType.BYTE, 1.toByte())

        player.playSound(Sound.sound(BukkitSound.BLOCK_SCULK_SHRIEKER_SHRIEK, Sound.Source.MASTER, 0.8f, 1.4f))
        player.sendMessage(
            ("<dark_purple>[OSOK] 🕳 Singularität geworfen! <gray>Sie reißt alles in ihrer " +
                "Nähe zusammen.</gray></dark_purple>").mini()
        )
    }

    /**
     * Einschlaege beider hier verwalteter Geschosse: Singularitaets-Kugel und Turmpfeil.
     */
    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        when (val projectile = event.entity) {
            is Snowball ->
                if (projectile.persistentDataContainer.has(KEY_SINGULARITY_ORB, PersistentDataType.BYTE)) {
                    openSingularity(projectile.location.clone(), projectile.shooter as? Player)
                }

            is Arrow ->
                if (isTurretArrow(projectile)) handleTurretArrowHit(projectile, event)
        }
    }

    /**
     * Oeffnet die Singularitaet: 4 Sekunden Sog auf die Gegner im Umkreis.
     *
     * Vom Sog ausgenommen sind der **Werfer** und jeder, der waehrenddessen **eliminiert wurde** -
     * siehe [excludeFromSingularities]. Die Singularitaet richtet keinen Schaden an; sie ist ein
     * Aufbau-Item fuer Air-Strike, C4 und Bomber.
     */
    private fun openSingularity(center: Location, owner: Player?) {
        val world = center.world ?: return

        world.playSound(
            Sound.sound(BukkitSound.ENTITY_WARDEN_SONIC_BOOM, Sound.Source.MASTER, 1.0f, 1.3f),
            center.x(), center.y(), center.z(),
        )
        owner?.let {
            Bukkit.broadcast(
                ("<dark_purple>[OSOK] 🕳 <white>${it.name}</white> hat eine " +
                    "<b>SINGULARITÄT</b> geöffnet!</dark_purple>").mini()
            )
        }

        val singularity = ActiveSingularity()
        // Der Werfer wird von seiner eigenen Singularitaet nicht erfasst
        owner?.let { singularity.excluded.add(it.uniqueId) }
        activeSingularities.add(singularity)

        var ticksLeft = SINGULARITY_DURATION_TICKS
        singularity.task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (ticksLeft <= 0) {
                    task.cancel()
                    activeSingularities.remove(singularity)
                    collapse(center)
                    return@runAtFixedRate
                }

                drawVortex(center, ticksLeft)

                // Paper Spatial Entity Index: direkte Spieler-Abfrage statt Entity-Box + instanceof
                center.getNearbyPlayers(SINGULARITY_RADIUS)
                    .filterNot { it.uniqueId in singularity.excluded }
                    .filter { plugin.arenaManager.isInArenaArea(it.location) }
                    .forEach { applyPull(it, center) }

                ticksLeft -= SINGULARITY_PERIOD_TICKS.toInt()
            },
            1L,
            SINGULARITY_PERIOD_TICKS,
        )
    }

    /**
     * Nimmt einen Spieler dauerhaft aus allen laufenden Singularitaeten heraus.
     *
     * Wird bei jeder Eliminierung gerufen. Ohne das wuerde ein Spieler, der beim Respawn zufaellig
     * wieder in Reichweite landet, sofort erneut eingesogen - der Sog haengt nur an der Position,
     * nicht daran, ob es noch derselbe "Anlauf" ist.
     */
    fun excludeFromSingularities(playerId: UUID) {
        activeSingularities.forEach { it.excluded.add(playerId) }
    }

    private fun applyPull(player: Player, center: Location) {
        val delta = center.toVector().subtract(player.location.toVector())
        val distance = delta.length()
        if (distance < 0.6) {
            player.velocity = Vector(0.0, 0.22, 0.0)
            return
        }

        // Naeher am Zentrum zieht es staerker, aber auch am Rand bleibt ein spuerbarer Sog
        val strength = SINGULARITY_PULL * (0.45 + 0.55 * (1.0 - minOf(distance / SINGULARITY_RADIUS, 1.0)))
        val pull = delta.normalize().multiply(strength)
        // Leichter Auftrieb, damit die Bodenreibung den Sog nicht auffrisst
        pull.setY(maxOf(pull.y, 0.14))

        player.velocity = player.velocity.multiply(0.55).add(pull)
    }

    private fun drawVortex(center: Location, ticksLeft: Int) {
        val world = center.world ?: return

        val phase = (SINGULARITY_DURATION_TICKS - ticksLeft) * 0.35
        val radius = SINGULARITY_RADIUS * (0.35 + 0.65 * (ticksLeft / SINGULARITY_DURATION_TICKS.toDouble()))

        for (arm in 0 until VORTEX_ARMS) {
            val angle = phase + arm * (Math.PI / 2.0)
            world.spawnParticle(
                Particle.REVERSE_PORTAL,
                center.x + cos(angle) * radius,
                center.y + 0.6,
                center.z + sin(angle) * radius,
                3, 0.1, 0.4, 0.1, 0.02,
            )
        }
        world.spawnParticle(Particle.SCULK_SOUL, center, 4, 0.3, 0.3, 0.3, 0.01)
        world.spawnParticle(Particle.PORTAL, center, 12, 0.4, 0.4, 0.4, 0.6)
    }

    private fun collapse(center: Location) {
        val world = center.world ?: return

        world.spawnParticle(Particle.SONIC_BOOM, center, 1)
        world.spawnParticle(Particle.SCULK_SOUL, center, 40, 0.2, 0.2, 0.2, 0.25)
        world.playSound(
            Sound.sound(BukkitSound.BLOCK_CONDUIT_DEACTIVATE, Sound.Source.MASTER, 1.0f, 0.6f),
            center.x(), center.y(), center.z(),
        )
    }

    // ==================================================================
    // 🦅 Gleitflug
    // ==================================================================

    /**
     * Startet den Gleitflug: Schwingen in den Brustslot, Startschub, danach 8 Sekunden Gleiten mit
     * regelmaessigen Schubstoessen.
     *
     * @return `false`, wenn bereits ein Gleitflug laeuft (Item nicht verbrauchen)
     */
    fun startGlide(player: Player): Boolean {
        val playerId = player.uniqueId
        if (!activeGliders.add(playerId)) {
            player.sendMessage("<red>[OSOK] 🦅 Dein Gleitflug läuft bereits!</red>".mini())
            player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            return false
        }

        player.inventory.setChestplate(createGliderWings())
        player.velocity = player.location.direction.normalize().multiply(0.7).setY(1.15)
        player.playSound(Sound.sound(BukkitSound.ENTITY_FIREWORK_ROCKET_LAUNCH, Sound.Source.MASTER, 1.0f, 1.2f))
        player.sendMessage(
            ("<aqua>[OSOK] 🦅 <b>GLEITFLUG AKTIV!</b> <gray>8 Sekunden - schau in die Richtung, " +
                "in die du willst.</gray></aqua>").mini()
        )

        var ticksLeft = GLIDE_DURATION_TICKS
        var runs = 0
        /** setGliding wurde angestossen. */
        var launched = false
        /** Der Server hat den Gleitflug bestaetigt - erst danach zaehlt eine Landung. */
        var airborne = false

        // Paper Entity Scheduler: an den Tick des Spielers gebunden
        player.scheduler.runAtFixedRate(
            plugin,
            { task ->
                if (!player.isOnline || playerId !in activeGliders || ticksLeft <= 0) {
                    task.cancel()
                    stopGlide(player, true)
                    return@runAtFixedRate
                }

                // Erst nach dem Startschub gleiten - sonst faellt der Spieler sofort wieder
                if (!launched && runs >= 2) {
                    player.isGliding = true
                    launched = true
                }

                val gliding = player.isGliding
                if (launched && gliding) {
                    airborne = true
                }

                // Landung: Der Flug endet sofort, nicht erst nach Ablauf der acht Sekunden. Ohne
                // diese Pruefung liefen Partikel und Flugsound am Boden weiter.
                if (airborne && !gliding) {
                    task.cancel()
                    stopGlide(player, true)
                    return@runAtFixedRate
                }

                if (gliding && runs % GLIDE_BOOST_EVERY == 0) {
                    applyGlideBoost(player)
                }

                val trail = player.location
                trail.world?.spawnParticle(Particle.END_ROD, trail, 2, 0.2, 0.2, 0.2, 0.01)

                runs++
                ticksLeft -= GLIDE_PERIOD_TICKS.toInt()
            },
            null,
            2L,
            GLIDE_PERIOD_TICKS,
        )

        return true
    }

    /**
     * Schubstoss nach vorn, gedeckelt durch Hoechstgeschwindigkeit und Flughoehe.
     *
     * Die Hoehenbegrenzung ist Pflicht: Auf der offenen DustPvP-Map koennte der Spieler sonst ueber
     * die Arena-Oberkante hinaussteigen - und ausserhalb der Arena ist jeder Kampf deaktiviert.
     */
    private fun applyGlideBoost(player: Player) {
        val map = plugin.worldManager.activeMapConfig
        var velocity = player.velocity.add(player.location.direction.normalize().multiply(GLIDE_BOOST))

        if (velocity.length() > GLIDE_MAX_SPEED) {
            velocity = velocity.normalize().multiply(GLIDE_MAX_SPEED)
        }

        val ceiling = minOf(map.maxFlyY, map.maxY + GLIDE_HEADROOM)
        if (player.location.y >= ceiling && velocity.y > 0.0) {
            velocity.setY(-0.2)
        }

        player.velocity = velocity
    }

    /**
     * Beendet einen laufenden Gleitflug. Mehrfachaufrufe sind unschaedlich.
     *
     * Der Flugsound wird ausdruecklich per `stopSound` abgewuergt. Der Client spielt
     * `item.elytra.flying` als eigene, laufende Soundinstanz, solange er den Spieler fuer gleitend
     * haelt - ein blosses `setGliding(false)` liess ihn noch sekundenlang nachklingen, sowohl bei
     * der Landung als auch nach Ablauf der acht Sekunden.
     */
    fun stopGlide(player: Player, notify: Boolean) {
        if (!activeGliders.remove(player.uniqueId)) return
        if (!player.isOnline) return

        player.isGliding = false
        removeGliderWings(player)
        // Ohne Quellenangabe: stoppt den Flugsound auf jeder Sound-Kategorie
        player.stopSound(SoundStop.named(BukkitSound.ITEM_ELYTRA_FLYING))
        // Sanfte Landung: Sturzschaden waere in der Arena toedlich
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, false, false))

        if (notify) {
            player.sendMessage("<red>[OSOK] 🦅 Gleitflug beendet.</red>".mini())
            // Kurzer, abschliessender Ton statt einer weiteren Elytra-Schleife
            player.playSound(Sound.sound(BukkitSound.ITEM_ARMOR_EQUIP_ELYTRA, Sound.Source.MASTER, 0.8f, 0.8f))
        }
    }

    /**
     * Schwingen des Gleitflugs. Das `GLIDER`-Datenkomponent wird bewusst **explizit** gesetzt,
     * damit das Flugverhalten nicht von der Standardbelegung des Materials abhaengt.
     */
    private fun createGliderWings(): ItemStack = ItemStack.of(Material.ELYTRA).apply {
        // Paper DataComponents durchgehend - Name, Lore, Unzerstoerbarkeit, Gleit-Faehigkeit und
        // Glanz direkt am Item
        setData(DataComponentTypes.CUSTOM_NAME, "<aqua><b>🦅 Gleitflug-Schwingen</b></aqua>".mini())
        setData(
            DataComponentTypes.LORE,
            ItemLore.lore(listOf("<gray>Verschwinden am Ende des Fluges.</gray>".mini())),
        )
        setData(DataComponentTypes.UNBREAKABLE)
        setData(DataComponentTypes.GLIDER)
        setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
        editPersistentDataContainer { pdc -> pdc.set(KEY_GLIDER_WINGS, PersistentDataType.BYTE, 1.toByte()) }
    }

    /** Entfernt die Schwingen aus dem gesamten Inventar - auch wenn sie umgelagert wurden. */
    private fun removeGliderWings(player: Player) {
        if (isGliderWings(player.inventory.chestplate)) {
            player.inventory.setChestplate(null)
        }
        player.inventory.contents.forEachIndexed { slot, stack ->
            if (isGliderWings(stack)) {
                player.inventory.setItem(slot, null)
            }
        }
    }

    private fun isGliderWings(stack: ItemStack?): Boolean =
        stack != null && !stack.isEmpty &&
            stack.persistentDataContainer.has(KEY_GLIDER_WINGS, PersistentDataType.BYTE)

    /** Die Schwingen sind Leihgabe: Sie duerfen nicht im Inventar umgelagert werden. */
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (isGliderWings(event.currentItem) || isGliderWings(event.cursor)) {
            event.isCancelled = true
        }
    }

    // ==================================================================
    // 🤖 Geschuetzturm (Sentry Turret)
    // ==================================================================

    /**
     * Stellt einen Geschuetzturm auf den angeklickten Block.
     *
     * @return `false`, wenn nicht platziert werden durfte (Item nicht verbrauchen)
     */
    fun placeSentryTurret(owner: Player, clickLoc: Location): Boolean {
        val match = plugin.matchManager
        if (!match.isMatchStarted || match.isMatchPaused) {
            owner.sendMessage("<red>[OSOK] 🤖 Das Match ist nicht aktiv!</red>".mini())
            owner.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            return false
        }
        if (!plugin.arenaManager.isInArenaArea(clickLoc)) {
            owner.sendMessage(
                "<red>[OSOK] 🤖 Geschützturm kann nur in der Arena platziert werden!</red>".mini()
            )
            owner.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            return false
        }

        val turretLoc = clickLoc.clone().add(0.5, 0.2, 0.5)
        val world = turretLoc.world ?: return false

        val stand = world.spawn(turretLoc, ArmorStand::class.java) { armor ->
            armor.isInvisible = true
            armor.isSmall = true
            armor.setGravity(false)
            armor.isMarker = false
            armor.equipment.setHelmet(ItemStack.of(Material.DISPENSER))
            armor.customName(turretName(TURRET_DURATION_TICKS / 20))
            armor.isCustomNameVisible = true
            // Nicht speichern und per PDC markieren: Ohne beides bliebe nach einem Serverabsturz
            // ein unsichtbarer Stand fuer immer in der Map liegen, den clearAll() nicht mehr
            // findet. Gleiche Absicherung wie bei C4 und Bomber.
            armor.isPersistent = false
            armor.persistentDataContainer.set(KEY_SENTRY_TURRET_ENTITY, PersistentDataType.BYTE, 1.toByte())

            // Ausruestung sperren, sonst laesst sich der Dispenser per Rechtsklick vom Kopf nehmen -
            // der Turm waere damit wieder "aufgesammelt". addEquipmentLock ist die
            // Vanilla-Mechanik (DisabledSlots): Sie sitzt an der Entity selbst und braucht keinen
            // zusaetzlichen Listener. Muss NACH setHelmet stehen, sonst waere schon das Anlegen
            // blockiert.
            TURRET_LOCKED_SLOTS.forEach { slot ->
                armor.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING)
                armor.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING)
            }
        }

        world.playSound(
            Sound.sound(BukkitSound.BLOCK_ANVIL_USE, Sound.Source.MASTER, 1.0f, 1.4f),
            turretLoc.x(), turretLoc.y(), turretLoc.z(),
        )
        world.spawnParticle(Particle.FLAME, turretLoc.clone().add(0.0, 0.8, 0.0), 20, 0.3, 0.3, 0.3, 0.05)

        val activeTurret = ActiveTurret(stand, owner.uniqueId, TURRET_DURATION_TICKS)
        activeTurrets.add(activeTurret)

        owner.sendMessage(
            ("<green>[OSOK] 🤖 <b>GESCHÜTZTURM PLATZIERT!</b> <gray>Er sichert den Bereich für " +
                "20 Sekunden - <yellow>$TURRET_HITS_TO_KILL Treffer</yellow> schalten einen " +
                "Gegner aus.</gray></green>").mini()
        )

        activeTurret.task = stand.scheduler.runAtFixedRate(
            plugin,
            { task -> tickTurret(activeTurret, task) },
            // retired: Paper ruft das, wenn die Entity verschwindet, bevor der Takt endet
            // (Chunk-Entladung, fremdes Plugin, /kill). Mit null bliebe ein Karteileichen-Eintrag
            // in activeTurrets stehen.
            { removeTurret(activeTurret) },
            1L,
            TURRET_FIRE_INTERVAL_TICKS,
        )

        return true
    }

    /**
     * Ein Feuertakt: Restzeit fortschreiben, Ziel bestimmen, ausrichten, schiessen.
     *
     * Der Besitzer wird in **jedem** Takt frisch aufgeloest. Ist er offline, endet der Turm - ohne
     * Schuetzen liesse sich kein Treffer mehr zuordnen.
     */
    private fun tickTurret(turret: ActiveTurret, task: ScheduledTask) {
        val stand = turret.entity
        turret.ticksRemaining -= TURRET_FIRE_INTERVAL_TICKS.toInt()

        val match = plugin.matchManager
        val owner = Bukkit.getPlayer(turret.ownerId)
        if (owner == null || !stand.isValid || turret.ticksRemaining <= 0 ||
            !match.isMatchStarted || match.isMatchPaused
        ) {
            task.cancel()
            removeTurret(turret)
            return
        }

        val world = stand.world
        val secondsLeft = maxOf(1, (turret.ticksRemaining + 19) / 20)
        // Der Name wird nur beim echten Sekundenwechsel neu gebaut - sonst parst MiniMessage
        // mehrmals pro Sekunde denselben Text.
        if (secondsLeft != turret.shownSeconds) {
            turret.shownSeconds = secondsLeft
            stand.customName(turretName(secondsLeft))
        }

        val muzzle = stand.location.add(0.0, TURRET_MUZZLE_HEIGHT, 0.0)
        val target = acquireTarget(turret, muzzle, owner) ?: return

        val aim = predictAim(turret, target, muzzle)
        val direction = aim.clone().subtract(muzzle.toVector()).normalize()

        // Nur die Ausrichtung aendern, NICHT die Position: Die Muendung liegt 0.8 Bloecke ueber dem
        // Stand. Wurde der Stand dorthin teleportiert, stieg er mit jedem Schuss um 0.8 auf und
        // schwebte wegen setGravity(false) davon. setRotation dreht ihn an Ort und Stelle und
        // spart den Teleport ganz ein.
        val facing = stand.location.setDirection(direction)
        stand.setRotation(Angle.absolute(facing.yaw), Angle.absolute(facing.pitch))
        // Der Dispenser sitzt auf dem Kopf: Ueber die Kopfhaltung neigt sich das Rohr sichtbar mit,
        // statt nur den Koerper zu drehen.
        stand.headPose = EulerAngle(Math.toRadians(facing.pitch.toDouble()), 0.0, 0.0)

        val velocity = direction.clone().multiply(TURRET_ARROW_SPEED)
        val barrel = muzzle.clone().add(direction.clone().multiply(TURRET_MUZZLE_OFFSET))

        world.spawn(barrel, Arrow::class.java) { arrow ->
            arrow.shooter = owner
            arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
            arrow.isCritical = false
            // Der Turmpfeil richtet keinen Vanilla-Schaden an: Gezaehlt wird im
            // ProjectileHitEvent, und ein Pfeiltreffer wuerde in diesem Spiel sofort toeten.
            arrow.damage = 0.0
            // Nicht speichern - sonst laege nach einem Absturz Munition in der Map
            arrow.isPersistent = false
            arrow.persistentDataContainer.set(KEY_SENTRY_TURRET_ARROW, PersistentDataType.BYTE, 1.toByte())
            arrow.velocity = velocity
        }

        world.playSound(
            Sound.sound(BukkitSound.BLOCK_DISPENSER_LAUNCH, Sound.Source.MASTER, 1.0f, 1.4f),
            muzzle.x(), muzzle.y(), muzzle.z(),
        )

        drawTracer(world, barrel, velocity)
    }

    /**
     * Liefert das Ziel des naechsten Schusses.
     *
     * Ein einmal gefasstes Ziel wird **gehalten**, solange es gueltig bleibt. Das ist keine
     * Bequemlichkeit, sondern Voraussetzung: Der Turm braucht drei Treffer fuer eine Eliminierung,
     * und wer seine Schuesse reihum auf alle Gegner in Reichweite verteilt, kommt nie auf drei.
     * Nebeneffekt: Der teure Sichtlinien-Strahl laeuft nur, wenn das Ziel wirklich neu gesucht
     * werden muss.
     */
    private fun acquireTarget(turret: ActiveTurret, muzzle: Location, owner: Player): Player? {
        val locked = turret.targetId?.let { Bukkit.getPlayer(it) }
        if (locked != null && isTurretTarget(locked, muzzle, owner)) return locked

        // Nach Entfernung sortiert und dann der erste gueltige: So wird nur so lange gestrahlt,
        // bis ein Ziel feststeht - statt fuer jeden Kandidaten in Reichweite.
        val next = muzzle.getNearbyPlayers(TURRET_RANGE)
            .sortedBy { it.location.distanceSquared(muzzle) }
            .firstOrNull { isTurretTarget(it, muzzle, owner) }

        turret.targetId = next?.uniqueId
        // Ein neues Ziel hat noch keine Vorgeschichte - ohne das Zuruecksetzen entstuende der
        // Vorhalt aus der Positionsdifferenz zweier verschiedener Spieler.
        turret.lastTargetPos = null
        return next
    }

    /**
     * Ein gueltiges Ziel ist online, spielt aktiv mit, steht in Reichweite **und** in der Arena,
     * ist nicht unsichtbar und hat freie Sichtlinie.
     *
     * Die Unsichtbarkeitspruefung ist Absicht: Der Unsichtbarkeits-Mantel waere wertlos, wenn ein
     * Automat weiterhin zielsicher darauf schoesse.
     */
    private fun isTurretTarget(candidate: Player, muzzle: Location, owner: Player): Boolean {
        val world = muzzle.world ?: return false

        return candidate.uniqueId != owner.uniqueId &&
            candidate.isOnline && !candidate.isDead &&
            (candidate.gameMode == GameMode.SURVIVAL || candidate.gameMode == GameMode.ADVENTURE) &&
            !candidate.hasPotionEffect(PotionEffectType.INVISIBILITY) &&
            candidate.world == world &&
            candidate.location.distanceSquared(muzzle) <= TURRET_RANGE * TURRET_RANGE &&
            plugin.arenaManager.isInArenaArea(candidate.location) &&
            hasLineOfSight(muzzle, candidate, world)
    }

    /**
     * Zielpunkt des naechsten Schusses: Brusthoehe des Gegners, um den Vorhalt auf seine Bewegung
     * und um den Schwerkraftabfall des Pfeils versetzt.
     *
     * Der Vorhalt stammt aus der **selbst gemessenen** Positionsdifferenz zweier Takte.
     * `Player#getVelocity` taugt dafuer nicht: Bei Spielern liefert es die serverseitige
     * Delta-Bewegung, die aus Bewegungspaketen gar nicht gespeist wird und beim laufenden Spieler
     * bei null bleibt (gegen die Server-JAR geprueft).
     */
    private fun predictAim(turret: ActiveTurret, target: Player, muzzle: Location): Vector {
        val aim = target.eyeLocation.toVector().subtract(Vector(0.0, TURRET_AIM_CHEST_DROP, 0.0))
        val previous = turret.lastTargetPos
        turret.lastTargetPos = aim.clone()

        var flightTicks = muzzle.toVector().distance(aim) / TURRET_ARROW_SPEED

        if (previous != null) {
            val perTick = aim.clone().subtract(previous).multiply(1.0 / TURRET_FIRE_INTERVAL_TICKS)
            // Nur die waagerechte Bewegung: Spruenge sind zu kurz, um vorhaltbar zu sein, und
            // wuerden den Schuss nur ueber den Kopf des Gegners lenken.
            perTick.y = 0.0

            val lead = perTick.multiply(flightTicks)
            if (lead.length() > TURRET_MAX_LEAD) {
                lead.normalize().multiply(TURRET_MAX_LEAD)
            }
            aim.add(lead)
            flightTicks = muzzle.toVector().distance(aim) / TURRET_ARROW_SPEED
        }

        // Schwerkraftausgleich: Der Pfeil faellt pro Tick um ARROW_GRAVITY, aufsummiert ueber die
        // Flugzeit. Ohne diesen Zuschlag schlaegt er am Reichweitenrand rund einen Block zu tief
        // ein - genau dort, wo der Turm seine Ziele meistens hat.
        aim.y += ARROW_GRAVITY * flightTicks * (flightTicks + 1.0) / 2.0
        return aim
    }

    /**
     * Zeichnet die Leuchtspur entlang der **tatsaechlichen** Flugbahn: dieselbe Schrittweite,
     * dieselbe Luftreibung, dieselbe Schwerkraft wie beim Pfeil. Eine schnurgerade Linie wuerde
     * eine Bahn zeigen, die der Pfeil gar nicht fliegt.
     */
    private fun drawTracer(world: World, from: Location, velocity: Vector) {
        val point = from.clone()
        val step = velocity.clone().multiply(1.0 / TURRET_TRACER_SUBSTEPS)

        repeat(TURRET_TRACER_TICKS) {
            repeat(TURRET_TRACER_SUBSTEPS) {
                point.add(step)
                world.spawnParticle(Particle.DUST, point, 1, TURRET_TRACER_DUST)
            }
            step.multiply(ARROW_DRAG)
            step.y -= ARROW_GRAVITY / TURRET_TRACER_SUBSTEPS
        }
    }

    // ---------------- Treffer-Buchfuehrung ----------------

    /**
     * Erkennt einen Turmpfeil an seiner PDC-Markierung.
     *
     * Oeffentlich, weil auch der
     * [de.oneshotonekill.listener.CombatListener] ihn kennen muss: Dort endet jeder Pfeiltreffer
     * sonst in der Sofort-Eliminierung.
     */
    fun isTurretArrow(arrow: Arrow): Boolean =
        arrow.persistentDataContainer.has(KEY_SENTRY_TURRET_ARROW, PersistentDataType.BYTE)

    /**
     * Ein Turmpfeil ist eingeschlagen.
     *
     * Die Auswertung sitzt bewusst im `ProjectileHitEvent` und **nicht** im Schadensweg des
     * `CombatListener`:
     *
     * - Vanilla laesst nach einem Treffer 10 Ticks Unverwundbarkeit folgen und verschluckt gleich
     *   starke Folgetreffer **vor** jedem Schadensevent. Bei 0,4 s Feuertakt ginge damit jeder
     *   zweite Turmtreffer verloren und drei Treffer waeren kaum erreichbar.
     * - Der Projektil-Treffer laeuft davor: `Projectile#preHitTargetOrDeflectSelf` feuert diesen
     *   Event und ueberspringt bei einem Cancel den gesamten Treffer (gegen die Server-JAR
     *   geprueft). Der Pfeil richtet also garantiert keinen Schaden an.
     */
    private fun handleTurretArrowHit(arrow: Arrow, event: ProjectileHitEvent) {
        val shooter = arrow.shooter as? Player
        val victim = event.hitEntity as? Player
        val impact = arrow.velocity

        // Der Pfeil ist mit dem Einschlag verbraucht: Sonst bliebe er im Block stecken oder pralle
        // vom gecancelten Treffer ab und flaege weiter. Der kurze Funke ersetzt den steckenden
        // Pfeil als sichtbares Einschlagzeichen.
        arrow.world.spawnParticle(Particle.CRIT, arrow.location, 4, 0.05, 0.05, 0.05, 0.02)
        arrow.remove()
        if (victim == null) return

        event.isCancelled = true

        val match = plugin.matchManager
        if (!match.isMatchStarted || match.isMatchPaused || match.isMatchEnded) return
        if (!plugin.arenaManager.isInArenaArea(victim.location)) return

        // Ein Streuschuss auf den eigenen Besitzer zaehlt nicht: Der Turm nimmt ihn nie ins Visier,
        // ein Treffer ist also ein Artefakt der Flugbahn und keine Entscheidung.
        if (shooter == null || shooter.uniqueId == victim.uniqueId) return

        registerTurretHit(victim, shooter, impact)
    }

    /**
     * Verbucht einen Turmtreffer und eliminiert beim [TURRET_HITS_TO_KILL]-ten.
     *
     * Alte Treffer verfallen nach [TURRET_HIT_MEMORY_TICKS]: Wer sich lange genug aus der
     * Schusslinie haelt, faengt wieder bei null an - sonst summierten sich Streifschuesse ueber ein
     * ganzes Match zu einer Eliminierung.
     */
    private fun registerTurretHit(victim: Player, shooter: Player, impact: Vector) {
        val now = Bukkit.getCurrentTick()
        val account = turretHits[victim.uniqueId]
        val hits = if (account == null || now - account.lastHitTick > TURRET_HIT_MEMORY_TICKS) {
            1
        } else {
            account.count + 1
        }

        if (hits >= TURRET_HITS_TO_KILL) {
            turretHits.remove(victim.uniqueId)
            plugin.eliminationManager.eliminate(victim, shooter)
            return
        }

        turretHits[victim.uniqueId] = TurretHits(hits, now)

        victim.world.spawnParticle(
            Particle.DAMAGE_INDICATOR, victim.location.add(0.0, 1.0, 0.0), 6, 0.3, 0.4, 0.3, 0.05,
        )
        // Leichter Rueckstoss statt des weggefallenen Vanilla-Pfeilschadens: Der Treffer soll
        // spuerbar sein, darf den Getroffenen aber nicht von der Plattform schieben - ein Sturz
        // toetet in dieser Arena sofort und wuerde die Drei-Treffer-Regel aushebeln. Nur beim
        // nicht-toedlichen Treffer, denn eine gesetzte Geschwindigkeit ueberlebt den
        // Respawn-Teleport.
        if (impact.lengthSquared() > 0.0) {
            val push = impact.clone().normalize().multiply(TURRET_KNOCKBACK)
            push.y = 0.0
            victim.velocity = victim.velocity.add(push)
        }

        victim.playSound(Sound.sound(BukkitSound.ENTITY_ARROW_HIT_PLAYER, Sound.Source.MASTER, 1.0f, 0.8f))
        victim.sendActionBar(
            ("<red>🤖 Geschützturm-Treffer <b>$hits</b>/<b>$TURRET_HITS_TO_KILL</b> " +
                "<gray>(${shooter.name})</gray></red>").mini()
        )
        shooter.playSound(Sound.sound(BukkitSound.ENTITY_ARROW_HIT_PLAYER, Sound.Source.MASTER, 0.7f, 1.6f))
    }

    /**
     * Loescht das Turm-Trefferkonto eines Spielers. Wird bei jeder Eliminierung gerufen: Ein
     * frisches Leben faengt mit leerem Konto an, sonst genuegte nach dem Respawn ploetzlich ein
     * einziger Treffer.
     */
    fun clearTurretHits(playerId: UUID) {
        turretHits.remove(playerId)
    }

    private fun turretName(secondsLeft: Int): Component =
        "<gold>🤖 Geschützturm (<yellow>${secondsLeft}s</yellow>)</gold>".mini()

    private fun hasLineOfSight(turretEye: Location, candidate: Player, world: World): Boolean {
        val toCandidate = candidate.eyeLocation.toVector().subtract(turretEye.toVector())
        val distance = toCandidate.length()
        if (distance <= 0.1) return true

        // Paper RayTrace-Builder statt der Parameterliste - dieselbe Form, die die Railgun
        // weiter oben schon benutzt.
        val rayDir = toCandidate.clone().normalize()
        val ray = world.rayTrace { builder ->
            builder.start(turretEye)
                .direction(rayDir)
                .maxDistance(distance)
                .fluidCollisionMode(FluidCollisionMode.NEVER)
                .ignorePassableBlocks(true)
                .targets(RayTraceTarget.BLOCK)
        }
        return ray?.hitBlock == null
    }

    private fun removeTurret(turret: ActiveTurret) {
        if (!activeTurrets.remove(turret)) return

        turret.task?.cancel()
        if (turret.entity.isValid) {
            val loc = turret.entity.location
            loc.world?.let { world ->
                world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0.0, 0.5, 0.0), 2)
                world.playSound(
                    Sound.sound(BukkitSound.BLOCK_ANVIL_BREAK, Sound.Source.MASTER, 0.8f, 1.2f),
                    loc.x(), loc.y(), loc.z(),
                )
            }
            turret.entity.remove()
        }
    }

    // ==================================================================
    // Aufraeumen
    // ==================================================================

    /**
     * Nimmt alles zurueck: laufende Singularitaeten, Gleitfluege samt Schwingen, Geschuetztuerme und
     * ladende Railguns. Wird bei Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop gerufen.
     */
    fun clearAll() {
        activeSingularities.toList().forEach { it.task?.cancel() }
        activeSingularities.clear()

        activeTurrets.toList().forEach { removeTurret(it) }
        activeTurrets.clear()
        turretHits.clear()

        activeGliders.toList().forEach { gliderId ->
            val player = Bukkit.getPlayer(gliderId)
            if (player != null) stopGlide(player, false) else activeGliders.remove(gliderId)
        }
        activeGliders.clear()

        // Sicherheitsnetz: Schwingen einsammeln, deren Traeger den Task nie beendet hat
        Bukkit.getOnlinePlayers().forEach { removeGliderWings(it) }

        // Sicherheitsnetz fuer Geschuetztuerme: PDC-markierte Staende einsammeln, die nie
        // registriert wurden oder einen Absturz ueberlebt haben. Noch fliegende Turmpfeile kommen
        // mit weg - sie wuerden sonst nach dem Match-Ende noch einschlagen.
        var orphans = 0
        for (world in Bukkit.getWorlds()) {
            world.getEntitiesByClass(ArmorStand::class.java)
                .filter { it.persistentDataContainer.has(KEY_SENTRY_TURRET_ENTITY, PersistentDataType.BYTE) }
                .forEach {
                    it.remove()
                    orphans++
                }
            world.getEntitiesByClass(Arrow::class.java)
                .filter { isTurretArrow(it) }
                .forEach { it.remove() }
        }
        if (orphans > 0) {
            plugin.logger.info("[OSOK] $orphans verwaiste Geschuetzturm-Staende entfernt.")
        }
    }

    private companion object {
        // ---------------- Railgun ----------------
        /** Reichweite des Strahls in Bloecken. */
        const val RAILGUN_RANGE = 64.0

        /** Trefferradius des Strahls - etwas grosszuegiger als ein Pixelstrahl. */
        const val RAILGUN_RAY_SIZE = 0.6

        // ---------------- Singularitaet ----------------
        const val SINGULARITY_DURATION_TICKS = 80
        const val SINGULARITY_PERIOD_TICKS = 2L
        const val SINGULARITY_RADIUS = 8.0

        /** Grundstaerke des Sogs pro Impuls. */
        const val SINGULARITY_PULL = 0.75

        /** Arme des Partikelwirbels. */
        const val VORTEX_ARMS = 4

        // ---------------- Gleitflug ----------------
        const val GLIDE_DURATION_TICKS = 160
        const val GLIDE_PERIOD_TICKS = 2L

        /** Jeder wievielte Durchlauf einen Schubstoss gibt (alle 6 Ticks). */
        const val GLIDE_BOOST_EVERY = 3
        const val GLIDE_BOOST = 0.28

        /** Obergrenze der Fluggeschwindigkeit, damit niemand aus der Arena schiesst. */
        const val GLIDE_MAX_SPEED = 1.7

        /** Spielraum ueber der Arena-Oberkante, den der Gleitflug nutzen darf. */
        const val GLIDE_HEADROOM = 8.0

        // ---------------- Geschuetzturm (Sentry Turret) ----------------
        /** 20 Sekunden. */
        const val TURRET_DURATION_TICKS = 400

        /** Alle 0,4 s. */
        const val TURRET_FIRE_INTERVAL_TICKS = 8L
        const val TURRET_RANGE = 14.0

        /**
         * So viele Treffer kostet eine Eliminierung durch den Geschuetzturm.
         *
         * Der Turm ist damit die einzige Waffe im Spiel, die nicht mit einem Schlag toetet: Er
         * schiesst von allein und ohne Zielfehler - mit Sofort-Kill waere jede Deckung, die er
         * einsieht, unbetretbar.
         */
        const val TURRET_HITS_TO_KILL = 3

        /** Nach 8 Sekunden ohne Turmtreffer verfaellt das Trefferkonto. */
        const val TURRET_HIT_MEMORY_TICKS = 160

        /** Rueckstoss pro Treffer - spuerbar, aber zu schwach, um jemanden herunterzustossen. */
        const val TURRET_KNOCKBACK = 0.25

        /** Hoehe der Muendung ueber dem Fusspunkt des Stands. */
        const val TURRET_MUZZLE_HEIGHT = 0.8

        /** Abstand des Abschusspunkts vor der Muendung - sonst trifft der Pfeil den Turm selbst. */
        const val TURRET_MUZZLE_OFFSET = 0.6

        /** Startgeschwindigkeit des Turmpfeils in Bloecken pro Tick. */
        const val TURRET_ARROW_SPEED = 2.2

        /** Obergrenze des Vorhalts in Bloecken - gegen absurde Zielpunkte bei Teleports. */
        const val TURRET_MAX_LEAD = 3.0

        /** Zielpunkt unterhalb der Augen: Brusthoehe trifft auch geduckte Gegner. */
        const val TURRET_AIM_CHEST_DROP = 0.35

        /** Ticks Flugbahn, die die Leuchtspur vorzeichnet. */
        const val TURRET_TRACER_TICKS = 5

        /** Partikel je vorgezeichnetem Tick. */
        const val TURRET_TRACER_SUBSTEPS = 4

        /** Einmal angelegt statt je Partikel neu - die Optionen sind unveraenderlich. */
        val TURRET_TRACER_DUST = Particle.DustOptions(Color.RED, 0.8f)

        /** Vanilla-Pfeilphysik: Fall pro Tick und Luftreibung je Tick. */
        const val ARROW_GRAVITY = 0.05
        const val ARROW_DRAG = 0.99

        /**
         * Ausruestungsplaetze, die am Geschuetzturm gesperrt werden.
         *
         * Bewusst nur die sechs, die ein ArmorStand tatsaechlich fuehrt - `BODY` und `SADDLE` aus
         * [EquipmentSlot] gehoeren zu Reittieren.
         */
        val TURRET_LOCKED_SLOTS: List<EquipmentSlot> = listOf(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
        )

        val KEY_SINGULARITY_ORB = NamespacedKey("oneshotonekill", "singularity_orb")
        val KEY_GLIDER_WINGS = NamespacedKey("oneshotonekill", "glider_wings")

        /** Markiert den ArmorStand eines Geschuetzturms, damit auch Waisen auffindbar bleiben. */
        val KEY_SENTRY_TURRET_ENTITY = NamespacedKey("oneshotonekill", "sentry_turret_entity")

        /** Markiert einen Turmpfeil - er zaehlt, statt sofort zu toeten. */
        val KEY_SENTRY_TURRET_ARROW = NamespacedKey("oneshotonekill", "sentry_turret_arrow")
    }
}
