package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.raytracing.RayTraceTarget
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
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
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.Sound as BukkitSound

/**
 * Drei taktische Spezial-Items, die sich Infrastruktur teilen (Partikelstrahl, Aufraeumen,
 * Arena-Grenzpruefung):
 *
 * - **🔭 Railgun** - Hitscan-Schuss per `World#rayTrace`. Sichtlinie = Kill.
 * - **🕳 Singularitaet** - Wurfgeschoss, das 4s lang alle Spieler zum Zentrum zieht.
 * - **🦅 Gleitflug** - 8s Elytra-Flug mit Schubstoessen, gedeckelt durch die Map-Decke.
 *
 * Alle drei sind ueber [clearAll] restlos zurueckzunehmen, damit Match-Ende, Map-Wechsel und
 * Plugin-Stop keine Reste hinterlassen.
 */
class TacticalItemsManager(private val plugin: OneShotOneKill) : Listener {

    private class ActiveTurret(val entity: ArmorStand, var ticksRemaining: Int) {
        var task: ScheduledTask? = null
    }

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

    @EventHandler
    fun onProjectileHit(event: ProjectileHitEvent) {
        val orb = event.entity as? Snowball ?: return
        if (!orb.persistentDataContainer.has(KEY_SINGULARITY_ORB, PersistentDataType.BYTE)) return

        openSingularity(orb.location.clone(), orb.shooter as? Player)
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
            armor.customName("<gold>🤖 Geschützturm (<yellow>20s</yellow>)</gold>".mini())
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

        val activeTurret = ActiveTurret(stand, TURRET_DURATION_TICKS)
        activeTurrets.add(activeTurret)

        owner.sendMessage(
            ("<green>[OSOK] 🤖 <b>GESCHÜTZTURM PLATZIERT!</b> <gray>Er sichert den Bereich " +
                "für 20 Sekunden.</gray></green>").mini()
        )

        stand.scheduler.runAtFixedRate(
            plugin,
            { task -> tickTurret(activeTurret, task, stand, owner, world) },
            // retired: Paper ruft das, wenn die Entity verschwindet, bevor der Takt endet
            // (Chunk-Entladung, fremdes Plugin, /kill). Mit null bliebe ein Karteileichen-Eintrag
            // in activeTurrets stehen.
            { removeTurret(activeTurret) },
            1L,
            TURRET_FIRE_INTERVAL_TICKS,
        )

        return true
    }

    private fun tickTurret(
        turret: ActiveTurret,
        task: ScheduledTask,
        stand: ArmorStand,
        owner: Player,
        world: World,
    ) {
        turret.task = task
        turret.ticksRemaining -= TURRET_FIRE_INTERVAL_TICKS.toInt()

        val match = plugin.matchManager
        if (!stand.isValid || turret.ticksRemaining <= 0 || !match.isMatchStarted || match.isMatchPaused) {
            task.cancel()
            removeTurret(turret)
            return
        }

        val secondsLeft = maxOf(1, (turret.ticksRemaining + 19) / 20)
        stand.customName("<gold>🤖 Geschützturm (<yellow>${secondsLeft}s</yellow>)</gold>".mini())

        val turretEye = stand.location.add(0.0, 0.8, 0.0)
        val target = findTurretTarget(turretEye, owner, world) ?: return

        val dir = target.eyeLocation.toVector().subtract(turretEye.toVector()).normalize()
        // Nur die Blickrichtung aendern, NICHT die Position: turretEye liegt 0.8 Bloecke ueber dem
        // Stand. Wurde dorthin teleportiert, stieg der Turm mit jedem Schuss um 0.8 auf und
        // schwebte wegen setGravity(false) davon. Regel 6 erlaubt das synchrone teleport hier:
        // dieselbe Welt, jeder Takt, Zielchunk geladen, Aufruf bereits auf dem Main-Thread.
        stand.teleport(stand.location.apply { direction = dir })

        world.spawn(turretEye.clone().add(dir.clone().multiply(0.4)), Arrow::class.java) { arrow ->
            arrow.shooter = owner
            arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
            arrow.velocity = dir.clone().multiply(1.8)
        }

        world.playSound(
            Sound.sound(BukkitSound.BLOCK_DISPENSER_LAUNCH, Sound.Source.MASTER, 1.0f, 1.4f),
            turretEye.x(), turretEye.y(), turretEye.z(),
        )

        val particleStep = dir.clone().multiply(0.5)
        val particleLoc = turretEye.clone()
        repeat(TURRET_TRACER_STEPS) {
            particleLoc.add(particleStep)
            world.spawnParticle(Particle.DUST, particleLoc, 1, Particle.DustOptions(Color.RED, 0.8f))
        }
    }

    /** Naechster erreichbarer Gegner in Reichweite - Waende blocken die Sichtlinie. */
    private fun findTurretTarget(turretEye: Location, owner: Player, world: World): Player? =
        turretEye.getNearbyPlayers(TURRET_RANGE)
            .filter { it.uniqueId != owner.uniqueId }
            .filterNot { it.isDead || !it.isOnline }
            .filter { it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE }
            .filter { plugin.arenaManager.isInArenaArea(it.location) }
            .filter { hasLineOfSight(turretEye, it, world) }
            .minByOrNull { it.location.distanceSquared(turretEye) }

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

        activeGliders.toList().forEach { gliderId ->
            val player = Bukkit.getPlayer(gliderId)
            if (player != null) stopGlide(player, false) else activeGliders.remove(gliderId)
        }
        activeGliders.clear()

        // Sicherheitsnetz: Schwingen einsammeln, deren Traeger den Task nie beendet hat
        Bukkit.getOnlinePlayers().forEach { removeGliderWings(it) }

        // Sicherheitsnetz fuer Geschuetztuerme: PDC-markierte Staende einsammeln, die nie
        // registriert wurden oder einen Absturz ueberlebt haben.
        var orphans = 0
        for (world in Bukkit.getWorlds()) {
            world.getEntitiesByClass(ArmorStand::class.java)
                .filter { it.persistentDataContainer.has(KEY_SENTRY_TURRET_ENTITY, PersistentDataType.BYTE) }
                .forEach {
                    it.remove()
                    orphans++
                }
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
        const val TURRET_TRACER_STEPS = 8

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
    }
}
