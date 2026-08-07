package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
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
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
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

    private var bombardmentTask: ScheduledTask? = null
    private var gasTask: ScheduledTask? = null

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
    // Abschuss & Bombardement
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
                "<red>Einschlag in Kürze…</red>".mini(),
                Title.Times.times(Ticks.duration(10), Ticks.duration(50), Ticks.duration(20)),
            )
        )
        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.EVENT_RAID_HORN, Sound.Source.MASTER, 1.0f, 0.7f)
        )

        startBombardment()
    }

    /**
     * TNT-Regen ueber der gesamten Arena.
     *
     * Reine Kulisse: Die Bloecke schuetzt [onEntityExplode], die Spieler der `CombatListener`. Die
     * Zuendschnur ist je Bombe leicht verschieden, damit die Einschlaege prasseln statt im
     * Gleichtakt zu knallen.
     */
    private fun startBombardment() {
        val world = plugin.worldManager.osokWorld
        if (world == null) {
            startGas()
            return
        }

        val map = plugin.worldManager.activeMapConfig
        val dropY = minOf(map.maxY + BOMB_HEIGHT_ABOVE_ARENA, map.maxFlyY)
        var wave = 0

        bombardmentTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (phase != Phase.RUNNING) {
                    task.cancel()
                    bombardmentTask = null
                    return@runAtFixedRate
                }
                if (wave >= BOMB_WAVES) {
                    task.cancel()
                    bombardmentTask = null
                    startGas()
                    return@runAtFixedRate
                }

                repeat(BOMBS_PER_WAVE) {
                    val x = map.minX + Random.nextDouble() * (map.maxX - map.minX)
                    val z = map.minZ + Random.nextDouble() * (map.maxZ - map.minZ)
                    dropBomb(world, Location(world, x, dropY, z))
                }
                wave++
            },
            10L,
            BOMB_WAVE_PERIOD_TICKS,
        )
    }

    private fun dropBomb(world: World, loc: Location) {
        world.spawn(loc, TNTPrimed::class.java) { tnt ->
            tnt.fuseTicks = BOMB_FUSE_TICKS + Random.nextInt(BOMB_FUSE_SPREAD_TICKS)
            tnt.setIsIncendiary(false)
            tnt.velocity = Vector(0.0, BOMB_DROP_VELOCITY, 0.0)
            tnt.isPersistent = false
            tnt.persistentDataContainer.set(KEY_NUKE_TNT, PersistentDataType.BYTE, 1.toByte())
        }
    }

    /**
     * Die Nuke darf die Karte **nicht** beschaedigen. Anders als bei Air-Strike und C4 laeuft hier
     * die Vanilla-Explosion des TNT - deren Blockliste wird geleert, statt eine eigene Sprengung zu
     * bauen: Es geht nur um Optik und Krach, nicht um Schaden.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (!event.entity.persistentDataContainer.has(KEY_NUKE_TNT, PersistentDataType.BYTE)) return

        event.blockList().clear()
        event.yield = 0.0f
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
        bombardmentTask?.cancel()
        bombardmentTask = null
        gasTask?.cancel()
        gasTask = null
        fogTask?.cancel()
        fogTask = null

        gasClouds.filter { it.isValid }.forEach { it.remove() }
        gasClouds.clear()

        // Sicherheitsnetz: PDC-markierte Reste einsammeln, die einen Absturz ueberlebt haben
        for (world in Bukkit.getWorlds()) {
            world.getEntitiesByClass(AreaEffectCloud::class.java)
                .filter { it.persistentDataContainer.has(KEY_GAS_CLOUD, PersistentDataType.BYTE) }
                .forEach { it.remove() }
            world.getEntitiesByClass(TNTPrimed::class.java)
                .filter { it.persistentDataContainer.has(KEY_NUKE_TNT, PersistentDataType.BYTE) }
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

        // ---------------- Bombardement ----------------
        /** Abwurfhoehe ueber der Arena-Oberkante, sofern keine Decke im Weg ist. */
        private const val BOMB_HEIGHT_ABOVE_ARENA = 14.0
        private const val BOMB_WAVES = 12
        private const val BOMBS_PER_WAVE = 6
        private const val BOMB_WAVE_PERIOD_TICKS = 10L
        private const val BOMB_FUSE_TICKS = 20
        private const val BOMB_FUSE_SPREAD_TICKS = 20
        private const val BOMB_DROP_VELOCITY = -0.8

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
        private const val GAS_DOSE_TO_DEATH = 12

        /** Ab so vielen verbleibenden Sekunden wird es schwarz vor Augen. */
        private const val GAS_DARKNESS_FROM = 5

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
        private val KEY_NUKE_TNT = NamespacedKey("oneshotonekill", "nuke_tnt")
        private val KEY_GAS_CLOUD = NamespacedKey("oneshotonekill", "nuke_gas_cloud")

        private const val ACTION_DIGIT = "digit"
        private const val ACTION_BACKSPACE = "backspace"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_CONFIRM = "confirm"
    }
}
