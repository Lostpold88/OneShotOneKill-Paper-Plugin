package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.math.Position
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import net.kyori.adventure.util.Ticks
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.bukkit.Sound as BukkitSound

/**
 * ☢ **Nuke-Finale** - der einzige Weg, eine Runde regulaer zu beenden.
 *
 * Wer das Match-Ziel erreicht (Kill-Limit oder Ablauf der Zeit), gewinnt **nicht** mehr sofort: Er
 * bekommt den *Nuke-Auslöser* ins Inventar und muss die Runde selbst beenden. Der Ablauf:
 *
 * 1. [arm] - Auslöser vergeben, das Match laeuft normal weiter.
 * 2. [openCodeGui] - Freigabemenue mit vierstelligem Code zum Abtippen, dazu *Abbrechen* und
 *    *Bestaetigen*.
 * 3. [launch] - TNT-Bombardement ueber der ganzen Arena (ohne Blockschaden, ohne Spielerschaden).
 * 4. [startGas] - Giftgas ueber der ganzen Karte, an dem **alle** Spieler langsam ersticken.
 * 5. [finish] - Sind alle erstickt, sitzen alle als Zuschauer in der vergasten Arena; **erst dann**
 *    wird der Sieger ausgerufen.
 *
 * **Warum das Gas und nicht das TNT toetet:** Das Bombardement ist reine Kulisse. Waehrend
 * [isRunning] canceln wir im `CombatListener` jeden Schaden - sonst waere der halbe Server tot,
 * bevor das Gas ueberhaupt austritt, und die Reihenfolge aus der Anforderung ("erst TNT, dann Gas,
 * dann tot") liesse sich nicht halten.
 *
 * **Umsetzung des Gases:** Sichtbar wird es durch ein Raster aus [AreaEffectCloud]s mit dem
 * Vanilla-Partikel `NOXIOUS_GAS_CLOUD` - der Client rendert die Schwaden selbst, das kostet den
 * Server nichts. Die Wirkung haengt bewusst **nicht** an den Wolken (deren Trefferpruefung ist
 * flach und endet an Waenden), sondern an einer eigenen Dosis-Buchfuehrung pro Spieler: Jede
 * Sekunde steigt die Dosis, die Sicht truebt sich, die Lebensanzeige sinkt - und bei voller Dosis
 * erstickt der Spieler.
 */
class NukeManager(private val plugin: OneShotOneKill) : Listener {

    /** Zustand des Finales. */
    enum class Phase {
        /** Niemand hat das Match-Ziel erreicht. */
        IDLE,

        /** Ein Spieler traegt den Auslöser, das Match laeuft weiter. */
        ARMED,

        /** Bombardement und Gas laufen. */
        RUNNING,

        /** Alle sind erstickt, der Sieger steht fest. */
        FINISHED,
    }

    var phase: Phase = Phase.IDLE
        private set

    /**
     * Laeuft das Finale gerade? Solange das gilt, ist **jeder** Schaden abgeschaltet - siehe
     * `CombatListener`.
     */
    val isRunning: Boolean
        get() = phase == Phase.RUNNING

    /** Wer den Auslöser traegt und damit die Runde gewinnt. */
    private var armedId: UUID? = null

    /** Geforderter Code je Spieler - wird bei jedem Oeffnen des Menues neu gewuerfelt. */
    private val codes = mutableMapOf<UUID, String>()

    /** Bereits abgetippte Ziffern je Spieler. */
    private val entries = mutableMapOf<UUID, String>()

    private val gasClouds = mutableSetOf<AreaEffectCloud>()

    /** Wie viele Gas-Takte ein Spieler schon abbekommen hat. */
    private val gasDose = mutableMapOf<UUID, Int>()

    /** Wer bereits erstickt ist. */
    private val chokedOut = mutableSetOf<UUID>()

    /** Wen **wir** in den Zuschauermodus gesetzt haben - nur die holen wir auch zurueck. */
    private val spectators = mutableSetOf<UUID>()

    private var approachTask: ScheduledTask? = null
    private var shockwaveTask: ScheduledTask? = null
    private var cloudTask: ScheduledTask? = null
    private var gasTask: ScheduledTask? = null

    /** Der sichtbare Sprengkopf waehrend des Anflugs. */
    private var warheadDisplay: BlockDisplay? = null

    /**
     * Jeder von der Druckwelle veraenderte Block in seinem Zustand **davor**.
     *
     * [Position] statt [Location] als Schluessel: unveraenderlich und ohne Welt-Referenz, damit sie
     * als Schluessel taugt. Die Welt steht separat in [snapshotWorldId] - passt sie beim
     * Wiederherstellen nicht mehr, ist der Krater ohnehin mit der alten Welt verschwunden.
     */
    private val mapSnapshot = mutableMapOf<Position, BlockData>()
    private var snapshotWorldId: UUID? = null

    /**
     * Zeichnet die Schwaden. Laeuft bewusst **ueber das Ende hinaus**, damit die Zuschauer die
     * vergaste Karte auch nach der Siegerehrung noch sehen - beendet wird er erst von [clearAll].
     */
    private var fogTask: ScheduledTask? = null

    // ==================================================================
    // Freischaltung
    // ==================================================================

    /**
     * Vergibt den Nuke-Auslöser an [player]. Wird vom `MatchManager` gerufen, sobald das Kill-Ziel
     * erreicht oder die Zeit abgelaufen ist.
     *
     * Nur **einer** kann freigeschaltet sein: Erreichen zwei Spieler das Ziel kurz hintereinander,
     * bleibt es beim Ersten - sonst haetten am Ende zwei Spieler einen Auslöser und die Runde
     * haette zwei Sieger.
     *
     * @param reason kurze Begruendung fuer die Ansage ("20 Kills", "Zeit abgelaufen")
     */
    fun arm(player: Player, reason: String) {
        if (phase != Phase.IDLE) return

        phase = Phase.ARMED
        armedId = player.uniqueId
        player.inventory.addItem(createNukeItem())

        Bukkit.broadcast(" ".mini())
        Bukkit.broadcast("<dark_red><b>=======================================</b></dark_red>".mini())
        Bukkit.broadcast(
            ("<dark_red><b>   ☢ NUKE-FREIGABE ERTEILT!   </b></dark_red>").mini()
        )
        Bukkit.broadcast(
            ("<white>  <yellow><b>${player.name}</b></yellow> <gray>hat das Match-Ziel erreicht " +
                "($reason) und traegt jetzt den <red><b>Nuke-Auslöser</b></red>.</gray></white>").mini()
        )
        Bukkit.broadcast(
            "<gray>  Sobald er ihn benutzt, endet die Runde - für <b>alle</b>.</gray>".mini()
        )
        Bukkit.broadcast("<dark_red><b>=======================================</b></dark_red>".mini())
        Bukkit.broadcast(" ".mini())

        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.ENTITY_WITHER_SPAWN, Sound.Source.MASTER, 0.7f, 1.4f)
        )
        player.showTitle(
            Title.title(
                "<dark_red><b>☢ NUKE FREIGESCHALTET</b></dark_red>".mini(),
                "<gray>Rechtsklick mit dem Auslöser</gray>".mini(),
                Title.Times.times(Ticks.duration(10), Ticks.duration(60), Ticks.duration(20)),
            )
        )
    }

    /**
     * Verliert der Freigeschaltete die Verbindung, wandert der Auslöser an den aktuell Fuehrenden.
     *
     * Ohne diesen Uebergang haengt die Runde: Das Match-Ziel ist erreicht, der Timer abgelaufen -
     * aber niemand kann die Nuke mehr zuenden.
     */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val leaver = event.player
        codes.remove(leaver.uniqueId)
        entries.remove(leaver.uniqueId)
        spectators.remove(leaver.uniqueId)

        if (phase != Phase.ARMED || leaver.uniqueId != armedId) return

        val successor = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != leaver.uniqueId }
            .maxByOrNull { plugin.scoreboardManager.getKills(it.uniqueId) }

        phase = Phase.IDLE
        armedId = null

        if (successor == null) return

        arm(successor, "Nachrücker für ${leaver.name}")
    }

    // ==================================================================
    // Freigabemenue
    // ==================================================================

    /**
     * Oeffnet das Freigabemenue. Der Code wird bei **jedem** Oeffnen neu gewuerfelt und die
     * bisherige Eingabe verworfen - ein halb getippter Code aus einem abgebrochenen Versuch soll
     * nicht stehen bleiben.
     */
    fun openCodeGui(player: Player) {
        if (player.uniqueId != armedId) {
            player.sendMessage("<red>[OSOK] ☢ Du hast keine Nuke-Freigabe!</red>".mini())
            player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
            return
        }
        if (phase != Phase.ARMED) return

        codes[player.uniqueId] = String.format(Locale.US, "%04d", Random.nextInt(10_000))
        entries[player.uniqueId] = ""

        val gui = Bukkit.createInventory(null, SIZE, GUI_TITLE)
        render(gui, player)
        player.openInventory(gui)
        player.playSound(Sound.sound(BukkitSound.BLOCK_BEACON_ACTIVATE, Sound.Source.MASTER, 1.0f, 0.8f))
    }

    private fun render(gui: Inventory, player: Player) {
        gui.clear()

        val code = codes[player.uniqueId] ?: return
        val entry = entries[player.uniqueId].orEmpty()

        code.forEachIndexed { index, digit ->
            gui.setItem(SLOT_CODE_START + index, codeDigit(digit))
        }
        for (index in 0 until CODE_LENGTH) {
            gui.setItem(SLOT_ENTRY_START + index, entryDigit(entry.getOrNull(index)))
        }

        // Reihe 3 fasst die Ziffern 1-9, die Null sitzt mittig darunter
        for (digit in 1..9) {
            gui.setItem(SLOT_DIGITS_START + digit - 1, digitButton(digit))
        }
        gui.setItem(SLOT_DIGIT_ZERO, digitButton(0))
        gui.setItem(SLOT_BACKSPACE, backspaceButton())
        gui.setItem(SLOT_CANCEL, cancelButton())
        gui.setItem(SLOT_CONFIRM, confirmButton(entry.length == CODE_LENGTH))

        for (slot in 0 until SIZE) {
            if (gui.getItem(slot) == null) {
                gui.setItem(slot, filler())
            }
        }
    }

    private fun codeDigit(digit: Char): ItemStack = ItemStack.of(Material.PAPER).apply {
        setData(DataComponentTypes.CUSTOM_NAME, "<gold><b>$digit</b></gold>".mini())
        setData(
            DataComponentTypes.LORE,
            ItemLore.lore(listOf("<gray>Freigabecode - abtippen.</gray>".mini())),
        )
    }

    private fun entryDigit(digit: Char?): ItemStack =
        if (digit == null) {
            ItemStack.of(Material.GRAY_STAINED_GLASS_PANE).apply {
                setData(DataComponentTypes.CUSTOM_NAME, "<dark_gray>_</dark_gray>".mini())
            }
        } else {
            ItemStack.of(Material.LIME_STAINED_GLASS_PANE).apply {
                setData(DataComponentTypes.CUSTOM_NAME, "<green><b>$digit</b></green>".mini())
            }
        }

    private fun digitButton(digit: Int): ItemStack = ItemStack.of(Material.WHITE_CONCRETE).apply {
        setData(DataComponentTypes.CUSTOM_NAME, "<white><b>$digit</b></white>".mini())
        editPersistentDataContainer { pdc ->
            pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_DIGIT)
            pdc.set(KEY_DIGIT, PersistentDataType.INTEGER, digit)
        }
    }

    private fun backspaceButton(): ItemStack = ItemStack.of(Material.ORANGE_CONCRETE).apply {
        setData(DataComponentTypes.CUSTOM_NAME, "<gold><b>⌫ Löschen</b></gold>".mini())
        setData(
            DataComponentTypes.LORE,
            ItemLore.lore(listOf("<gray>Entfernt die letzte Ziffer.</gray>".mini())),
        )
        editPersistentDataContainer { pdc -> pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_BACKSPACE) }
    }

    private fun cancelButton(): ItemStack = ItemStack.of(Material.RED_CONCRETE).apply {
        setData(DataComponentTypes.CUSTOM_NAME, "<red><b>✖ Abbrechen</b></red>".mini())
        setData(
            DataComponentTypes.LORE,
            ItemLore.lore(listOf("<gray>Der Auslöser bleibt dir erhalten.</gray>".mini())),
        )
        editPersistentDataContainer { pdc -> pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_CANCEL) }
    }

    private fun confirmButton(ready: Boolean): ItemStack =
        ItemStack.of(if (ready) Material.LIME_CONCRETE else Material.GRAY_CONCRETE).apply {
            setData(
                DataComponentTypes.CUSTOM_NAME,
                if (ready) {
                    "<green><b>✔ Bestätigen</b></green>".mini()
                } else {
                    "<dark_gray><b>✔ Bestätigen</b></dark_gray>".mini()
                },
            )
            setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                    listOf(
                        if (ready) {
                            "<gray>Startet den Angriff. <red>Unwiderruflich.</red></gray>".mini()
                        } else {
                            "<gray>Erst den vierstelligen Code abtippen.</gray>".mini()
                        },
                    )
                ),
            )
            editPersistentDataContainer { pdc -> pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_CONFIRM) }
        }

    private fun filler(): ItemStack = ItemStack.of(Material.BLACK_STAINED_GLASS_PANE).apply {
        setData(DataComponentTypes.CUSTOM_NAME, Component.empty())
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title() != GUI_TITLE) return

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (player.uniqueId != armedId || phase != Phase.ARMED) return

        val clicked = event.currentItem ?: return
        if (!clicked.hasItemMeta()) return

        val action = clicked.persistentDataContainer.get(KEY_ACTION, PersistentDataType.STRING) ?: return

        when (action) {
            ACTION_DIGIT -> {
                val digit = clicked.persistentDataContainer.get(KEY_DIGIT, PersistentDataType.INTEGER) ?: return
                val entry = entries[player.uniqueId].orEmpty()
                if (entry.length >= CODE_LENGTH) {
                    player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 0.7f, 1.0f))
                    return
                }
                entries[player.uniqueId] = entry + digit
                render(event.inventory, player)
                player.playSound(
                    Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 0.8f, 1.6f)
                )
            }

            ACTION_BACKSPACE -> {
                val entry = entries[player.uniqueId].orEmpty()
                if (entry.isEmpty()) {
                    player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 0.7f, 1.0f))
                    return
                }
                entries[player.uniqueId] = entry.dropLast(1)
                render(event.inventory, player)
                player.playSound(
                    Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 0.8f, 1.0f)
                )
            }

            ACTION_CANCEL -> {
                player.closeInventory()
                player.playSound(
                    Sound.sound(BukkitSound.BLOCK_BEACON_DEACTIVATE, Sound.Source.MASTER, 1.0f, 1.2f)
                )
                player.sendMessage(
                    ("<gray>[OSOK] ☢ Abgebrochen. <yellow>Der Auslöser bleibt in deinem " +
                        "Inventar.</yellow></gray>").mini()
                )
            }

            ACTION_CONFIRM -> confirm(player, event.inventory)
        }
    }

    private fun confirm(player: Player, gui: Inventory) {
        val code = codes[player.uniqueId] ?: return
        val entry = entries[player.uniqueId].orEmpty()

        if (entry != code) {
            entries[player.uniqueId] = ""
            render(gui, player)
            player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_DIDGERIDOO, Sound.Source.MASTER, 1.0f, 0.6f))
            player.sendMessage(
                "<red>[OSOK] ☢ Falscher Freigabecode! <gray>Nochmal von vorn.</gray></red>".mini()
            )
            return
        }

        player.closeInventory()
        codes.remove(player.uniqueId)
        entries.remove(player.uniqueId)
        launch(player)
    }

    /** Verhindert, dass per Ziehen etwas in das Menue gelegt wird. */
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.title() == GUI_TITLE) {
            event.isCancelled = true
        }
    }

    // ==================================================================
    // Abschuss, Anflug und Detonation
    // ==================================================================

    private fun launch(owner: Player) {
        phase = Phase.RUNNING
        armedId = owner.uniqueId
        consumeNukeItem(owner)

        // Ab hier ist das Match vorbei: kein Kampf, kein Timer, keine Item-Wirkungen mehr.
        plugin.matchManager.beginFinale()

        Bukkit.broadcast(" ".mini())
        Bukkit.broadcast("<dark_red><b>=======================================</b></dark_red>".mini())
        Bukkit.broadcast("<dark_red><b>   ☢ NUKE ANGEFORDERT!   </b></dark_red>".mini())
        Bukkit.broadcast(
            ("<white>  <yellow><b>${owner.name}</b></yellow> <gray>hat den Angriff " +
                "freigegeben. Sucht Deckung - es gibt keine.</gray></white>").mini()
        )
        Bukkit.broadcast("<dark_red><b>=======================================</b></dark_red>".mini())
        Bukkit.broadcast(" ".mini())

        Bukkit.getServer().showTitle(
            Title.title(
                "<dark_red><b>☢ NUKE ☢</b></dark_red>".mini(),
                "<red>Der Sprengkopf ist unterwegs…</red>".mini(),
                Title.Times.times(Ticks.duration(10), Ticks.duration(50), Ticks.duration(20)),
            )
        )
        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.EVENT_RAID_HORN, Sound.Source.MASTER, 1.0f, 0.7f)
        )

        startApproach()
    }

    /** Bodennullpunkt: Mitte der Arena, auf Hoehe der Arena-Unterkante. */
    private fun groundZero(world: World): Location {
        val map = plugin.worldManager.activeMapConfig
        return Location(world, (map.minX + map.maxX) / 2.0, map.minY, (map.minZ + map.maxZ) / 2.0)
    }

    /**
     * Anflug: Der Sprengkopf sinkt sichtbar auf die Arenamitte zu, waehrend die Sirene laeuft und
     * ein Countdown ueber den Bildschirm geht.
     *
     * Der Sprengkopf ist ein vergroessertes [BlockDisplay] statt einer fallenden Entity: Er soll
     * exakt auf die Sekunde einschlagen, nicht der Schwerkraft folgen, und weder von Bloecken
     * aufgehalten noch von der Physik abgelenkt werden.
     */
    private fun startApproach() {
        val world = plugin.worldManager.osokWorld
        if (world == null) {
            detonate()
            return
        }

        val map = plugin.worldManager.activeMapConfig
        val target = groundZero(world)

        // Unter der Decke bleiben: Auf einer ueberdachten Map (Standard) haenge der Sprengkopf
        // sonst ueber dem Dach und waere fuer alle in der Arena unsichtbar - Anflug ohne Bild.
        val approachHeight = if (map.hasCeiling) {
            minOf(BOMB_APPROACH_HEIGHT, map.maxFlyY - map.minY - 1.0)
        } else {
            BOMB_APPROACH_HEIGHT
        }
        val start = target.clone().add(0.0, approachHeight, 0.0)

        val warhead = world.spawn(start, BlockDisplay::class.java) { display ->
            display.block = Material.TNT.createBlockData()
            // Skalierung ueber die Transformation - ein einzelner Block waere aus der Distanz
            // ueberhaupt nicht zu sehen. Die Verschiebung zentriert den vergroesserten Block.
            display.transformation = Transformation(
                Vector3f(-BOMB_SCALE / 2f, -BOMB_SCALE / 2f, -BOMB_SCALE / 2f),
                AxisAngle4f(),
                Vector3f(BOMB_SCALE, BOMB_SCALE, BOMB_SCALE),
                AxisAngle4f(),
            )
            display.brightness = Display.Brightness(15, 15)
            display.isGlowing = true
            display.glowColorOverride = Color.RED
            display.isPersistent = false
            display.persistentDataContainer.set(KEY_NUKE_WARHEAD, PersistentDataType.BYTE, 1.toByte())
        }
        warheadDisplay = warhead

        var ticks = 0
        approachTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (phase != Phase.RUNNING) {
                    task.cancel()
                    approachTask = null
                    return@runAtFixedRate
                }

                val progress = ticks.toDouble() / BOMB_APPROACH_TICKS
                if (progress >= 1.0 || !warhead.isValid) {
                    task.cancel()
                    approachTask = null
                    detonate()
                    return@runAtFixedRate
                }

                // Fallhoehe quadratisch: Der Sprengkopf wird sichtbar schneller, je naeher er kommt
                val height = approachHeight * (1.0 - progress * progress)
                val at = target.clone().add(0.0, height, 0.0)
                // Synchrones teleport ist hier die richtige Wahl (Vorgabe 6): dieselbe Welt, jeder
                // Takt, geladener Chunk, Aufruf auf dem Main-Thread. Ein teleportAsync waere
                // mehrfach offen, bevor der vorherige aufgeloest ist, und der Sprengkopf ruckelte.
                warhead.teleport(at)

                world.spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.6, 0.6, 0.6, 0.02)
                world.spawnParticle(Particle.FLAME, at, 8, 0.4, 0.4, 0.4, 0.01)

                // Zielsaeule vom Boden bis zum Sprengkopf - die Arena sieht, wo es einschlaegt
                var y = target.y
                while (y < at.y) {
                    world.spawnParticle(Particle.SMALL_FLAME, target.x, y, target.z, 2, 0.2, 0.2, 0.2, 0.0)
                    y += 3.0
                }

                val secondsLeft = ((BOMB_APPROACH_TICKS - ticks) / 20.0).toInt() + 1
                if (ticks % 20 == 0) {
                    Bukkit.getServer().showTitle(
                        Title.title(
                            "<dark_red><b>☢ $secondsLeft ☢</b></dark_red>".mini(),
                            "<red>Einschlag steht bevor</red>".mini(),
                            Title.Times.times(Ticks.duration(0), Ticks.duration(25), Ticks.duration(5)),
                        )
                    )
                    Bukkit.getServer().playSound(
                        Sound.sound(
                            BukkitSound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 1.0f,
                            0.5f + (1.0f - progress.toFloat()) * 0.2f,
                        )
                    )
                }

                ticks += BOMB_APPROACH_PERIOD_TICKS.toInt()
            },
            BOMB_APPROACH_PERIOD_TICKS,
            BOMB_APPROACH_PERIOD_TICKS,
        )
    }

    /**
     * Der Einschlag: Blitz, Druckwelle, Pilzwolke - und die Arena wird tatsaechlich zerstoert.
     *
     * Druckwelle und Pilzwolke laufen **nebeneinander**: Die Welle ist nach gut vier Sekunden durch
     * und uebergibt an das Gas, waehrend die Wolke noch rund zwanzig Sekunden weitersteigt.
     */
    private fun detonate() {
        removeWarhead()

        val world = plugin.worldManager.osokWorld
        if (world == null) {
            startGas()
            return
        }
        val center = groundZero(world)

        Bukkit.broadcast(" ".mini())
        Bukkit.broadcast(
            ("<dark_red><b>[OSOK] ☢ DETONATION!</b> <gray>Die Arena ist nicht mehr " +
                "das, was sie war.</gray></dark_red>").mini()
        )
        Bukkit.broadcast(" ".mini())

        Bukkit.getServer().showTitle(
            Title.title(
                "<white><b>☢</b></white>".mini(),
                "<dark_red><b>DETONATION</b></dark_red>".mini(),
                Title.Times.times(Ticks.duration(0), Ticks.duration(40), Ticks.duration(20)),
            )
        )

        // Der weisse Blitz zuerst - Particle.FLASH braucht zwingend ein Color-Datenobjekt,
        // sonst bricht der ganze Aufruf mit "missing required data class" ab.
        repeat(FLASH_BURSTS) {
            world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0, Color.WHITE)
        }
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 24, 8.0, 4.0, 8.0, 0.0)
        world.spawnParticle(Particle.SONIC_BOOM, center, 3, 2.0, 1.0, 2.0, 0.0)
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, center, 12, 10.0, 3.0, 10.0, 0.0)

        // Vier Schichten Krach: der Knall, das Grollen, das Nachhallen, das Kreischen
        val server = Bukkit.getServer()
        server.playSound(Sound.sound(BukkitSound.ENTITY_GENERIC_EXPLODE, Sound.Source.MASTER, 1.0f, 0.4f))
        server.playSound(Sound.sound(BukkitSound.ENTITY_LIGHTNING_BOLT_THUNDER, Sound.Source.MASTER, 1.0f, 0.5f))
        server.playSound(Sound.sound(BukkitSound.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 1.0f, 0.4f))
        server.playSound(Sound.sound(BukkitSound.ENTITY_WARDEN_SONIC_BOOM, Sound.Source.MASTER, 1.0f, 0.6f))

        // Kurz blind und taub: Der Blitz soll auch spuerbar sein, nicht nur sichtbar
        Bukkit.getOnlinePlayers().forEach { player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, FLASH_BLIND_TICKS, 0, false, false))
            player.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, FLASH_BLIND_TICKS * 2, 0, false, false))
        }

        startShockwave(world, center)
        startMushroomCloud(world, center)
    }

    /**
     * Die Druckwelle: ein Ring, der von der Mitte nach aussen laeuft und alles einebnet, was er
     * ueberrollt.
     *
     * Zerstoert wird **spaltenweise und nur innerhalb der Arena-Grenzen** - die Lobby liegt
     * ausserhalb und muss stehen bleiben, sonst gaebe es nach der Runde keinen Ort mehr, an den die
     * Spieler zurueckkoennen.
     *
     * Jeder veraenderte Block wird vorher gesichert ([mapSnapshot]) und beim naechsten
     * `/osok start` wiederhergestellt. Ohne das waere die Karte nach der ersten Runde dauerhaft ein
     * Krater, und jede weitere Runde faende auf Schutt statt auf der Map statt.
     */
    private fun startShockwave(world: World, center: Location) {
        val map = plugin.worldManager.activeMapConfig
        val maxRadius = maxOf(map.maxX - map.minX, map.maxZ - map.minZ)
        snapshotWorldId = world.uid
        var step = 0

        shockwaveTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (phase != Phase.RUNNING) {
                    task.cancel()
                    shockwaveTask = null
                    return@runAtFixedRate
                }
                if (step >= SHOCKWAVE_STEPS) {
                    task.cancel()
                    shockwaveTask = null
                    startGas()
                    return@runAtFixedRate
                }

                val inner = maxRadius * step / SHOCKWAVE_STEPS.toDouble()
                val outer = maxRadius * (step + 1) / SHOCKWAVE_STEPS.toDouble()
                flattenRing(world, center, inner, outer)
                drawShockwaveRing(world, center, outer)
                pushPlayers(center, outer)

                step++
            },
            1L,
            SHOCKWAVE_PERIOD_TICKS,
        )
    }

    /**
     * Ebnet alle Spalten im Ring zwischen [inner] und [outer] ein.
     *
     * Pro Spalte bleibt der **unterste** feste Block stehen und wird zu verbranntem Gestein; alles
     * darueber verschwindet. Das ergibt eine flache, verkohlte Flaeche statt eines Lochs - wichtig,
     * weil die Spieler bis zum Gas noch darauf stehen und sonst ins Leere fielen.
     *
     * `setType(..., applyPhysics = false)` ist Pflicht: Mit Physik loesen tausende Aenderungen
     * Nachbar-Updates, Sandfall und Lichtneuberechnungen aus - das legt den Server lahm.
     */
    private fun flattenRing(world: World, center: Location, inner: Double, outer: Double) {
        val map = plugin.worldManager.activeMapConfig
        val scanBottom = (map.minY - SCAN_BELOW).toInt()
        val scanTop = (map.maxY + SCAN_ABOVE).toInt()

        // floor/ceil statt toInt(): toInt() schneidet Richtung null ab und liesse bei negativen
        // Koordinaten - DustPvP faengt bei x = -25 an - den Randstreifen der Arena stehen.
        val minX = floor(maxOf(map.minX, center.x - outer)).toInt()
        val maxX = ceil(minOf(map.maxX, center.x + outer)).toInt()
        val minZ = floor(maxOf(map.minZ, center.z - outer)).toInt()
        val maxZ = ceil(minOf(map.maxZ, center.z + outer)).toInt()

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val dx = x + 0.5 - center.x
                val dz = z + 0.5 - center.z
                val distance = sqrt(dx * dx + dz * dz)
                if (distance < inner || distance >= outer) continue

                flattenColumn(world, x, z, scanBottom, scanTop)
            }
        }
    }

    private fun flattenColumn(world: World, x: Int, z: Int, scanBottom: Int, scanTop: Int) {
        var floorFound = false

        for (y in scanBottom..scanTop) {
            val block = world.getBlockAt(x, y, z)
            if (block.type.isAir) continue

            mapSnapshot[Position.block(x, y, z)] = block.blockData

            if (!floorFound) {
                floorFound = true
                block.setType(SCORCHED[Random.nextInt(SCORCHED.size)], false)
            } else {
                block.setType(Material.AIR, false)
            }
        }
    }

    private fun drawShockwaveRing(world: World, center: Location, radius: Double) {
        if (radius < 1.0) return

        val points = (radius * SHOCKWAVE_POINTS_PER_BLOCK).toInt().coerceIn(16, SHOCKWAVE_MAX_POINTS)
        for (index in 0 until points) {
            val angle = 2.0 * Math.PI * index / points
            val x = center.x + cos(angle) * radius
            val z = center.z + sin(angle) * radius

            world.spawnParticle(Particle.LARGE_SMOKE, x, center.y + 1.5, z, 3, 0.4, 1.2, 0.4, 0.02)
            world.spawnParticle(Particle.FLAME, x, center.y + 0.8, z, 2, 0.3, 0.3, 0.3, 0.02)
            if (index % 4 == 0) {
                world.spawnParticle(Particle.GUST, x, center.y + 2.0, z, 1, 0.2, 0.2, 0.2, 0.0)
            }
        }
    }

    /** Die Welle wirft die Getroffenen um - Schaden nimmt dabei niemand, der ist abgeschaltet. */
    private fun pushPlayers(center: Location, radius: Double) {
        center.getNearbyPlayers(radius + 2.0)
            .filter { it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE }
            .forEach { player ->
                val away = player.location.toVector().subtract(center.toVector())
                if (away.lengthSquared() < 0.01) return@forEach

                val push = away.normalize().multiply(SHOCKWAVE_PUSH)
                push.y = SHOCKWAVE_LIFT
                player.velocity = player.velocity.add(push)
            }
    }

    /**
     * Die Pilzwolke: ein aufsteigender Stiel, der sich oben zu einem Hut oeffnet.
     *
     * Gezeichnet wird ueber `World#spawnParticle` - anders als beim Gas ist das hier richtig: Die
     * Wolke ist ein Objekt in der Welt, das alle aus derselben Richtung sehen sollen, nicht eine
     * Huelle um den einzelnen Betrachter.
     */
    private fun startMushroomCloud(world: World, center: Location) {
        var step = 0

        cloudTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (phase == Phase.IDLE || step >= CLOUD_STEPS) {
                    task.cancel()
                    cloudTask = null
                    return@runAtFixedRate
                }

                val progress = step / CLOUD_STEPS.toDouble()
                val topY = center.y + CLOUD_MAX_HEIGHT * minOf(1.0, progress * CLOUD_RISE_SPEED)

                // Stiel: mehrere Ringe uebereinander, unten breiter als oben
                var y = center.y
                while (y < topY) {
                    val heightShare = (y - center.y) / CLOUD_MAX_HEIGHT
                    val radius = CLOUD_STEM_RADIUS * (1.0 - heightShare * 0.4)
                    drawCloudRing(world, center, y, radius, CLOUD_STEM_POINTS)
                    y += CLOUD_STEM_SPACING
                }

                // Hut: oeffnet sich, sobald der Stiel Hoehe hat, und waechst weiter
                if (progress > CLOUD_CAP_FROM) {
                    val capShare = (progress - CLOUD_CAP_FROM) / (1.0 - CLOUD_CAP_FROM)
                    val capRadius = CLOUD_CAP_RADIUS * capShare
                    for (ring in 0 until CLOUD_CAP_RINGS) {
                        val ringY = topY - ring * CLOUD_CAP_SPACING
                        val shrink = 1.0 - ring / CLOUD_CAP_RINGS.toDouble() * 0.5
                        drawCloudRing(world, center, ringY, capRadius * shrink, CLOUD_CAP_POINTS)
                    }
                }

                // Glut im Fuss der Wolke
                world.spawnParticle(Particle.LAVA, center.clone().add(0.0, 1.0, 0.0), 4, 3.0, 1.0, 3.0, 0.0)
                world.spawnParticle(Particle.FLAME, center.clone().add(0.0, 1.0, 0.0), 12, 4.0, 1.5, 4.0, 0.02)

                step++
            },
            1L,
            CLOUD_PERIOD_TICKS,
        )
    }

    private fun drawCloudRing(world: World, center: Location, y: Double, radius: Double, points: Int) {
        if (radius <= 0.1) return

        for (index in 0 until points) {
            val angle = 2.0 * Math.PI * index / points + y * 0.15
            val x = center.x + cos(angle) * radius
            val z = center.z + sin(angle) * radius
            world.spawnParticle(Particle.LARGE_SMOKE, x, y, z, 2, 0.8, 0.8, 0.8, 0.01)
            if (index % 3 == 0) {
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, x, y, z, 1, 0.5, 0.5, 0.5, 0.005)
            }
        }
    }

    private fun removeWarhead() {
        warheadDisplay?.takeIf { it.isValid }?.remove()
        warheadDisplay = null
    }

    /**
     * Stellt die eingeebnete Arena wieder her.
     *
     * Laeuft beim naechsten `/osok start` (ueber [clearAll]) und damit **bevor** die Spieler in die
     * Arena teleportiert werden. Ohne Physik zurueckgesetzt, sonst loest jeder Block beim Einsetzen
     * Nachbar-Updates aus.
     */
    private fun restoreMap() {
        if (mapSnapshot.isEmpty()) return

        val world = snapshotWorldId?.let { Bukkit.getWorld(it) }
        if (world == null) {
            // Die Welt ist weg (Map-Wechsel, Neustart) - dann ist auch der Krater weg
            mapSnapshot.clear()
            snapshotWorldId = null
            return
        }

        mapSnapshot.forEach { (position, data) ->
            world.getBlockAt(position.blockX(), position.blockY(), position.blockZ())
                .setBlockData(data, false)
        }
        plugin.logger.info("[OSOK] ${mapSnapshot.size} Bloecke der Arena wiederhergestellt.")

        mapSnapshot.clear()
        snapshotWorldId = null
    }

    // ==================================================================
    // Giftgas
    // ==================================================================

    private fun startGas() {
        if (phase != Phase.RUNNING) return

        Bukkit.broadcast(" ".mini())
        Bukkit.broadcast(
            ("<dark_green><b>[OSOK] ☠ GIFTGAS BREITET SICH AUS!</b> <gray>Die Luft brennt in " +
                "den Lungen - niemand kommt hier raus.</gray></dark_green>").mini()
        )
        Bukkit.broadcast(" ".mini())

        Bukkit.getServer().showTitle(
            Title.title(
                "<dark_green><b>☠ GIFTGAS</b></dark_green>".mini(),
                "<gray>Du bekommst keine Luft mehr…</gray>".mini(),
                Title.Times.times(Ticks.duration(10), Ticks.duration(60), Ticks.duration(20)),
            )
        )
        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.ENTITY_BREEZE_WIND_BURST, Sound.Source.MASTER, 1.0f, 0.5f)
        )

        plugin.worldManager.osokWorld?.let { spawnGasClouds(it) }

        gasTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (phase != Phase.RUNNING) {
                    task.cancel()
                    gasTask = null
                    return@runAtFixedRate
                }
                tickGas()
            },
            GAS_PERIOD_TICKS,
            GAS_PERIOD_TICKS,
        )

        // Die Schwaden laufen im eigenen, schnelleren Takt und ueberdauern das Rundenende -
        // die Zuschauer sollen auf eine vergaste Karte blicken, nicht auf klare Luft.
        fogTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (phase != Phase.RUNNING && phase != Phase.FINISHED) {
                    task.cancel()
                    fogTask = null
                    return@runAtFixedRate
                }
                val world = plugin.worldManager.osokWorld
                Bukkit.getOnlinePlayers()
                    .filter { world == null || it.world == world }
                    .forEach { renderFog(it) }
            },
            FOG_PERIOD_TICKS,
            FOG_PERIOD_TICKS,
        )
    }

    /**
     * Legt jedem Betrachter sein eigenes Gasvolumen um sich herum.
     *
     * Zwei Entscheidungen stecken darin:
     *
     * - **Pro Spieler statt ueber die Welt.** `World#spawnParticle` schickt jede Schwade an alle
     *   Umstehenden; bei fuenf Spielern kaeme dieselbe Wolke fuenfmal an. `Player#spawnParticle`
     *   zeichnet nur fuer den einen Betrachter - und weil das Volumen ohnehin um ihn herum liegt,
     *   sieht er exakt dasselbe.
     * - **Ein Aufruf statt hunderter.** Die Streuwerte spannen einen Quader auf, in dem der
     *   **Client** die angeforderten Partikel selbst verteilt. Aus einem Paket werden so hunderte
     *   Schwaden; der Server zaehlt sie nicht einmal.
     *
     * Die Farbe kommt aus [Particle.DustOptions] - nur damit laesst sich ein Partikel wirklich
     * gruen faerben. Zwei Toene uebereinander geben der Wolke Tiefe, sonst wirkt sie wie eine
     * flache Wand.
     */
    private fun renderFog(viewer: Player) {
        val chest = viewer.location.add(0.0, FOG_EYE_HEIGHT, 0.0)

        // Fallout: Asche rieselt von oben durch die Schwaden. Kommt aus derselben Ueberlegung wie
        // der Nebel - hoch angesetzt, weit gestreut, ein Aufruf pro Betrachter.
        viewer.spawnParticle(
            Particle.ASH, chest.clone().add(0.0, FALLOUT_HEIGHT, 0.0), FALLOUT_DENSITY,
            FOG_RADIUS, FALLOUT_SPREAD, FOG_RADIUS, 0.0,
        )
        viewer.spawnParticle(
            Particle.WHITE_ASH, chest.clone().add(0.0, FALLOUT_HEIGHT, 0.0), FALLOUT_DENSITY / 2,
            FOG_RADIUS, FALLOUT_SPREAD, FOG_RADIUS, 0.0,
        )

        viewer.spawnParticle(
            Particle.DUST, chest, FOG_DENSITY_BRIGHT,
            FOG_RADIUS, FOG_HEIGHT, FOG_RADIUS, 0.0, GAS_DUST_BRIGHT,
        )
        viewer.spawnParticle(
            Particle.DUST, chest, FOG_DENSITY_DARK,
            FOG_RADIUS, FOG_HEIGHT, FOG_RADIUS, 0.0, GAS_DUST_DARK,
        )

        // Bodenteppich: haengt an den Fuessen des Betrachters und liegt damit auf jeder Ebene der
        // Arena auf - auch auf Bruecken und Plattformen.
        viewer.spawnParticle(
            Particle.DUST, viewer.location, FOG_DENSITY_GROUND,
            FOG_RADIUS, FOG_GROUND_HEIGHT, FOG_RADIUS, 0.0, GAS_DUST_GROUND,
        )

        // Etwas Rauch dazwischen, damit die Wolke Struktur bekommt und nicht nur flimmert
        viewer.spawnParticle(
            Particle.LARGE_SMOKE, chest, FOG_DENSITY_SMOKE,
            FOG_RADIUS, FOG_HEIGHT * 0.6, FOG_RADIUS, 0.01,
        )
    }

    /**
     * Legt ein Raster aus Gaswolken ueber die Arena.
     *
     * [AreaEffectCloud] ist hier der billigste Weg zu flaechendeckendem Nebel: Die Wolke wird einmal
     * gesetzt, danach zeichnet sie **der Client**. Wirkstoffe traegt sie bewusst keine - das
     * Ersticken buchfuehrt [tickGas], damit es auch unter Daechern und hinter Waenden greift.
     */
    private fun spawnGasClouds(world: World) {
        val map = plugin.worldManager.activeMapConfig
        val cloudY = map.minY + GAS_CLOUD_HEIGHT

        var x = map.minX
        while (x <= map.maxX) {
            var z = map.minZ
            while (z <= map.maxZ) {
                val cloud = world.spawn(Location(world, x, cloudY, z), AreaEffectCloud::class.java) { spawned ->
                    spawned.radius = GAS_CLOUD_RADIUS
                    spawned.duration = GAS_CLOUD_DURATION_TICKS
                    spawned.radiusPerTick = 0.0f
                    spawned.waitTime = 0
                    // Gruen wie die Schwaden um den Spieler - ueber DustOptions, weil sich nur
                    // damit die Farbe eines Partikels wirklich festlegen laesst.
                    spawned.setParticle(Particle.DUST, GAS_DUST_BRIGHT)
                    spawned.isPersistent = false
                    spawned.persistentDataContainer.set(KEY_GAS_CLOUD, PersistentDataType.BYTE, 1.toByte())
                }
                gasClouds.add(cloud)
                z += GAS_CLOUD_SPACING
            }
            x += GAS_CLOUD_SPACING
        }
    }

    /**
     * Ein Gas-Takt: Dosis erhoehen, Wirkung anzeigen, Erstickte aussortieren.
     *
     * Erfasst werden alle **Mitspieler** (Ueberlebensmodus/Abenteuer) - unabhaengig davon, wo sie
     * stehen. Das Gas liegt ueber der ganzen Karte, und ein Spieler in der Lobby duerfte die Runde
     * nicht blockieren.
     */
    private fun tickGas() {
        val victims = participants()
        if (victims.isEmpty()) {
            finish()
            return
        }

        for (victim in victims) {
            val dose = (gasDose[victim.uniqueId] ?: 0) + 1
            gasDose[victim.uniqueId] = dose

            if (dose >= GAS_DOSE_TO_DEATH) {
                chokeOut(victim)
            } else {
                applyChoking(victim, dose)
            }
        }

        if (participants().isEmpty()) {
            finish()
        }
    }

    /** Spieler, die noch mitspielen und noch nicht erstickt sind. */
    private fun participants(): List<Player> = Bukkit.getOnlinePlayers()
        .filter { it.uniqueId !in chokedOut }
        .filter { it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE }

    private fun applyChoking(victim: Player, dose: Int) {
        val remaining = GAS_DOSE_TO_DEATH - dose

        // Die Lebensanzeige sinkt sichtbar mit der Dosis - ohne echten Schaden, denn jeder
        // Schadensweg ist waehrend des Finales gesperrt. Nie auf 0: Ein echter Tod wuerde den
        // Respawn-Bildschirm zeigen und die Buchfuehrung hier umgehen.
        val share = 1.0 - dose.toDouble() / GAS_DOSE_TO_DEATH
        victim.health = (MAX_HEALTH * share).coerceAtLeast(MIN_CHOKE_HEALTH)

        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, GAS_EFFECT_TICKS, 1, false, false))
        victim.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, GAS_EFFECT_TICKS, 0, false, false))
        if (remaining <= GAS_DARKNESS_FROM) {
            victim.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, GAS_EFFECT_TICKS, 0, false, false))
        }

        victim.sendActionBar(
            ("<dark_green>☠ Du erstickst im Gas… <gray>noch <yellow>$remaining</yellow> " +
                "Sekunden</gray></dark_green>").mini()
        )
        victim.playSound(
            Sound.sound(BukkitSound.ENTITY_PLAYER_HURT_DROWN, Sound.Source.MASTER, 0.6f, 0.8f)
        )
        // Schwaden direkt vor dem Gesicht - die Dosis soll man sehen, nicht nur lesen
        victim.spawnParticle(
            Particle.DUST, victim.eyeLocation, CHOKE_DENSITY, 0.7, 0.5, 0.7, 0.0, GAS_DUST_DARK,
        )

        // Herzschlag, der mit der Dosis schneller und hoeher wird - der akustische Countdown zum
        // Ersticken. Dazu das Knistern des Geigerzaehlers.
        val urgency = dose.toFloat() / GAS_DOSE_TO_DEATH
        victim.playSound(
            Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_BASEDRUM, Sound.Source.MASTER, 0.8f, 0.5f + urgency * 0.8f)
        )
        repeat(1 + (urgency * GEIGER_MAX_CLICKS).toInt()) {
            victim.playSound(
                Sound.sound(BukkitSound.BLOCK_STONE_BUTTON_CLICK_ON, Sound.Source.MASTER, 0.4f, 2.0f)
            )
        }
    }

    /** Ein Spieler ist erstickt: Zuschauermodus statt Respawn - er bleibt bis zum Ende draussen. */
    private fun chokeOut(victim: Player) {
        chokedOut.add(victim.uniqueId)
        spectators.add(victim.uniqueId)
        gasDose.remove(victim.uniqueId)

        plugin.killEffectManager.playKillEffect(victim.location)
        Bukkit.broadcast(
            "<dark_green>☠ <white>${victim.name}</white> <gray>ist im Giftgas erstickt.</gray></dark_green>".mini()
        )

        victim.activePotionEffects.toList().forEach { victim.removePotionEffect(it.type) }
        plugin.equipmentManager.clearBaseEquipment(victim)
        victim.gameMode = GameMode.SPECTATOR

        victim.showTitle(
            Title.title(
                "<dark_red><b>ERSTICKT</b></dark_red>".mini(),
                "<gray>Du siehst dem Ende der Runde zu.</gray>".mini(),
                Title.Times.times(Ticks.duration(10), Ticks.duration(50), Ticks.duration(20)),
            )
        )
        victim.playSound(Sound.sound(BukkitSound.ENTITY_PLAYER_DEATH, Sound.Source.MASTER, 1.0f, 0.7f))
    }

    // ==================================================================
    // Abschluss
    // ==================================================================

    /**
     * Alle sind erstickt: Zuschauer in die vergaste Arena setzen und **danach** den Sieger
     * ausrufen.
     *
     * Die Ausrufung wartet ausdruecklich auf die Teleports (`CompletableFuture.allOf`) - sonst
     * stuende der Siegertext im Chat, waehrend die Spieler noch auf ihrem Sterbepunkt haengen und
     * von der vergasten Karte nichts sehen.
     */
    private fun finish() {
        if (phase != Phase.RUNNING) return

        phase = Phase.FINISHED
        gasTask?.cancel()
        gasTask = null

        val viewpoint = arenaViewpoint()
        val winner = armedId?.let { Bukkit.getPlayer(it) }

        val teleports = spectators.toList()
            .mapNotNull { Bukkit.getPlayer(it) }
            .filter { it.isOnline }
            .map { spectator ->
                if (viewpoint == null) {
                    CompletableFuture.completedFuture(true)
                } else {
                    spectator.teleportAsync(viewpoint)
                }
            }

        // Der Rueckruf von allOf laeuft auf dem Thread, der den letzten Teleport abgeschlossen hat -
        // die Sieger-Zeremonie ist Server-API und gehoert auf den Main-Thread. Gleiche Absicherung
        // wie beim Map-Wechsel im WorldManager.
        CompletableFuture.allOf(*teleports.toTypedArray()).whenComplete { _, _ ->
            Bukkit.getGlobalRegionScheduler().run(plugin) { announceWinner(winner) }
        }
    }

    private fun announceWinner(winner: Player?) {
        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.AMBIENT_CAVE, Sound.Source.MASTER, 0.6f, 0.6f)
        )

        if (winner != null && winner.isOnline) {
            plugin.matchManager.celebrateWinner(winner)
        } else {
            Bukkit.broadcast(
                ("<red>[OSOK] ☢ Die Nuke hat alles ausgelöscht - der Auslöser ist nicht mehr " +
                    "online.</red>").mini()
            )
        }
    }

    /** Mittelpunkt der Arena, leicht erhoeht und unterhalb der Decke - der Blick ueber das Gas. */
    private fun arenaViewpoint(): Location? {
        val world = plugin.worldManager.osokWorld ?: return null
        val map = plugin.worldManager.activeMapConfig

        val y = minOf(map.maxY + VIEWPOINT_HEIGHT_ABOVE_ARENA, map.maxFlyY)
        return Location(
            world,
            (map.minX + map.maxX) / 2.0,
            y,
            (map.minZ + map.maxZ) / 2.0,
            0f,
            VIEWPOINT_PITCH,
        )
    }

    // ==================================================================
    // Item & Aufraeumen
    // ==================================================================

    /**
     * Der Auslöser. Traegt denselben Spezial-Item-Schluessel wie die Killstreak-Items, damit ihn
     * `EquipmentManager` beim Respawn rettet statt ihn zu ueberschreiben.
     */
    private fun createNukeItem(): ItemStack = plugin.killstreakManager.createSpecialItem(
        Material.WITHER_SKELETON_SKULL,
        "<dark_red><b>[☢] Nuke-Auslöser (Rechtsklick)</b></dark_red>",
        "<gray>Fordert den Angriff an und beendet die Runde!</gray>",
        KEY_NUKE,
    )

    private fun consumeNukeItem(owner: Player) {
        owner.inventory.contents.forEachIndexed { slot, stack ->
            if (isNukeItem(stack)) {
                owner.inventory.setItem(slot, null)
            }
        }
    }

    private fun isNukeItem(stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty) return false
        return stack.persistentDataContainer
            .get(plugin.killstreakManager.specialItemKey, PersistentDataType.STRING) == KEY_NUKE
    }

    /**
     * Nimmt das Finale restlos zurueck: Tasks, Gaswolken, fallendes TNT, Zuschauermodus und der
     * Auslöser. Wird bei Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop gerufen.
     *
     * Zurueck in den Ueberlebensmodus kommt **nur**, wen wir selbst zum Zuschauer gemacht haben -
     * ein Admin, der freiwillig zuschaut, bleibt, wo er ist.
     */
    fun clearAll() {
        approachTask?.cancel()
        approachTask = null
        shockwaveTask?.cancel()
        shockwaveTask = null
        cloudTask?.cancel()
        cloudTask = null
        gasTask?.cancel()
        gasTask = null
        fogTask?.cancel()
        fogTask = null

        removeWarhead()
        // Die eingeebnete Arena kommt zurueck, bevor wieder jemand darauf gesetzt wird
        restoreMap()

        gasClouds.filter { it.isValid }.forEach { it.remove() }
        gasClouds.clear()

        // Sicherheitsnetz: PDC-markierte Reste einsammeln, die einen Absturz ueberlebt haben
        for (world in Bukkit.getWorlds()) {
            world.getEntitiesByClass(AreaEffectCloud::class.java)
                .filter { it.persistentDataContainer.has(KEY_GAS_CLOUD, PersistentDataType.BYTE) }
                .forEach { it.remove() }
            world.getEntitiesByClass(BlockDisplay::class.java)
                .filter { it.persistentDataContainer.has(KEY_NUKE_WARHEAD, PersistentDataType.BYTE) }
                .forEach { it.remove() }
        }

        spectators.toList()
            .mapNotNull { Bukkit.getPlayer(it) }
            .forEach { spectator ->
                spectator.gameMode = GameMode.SURVIVAL
                spectator.activePotionEffects.toList().forEach { spectator.removePotionEffect(it.type) }
                spectator.health = MAX_HEALTH
            }
        spectators.clear()

        Bukkit.getOnlinePlayers().forEach { consumeNukeItem(it) }

        chokedOut.clear()
        gasDose.clear()
        codes.clear()
        entries.clear()
        armedId = null
        phase = Phase.IDLE
    }

    companion object {
        /** Spezial-Item-Kennung des Auslösers - der `SpecialItemListener` schaltet darueber. */
        const val KEY_NUKE = "nuke_trigger"

        val GUI_TITLE: Component = "<dark_red><b>☢ Nuke - Freigabecode</b></dark_red>".mini()

        private const val COLS = 9
        private const val SIZE = COLS * 6
        private const val CODE_LENGTH = 4

        /**
         * Aufbau des Freigabemenues:
         *
         * ```
         *   Reihe 0  · · C C C C · · ·   Code zum Abtippen
         *   Reihe 1  · · E E E E · · ·   Eingabe
         *   Reihe 2  · · · · · · · · ·
         *   Reihe 3  1 2 3 4 5 6 7 8 9   Zifferntasten
         *   Reihe 4  · · ⌫ · 0 · · · ·   Loeschen / Null
         *   Reihe 5  · · ✖ · · · ✔ · ·   Abbrechen / Bestaetigen
         * ```
         */
        private const val SLOT_CODE_START = 2
        private const val SLOT_ENTRY_START = COLS + 2
        private const val SLOT_DIGITS_START = COLS * 3
        private const val SLOT_BACKSPACE = COLS * 4 + 2
        private const val SLOT_DIGIT_ZERO = COLS * 4 + 4
        private const val SLOT_CANCEL = COLS * 5 + 2
        private const val SLOT_CONFIRM = COLS * 5 + 6

        // ---------------- Anflug ----------------
        /** 8 Sekunden Anflug - Zeit genug fuer Sirene, Countdown und Gaensehaut. */
        private const val BOMB_APPROACH_TICKS = 160
        private const val BOMB_APPROACH_PERIOD_TICKS = 2L

        /** Aus dieser Hoehe ueber dem Bodennullpunkt kommt der Sprengkopf. */
        private const val BOMB_APPROACH_HEIGHT = 70.0

        /** Kantenlaenge des Sprengkopfs - ein einzelner Block waere aus der Ferne unsichtbar. */
        private const val BOMB_SCALE = 3.0f

        // ---------------- Detonation ----------------
        private const val FLASH_BURSTS = 6
        private const val FLASH_BLIND_TICKS = 40

        /** Druckwelle: 40 Ringe zu je 2 Ticks - gut vier Sekunden, bis die Arena flach ist. */
        private const val SHOCKWAVE_STEPS = 40
        private const val SHOCKWAVE_PERIOD_TICKS = 2L
        private const val SHOCKWAVE_POINTS_PER_BLOCK = 3.0
        private const val SHOCKWAVE_MAX_POINTS = 180
        private const val SHOCKWAVE_PUSH = 1.1
        private const val SHOCKWAVE_LIFT = 0.55

        /** Suchfenster der Einebnung relativ zu den Arena-Grenzen. */
        private const val SCAN_BELOW = 2.0
        private const val SCAN_ABOVE = 10.0

        /** Womit der Boden nach dem Einschlag belegt wird - verkohlt, nicht bunt. */
        val SCORCHED: List<Material> = listOf(
            Material.BLACKSTONE,
            Material.BASALT,
            Material.COBBLED_DEEPSLATE,
            Material.MAGMA_BLOCK,
            Material.TUFF,
        )

        // ---------------- Pilzwolke ----------------
        private const val CLOUD_STEPS = 200
        private const val CLOUD_PERIOD_TICKS = 2L
        private const val CLOUD_MAX_HEIGHT = 55.0
        private const val CLOUD_RISE_SPEED = 2.5
        private const val CLOUD_STEM_RADIUS = 6.0
        private const val CLOUD_STEM_SPACING = 3.0
        private const val CLOUD_STEM_POINTS = 12

        /** Ab welchem Anteil der Laufzeit sich der Hut oeffnet. */
        private const val CLOUD_CAP_FROM = 0.25
        private const val CLOUD_CAP_RADIUS = 24.0
        private const val CLOUD_CAP_RINGS = 4
        private const val CLOUD_CAP_SPACING = 3.5
        private const val CLOUD_CAP_POINTS = 28

        // ---------------- Gas ----------------
        /** Abstand der Gaswolken im Raster; deutlich enger als ihr Radius, damit sie ueberlappen. */
        private const val GAS_CLOUD_SPACING = 10.0
        private const val GAS_CLOUD_RADIUS = 10.0f
        private const val GAS_CLOUD_HEIGHT = 1.5
        private const val GAS_CLOUD_DURATION_TICKS = 12000

        /**
         * Giftgruen in zwei Toenen: Der helle Ton traegt die Wolke, der dunkle liegt als Schatten
         * darin. Ein einzelner Ton wirkt aus der Entfernung wie eine flache Wand.
         *
         * ⚠ **Die Partikelgroesse ist auf `[0.01, 4.0]` begrenzt** - `Particle.DustOptions` prueft
         * das im Konstruktor ueber `BoundChecker.requireRange`. Ein groesserer Wert fliegt nicht
         * erst beim Zeichnen auf: Diese Konstanten liegen im `companion object`, der Fehler
         * schlaegt also schon beim Laden der Klasse zu und verhindert das Aktivieren des ganzen
         * Plugins.
         */
        val GAS_DUST_BRIGHT: Particle.DustOptions = Particle.DustOptions(Color.fromRGB(124, 252, 40), 3.5f)
        val GAS_DUST_DARK: Particle.DustOptions = Particle.DustOptions(Color.fromRGB(46, 139, 20), 4.0f)
        val GAS_DUST_GROUND: Particle.DustOptions = Particle.DustOptions(Color.fromRGB(90, 200, 30), 4.0f)

        /** Takt der Schwaden: fuenfmal pro Sekunde - darunter flackert die Wolke sichtbar. */
        private const val FOG_PERIOD_TICKS = 4L

        /** Halbe Kantenlaenge des Quaders, in dem der Client die Schwaden verteilt. */
        private const val FOG_RADIUS = 14.0
        private const val FOG_HEIGHT = 5.0
        private const val FOG_GROUND_HEIGHT = 0.8
        private const val FOG_EYE_HEIGHT = 1.4

        /** Partikel je Aufruf - der Client verteilt sie selbst, der Server schickt nur ein Paket. */
        private const val FOG_DENSITY_BRIGHT = 200
        private const val FOG_DENSITY_DARK = 120
        private const val FOG_DENSITY_GROUND = 140
        private const val FOG_DENSITY_SMOKE = 30

        /** Schwaden vor dem Gesicht des Erstickenden. */
        private const val CHOKE_DENSITY = 40

        /** Ein Gas-Takt pro Sekunde. */
        private const val GAS_PERIOD_TICKS = 20L

        /** So viele Takte haelt ein Spieler die Luft an - danach erstickt er. */
        private const val GAS_DOSE_TO_DEATH = 25

        /** Ab so vielen verbleibenden Sekunden wird es schwarz vor Augen. */
        private const val GAS_DARKNESS_FROM = 8

        /** Fallout: Asche rieselt aus dieser Hoehe ueber dem Betrachter herunter. */
        private const val FALLOUT_HEIGHT = 6.0
        private const val FALLOUT_SPREAD = 4.0
        private const val FALLOUT_DENSITY = 60

        /** Hoechstzahl der Geigerzaehler-Klicks pro Takt, kurz vor dem Ersticken. */
        private const val GEIGER_MAX_CLICKS = 4

        /** Laufzeit der Wirkungen: etwas laenger als ein Takt, damit sie nicht flackern. */
        private const val GAS_EFFECT_TICKS = 45

        private const val MAX_HEALTH = 20.0

        /** Tiefster Stand der Lebensanzeige - ein echter Tod waere hier ein Bruch im Ablauf. */
        private const val MIN_CHOKE_HEALTH = 1.0

        // ---------------- Abschluss ----------------
        private const val VIEWPOINT_HEIGHT_ABOVE_ARENA = 6.0
        private const val VIEWPOINT_PITCH = 35f

        private val KEY_ACTION = NamespacedKey("oneshotonekill", "nuke_action")
        private val KEY_DIGIT = NamespacedKey("oneshotonekill", "nuke_digit")
        private val KEY_NUKE_WARHEAD = NamespacedKey("oneshotonekill", "nuke_warhead")
        private val KEY_GAS_CLOUD = NamespacedKey("oneshotonekill", "nuke_gas_cloud")

        private const val ACTION_DIGIT = "digit"
        private const val ACTION_BACKSPACE = "backspace"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_CONFIRM = "confirm"
    }
}
