package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.bukkit.Sound as BukkitSound

class KillstreakManager(private val plugin: OneShotOneKill) {

    enum class ItemMode {
        STREAK,
        SPAWN,
        BOTH,
    }

    val specialItemKey: NamespacedKey = NamespacedKey(plugin, "special_item_type")

    private val activeShields = mutableSetOf<UUID>()
    private val explosiveShots = mutableSetOf<UUID>()
    private val chainLightningShots = mutableSetOf<UUID>()
    private val activeMiniguns = mutableSetOf<UUID>()
    private val arrowMagnets = mutableSetOf<UUID>()

    private val activeGroundItems = mutableSetOf<Item>()

    /** Spawngewicht je Item-Typ, einstellbar ueber /osok itemgewichtung. */
    private val itemWeights = mutableMapOf<String, Int>()

    var itemMode: ItemMode = ItemMode.BOTH

    init {
        startGroundSpawnTask()
        startMarioKartParticleAnimation()
    }

    // ------------------------------------------------------------------
    // Spawngewichte (/osok itemgewichtung)
    // ------------------------------------------------------------------

    fun getItemWeight(typeId: String): Int = itemWeights[typeId] ?: DEFAULT_ITEM_WEIGHT

    /** Setzt das Gewicht eines Item-Typs. `0` bedeutet: Das Item spawnt nie. */
    fun setItemWeight(typeId: String, weight: Int) {
        itemWeights[typeId] = weight.coerceIn(0, MAX_ITEM_WEIGHT)
    }

    /** Setzt alle Gewichte auf [DEFAULT_ITEM_WEIGHT] zurueck. */
    fun resetItemWeights() {
        itemWeights.clear()
    }

    /** Summe aller Gewichte. `0` bedeutet: Es kann ueberhaupt kein Item mehr kommen. */
    val totalItemWeight: Int
        get() = SPECIAL_ITEM_IDS.sumOf { getItemWeight(it) }

    /** Spawnwahrscheinlichkeit eines Typs in Prozent. */
    fun getSpawnChance(typeId: String): Double {
        val total = totalItemWeight
        return if (total <= 0) 0.0 else getItemWeight(typeId) * 100.0 / total
    }

    /** Anzeigename eines Item-Typs - direkt vom erzeugten Item, damit nichts doppelt gepflegt wird. */
    fun getItemDisplayName(typeId: String): Component {
        val index = SPECIAL_ITEM_IDS.indexOf(typeId)
        if (index < 0) return Component.text(typeId)

        return createSpecificSpecialItem(index).getData(DataComponentTypes.CUSTOM_NAME)
            ?: Component.text(typeId)
    }

    /**
     * Gewichteter Zufallszug ueber alle Item-Typen.
     *
     * @return Item-Index, oder `-1` wenn saemtliche Gewichte auf 0 stehen
     */
    private fun rollItemIndex(): Int {
        val total = totalItemWeight
        if (total <= 0) return -1

        var roll = Random.nextInt(total)
        for (index in 0 until SPECIAL_ITEM_COUNT) {
            roll -= getItemWeight(SPECIAL_ITEM_IDS[index])
            if (roll < 0) return index
        }
        return SPECIAL_ITEM_COUNT - 1
    }

    fun clearAllGroundItems() {
        activeGroundItems.filter { it.isValid }.forEach {
            releaseChunkTicket(it)
            it.remove()
        }
        activeGroundItems.clear()
    }

    /** Gibt das Chunk-Ticket einer Boden-Box wieder frei. */
    private fun releaseChunkTicket(item: Item) {
        val loc = item.location
        loc.world?.removePluginChunkTicket(loc.blockX shr 4, loc.blockZ shr 4, plugin)
    }

    /** Paper Native Global Region Scheduler: Spawnt alle 30 Sekunden Mario-Kart-Boxen. */
    private fun startGroundSpawnTask() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            {
                val match = plugin.matchManager
                if (!match.isMatchStarted || match.isMatchEnded) return@runAtFixedRate

                if (itemMode == ItemMode.SPAWN || itemMode == ItemMode.BOTH) {
                    spawnGroundSpecialItem()
                }
            },
            GROUND_SPAWN_PERIOD_TICKS,
            GROUND_SPAWN_PERIOD_TICKS,
        )
    }

    private fun startMarioKartParticleAnimation() {
        var angle = 0.0

        // Paper Native Global Region Scheduler: Laeuft alle 2 Ticks fuer Partikel-Ringe
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            {
                if (activeGroundItems.isEmpty()) return@runAtFixedRate

                angle += 0.2
                if (angle > Math.PI * 2) angle = 0.0

                val iterator = activeGroundItems.iterator()
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    if (!item.isValid) {
                        releaseChunkTicket(item)
                        iterator.remove()
                        continue
                    }

                    val loc = item.location.add(0.0, 0.5, 0.0)
                    val world = loc.world ?: continue

                    world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 2, 0.1, 0.1, 0.1, 0.02)

                    val ringLoc = loc.clone().add(cos(angle) * 0.6, 0.0, sin(angle) * 0.6)
                    world.spawnParticle(Particle.END_ROD, ringLoc, 1, 0.0, 0.0, 0.0, 0.0)
                }
            },
            1L,
            2L,
        )
    }

    fun spawnGroundSpecialItem() {
        // Boden-Items ausschliesslich auf dem Arena-Boden - kein Dach, keine Plattform, keine Lobby
        val spawnLoc = plugin.arenaManager.getRandomFloorLocation()
        val world = spawnLoc?.world
        if (spawnLoc == null || world == null) {
            plugin.logger.warning(
                "[OSOK] Kein freier Arena-Bodenplatz fuer eine Item-Box gefunden - Spawn uebersprungen."
            )
            return
        }

        val itemType = rollItemIndex()
        // Alle Gewichte stehen auf 0 - dann soll auch keine Box erscheinen
        if (itemType < 0) return

        val itemStack = createSpecificSpecialItem(itemType)

        world.addPluginChunkTicket(spawnLoc.blockX shr 4, spawnLoc.blockZ shr 4, plugin)

        // Paper dropItem mit Consumer: Eigenschaften stehen fest, BEVOR das Item in der Welt
        // erscheint. Gravitation MUSS aktiv bleiben, sonst bleibt die Item-Box in der Luft haengen.
        val dropped = world.dropItem(spawnLoc, itemStack) { item ->
            item.setCanMobPickup(false)
            item.setGravity(true)
            item.velocity = Vector(0, 0, 0)
            // Deutlich sichtbarer Leuchtrahmen durch alle Waende hindurch
            item.isGlowing = true
            item.persistentDataContainer.set(KEY_GROUND_SPECIAL_PDC, PersistentDataType.BYTE, 1.toByte())
        }
        activeGroundItems.add(dropped)

        val effectLoc = spawnLoc.clone().add(0.0, 0.5, 0.0)
        world.spawnParticle(Particle.FIREWORK, effectLoc, 30, 0.4, 0.4, 0.4, 0.05)
        world.spawnParticle(Particle.END_ROD, effectLoc, 20, 0.3, 0.3, 0.3, 0.1)
        world.playSound(
            Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.5f),
            spawnLoc.x(), spawnLoc.y(), spawnLoc.z(),
        )

        // Paper Native Global Region Scheduler: Nach 60 Sekunden automatisch despawnen
        Bukkit.getGlobalRegionScheduler().runDelayed(
            plugin,
            {
                if (dropped.isValid) {
                    dropped.world.spawnParticle(Particle.SMOKE, dropped.location, 15, 0.2, 0.2, 0.2, 0.05)
                    dropped.remove()
                    activeGroundItems.remove(dropped)
                }
            },
            GROUND_DESPAWN_DELAY_TICKS,
        )
    }

    /**
     * Vergibt ein zufaelliges Spezial-Item als Killstreak- oder Kopfgeld-Belohnung. Zaehlt fuer die
     * Match-Zusammenfassung mit; das Admin-Testmenue nutzt bewusst [giveSpecificSpecialItem] und
     * zaehlt daher nicht.
     */
    fun awardRandomKillstreakItem(player: Player, streak: Int) {
        val itemType = rollItemIndex()
        // Alle Gewichte stehen auf 0 - dann gibt es auch keine Belohnung
        if (itemType < 0) return

        giveSpecificSpecialItem(player, itemType, streak)
        plugin.scoreboardManager.addItemsCollected(player.uniqueId, 1)
    }

    fun createSpecificSpecialItem(itemType: Int): ItemStack = when (itemType) {
        0 -> createSpecialItem(
            Material.ENDER_EYE,
            "<yellow><b>[✦] Radar-Puls (Rechtsklick)</b></yellow>",
            "<gray>Enthüllt alle Gegner in der Arena für 30 Sekunden!</gray>",
            KEY_RADAR,
        )

        1 -> createSpecialItem(
            Material.TNT,
            "<red><b>[★] Explosiv-Schuss (Rechtsklick)</b></red>",
            "<gray>Dein nächster Pfeil erzeugt eine Explosion!</gray>",
            KEY_EXPLOSIVE,
        )

        2 -> createSpecialItem(
            Material.NETHER_STAR,
            "<aqua><b>[🛡] Reflektor-Schild (Rechtsklick)</b></aqua>",
            "<gray>Blockiert den nächsten tödlichen Treffer!</gray>",
            KEY_REFLECTOR,
        )

        3 -> createSpecialItem(
            Material.SNOWBALL,
            "<white><b>[☁] Rauchbombe (Werfen)</b></white>",
            "<gray>Erzeugt eine dichte Rauchwolke!</gray>",
            KEY_SMOKE,
        )

        4 -> createSpecialItem(
            Material.HEAVY_WEIGHTED_PRESSURE_PLATE,
            "<aqua><b>[❄] Frost-Trap (Plazieren)</b></aqua>",
            "<gray>Friert betretende Gegner für 7s fest!</gray>",
            KEY_FROST,
        )

        5 -> createSpecialItem(
            Material.BLAZE_ROD,
            "<gold><b>[🔥] Krass Minigun (Rechtsklick)</b></gold>",
            "<gray>Feuert 8 Sekunden lang automatisch Pfeile ab!</gray>",
            KEY_MINIGUN,
        )

        6 -> createSpecialItem(
            Material.ENDER_PEARL,
            "<light_purple><b>[🌀] Teleport-Granate (Werfen)</b></light_purple>",
            "<gray>Teleportiert & erzeugt eine Druckwelle!</gray>",
            KEY_TELEPORT,
        )

        7 -> createSpecialItem(
            Material.PHANTOM_MEMBRANE,
            "<gray><b>[✦] Unsichtbarkeits-Mantel (Rechtsklick)</b></gray>",
            "<gray>Macht dich für 15s komplett unsichtbar!</gray>",
            KEY_INVISIBILITY,
        )

        8 -> createSpecialItem(
            Material.HEART_OF_THE_SEA,
            "<blue><b>[⚓] Pfeil-Magnetfeld (Rechtsklick)</b></blue>",
            "<gray>Lenkt herannahende Pfeile für 15s ab!</gray>",
            KEY_MAGNET,
        )

        9 -> createSpecialItem(
            Material.LIGHTNING_ROD,
            "<yellow><b>[⚡] Kettenblitz-Schuss (Rechtsklick)</b></yellow>",
            "<gray>Dein nächster Schuss erzeugt Blitze!</gray>",
            KEY_CHAIN_LIGHTNING,
        )

        10 -> createSpecialItem(
            Material.DRAGON_HEAD,
            "<dark_purple><b>[🐉] Tarnkappenbomber (Rechtsklick)</b></dark_purple>",
            "<gray>Setzt 10s lang einen TNT-werfenden Drachen auf ein Ziel an!</gray>",
            KEY_STEALTH_BOMBER,
        )

        11 -> createSpecialItem(
            Material.FILLED_MAP,
            "<red><b>[🛰] Air-Strike (Rechtsklick)</b></red>",
            "<gray>Arena-Karte öffnen und einen Bombenhagel anfordern!</gray>",
            KEY_AIRSTRIKE,
        )

        12 -> createSpecialItem(
            Material.TNT_MINECART,
            "<gold><b>[💥] C4 (Auf Block platzieren)</b></gold>",
            "<gray>Platzieren und per Fernzünder auslösen!</gray>",
            KEY_C4,
        )

        13 -> createSpecialItem(
            Material.SPYGLASS,
            "<white><b>[🔭] Railgun (Rechtsklick)</b></white>",
            "<gray>Lädt 1s und tötet dann alles auf der Sichtlinie!</gray>",
            KEY_RAILGUN,
        )

        14 -> createSpecialItem(
            Material.ECHO_SHARD,
            "<dark_purple><b>[🕳] Singularität (Werfen)</b></dark_purple>",
            "<gray>Reißt 4s lang alle Spieler im Umkreis zusammen!</gray>",
            KEY_SINGULARITY,
        )

        15 -> createGliderItem()

        else -> createSentryTurretItem()
    }

    private fun createSentryTurretItem(): ItemStack = createSpecialItem(
        Material.DISPENSER,
        "<gold><b>[🤖] Geschützturm (Auf Block platzieren)</b></gold>",
        "<gray>Platziere einen automatischen Geschützturm (20s Dauerfeuer)!</gray>",
        KEY_SENTRY_TURRET,
    )

    /**
     * Gleitflug-Item fuer die Hotbar.
     *
     * Die Elytra darf ausdruecklich **nicht** angezogen werden koennen - sonst haette der Spieler
     * unbegrenzten Flug statt der acht Sekunden. Beide Faehigkeiten werden deshalb ueber die Paper
     * Data Components vom Item entfernt. Die eigentlichen Schwingen vergibt fuer die Flugdauer der
     * `TacticalItemsManager`.
     */
    private fun createGliderItem(): ItemStack = createSpecialItem(
        Material.ELYTRA,
        "<aqua><b>[🦅] Gleitflug (Rechtsklick)</b></aqua>",
        "<gray>8 Sekunden Flug mit Schubstößen!</gray>",
        KEY_GLIDER,
    ).apply {
        unsetData(DataComponentTypes.EQUIPPABLE)
        unsetData(DataComponentTypes.GLIDER)
    }

    fun giveSpecificSpecialItem(player: Player, itemType: Int, streak: Int) {
        val item = createSpecificSpecialItem(itemType)
        val itemName = item.getData(DataComponentTypes.CUSTOM_NAME) ?: Component.text("Spezial-Item")

        player.inventory.addItem(item)
        player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.8f))

        if (streak > 0) {
            player.sendMessage(
                ("<green>[OSOK] 🎁 <b>${streak}er Killstreak!</b> " +
                    "<gray>Du hast den Spezial-Item erhalten: </gray></green>").mini().append(itemName)
            )
            Bukkit.broadcast(
                ("<yellow>[OSOK] 🔥 <white>${player.name}</white> hat eine " +
                    "<b>${streak}er Killstreak</b> erreicht!</yellow>").mini()
            )
        } else {
            player.sendMessage(
                "<green>[OSOK] 🧪 Itemtest: </green>".mini()
                    .append(itemName)
                    .append("<green> erhalten!</green>".mini())
            )
        }
    }

    fun createSpecialItem(
        mat: Material,
        miniMessageName: String,
        miniMessageLore: String,
        itemTypeId: String? = null,
    ): ItemStack = ItemStack.of(mat).apply {
        // Paper DataComponents statt ItemMeta: schreibt direkt in die Vanilla-Komponenten
        // custom_name und lore, ohne eine Meta-Kopie anzulegen.
        setData(DataComponentTypes.CUSTOM_NAME, miniMessageName.mini())
        setData(DataComponentTypes.LORE, ItemLore.lore(listOf(miniMessageLore.mini())))
        if (itemTypeId != null) {
            // Der PDC haengt am Stack, nicht an der Meta - gleicher Speicher, ein Zugriff weniger
            editPersistentDataContainer { pdc ->
                pdc.set(specialItemKey, PersistentDataType.STRING, itemTypeId)
            }
        }
    }

    fun activateMinigun(player: Player) {
        if (!activeMiniguns.add(player.uniqueId)) {
            player.sendMessage("<red>[OSOK] 🔥 Minigun ist bereits aktiv!</red>".mini())
            return
        }

        player.sendMessage(
            "<green>[OSOK] 🔥 <b>MINIGUN AKTIVIERT!</b> <gray>8 Sekunden Dauerfeuer!</gray></green>".mini()
        )
        player.playSound(Sound.sound(BukkitSound.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 0.8f, 1.5f))

        val minigunArrows = mutableListOf<Arrow>()
        var ticksLeft = MINIGUN_DURATION_TICKS

        // Paper Native Entity Scheduler: Feuert alle 2 Ticks gebunden an den Player-Tick
        player.scheduler.runAtFixedRate(
            plugin,
            { task ->
                if (!player.isOnline || player.isDead || ticksLeft <= 0) {
                    activeMiniguns.remove(player.uniqueId)
                    task.cancel()

                    minigunArrows.filter { it.isValid }.forEach { it.remove() }
                    minigunArrows.clear()

                    if (player.isOnline) {
                        player.sendMessage("<red>[OSOK] 🔥 Minigun abgelaufen. Pfeile wurden entfernt!</red>".mini())
                        player.playSound(
                            Sound.sound(BukkitSound.BLOCK_FIRE_EXTINGUISH, Sound.Source.MASTER, 1.0f, 1.0f)
                        )
                    }
                    return@runAtFixedRate
                }

                val arrow = player.launchProjectile(
                    Arrow::class.java,
                    player.eyeLocation.direction.multiply(2.5),
                )
                arrow.shooter = player
                arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                minigunArrows.add(arrow)

                player.world.playSound(
                    Sound.sound(BukkitSound.ENTITY_ARROW_SHOOT, Sound.Source.MASTER, 0.8f, 2.0f),
                    player.location.x(), player.location.y(), player.location.z(),
                )
                player.world.spawnParticle(
                    Particle.FLAME,
                    player.eyeLocation.add(player.eyeLocation.direction),
                    3, 0.1, 0.1, 0.1, 0.05,
                )

                ticksLeft -= 2
            },
            null,
            1L,
            2L,
        )
    }

    fun activateArrowMagnet(player: Player) {
        if (!arrowMagnets.add(player.uniqueId)) return

        player.sendMessage("<green>[OSOK] ⚓ Pfeil-Magnetfeld für 15 Sekunden aktiv!</green>".mini())
        player.playSound(Sound.sound(BukkitSound.BLOCK_BEACON_ACTIVATE, Sound.Source.MASTER, 1.0f, 1.5f))

        var ticksLeft = ARROW_MAGNET_DURATION_TICKS

        // Paper Native Entity Scheduler: Lenkt Pfeile ab gebunden an den Player-Tick
        player.scheduler.runAtFixedRate(
            plugin,
            { task ->
                if (!player.isOnline || player.isDead || ticksLeft <= 0) {
                    arrowMagnets.remove(player.uniqueId)
                    task.cancel()
                    if (player.isOnline) {
                        player.sendMessage("<red>[OSOK] ⚓ Pfeil-Magnetfeld abgelaufen.</red>".mini())
                        player.playSound(
                            Sound.sound(BukkitSound.BLOCK_BEACON_DEACTIVATE, Sound.Source.MASTER, 1.0f, 1.0f)
                        )
                    }
                    return@runAtFixedRate
                }

                val playerLoc = player.location.add(0.0, 1.0, 0.0)
                val world = playerLoc.world ?: return@runAtFixedRate
                world.spawnParticle(Particle.END_ROD, playerLoc, 8, 1.2, 1.2, 1.2, 0.05)

                // Paper Spatial Entity Index: getNearbyEntitiesByType statt Distanzschleife
                playerLoc.getNearbyEntitiesByType(Arrow::class.java, ARROW_MAGNET_RADIUS)
                    .filter { it.shooter != null && it.shooter != player }
                    .forEach { arrow ->
                        val pushAway = arrow.location.toVector()
                            .subtract(playerLoc.toVector())
                            .normalize()
                            .multiply(1.8)
                        arrow.velocity = if (pushAway.x.isNaN()) Vector(0.0, 0.5, 0.0) else pushAway
                        world.spawnParticle(Particle.CRIT, arrow.location, 5)
                    }

                ticksLeft -= 2
            },
            null,
            1L,
            2L,
        )
    }

    fun isMinigunActive(uuid: UUID): Boolean = uuid in activeMiniguns

    fun hasShield(uuid: UUID): Boolean = uuid in activeShields

    fun addShield(uuid: UUID) {
        activeShields.add(uuid)
    }

    fun removeShield(uuid: UUID) {
        activeShields.remove(uuid)
    }

    fun hasExplosiveShot(uuid: UUID): Boolean = uuid in explosiveShots

    fun addExplosiveShot(uuid: UUID) {
        explosiveShots.add(uuid)
    }

    fun removeExplosiveShot(uuid: UUID) {
        explosiveShots.remove(uuid)
    }

    fun hasChainLightningShot(uuid: UUID): Boolean = uuid in chainLightningShots

    fun addChainLightningShot(uuid: UUID) {
        chainLightningShots.add(uuid)
    }

    fun removeChainLightningShot(uuid: UUID) {
        chainLightningShots.remove(uuid)
    }

    companion object {
        const val KEY_RADAR = "radar_puls"
        const val KEY_EXPLOSIVE = "explosive_shot"
        const val KEY_REFLECTOR = "reflector_shield"
        const val KEY_SMOKE = "smoke_bomb"
        const val KEY_FROST = "frost_trap"
        const val KEY_MINIGUN = "minigun"
        const val KEY_TELEPORT = "teleport_grenade"
        const val KEY_INVISIBILITY = "invisibility_cloak"
        const val KEY_MAGNET = "arrow_magnet"
        const val KEY_CHAIN_LIGHTNING = "chain_lightning"
        const val KEY_STEALTH_BOMBER = "stealth_bomber"
        const val KEY_AIRSTRIKE = "air_strike"
        const val KEY_C4 = "c4_charge_item"
        const val KEY_RAILGUN = "railgun"
        const val KEY_SINGULARITY = "singularity"
        const val KEY_GLIDER = "glider_flight"
        const val KEY_SENTRY_TURRET = "sentry_turret"

        /**
         * Alle Spezial-Item-Typen in **Index-Reihenfolge**. Die Reihenfolge muss zu
         * [createSpecificSpecialItem] passen; [SPECIAL_ITEM_COUNT] leitet sich daraus ab, damit die
         * Anzahl nirgends doppelt gepflegt werden muss.
         */
        val SPECIAL_ITEM_IDS: List<String> = listOf(
            KEY_RADAR, KEY_EXPLOSIVE, KEY_REFLECTOR, KEY_SMOKE, KEY_FROST, KEY_MINIGUN,
            KEY_TELEPORT, KEY_INVISIBILITY, KEY_MAGNET, KEY_CHAIN_LIGHTNING, KEY_STEALTH_BOMBER,
            KEY_AIRSTRIKE, KEY_C4, KEY_RAILGUN, KEY_SINGULARITY, KEY_GLIDER, KEY_SENTRY_TURRET,
        )

        /** Anzahl der verfuegbaren Spezial-Item-Typen (Indizes 0 bis SPECIAL_ITEM_COUNT-1). */
        val SPECIAL_ITEM_COUNT: Int = SPECIAL_ITEM_IDS.size

        /** Startgewicht jedes Items. Bewusst nicht 1, damit sich Gewichte auch senken lassen. */
        const val DEFAULT_ITEM_WEIGHT = 10

        /** Obergrenze eines Gewichts - verhindert absurde Werte per Tippfehler. */
        const val MAX_ITEM_WEIGHT = 1000

        val KEY_EXPLOSIVE_PDC = NamespacedKey("oneshotonekill", "explosive_arrow")
        val KEY_CHAIN_LIGHTNING_PDC = NamespacedKey("oneshotonekill", "chain_lightning_arrow")
        val KEY_TP_GRENADE_PDC = NamespacedKey("oneshotonekill", "tp_grenade")
        val KEY_GROUND_SPECIAL_PDC = NamespacedKey("oneshotonekill", "ground_special")

        /** Takt des Boden-Spawns: alle 30 Sekunden. */
        private const val GROUND_SPAWN_PERIOD_TICKS = 600L

        /** Eine Boden-Box verschwindet nach 60 Sekunden von selbst. */
        private const val GROUND_DESPAWN_DELAY_TICKS = 1200L

        private const val MINIGUN_DURATION_TICKS = 160
        private const val ARROW_MAGNET_DURATION_TICKS = 300
        private const val ARROW_MAGNET_RADIUS = 8.0
    }
}
