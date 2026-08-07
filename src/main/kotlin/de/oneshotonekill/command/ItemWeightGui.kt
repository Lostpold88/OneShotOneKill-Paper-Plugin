package de.oneshotonekill.command

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.manager.KillstreakManager
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
import org.bukkit.Sound as BukkitSound

/**
 * GUI fuer `/osok itemgewichtung`.
 *
 * Aufbau: Jede Item-Reihe ist von einer Pfeilreihe eingerahmt - **darueber** der Pfeil zum
 * Erhoehen, **darunter** der zum Senken. Die Spezial-Items verteilen sich damit auf zwei Bloecke zu
 * je drei Reihen.
 *
 * ```
 *   Reihe 0  ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲     Gewicht erhoehen  (Items 1-9)
 *   Reihe 1  ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪     Items 1-9
 *   Reihe 2  ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼     Gewicht senken
 *   Reihe 3  ▲ ▲ ▲ ▲ ▲ ▲ ▲ · ·     Gewicht erhoehen  (Items 10-16)
 *   Reihe 4  ▪ ▪ ▪ ▪ ▪ ▪ ▪ R X     Items 10-16, Reset, Schliessen
 *   Reihe 5  ▼ ▼ ▼ ▼ ▼ ▼ ▼ · ·     Gewicht senken
 * ```
 *
 * Linksklick aendert um [SMALL_STEP], Rechtsklick um [LARGE_STEP].
 *
 * Die Anzeige wird nach jedem Klick komplett neu aufgebaut, weil sich mit einem einzelnen Gewicht
 * auch die Prozentchancen **aller anderen** Items verschieben.
 */
class ItemWeightGui(private val plugin: OneShotOneKill) : Listener {

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

        KillstreakManager.SPECIAL_ITEM_IDS.forEachIndexed { index, typeId ->
            val itemSlot = itemSlot(index)
            if (itemSlot < 0) return@forEachIndexed

            gui.setItem(itemSlot - COLS, arrowButton(typeId, increase = true))
            gui.setItem(itemSlot, weightDisplay(index, typeId))
            gui.setItem(itemSlot + COLS, arrowButton(typeId, increase = false))
        }

        gui.setItem(SLOT_RESET, resetButton())
        gui.setItem(SLOT_CLOSE, closeButton())

        // Ungenutzte Randslots fuellen, damit nichts hineingelegt werden kann
        for (slot in 0 until SIZE) {
            if (gui.getItem(slot) == null) {
                gui.setItem(slot, filler())
            }
        }
    }

    /** Slot der Item-Anzeige, oder -1 wenn der Index nicht ins Raster passt. */
    private fun itemSlot(index: Int): Int {
        if (index < BLOCK_ONE_CAPACITY) return BLOCK_ONE_ITEM_START + index

        val local = index - BLOCK_ONE_CAPACITY
        return if (local < BLOCK_TWO_CAPACITY) BLOCK_TWO_ITEM_START + local else -1
    }

    /**
     * Anzeige eines Items. Die Stapelgroesse spiegelt das Gewicht wider (auf 1-64 begrenzt), der
     * exakte Wert steht im Namen - ein Gewicht von 0 oder ueber 64 waere als Stapelgroesse gar
     * nicht darstellbar.
     */
    private fun weightDisplay(index: Int, typeId: String): ItemStack {
        val killstreak = plugin.killstreakManager
        val weight = killstreak.getItemWeight(typeId)

        // Bewusst ein frisches Item statt einer Kopie des Spezial-Items: Die Anzeige soll keinen
        // Spezial-Item-PDC tragen und damit niemals als echtes Item durchgehen.
        return ItemStack.of(
            killstreak.createSpecificSpecialItem(index).type,
            weight.coerceIn(1, MAX_STACK_SIZE),
        ).apply {
            setData(DataComponentTypes.CUSTOM_NAME, killstreak.getItemDisplayName(typeId))
            setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                    listOf(
                        ("<gray>Gewicht: <yellow><b>$weight</b></yellow>" +
                            if (weight == 0) " <red>(spawnt nie)</red>" else "").mini(),
                        ("<gray>Spawnchance: <aqua>" +
                            String.format(Locale.GERMANY, "%.1f", killstreak.getSpawnChance(typeId)) +
                            " %</aqua>").mini(),
                        Component.empty(),
                        "<dark_gray>ID: $typeId</dark_gray>".mini(),
                    )
                ),
            )
        }
    }

    private fun arrowButton(typeId: String, increase: Boolean): ItemStack {
        val material = if (increase) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE

        return ItemStack.of(material).apply {
            setData(
                DataComponentTypes.CUSTOM_NAME,
                if (increase) {
                    "<green><b>▲ Gewicht erhöhen</b></green>".mini()
                } else {
                    "<red><b>▼ Gewicht senken</b></red>".mini()
                },
            )
            setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                    listOf(
                        "<gray>Linksklick: <yellow>$SMALL_STEP</yellow></gray>".mini(),
                        "<gray>Rechtsklick: <yellow>$LARGE_STEP</yellow></gray>".mini(),
                    )
                ),
            )
            editPersistentDataContainer { pdc ->
                pdc.set(KEY_ITEM_TARGET, PersistentDataType.STRING, typeId)
                pdc.set(KEY_DIRECTION, PersistentDataType.INTEGER, if (increase) 1 else -1)
            }
        }
    }

    private fun resetButton(): ItemStack = ItemStack.of(Material.BARREL).apply {
        setData(DataComponentTypes.CUSTOM_NAME, "<gold><b>🔄 Zurücksetzen</b></gold>".mini())
        setData(
            DataComponentTypes.LORE,
            ItemLore.lore(
                listOf(
                    ("<gray>Setzt alle Gewichte auf " +
                        "<yellow>${KillstreakManager.DEFAULT_ITEM_WEIGHT}</yellow>.</gray>").mini()
                )
            ),
        )
        editPersistentDataContainer { pdc -> pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_RESET) }
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

        // Gilt auch fuer das eigene Inventar: Aus diesem Menue wird nichts herausgetragen
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        if (!player.isOp && !plugin.accessManager.isPrivileged(player)) return

        val clicked = event.currentItem ?: return
        if (!clicked.hasItemMeta()) return

        when (clicked.persistentDataContainer.get(KEY_ACTION, PersistentDataType.STRING)) {
            ACTION_CLOSE -> {
                player.closeInventory()
                player.playSound(
                    Sound.sound(BukkitSound.BLOCK_ENDER_CHEST_CLOSE, Sound.Source.MASTER, 1.0f, 1.2f)
                )
                return
            }

            ACTION_RESET -> {
                plugin.killstreakManager.resetItemWeights()
                render(event.inventory)
                player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_BELL, Sound.Source.MASTER, 1.0f, 1.2f))
                player.sendMessage(
                    ("<yellow>[OSOK] 🎲 Alle Item-Gewichte auf " +
                        "<green>${KillstreakManager.DEFAULT_ITEM_WEIGHT}</green> " +
                        "zurückgesetzt.</yellow>").mini()
                )
                return
            }
        }

        val pdc = clicked.persistentDataContainer
        val typeId = pdc.get(KEY_ITEM_TARGET, PersistentDataType.STRING) ?: return
        val direction = pdc.get(KEY_DIRECTION, PersistentDataType.INTEGER) ?: return

        val killstreak = plugin.killstreakManager
        val step = if (event.isRightClick) LARGE_STEP else SMALL_STEP
        val current = killstreak.getItemWeight(typeId)
        val updated = (current + direction * step).coerceIn(0, KillstreakManager.MAX_ITEM_WEIGHT)

        if (updated == current) {
            player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 0.7f, 1.0f))
            return
        }

        killstreak.setItemWeight(typeId, updated)
        render(event.inventory)

        // Tonhoehe folgt dem Gewicht - hoeher wird heller
        val pitch = 0.8f + minOf(updated, 40) * 0.02f
        player.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 0.8f, pitch))

        if (killstreak.totalItemWeight <= 0) {
            player.sendMessage(
                ("<red>[OSOK] ⚠ Alle Gewichte stehen auf 0 - es können gar keine Spezial-Items " +
                    "mehr erscheinen!</red>").mini()
            )
        }
    }

    /** Verhindert, dass per Ziehen etwas in das Menue gelegt wird. */
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.title() == GUI_TITLE) {
            event.isCancelled = true
        }
    }

    companion object {
        val GUI_TITLE: Component = "<yellow><b>🎲 Item-Gewichtung</b></yellow>".mini()

        private const val COLS = 9
        private const val SIZE = COLS * 6

        /** Slots der Item-Reihen; die Pfeile liegen jeweils eine Reihe darueber und darunter. */
        private const val BLOCK_ONE_ITEM_START = COLS // Reihe 1
        private const val BLOCK_TWO_ITEM_START = COLS * 4 // Reihe 4
        private const val BLOCK_ONE_CAPACITY = 9

        /** Reihe 4 laesst Platz fuer Reset und Reihe 5 fuer Schliessen. */
        private const val BLOCK_TWO_CAPACITY = 8

        private const val SLOT_RESET = COLS * 4 + 8
        private const val SLOT_CLOSE = COLS * 5 + 8

        private const val SMALL_STEP = 1
        private const val LARGE_STEP = 5

        /** Obergrenze der Stapelanzeige - mehr laesst sich als Stapelgroesse nicht darstellen. */
        private const val MAX_STACK_SIZE = 64

        private val KEY_ITEM_TARGET = NamespacedKey("oneshotonekill", "weightgui_target")
        private val KEY_DIRECTION = NamespacedKey("oneshotonekill", "weightgui_direction")
        private val KEY_ACTION = NamespacedKey("oneshotonekill", "weightgui_action")

        private const val ACTION_RESET = "reset"
        private const val ACTION_CLOSE = "close"
    }
}
