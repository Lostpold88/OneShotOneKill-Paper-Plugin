package de.oneshotonekill.command

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.manager.KillstreakManager
import de.oneshotonekill.util.mini
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

/**
 * Spezial-Item Test-GUI fuer `/osok itemtest`.
 *
 * Implementiert nur noch [Listener] fuer die GUI-Klicks; die Befehlsregistrierung laeuft
 * ausschliesslich ueber die Paper Lifecycle Commands API (Brigadier `BasicCommand`).
 */
class ItemTestCommand(private val plugin: OneShotOneKill) : Listener {

    fun openTestGui(player: Player) {
        val gui = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE)

        for (slot in 0 until KillstreakManager.SPECIAL_ITEM_COUNT) {
            gui.setItem(slot, plugin.killstreakManager.createSpecificSpecialItem(slot))
        }

        player.openInventory(gui)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.title() != GUI_TITLE) return
        event.isCancelled = true

        val player = event.whoClicked as? Player ?: return
        val slot = event.rawSlot
        if (slot in 0 until KillstreakManager.SPECIAL_ITEM_COUNT) {
            plugin.killstreakManager.giveSpecificSpecialItem(player, slot, 0)
        }
    }

    companion object {
        val GUI_TITLE: Component =
            ("<yellow><b>🧪 Spezial-Item Test-Menü (${KillstreakManager.SPECIAL_ITEM_COUNT} Items)" +
                "</b></yellow>").mini()

        /** Zwei Reihen - genug fuer alle Spezial-Items. */
        private const val GUI_SIZE = 18
    }
}
