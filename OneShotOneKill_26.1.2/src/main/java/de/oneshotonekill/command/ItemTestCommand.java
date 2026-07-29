package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

/**
 * Spezial-Item Test-GUI fuer <code>/osok itemtest</code>.
 * <p>
 * Implementiert nur noch {@link Listener} fuer die GUI-Klicks; die Befehlsregistrierung laeuft
 * ausschliesslich ueber die Paper Lifecycle Commands API (Brigadier {@code BasicCommand}).
 */
public class ItemTestCommand implements Listener {

    private final OneShotOneKill plugin;
    public static final Component GUI_TITLE = MiniMessage.miniMessage().deserialize("<yellow><b>🧪 Spezial-Item Test-Menü (11 Items)</b></yellow>");

    public ItemTestCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public void openTestGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 18, GUI_TITLE);

        for (int i = 0; i < 11; i++) {
            gui.setItem(i, getTestItemForSlot(i));
        }

        player.openInventory(gui);
    }

    private org.bukkit.inventory.ItemStack getTestItemForSlot(int slot) {
        return plugin.getKillstreakManager().createSpecificSpecialItem(slot);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().title().equals(GUI_TITLE)) {
            event.setCancelled(true);

            if (event.getWhoClicked() instanceof Player player) {
                int slot = event.getRawSlot();
                if (slot >= 0 && slot < 11) {
                    plugin.getKillstreakManager().giveSpecificSpecialItem(player, slot, 0);
                }
            }
        }
    }
}
