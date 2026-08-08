package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.model.MapConfig
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
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.random.Random
import org.bukkit.Sound as BukkitSound

/**
 * Sprengstoff-Spezialitems: **Air-Strike** und **C4**.
 *
 * Beide nutzen dieselbe Sprengung: `createExplosion(..., breakBlocks = false)` richtet gewaltigen
 * Schaden an, kann die Map aber grundsaetzlich nicht beschaedigen - es werden gar keine Bloecke
 * angetastet, statt eine Blockliste nachtraeglich zu leeren. Die C4 sprengt dabei mit groesserer
 * Reichweite als eine Air-Strike-Bombe.
 *
 * Getroffen wird **jeder** Spieler in Reichweite, auch der Ausloeser - Details siehe [blast].
 */
class ExplosivesManager(private val plugin: OneShotOneKill) : Listener {

    /** Merkzettel: Wer hat die Sprengung ausgeloest, die diesen Spieler erwischt hat? */
    private data class BlastCredit(val owner: UUID, val expiresAt: Long)

    private val activeBombs = mutableSetOf<TNTPrimed>()
    private val c4Charges = mutableMapOf<UUID, MutableList<BlockDisplay>>()

    /** Ausloeser der aktuell laufenden Sprengung, nur waehrend `createExplosion` gesetzt. */
    private var currentBlastOwner: Player? = null

    /** Nachwirkende Zuordnung fuer Folgeschaeden, siehe [resolveBlastKiller]. */
    private val blastCredits = mutableMapOf<UUID, BlastCredit>()

    /**
     * Ermittelt, wem der Tod eines Spielers als Sprengungs-Kill zuzuschreiben ist.
     *
     * Reicht [currentBlastOwner] nicht aus, greift ein Kurzzeitgedaechtnis: Bei jeder Sprengung
     * wird fuer alle Spieler in Reichweite [BLAST_CREDIT_MILLIS] lang vermerkt, wer gezuendet hat.
     *
     * Das ist noetig, weil eine Sprengung nicht nur direkt toetet. Eine C4 mit Staerke 12
     * schleudert Getroffene weit nach oben - wer den Treffer knapp ueberlebt, stirbt Sekunden
     * spaeter am Sturz. Der Schaden traegt dann die Ursache `FALL` und gar keine
     * Verursacher-Entity, der Kill blieb also unzugeordnet und wurde als "ist gestorben" gemeldet.
     * Mit dem Zeitfenster bekommt ihn der Zuender.
     *
     * @return der Ausloeser, oder `null` wenn keine Sprengung in Frage kommt
     */
    fun resolveBlastKiller(victim: Player?): Player? {
        currentBlastOwner?.let { return it }
        if (victim == null) return null

        val credit = blastCredits[victim.uniqueId]
        if (credit == null || System.currentTimeMillis() > credit.expiresAt) {
            blastCredits.remove(victim.uniqueId)
            return null
        }

        return Bukkit.getPlayer(credit.owner)?.takeIf { it.isOnline }
    }

    /**
     * Vermerkt fuer alle Spieler in Reichweite, wer die Sprengung ausgeloest hat.
     *
     * Der Radius muss der **tatsaechlichen** Schadensreichweite entsprechen, nicht der
     * Sprengkraft - siehe [EXPLOSION_DAMAGE_RADIUS_FACTOR].
     */
    private fun rememberBlastCredit(center: Location, owner: Player?, power: Float) {
        if (owner == null) return

        val expiresAt = System.currentTimeMillis() + BLAST_CREDIT_MILLIS
        // Paper Spatial Entity Index: direkte Spieler-Abfrage statt Entity-Box + instanceof
        center.getNearbyPlayers(power * EXPLOSION_DAMAGE_RADIUS_FACTOR)
            .filter { it.uniqueId != owner.uniqueId }
            .forEach { blastCredits[it.uniqueId] = BlastCredit(owner.uniqueId, expiresAt) }

        // Abgelaufene Eintraege bei der Gelegenheit wegraeumen
        blastCredits.values.removeIf { System.currentTimeMillis() > it.expiresAt }
    }

    /**
     * Loescht den Merkzettel-Eintrag eines Spielers.
     *
     * Wird aufgerufen, sobald seine Eliminierung abgewickelt ist. Ohne das bliebe der Eintrag bis
     * zum Ablauf des Zeitfensters stehen und ein voellig unabhaengiger Sturz kurz nach dem Respawn
     * wuerde faelschlich noch dem Zuender gutgeschrieben.
     */
    fun clearBlastCredit(victimId: UUID) {
        blastCredits.remove(victimId)
    }

    // ==================================================================
    // Gemeinsame Sprengung
    // ==================================================================

    /**
     * Gewaltige Explosion ohne jede Blockveraenderung, die **jeden** Spieler in Reichweite trifft -
     * auch den Ausloeser selbst.
     *
     * Wichtig: Es wird bewusst **keine** Verursacher-Entity uebergeben. Minecraft ermittelt die
     * Explosionsopfer ueber `getEntities(source, box)`, und diese Abfrage schliesst die
     * Quell-Entity aus. Wuerde der Ausloeser als Quelle uebergeben, waere er von seiner eigenen
     * Sprengung ausgenommen.
     *
     * Fuer die Kill-Zuordnung wird der Ausloeser stattdessen fuer die Dauer der Sprengung in
     * [currentBlastOwner] hinterlegt. Das ist zuverlaessig, weil `createExplosion` synchron laeuft
     * und die Schadensevents unmittelbar ausloest.
     */
    private fun blast(loc: Location, owner: Player?, power: Float) {
        val world = loc.world ?: return

        // Zuordnung VOR der Sprengung merken - danach koennen Getroffene noch sekundenlang an
        // Folgeschaden (vor allem Sturz) sterben, siehe resolveBlastKiller
        rememberBlastCredit(loc, owner, power)

        // breakBlocks = false -> die Map kann nicht beschaedigt werden. setFire = false -> kein Feuer.
        currentBlastOwner = owner?.takeIf { it.isOnline }
        try {
            world.createExplosion(loc, power, false, false)
        } finally {
            currentBlastOwner = null
        }

        // Partikelradius skaliert mit der Sprengkraft, damit die Optik zur Reichweite passt
        val spread = power / 3.0
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 8, spread, spread * 0.6, spread, 0.0)
        world.spawnParticle(Particle.FLAME, loc, 160, spread * 1.4, 2.0, spread * 1.4, 0.12)
        world.spawnParticle(Particle.LARGE_SMOKE, loc, 90, spread * 1.2, 2.0, spread * 1.2, 0.08)
        world.playSound(
            Sound.sound(BukkitSound.ENTITY_GENERIC_EXPLODE, Sound.Source.MASTER, 1.0f, 0.55f),
            loc.x(), loc.y(), loc.z(),
        )
    }

    // ==================================================================
    // Air-Strike
    // ==================================================================

    /**
     * Oeffnet die Arena-Karte zur Zielauswahl. Das Raster bildet die XZ-Grenzen der aktiven Map auf
     * 9x6 Felder ab; Spieler in der Arena werden als Kopf auf ihrem Feld eingezeichnet. Liefert
     * `false`, wenn keine Karte aufgebaut werden konnte.
     */
    fun openAirStrikeMap(user: Player): Boolean {
        val map = plugin.worldManager.activeMapConfig
        val world = plugin.worldManager.osokWorld
        if (world == null) {
            user.sendMessage("<red>[OSOK] 🛰 Die Arena ist aktuell nicht geladen!</red>".mini())
            return false
        }

        val gui = Bukkit.createInventory(null, GUI_COLS * GUI_ROWS, AIRSTRIKE_GUI_TITLE)

        for (row in 0 until GUI_ROWS) {
            for (col in 0 until GUI_COLS) {
                val cell = cellCenter(map, world, col, row)
                gui.setItem(row * GUI_COLS + col, createTerrainCell(cell, map.containsColumn(cell.x, cell.z)))
            }
        }

        // Spieler auf der Karte einzeichnen - inklusive des Nutzers selbst zur Orientierung
        for (shown in Bukkit.getOnlinePlayers()) {
            if (!plugin.arenaManager.isInArenaArea(shown.location)) continue

            val slot = slotFor(map, shown.location)
            if (slot < 0) continue

            gui.setItem(slot, createPlayerCell(shown, self = shown.uniqueId == user.uniqueId))
        }

        user.openInventory(gui)
        user.playSound(Sound.sound(BukkitSound.ITEM_SPYGLASS_USE, Sound.Source.MASTER, 1.0f, 1.0f))
        return true
    }

    private fun cellCenter(map: MapConfig, world: World, col: Int, row: Int): Location {
        val x = map.minX + (map.maxX - map.minX) * ((col + 0.5) / GUI_COLS)
        val z = map.minZ + (map.maxZ - map.minZ) * ((row + 0.5) / GUI_ROWS)
        return Location(world, x, map.minY, z)
    }

    /** Rasterfeld einer Weltposition, oder -1 wenn ausserhalb. */
    private fun slotFor(map: MapConfig, loc: Location): Int {
        val spanX = map.maxX - map.minX
        val spanZ = map.maxZ - map.minZ
        if (spanX <= 0 || spanZ <= 0) return -1

        val col = floor((loc.x - map.minX) / spanX * GUI_COLS).toInt()
        val row = floor((loc.z - map.minZ) / spanZ * GUI_ROWS).toInt()
        if (col !in 0 until GUI_COLS || row !in 0 until GUI_ROWS) return -1

        return row * GUI_COLS + col
    }

    /**
     * Ein Rasterfeld der Karte.
     *
     * Felder, deren Mitte gar nicht ueber der Arena liegt, werden dunkel dargestellt: Das Raster
     * spannt die umschliessende Box auf, und bei einer Arena mit Umriss (BO2) faellt ein guter Teil
     * davon neben die Kampfzone. Ohne die Unterscheidung sieht die Karte rechteckig aus und man
     * wirft Bomben ins Nichts.
     */
    private fun createTerrainCell(cell: Location, inArena: Boolean): ItemStack =
        if (inArena) {
            ItemStack.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                .also { writeCellData(it, "<gray>Sektor</gray>".mini(), cell, null) }
        } else {
            ItemStack.of(Material.BLACK_STAINED_GLASS_PANE)
                .also { writeCellData(it, "<dark_gray>Ausserhalb der Arena</dark_gray>".mini(), cell, null) }
        }

    /**
     * Kopf-Feld eines Spielers. Der Zielpunkt ist seine **eigene** Position, nicht die Sektormitte -
     * ein Sektor ist mehrere Bloecke breit, die Mitte lag also regelmaessig neben dem Gegner.
     */
    private fun createPlayerCell(shown: Player, self: Boolean): ItemStack {
        val name = if (self) {
            "<aqua><b>${shown.name}</b> <gray>(du)</gray></aqua>".mini()
        } else {
            "<red><b>${shown.name}</b></red>".mini()
        }

        return ItemStack.of(Material.PLAYER_HEAD).also { item ->
            // PROFILE ersetzt SkullMeta#setOwningPlayer - kein SkullMeta und keine Meta-Kopie mehr,
            // was bei 54 Feldern pro Menue spuerbar ist.
            item.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(shown.playerProfile))
            writeCellData(item, name, shown.location, shown.uniqueId)
        }
    }

    /**
     * Schreibt Name, Lore und Zielkoordinaten eines Rasterfeldes als Paper DataComponents bzw. in
     * den PDC des Stacks.
     *
     * @param aim Zielpunkt des Feldes - Sektormitte, oder die Position des Gegners
     * @param targetPlayer markierter Gegner, oder `null` fuer ein leeres Feld
     */
    private fun writeCellData(item: ItemStack, displayName: Component, aim: Location, targetPlayer: UUID?) {
        item.setData(DataComponentTypes.CUSTOM_NAME, displayName)
        item.setData(
            DataComponentTypes.LORE,
            ItemLore.lore(
                listOf(
                    String.format(
                        Locale.US,
                        "<gray>X <white>%.0f</white>   Z <white>%.0f</white></gray>",
                        aim.x, aim.z,
                    ).mini(),
                    if (targetPlayer != null) {
                        "<red>Gegner in diesem Sektor!</red>".mini()
                    } else {
                        "<dark_gray>leer</dark_gray>".mini()
                    },
                    "<yellow>Klicken, um den Air-Strike anzufordern</yellow>".mini(),
                )
            ),
        )
        item.editPersistentDataContainer { pdc ->
            pdc.set(KEY_TARGET_X, PersistentDataType.DOUBLE, aim.x)
            pdc.set(KEY_TARGET_Z, PersistentDataType.DOUBLE, aim.z)
            if (targetPlayer != null) {
                pdc.set(KEY_TARGET_PLAYER, PersistentDataType.STRING, targetPlayer.toString())
            }
        }
    }

    @EventHandler
    fun onAirStrikeMapClick(event: InventoryClickEvent) {
        if (event.view.title() != AIRSTRIKE_GUI_TITLE) return
        event.isCancelled = true

        val user = event.whoClicked as? Player ?: return

        val clicked = event.currentItem ?: return
        if (!clicked.hasItemMeta()) return

        val pdc = clicked.persistentDataContainer
        var targetX = pdc.get(KEY_TARGET_X, PersistentDataType.DOUBLE) ?: return
        var targetZ = pdc.get(KEY_TARGET_Z, PersistentDataType.DOUBLE) ?: return

        // Auf einen markierten Gegner wird seine Position im Moment des Klicks angepeilt, nicht die
        // beim Oeffnen des Menues gespeicherte. Die Karte ist eine Momentaufnahme - bis zum Klick
        // ist der Gegner laengst weitergelaufen, und der Abwurf ging ins Leere. Die Vorwarnzeit bis
        // zum Einschlag bleibt unangetastet: Ausweichen ist weiter moeglich.
        pdc.get(KEY_TARGET_PLAYER, PersistentDataType.STRING)
            ?.let { Bukkit.getPlayer(UUID.fromString(it)) }
            ?.takeIf { it.isOnline && plugin.arenaManager.isInArenaArea(it.location) }
            ?.let {
                targetX = it.location.x
                targetZ = it.location.z
            }

        user.closeInventory()

        // Verbrauch erst bei der Auswahl - wer das Menue schliesst, behaelt sein Item
        if (!consumeSpecialItem(user, KillstreakManager.KEY_AIRSTRIKE)) {
            user.sendMessage("<red>[OSOK] 🛰 Du hast keinen Air-Strike mehr im Inventar!</red>".mini())
            return
        }

        callAirStrike(user, targetX, targetZ)
    }

    fun callAirStrike(owner: Player, targetX: Double, targetZ: Double) {
        val world = plugin.worldManager.osokWorld ?: return
        val map = plugin.worldManager.activeMapConfig

        // Abwurfhoehe: ueber der Arena, aber niemals durch die Decke (Standard-Map ist ueberdacht)
        val dropY = minOf(map.maxY + AIRSTRIKE_HEIGHT_ABOVE_ARENA, map.maxFlyY)

        Bukkit.broadcast(
            ("<red>[OSOK] 🛰 <white>${owner.name}</white> hat einen <b>AIR-STRIKE</b> angefordert! " +
                "<gray>Einschlag in Kürze…</gray></red>").mini()
        )

        val marker = Location(world, targetX, dropY, targetZ)
        world.playSound(
            Sound.sound(BukkitSound.ENTITY_WITHER_SPAWN, Sound.Source.MASTER, 1.0f, 1.4f),
            marker.x(), marker.y(), marker.z(),
        )

        // Zielmarkierung als Partikelsaeule, damit der Einschlag angekuendigt wird
        var elapsed = 0L
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (elapsed >= AIRSTRIKE_DELAY_TICKS) {
                    task.cancel()
                    return@runAtFixedRate
                }
                var y = map.minY
                while (y <= dropY) {
                    world.spawnParticle(Particle.SMALL_FLAME, targetX, y, targetZ, 2, 0.15, 0.15, 0.15, 0.0)
                    y += 1.5
                }
                elapsed += 5L
            },
            1L,
            5L,
        )

        Bukkit.getGlobalRegionScheduler().runDelayed(
            plugin,
            {
                repeat(AIRSTRIKE_BOMB_COUNT) {
                    val offsetX = (Random.nextDouble() - 0.5) * 2.0 * AIRSTRIKE_SPREAD
                    val offsetZ = (Random.nextDouble() - 0.5) * 2.0 * AIRSTRIKE_SPREAD
                    dropBomb(Location(world, targetX + offsetX, dropY, targetZ + offsetZ), owner)
                }
            },
            AIRSTRIKE_DELAY_TICKS,
        )
    }

    /** Fallende Bombe, die bei Bodenkontakt gezuendet wird. */
    private fun dropBomb(loc: Location, owner: Player) {
        val world = loc.world ?: return

        val bomb = world.spawn(loc, TNTPrimed::class.java) { spawned ->
            // Lange Zuendschnur: Gezuendet wird bei Bodenkontakt, nicht per Timer.
            spawned.fuseTicks = BOMB_SAFETY_FUSE_TICKS
            spawned.setIsIncendiary(false)
            spawned.velocity = Vector(0.0, BOMB_DROP_VELOCITY, 0.0)
            spawned.persistentDataContainer.set(KEY_AIRSTRIKE_BOMB, PersistentDataType.BYTE, 1.toByte())
            spawned.persistentDataContainer.set(KEY_OWNER, PersistentDataType.STRING, owner.uniqueId.toString())
        }
        activeBombs.add(bomb)

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (!bomb.isValid) {
                    task.cancel()
                    activeBombs.remove(bomb)
                    return@runAtFixedRate
                }
                if (bomb.isOnGround) {
                    task.cancel()
                    val impact = bomb.location.clone()
                    activeBombs.remove(bomb)
                    // Eigene Sprengung statt der Vanilla-Explosion des TNT: garantiert ohne
                    // Blockschaden
                    bomb.remove()
                    blast(impact, owner, AIRSTRIKE_BLAST_POWER)
                }
            },
            1L,
            1L,
        )
    }

    // ==================================================================
    // C4
    // ==================================================================

    /** Platziert eine C4-Ladung auf dem angeklickten Block und gibt dem Spieler den Fernzuender. */
    fun placeC4(owner: Player, clickedBlock: Block): Boolean {
        val above = clickedBlock.getRelative(BlockFace.UP)
        if (!above.isPassable) {
            owner.sendMessage("<red>[OSOK] 💥 Hier ist kein Platz für die Ladung!</red>".mini())
            return false
        }

        val loc = above.location
        val world = loc.world ?: return false

        // BlockDisplay statt echtem Block: Die Map bleibt voellig unberuehrt.
        val charge = world.spawn(loc, BlockDisplay::class.java) { spawned ->
            spawned.block = Material.TNT.createBlockData()
            // Bewusst ohne Leuchtrahmen: Die Ladung soll nicht durch Waende sichtbar sein.
            spawned.isPersistent = false
            spawned.persistentDataContainer.set(KEY_C4_CHARGE, PersistentDataType.BYTE, 1.toByte())
            spawned.persistentDataContainer.set(KEY_OWNER, PersistentDataType.STRING, owner.uniqueId.toString())
        }

        c4Charges.getOrPut(owner.uniqueId) { mutableListOf() }.add(charge)

        owner.playSound(Sound.sound(BukkitSound.BLOCK_STONE_PLACE, Sound.Source.MASTER, 1.0f, 0.8f))
        owner.sendMessage(
            "<green>[OSOK] 💥 C4 platziert! <gray>Mit dem <yellow>Fernzünder</yellow> auslösen.</gray></green>".mini()
        )

        giveDetonator(owner)
        return true
    }

    private fun giveDetonator(owner: Player) {
        // hat schon einen
        if (owner.inventory.contents.any { isDetonator(it) }) return

        val detonator = ItemStack.of(Material.LEVER).apply {
            // Paper DataComponents statt ItemMeta
            setData(DataComponentTypes.CUSTOM_NAME, "<red><b>[💥] Fernzünder (Rechtsklick)</b></red>".mini())
            setData(
                DataComponentTypes.LORE,
                ItemLore.lore(listOf("<gray>Zündet alle deine C4-Ladungen!</gray>".mini())),
            )
            editPersistentDataContainer { pdc -> pdc.set(KEY_DETONATOR, PersistentDataType.BYTE, 1.toByte()) }
        }
        owner.inventory.addItem(detonator)
    }

    /**
     * Aufheben einer platzierten C4: Rechtsklick auf den Block, auf dem die Ladung liegt.
     *
     * Die Ladung ist ein [BlockDisplay] und damit nicht anklickbar - deshalb wird der
     * **Traegerblock** angeklickt und geprueft, ob direkt darueber eine eigene Ladung sitzt. Mit
     * einer C4 oder dem Fernzuender in der Hand greift das bewusst nicht: Damit wird platziert bzw.
     * gezuendet.
     *
     * Es lassen sich nur **eigene** Ladungen aufnehmen. Mit der letzten Ladung verschwindet auch
     * der Fernzuender - ohne Ladung hat er keine Funktion mehr.
     *
     * **`LOWEST` ist Pflicht.** Der `SpecialItemListener` platziert die C4 auf `NORMAL` und
     * verbraucht sie danach per `subtract(1)`. Bei genau einer C4 im Stapel ist `event.getItem()`
     * anschliessend leer - die Pruefung unten haette den Platzierungsvorgang dann nicht mehr als
     * solchen erkannt und die eben gesetzte Ladung im selben Klick wieder eingesammelt.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onC4Pickup(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val clickedBlock = event.clickedBlock ?: return

        val held = event.item
        if (isC4Item(held) || isDetonator(held)) return

        val owner = event.player
        val charges = c4Charges[owner.uniqueId]
        if (charges.isNullOrEmpty()) return

        val above = clickedBlock.getRelative(BlockFace.UP).location
        val found = charges.firstOrNull {
            it.isValid && isSameBlock(it.location, above) && isOwnedBy(it, owner)
        } ?: return

        event.isCancelled = true
        charges.remove(found)
        val chargeLoc = found.location.clone()
        found.remove()

        // Ladung zurueck ins Inventar
        val c4Index = KillstreakManager.SPECIAL_ITEM_IDS.indexOf(KillstreakManager.KEY_C4)
        owner.inventory.addItem(plugin.killstreakManager.createSpecificSpecialItem(c4Index))

        owner.playSound(Sound.sound(BukkitSound.BLOCK_STONE_BREAK, Sound.Source.MASTER, 1.0f, 1.4f))
        chargeLoc.world?.spawnParticle(
            Particle.SMOKE, chargeLoc.clone().add(0.5, 0.5, 0.5), 12, 0.2, 0.2, 0.2, 0.02,
        )

        if (charges.isEmpty()) {
            c4Charges.remove(owner.uniqueId)
            removeDetonator(owner)
            owner.sendMessage(
                ("<green>[OSOK] 💥 C4 wieder aufgenommen. <gray>Es war deine letzte Ladung - " +
                    "der Fernzünder ist weg.</gray></green>").mini()
            )
        } else {
            owner.sendMessage(
                ("<green>[OSOK] 💥 C4 wieder aufgenommen. <gray>Noch <yellow>${charges.size}</yellow> " +
                    "Ladung(en) platziert.</gray></green>").mini()
            )
        }
    }

    /**
     * Gehoert die Ladung diesem Spieler?
     *
     * Die Zuordnung steckt zusaetzlich im PersistentDataContainer der Ladung selbst und nicht nur
     * in [c4Charges]. Damit haengt die Regel "nur der Platzierer darf aufheben" am Objekt und nicht
     * allein am Nachschlagepfad - eine falsch einsortierte Ladung koennte sonst von jemand anderem
     * aufgenommen werden.
     */
    private fun isOwnedBy(charge: BlockDisplay, player: Player): Boolean =
        charge.persistentDataContainer.get(KEY_OWNER, PersistentDataType.STRING) == player.uniqueId.toString()

    private fun isSameBlock(first: Location, second: Location): Boolean =
        first.world != null && first.world == second.world &&
            first.blockX == second.blockX &&
            first.blockY == second.blockY &&
            first.blockZ == second.blockZ

    private fun isC4Item(stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty) return false
        val type = stack.persistentDataContainer
            .get(plugin.killstreakManager.specialItemKey, PersistentDataType.STRING)
        return type == KillstreakManager.KEY_C4
    }

    private fun isDetonator(stack: ItemStack?): Boolean =
        stack != null && !stack.isEmpty &&
            stack.persistentDataContainer.has(KEY_DETONATOR, PersistentDataType.BYTE)

    /** Nimmt dem Spieler den Fernzuender ab - er ist ohne platzierte Ladung wirkungslos. */
    private fun removeDetonator(owner: Player) {
        owner.inventory.contents.forEachIndexed { slot, stack ->
            if (isDetonator(stack)) {
                owner.inventory.setItem(slot, null)
            }
        }
    }

    /** Fernzuender: zuendet alle Ladungen des Spielers. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onDetonatorUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item ?: return
        if (!item.hasItemMeta()) return
        if (!item.persistentDataContainer.has(KEY_DETONATOR, PersistentDataType.BYTE)) return

        event.isCancelled = true
        val owner = event.player

        val charges = c4Charges.remove(owner.uniqueId)
        if (charges.isNullOrEmpty()) {
            owner.sendMessage("<red>[OSOK] 💥 Du hast keine C4-Ladung platziert!</red>".mini())
            owner.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            return
        }

        var detonated = 0
        for (charge in charges) {
            if (!charge.isValid) continue
            val loc = charge.location.clone().add(0.5, 0.5, 0.5)
            charge.remove()
            blast(loc, owner, C4_BLAST_POWER)
            detonated++
        }

        owner.sendMessage("<green>[OSOK] 💥 <b>$detonated</b> C4-Ladung(en) gezündet!</green>".mini())

        // Zuender verbrauchen, es gibt keine Ladungen mehr
        item.subtract(1)
    }

    // ==================================================================
    // Hilfsmittel & Aufraeumen
    // ==================================================================

    /** Entfernt genau ein Spezial-Item des angegebenen Typs aus dem Inventar. */
    private fun consumeSpecialItem(user: Player, typeId: String): Boolean {
        val typeKey = plugin.killstreakManager.specialItemKey
        val stack = user.inventory.contents
            .filterNotNull()
            .filter { it.hasItemMeta() }
            .firstOrNull { it.persistentDataContainer.get(typeKey, PersistentDataType.STRING) == typeId }
            ?: return false

        stack.subtract(1)
        return true
    }

    /**
     * Entfernt alle fallenden Bomben und platzierten C4-Ladungen. Durchsucht zusaetzlich alle
     * Welten nach PDC-markierten Resten, damit auch Objekte verschwinden, die durch einen Fehler
     * oder Serverabsturz nie registriert wurden.
     */
    fun clearAll() {
        activeBombs.toList().filter { it.isValid }.forEach { it.remove() }
        activeBombs.clear()

        c4Charges.values.flatten().filter { it.isValid }.forEach { it.remove() }
        c4Charges.clear()
        blastCredits.clear()

        // Ohne Ladung ist der Fernzuender wirkungslos - er darf nicht im Inventar zurueckbleiben
        Bukkit.getOnlinePlayers().forEach { removeDetonator(it) }

        var orphans = 0
        for (world in Bukkit.getWorlds()) {
            world.getEntitiesByClass(TNTPrimed::class.java)
                .filter { it.persistentDataContainer.has(KEY_AIRSTRIKE_BOMB, PersistentDataType.BYTE) }
                .forEach {
                    it.remove()
                    orphans++
                }
            world.getEntitiesByClass(BlockDisplay::class.java)
                .filter { it.persistentDataContainer.has(KEY_C4_CHARGE, PersistentDataType.BYTE) }
                .forEach {
                    it.remove()
                    orphans++
                }
        }
        if (orphans > 0) {
            plugin.logger.info("[OSOK] $orphans verwaiste Sprengstoff-Objekte entfernt.")
        }
    }

    /**
     * Sicherheitsnetz: Sollte eine Air-Strike-Bombe doch einmal regulaer explodieren (z. B. weil
     * die Zuendschnur abgelaufen ist), darf sie die Map nicht beschaedigen.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (event.entity.persistentDataContainer.has(KEY_AIRSTRIKE_BOMB, PersistentDataType.BYTE)) {
            event.blockList().clear()
            event.yield = 0.0f
        }
    }

    companion object {
        val AIRSTRIKE_GUI_TITLE: Component = "<red><b>🛰 Air-Strike - Ziel markieren</b></red>".mini()

        private val KEY_AIRSTRIKE_BOMB = NamespacedKey("oneshotonekill", "airstrike_bomb")
        private val KEY_C4_CHARGE = NamespacedKey("oneshotonekill", "c4_charge")
        private val KEY_DETONATOR = NamespacedKey("oneshotonekill", "c4_detonator")
        private val KEY_OWNER = NamespacedKey("oneshotonekill", "explosive_owner")
        private val KEY_TARGET_X = NamespacedKey("oneshotonekill", "airstrike_target_x")
        private val KEY_TARGET_Z = NamespacedKey("oneshotonekill", "airstrike_target_z")

        /** Auf welchen Spieler ein Sektor zeigt - erlaubt das Zielen auf die Live-Position. */
        private val KEY_TARGET_PLAYER = NamespacedKey("oneshotonekill", "airstrike_target_player")

        /** Sprengkraft einer Air-Strike-Bombe. Vanilla-TNT liegt bei 4.0. */
        private const val AIRSTRIKE_BLAST_POWER = 8.0f

        /** Sprengkraft einer C4-Ladung - bewusst groesser, da sie gezielt platziert wird. */
        private const val C4_BLAST_POWER = 12.0f

        private const val GUI_COLS = 9
        private const val GUI_ROWS = 6

        /** Vorwarnzeit zwischen Zielmarkierung und Einschlag. */
        private const val AIRSTRIKE_DELAY_TICKS = 45L
        private const val AIRSTRIKE_BOMB_COUNT = 8

        /**
         * Streuradius der Bomben um den Zielpunkt.
         *
         * Bewusst eng: Das Raster kann die Arena nur auf 9x6 Truhenfelder abbilden, ein Sektor ist
         * also mehrere Bloecke breit. Zusammen mit dem Zielen auf die Live-Position (siehe
         * [onAirStrikeMapClick]) landet der Abwurf dadurch dicht am Gegner statt irgendwo im
         * Sektor - das war der Kern von Issue #8, Punkt 3.
         */
        private const val AIRSTRIKE_SPREAD = 2.0

        /** Abwurfhoehe ueber der Arena-Oberkante, sofern keine Decke im Weg ist. */
        private const val AIRSTRIKE_HEIGHT_ABOVE_ARENA = 15.0

        private const val BOMB_SAFETY_FUSE_TICKS = 400

        /**
         * Startgeschwindigkeit der fallenden Bombe nach unten.
         *
         * Die Schwerkraft allein liess die Bombe traege heruntertrudeln - auf der Standard-Map sind
         * es wegen der Decke ohnehin nur rund zehn Bloecke Fallhoehe. Der Anschub verkuerzt die
         * Wartezeit zwischen Zielmarkierung und Einschlag spuerbar.
         */
        private const val BOMB_DROP_VELOCITY = -0.9

        /**
         * So lange nach einer Sprengung zaehlt ein Tod noch als deren Kill.
         *
         * Bewusst grosszuegig: Eine C4 mit Staerke 12 schleudert Getroffene dutzende Bloecke hoch.
         * Steigen und Fallen zusammen dauern regelmaessig laenger als die frueheren 6 Sekunden -
         * lief das Fenster mitten im Flug ab, wurde der Aufprall als "ist gestorben" gemeldet und
         * der Zuender ging leer aus (Issue #8).
         */
        private const val BLAST_CREDIT_MILLIS = 12000L

        /**
         * Verhaeltnis von Schadensreichweite zu Sprengkraft.
         *
         * `ServerExplosion#hurtEntities` sucht die Opfer im Umkreis `radius * 2.0` - eine C4 mit
         * Staerke 12 trifft also noch auf 24 Bloecke. Der Merkzettel wurde frueher nur mit `power`
         * gefuellt und liess damit die aeussere Haelfte der Druckwelle aus: Wer dort
         * hochgeschleudert wurde, starb ohne Zuordnung.
         */
        private const val EXPLOSION_DAMAGE_RADIUS_FACTOR = 2.0
    }
}
