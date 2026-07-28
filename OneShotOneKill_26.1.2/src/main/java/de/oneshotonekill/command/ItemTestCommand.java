package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

public class ItemTestCommand implements CommandExecutor, Listener {

    private final OneShotOneKill plugin;
    public static final String GUI_TITLE_STRING = "§e§l🧪 Spezial-Item Test-Menü (11 Items)";
    public static final Component GUI_TITLE = LegacyComponentSerializer.legacySection().deserialize(GUI_TITLE_STRING);

    public ItemTestCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cDieser Befehl ist nur für Spieler verfügbar."));
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cDazu hast du keine Rechte."));
            return true;
        }

        openTestGui(player);
        return true;
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
        if (event.getView().title().equals(GUI_TITLE) || LegacyComponentSerializer.legacySection().serialize(event.getView().title()).equals(GUI_TITLE_STRING)) {
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
