package de.oneshotonekill.command

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.manager.AntiCampManager
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Locale
import kotlin.math.roundToInt
import org.bukkit.Sound as BukkitSound

/**
 * GUI fuer `/osok camper`: Zeit, Radius und An/Aus der Camper-Markierung.
 *
 * Aufbau wie bei der Item-Gewichtung - der Wert steht in der Mitte, der Pfeil zum Erhoehen
 * darueber, der zum Senken darunter.
 *
 * ```
 *   Reihe 0  · · ▲ · · · ▲ · ·     Zeit +   /   Radius +
 *   Reihe 1  · · ⏱ · ⏻ · ⌖ · ·     Zeit     /  An-Aus  /  Radius
 *   Reihe 2  · · ▼ · ✖ · ▼ · ·     Zeit -   / Schliessen /  Radius -
 * ```
 *
 * Linksklick aendert um [SMALL_STEP], Rechtsklick um [LARGE_STEP].
 */
class CamperGui(private val plugin: OneShotOneKill) : Listener {

    fun openGui(player: Player) {
        val gui = Bukkit.createInventory(null, SIZE, GUI_TITLE)
        render(gui)
        player.openInventory(gui)
        player.playSound(Sound.sound(BukkitSound.BLOCK_ENDER_CHEST_OPEN, Sound.Source.MASTER, 1.0f, 1.4f))
    }

    // ==================================================================
    // Aufbau
    // ==================================================================

    private fun render(gui: Inventory) {
        gui.clear()
        val antiCamp = plugin.antiCampManager

        gui.setItem(SLOT_TIME_UP, arrowButton(ACTION_TIME, increase = true, unit = "Sekunden"))
        gui.setItem(SLOT_TIME_VALUE, timeDisplay(antiCamp))
        gui.setItem(SLOT_TIME_DOWN, arrowButton(ACTION_TIME, increase = false, unit = "Sekunden"))

        gui.setItem(SLOT_RADIUS_UP, arrowButton(ACTION_RADIUS, increase = true, unit = "Blöcke"))
        gui.setItem(SLOT_RADIUS_VALUE, radiusDisplay(antiCamp))
        gui.setItem(SLOT_RADIUS_DOWN, arrowButton(ACTION_RADIUS, increase = false, unit = "Blöcke"))

        gui.setItem(SLOT_TOGGLE, toggleButton(antiCamp))
        gui.setItem(SLOT_CLOSE, closeButton())

        for (slot in 0 until SIZE) {
            if (gui.getItem(slot) == null) {
                gui.setItem(slot, filler())
            }
        }
    }

    private fun timeDisplay(antiCamp: AntiCampManager): ItemStack {
        val seconds = antiCamp.campSeconds

        return ItemStack.of(Material.CLOCK, seconds.coerceIn(1, MAX_STACK_SIZE)).apply {
            setData(DataComponentTypes.CUSTOM_NAME, "<yellow><b>⏱ Zeit: ${seconds}s</b></yellow>".mini())
            setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                    listOf(
                        "<gray>So lange muss ein Spieler auf der Stelle</gray>".mini(),
                        "<gray>bleiben, bis er markiert wird.</gray>".mini(),
                        Component.empty(),
                        ("<dark_gray>Bereich: ${AntiCampManager.MIN_CAMP_SECONDS}-" +
                            "${AntiCampManager.MAX_CAMP_SECONDS}s</dark_gray>").mini(),
                    )
                ),
            )
        }
    }

    private fun radiusDisplay(antiCamp: AntiCampManager): ItemStack {
        val radius = antiCamp.campRadius

        return ItemStack.of(Material.COMPASS, radius.roundToInt().coerceIn(1, MAX_STACK_SIZE)).apply {
            setData(
                DataComponentTypes.CUSTOM_NAME,
                ("<aqua><b>⌖ Radius: ${String.format(Locale.GERMANY, "%.1f", radius)} " +
                    "Blöcke</b></aqua>").mini(),
            )
            setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                    listOf(
                        "<gray>Innerhalb dieses Umkreises gilt ein</gray>".mini(),
                        "<gray>Spieler als \"steht noch da\".</gray>".mini(),
                        Component.empty(),
                        ("<dark_gray>Bereich: " +
                            String.format(Locale.GERMANY, "%.0f", AntiCampManager.MIN_CAMP_RADIUS) + "-" +
                            String.format(Locale.GERMANY, "%.0f", AntiCampManager.MAX_CAMP_RADIUS) +
                            " Blöcke</dark_gray>").mini(),
                    )
                ),
            )
        }
    }

    private fun toggleButton(antiCamp: AntiCampManager): ItemStack {
        val enabled = antiCamp.isEnabled

        return ItemStack.of(if (enabled) Material.LIME_DYE else Material.GRAY_DYE).apply {
            setData(
                DataComponentTypes.CUSTOM_NAME,
                if (enabled) {
                    "<green><b>⏻ Markierung: AN</b></green>".mini()
                } else {
                    "<red><b>⏻ Markierung: AUS</b></red>".mini()
                },
            )
            setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                    listOf(
                        "<gray>Klicken zum Umschalten.</gray>".mini(),
                        Component.empty(),
                        "<dark_gray>Die Streckenmessung für die Match-</dark_gray>".mini(),
                        "<dark_gray>Zusammenfassung läuft unabhängig weiter.</dark_gray>".mini(),
                    )
                ),
            )
            editPersistentDataContainer { pdc -> pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_TOGGLE) }
        }
    }

    private fun arrowButton(action: String, increase: Boolean, unit: String): ItemStack {
        val material = if (increase) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE

        return ItemStack.of(material).apply {
            setData(
                DataComponentTypes.CUSTOM_NAME,
                if (increase) "<green><b>▲ Erhöhen</b></green>".mini() else "<red><b>▼ Senken</b></red>".mini(),
            )
            setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                    listOf(
                        "<gray>Linksklick: <yellow>$SMALL_STEP $unit</yellow></gray>".mini(),
                        "<gray>Rechtsklick: <yellow>$LARGE_STEP $unit</yellow></gray>".mini(),
                    )
                ),
            )
            editPersistentDataContainer { pdc ->
                pdc.set(KEY_ACTION, PersistentDataType.STRING, action)
                pdc.set(KEY_DIRECTION, PersistentDataType.INTEGER, if (increase) 1 else -1)
            }
        }
    }

    private fun closeButton(): ItemStack = ItemStack.of(Material.BARRIER).apply {
        setData(DataComponentTypes.CUSTOM_NAME, "<red><b>✖ Schließen</b></red>".mini())
        editPersistentDataContainer { pdc -> pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_CLOSE) }
    }

    private fun filler(): ItemStack = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE).apply {
        setData(DataComponentTypes.CUSTOM_NAME, Component.empty())
    }

    // ==================================================================
    // Klicks
    // ==================================================================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title() != GUI_TITLE) return

        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (!player.isOp && !plugin.accessManager.isPrivileged(player)) return

        val clicked = event.currentItem ?: return
        if (!clicked.hasItemMeta()) return

        val action = clicked.persistentDataContainer.get(KEY_ACTION, PersistentDataType.STRING) ?: return
        val antiCamp = plugin.antiCampManager

        when (action) {
            ACTION_CLOSE -> {
                player.closeInventory()
                player.playSound(
                    Sound.sound(BukkitSound.BLOCK_ENDER_CHEST_CLOSE, Sound.Source.MASTER, 1.0f, 1.2f)
                )
                return
            }

            ACTION_TOGGLE -> {
                toggleAntiCamp(player, event.inventory, antiCamp)
                return
            }
        }

        val direction = clicked.persistentDataContainer.get(KEY_DIRECTION, PersistentDataType.INTEGER) ?: return
        val step = if (event.isRightClick) LARGE_STEP else SMALL_STEP

        when (action) {
            ACTION_TIME -> {
                val before = antiCamp.campSeconds
                antiCamp.campSeconds = before + direction * step
                feedback(player, event.inventory, changed = before != antiCamp.campSeconds)
            }

            ACTION_RADIUS -> {
                val before = antiCamp.campRadius
                antiCamp.campRadius = before + direction * step
                feedback(player, event.inventory, changed = before != antiCamp.campRadius)
            }
        }
    }

    private fun toggleAntiCamp(player: Player, gui: Inventory, antiCamp: AntiCampManager) {
        val enabled = !antiCamp.isEnabled
        antiCamp.isEnabled = enabled
        render(gui)

        player.playSound(
            Sound.sound(
                if (enabled) BukkitSound.BLOCK_NOTE_BLOCK_BELL else BukkitSound.BLOCK_NOTE_BLOCK_BASS,
                Sound.Source.MASTER,
                1.0f,
                if (enabled) 1.4f else 0.8f,
            )
        )
        Bukkit.broadcast(
            if (enabled) {
                "<yellow>[OSOK] 🏕 Anti-Camping ist jetzt <green><b>AN</b></green>.</yellow>".mini()
            } else {
                ("<yellow>[OSOK] 🏕 Anti-Camping ist jetzt <red><b>AUS</b></red>. " +
                    "<gray>Niemand wird mehr markiert.</gray></yellow>").mini()
            }
        )
    }

    /** Baut die Anzeige neu auf und quittiert, ob der Wert sich tatsaechlich geaendert hat. */
    private fun feedback(player: Player, gui: Inventory, changed: Boolean) {
        if (!changed) {
            // Grenze erreicht - der Wert wurde von campSeconds/campRadius gekappt
            player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 0.7f, 1.0f))
            return
        }
        render(gui)
        player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 0.8f, 1.4f))
    }

    /** Verhindert, dass per Ziehen etwas in das Menue gelegt wird. */
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.title() == GUI_TITLE) {
            event.isCancelled = true
        }
    }

    companion object {
        val GUI_TITLE: Component = "<yellow><b>🏕 Anti-Camping</b></yellow>".mini()

        private const val COLS = 9
        private const val SIZE = COLS * 3

        private const val SLOT_TIME_UP = 2
        private const val SLOT_TIME_VALUE = COLS + 2
        private const val SLOT_TIME_DOWN = COLS * 2 + 2

        private const val SLOT_RADIUS_UP = 6
        private const val SLOT_RADIUS_VALUE = COLS + 6
        private const val SLOT_RADIUS_DOWN = COLS * 2 + 6

        private const val SLOT_TOGGLE = COLS + 4
        private const val SLOT_CLOSE = COLS * 2 + 4

        private const val SMALL_STEP = 1
        private const val LARGE_STEP = 5

        /** Obergrenze der Stapelanzeige - mehr laesst sich als Stapelgroesse nicht darstellen. */
        private const val MAX_STACK_SIZE = 64

        private val KEY_ACTION = NamespacedKey("oneshotonekill", "campergui_action")
        private val KEY_DIRECTION = NamespacedKey("oneshotonekill", "campergui_direction")

        private const val ACTION_TIME = "time"
        private const val ACTION_RADIUS = "radius"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_CLOSE = "close"
    }
}
