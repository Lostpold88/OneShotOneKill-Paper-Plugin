package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID
import org.bukkit.Sound as BukkitSound

/**
 * Tarnkappenbomber: Der Nutzer waehlt in einem Menue einen Spieler aus. Ueber diesem erscheint ein
 * Ender-Drache, der ihm 10 Sekunden lang folgt und dabei durchgehend TNT abwirft.
 *
 * Der Drache greift niemanden an (AI und Wahrnehmung deaktiviert, Schaden durch ihn wird
 * gecancelt). Das TNT zerstoert keine Bloecke (Blockliste wird geleert) und zuendet bei
 * Bodenkontakt sofort.
 */
class StealthBomberManager(private val plugin: OneShotOneKill) : Listener {

    private val activeDragons = mutableSetOf<UUID>()
    private val activeBombs = mutableSetOf<TNTPrimed>()

    // ------------------------------------------------------------------
    // Ziel-Auswahl GUI
    // ------------------------------------------------------------------

    /**
     * Oeffnet das Auswahlmenue. Liefert `false`, wenn es kein gueltiges Ziel gibt - dann darf das
     * Item nicht verbraucht werden.
     */
    fun openTargetMenu(user: Player): Boolean {
        val targets = Bukkit.getOnlinePlayers().filter { it.uniqueId != user.uniqueId }

        if (targets.isEmpty()) {
            user.sendMessage(
                "<red>[OSOK] 🐉 Kein Ziel verfuegbar - es ist kein anderer Spieler online!</red>".mini()
            )
            user.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            return false
        }

        val size = minOf(54, ((targets.size - 1) / 9 + 1) * 9)
        val gui = Bukkit.createInventory(null, size, GUI_TITLE)

        targets.take(size).forEachIndexed { index, target -> gui.setItem(index, createTargetHead(target)) }

        user.openInventory(gui)
        user.playSound(Sound.sound(BukkitSound.BLOCK_ENDER_CHEST_OPEN, Sound.Source.MASTER, 1.0f, 1.2f))
        return true
    }

    private fun createTargetHead(target: Player): ItemStack = ItemStack.of(Material.PLAYER_HEAD).apply {
        // Paper DataComponents: Kopfbesitzer, Name und Lore als Vanilla-Komponenten.
        // PROFILE ersetzt SkullMeta#setOwningPlayer - kein SkullMeta mehr noetig.
        setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(target.playerProfile))
        setData(DataComponentTypes.CUSTOM_NAME, "<light_purple><b>${target.name}</b></light_purple>".mini())
        setData(
            DataComponentTypes.LORE,
            ItemLore.lore(listOf("<gray>Klicken, um den Bomber zu starten</gray>".mini())),
        )
        editPersistentDataContainer { pdc ->
            pdc.set(KEY_GUI_TARGET, PersistentDataType.STRING, target.uniqueId.toString())
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title() != GUI_TITLE) return
        event.isCancelled = true

        val user = event.whoClicked as? Player ?: return

        val clicked = event.currentItem ?: return
        if (!clicked.hasItemMeta()) return

        val targetId = clicked.persistentDataContainer.get(KEY_GUI_TARGET, PersistentDataType.STRING) ?: return

        user.closeInventory()

        val target = Bukkit.getPlayer(UUID.fromString(targetId))
        if (target == null || !target.isOnline) {
            user.sendMessage("<red>[OSOK] 🐉 Das Ziel ist nicht mehr online!</red>".mini())
            return
        }

        // Verbrauch erst bei der Auswahl - wer das Menue schliesst, behaelt sein Item
        if (!consumeBomberItem(user)) {
            user.sendMessage("<red>[OSOK] 🐉 Du hast keinen Tarnkappenbomber mehr im Inventar!</red>".mini())
            return
        }

        launchBomber(user, target)
    }

    /** Entfernt genau einen Tarnkappenbomber aus dem Inventar. */
    private fun consumeBomberItem(user: Player): Boolean {
        val typeKey = plugin.killstreakManager.specialItemKey
        val bomber = user.inventory.contents
            .filterNotNull()
            .filter { it.hasItemMeta() }
            .firstOrNull {
                it.persistentDataContainer.get(typeKey, PersistentDataType.STRING) ==
                    KillstreakManager.KEY_STEALTH_BOMBER
            } ?: return false

        bomber.subtract(1)
        return true
    }

    // ------------------------------------------------------------------
    // Drache & TNT
    // ------------------------------------------------------------------

    /**
     * Flugposition des Drachen ueber dem Ziel, begrenzt durch die Decke der aktiven Map. Die
     * Standard-Arena ist ueberdacht - ohne diese Begrenzung wuerde der Drache in der Decke stecken
     * oder darueber schweben.
     */
    private fun dragonPositionAbove(target: Player): Location {
        val loc = target.location.clone().add(0.0, DRAGON_HEIGHT, 0.0)

        val maxY = plugin.worldManager.activeMapConfig.maxFlyY
        if (loc.y > maxY) {
            loc.y = maxY
        }
        return loc
    }

    fun launchBomber(owner: Player, target: Player) {
        val spawnLoc = dragonPositionAbove(target)

        val dragon = target.world.spawn(spawnLoc, EnderDragon::class.java) { spawned ->
            spawned.phase = EnderDragon.Phase.HOVER
            // Bewusst KEIN setAI(false): Das NoAI-Flag wird zum Client synchronisiert und der
            // Drache ist ein mehrteiliges Modell, dessen Segmente clientseitig in aiStep()
            // nachgefuehrt werden. Mit NoAI bleibt das Modell optisch stehen, obwohl die Entity
            // serverseitig nachweislich mitwandert. Aggression wird stattdessen ueber
            // setAware(false), Unverwundbarkeit und das Cancelling saemtlichen Drachenschadens
            // unterbunden.
            spawned.setAware(false)
            spawned.isInvulnerable = true
            spawned.setGravity(false)
            spawned.isSilent = false
            spawned.persistentDataContainer.set(KEY_BOMBER_DRAGON, PersistentDataType.BYTE, 1.toByte())
        }
        // Sofort registrieren, damit der Drache auch bei einem spaeteren Fehler aufraeumbar bleibt
        activeDragons.add(dragon.uniqueId)

        // Achtung: getBossBar() ist null, solange der Drache nicht in einer End-Welt mit
        // Drachenkampf lebt. In der Arena ist das immer der Fall - hier gibt es also schlicht
        // keine Leiste, die ausgeblendet werden muesste.
        dragon.bossBar?.isVisible = false

        Bukkit.broadcast(
            ("<dark_purple>[OSOK] 🐉 <white>${owner.name}</white> hat den <b>Tarnkappenbomber</b> " +
                "auf <yellow>${target.name}</yellow> angesetzt!</dark_purple>").mini()
        )
        target.playSound(Sound.sound(BukkitSound.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1.0f, 0.8f))

        val ownerId = owner.uniqueId
        var elapsed = 0

        // Paper Global Region Scheduler: Verfolgung und TNT-Abwurf
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (elapsed >= BOMBER_DURATION_TICKS || !dragon.isValid || !target.isOnline) {
                    task.cancel()
                    removeDragon(dragon)
                    return@runAtFixedRate
                }

                val above = dragonPositionAbove(target).apply {
                    // Blickrichtung des Ziels uebernehmen, damit der Drache mitdreht
                    yaw = target.location.yaw
                    pitch = 0f
                }

                // Bewusst synchron: Wir laufen bereits im GlobalRegionScheduler auf dem
                // Main-Thread und der Zielchunk ist geladen, weil dort der Zielspieler steht.
                dragon.teleport(above)
                dragon.velocity = Vector(0, 0, 0)

                // Die HOVER-Phase verankert den Drachen an einem festen Schwebepunkt. Der
                // Phasenwechsel setzt diesen Punkt auf die neue Position, damit der Drache nicht
                // gegen den Teleport zurueckfliegt.
                dragon.phase = EnderDragon.Phase.CIRCLING
                dragon.phase = EnderDragon.Phase.HOVER

                if (elapsed % TNT_DROP_INTERVAL_TICKS == 0) {
                    dropBomb(above.clone().subtract(0.0, 2.0, 0.0), ownerId)
                }

                elapsed += FOLLOW_PERIOD_TICKS.toInt()
            },
            1L,
            FOLLOW_PERIOD_TICKS,
        )
    }

    private fun dropBomb(loc: Location, ownerId: UUID) {
        val world = loc.world ?: return

        val tnt = world.spawn(loc, TNTPrimed::class.java) { spawned ->
            // Lange Zuendschnur: Gezuendet wird beim Bodenkontakt, nicht per Timer.
            spawned.fuseTicks = TNT_MAX_FUSE_TICKS
            spawned.setIsIncendiary(false)
            spawned.velocity = Vector(0.0, -0.2, 0.0)
            spawned.persistentDataContainer.set(KEY_BOMBER_TNT, PersistentDataType.BYTE, 1.toByte())
            spawned.persistentDataContainer.set(KEY_BOMBER_OWNER, PersistentDataType.STRING, ownerId.toString())
            // Native Verursacher-Zuordnung: Damit liefert DamageSource#getCausingEntity() den
            // Ausloeser, ohne dass der CombatListener die PDC durchsuchen muss. Betrifft nur die
            // Zuordnung - der Ausloeser nimmt weiterhin Schaden, denn die Explosion schliesst nur
            // die TNT-Entity selbst aus, nicht ihren Verursacher.
            Bukkit.getPlayer(ownerId)?.let { spawned.source = it }
        }
        activeBombs.add(tnt)

        // Bei Bodenkontakt sofort zuenden
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (!tnt.isValid) {
                    task.cancel()
                    activeBombs.remove(tnt)
                    return@runAtFixedRate
                }
                if (tnt.isOnGround) {
                    task.cancel()
                    tnt.fuseTicks = 0
                }
            },
            1L,
            1L,
        )
    }

    private fun removeDragon(dragon: EnderDragon) {
        activeDragons.remove(dragon.uniqueId)
        if (dragon.isValid) {
            dragon.world.spawnParticle(Particle.EXPLOSION_EMITTER, dragon.location, 1)
            dragon.remove()
        }
    }

    /**
     * Entfernt alle aktiven Drachen und Bomben (Plugin-Enable/Disable, Map-Wechsel, /osok start und
     * /osok stop).
     *
     * Zusaetzlich zu den selbst verwalteten Referenzen werden alle Welten nach PDC-markierten
     * Bomber-Entities durchsucht. So verschwinden auch Drachen, die durch einen Fehler oder einen
     * Serverabsturz nie registriert wurden.
     */
    fun clearAll() {
        activeDragons.toList().forEach { Bukkit.getEntity(it)?.remove() }
        activeDragons.clear()

        activeBombs.toList().filter { it.isValid }.forEach { it.remove() }
        activeBombs.clear()

        var orphans = 0
        for (world in Bukkit.getWorlds()) {
            world.getEntitiesByClass(EnderDragon::class.java)
                .filter { it.persistentDataContainer.has(KEY_BOMBER_DRAGON, PersistentDataType.BYTE) }
                .forEach {
                    it.remove()
                    orphans++
                }
            world.getEntitiesByClass(TNTPrimed::class.java)
                .filter { it.persistentDataContainer.has(KEY_BOMBER_TNT, PersistentDataType.BYTE) }
                .forEach {
                    it.remove()
                    orphans++
                }
        }
        if (orphans > 0) {
            plugin.logger.info("[OSOK] $orphans verwaiste Tarnkappenbomber-Entities entfernt.")
        }
    }

    // ------------------------------------------------------------------
    // Schutz-Listener
    // ------------------------------------------------------------------

    /** Das Bomber-TNT und der Drache duerfen die Map niemals beschaedigen. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (isBomberEntity(event.entity)) {
            event.blockList().clear()
            event.yield = 0.0f
        }
    }

    /**
     * Schaden durch den Drachen selbst wird komplett unterbunden - er soll niemanden angreifen.
     *
     * Das Bomber-TNT bleibt bewusst unangetastet: Es richtet regulaeren Explosionsschaden an und
     * toetet ausdruecklich **nicht** mit einem Treffer. Wird der Schaden toedlich, uebernimmt
     * `CombatListener#onEntityDamage` die Eliminierung und holt sich ueber
     * `DamageSource#getCausingEntity()` den Verursacher fuer die Kill-Gutschrift - das TNT setzt
     * ihn beim Spawn per `setSource`.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBomberDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager

        if (damager is EnderDragon &&
            damager.persistentDataContainer.has(KEY_BOMBER_DRAGON, PersistentDataType.BYTE)
        ) {
            event.isCancelled = true
            return
        }

        if (event.entity !is Player) return
        if (!damager.persistentDataContainer.has(KEY_BOMBER_TNT, PersistentDataType.BYTE)) return

        // Kein One-Shot: Vanilla-TNT richtet aus naechster Naehe fast vollen Schaden an. Der Deckel
        // sorgt dafuer, dass aus voller Gesundheit immer mehrere Treffer noetig sind. Laeuft auf
        // HIGH, also bevor CombatListener auf HIGHEST den toedlichen Schaden prueft.
        event.damage = minOf(event.damage, BOMB_MAX_DAMAGE)
    }

    private fun isBomberEntity(entity: Entity): Boolean =
        entity.persistentDataContainer.has(KEY_BOMBER_TNT, PersistentDataType.BYTE) ||
            entity.persistentDataContainer.has(KEY_BOMBER_DRAGON, PersistentDataType.BYTE)

    companion object {
        val GUI_TITLE: Component =
            "<dark_purple><b>🐉 Tarnkappenbomber - Ziel waehlen</b></dark_purple>".mini()

        private val KEY_BOMBER_DRAGON = NamespacedKey("oneshotonekill", "bomber_dragon")
        private val KEY_BOMBER_TNT = NamespacedKey("oneshotonekill", "bomber_tnt")
        private val KEY_BOMBER_OWNER = NamespacedKey("oneshotonekill", "bomber_owner")
        private val KEY_GUI_TARGET = NamespacedKey("oneshotonekill", "bomber_gui_target")

        /** Gesamtdauer des Angriffs (10 Sekunden). */
        private const val BOMBER_DURATION_TICKS = 200

        /** Takt der Verfolgung - jeden Tick, damit der Drache eng am Ziel bleibt. */
        private const val FOLLOW_PERIOD_TICKS = 1L

        /** Abstand zwischen zwei TNT-Abwuerfen. */
        private const val TNT_DROP_INTERVAL_TICKS = 10

        /** Flughoehe des Drachen ueber dem Ziel. */
        private const val DRAGON_HEIGHT = 12.0

        /** Sicherheits-Fuse, falls das TNT nie den Boden beruehrt. */
        private const val TNT_MAX_FUSE_TICKS = 120

        /**
         * Maximaler Schaden einer Bombe (3 Herzen). Ohne diesen Deckel toetet eine
         * Vanilla-TNT-Explosion aus naechster Naehe sofort - der Bomber soll aber ausdruecklich
         * nicht mit einem Treffer toeten.
         */
        private const val BOMB_MAX_DAMAGE = 6.0
    }
}
