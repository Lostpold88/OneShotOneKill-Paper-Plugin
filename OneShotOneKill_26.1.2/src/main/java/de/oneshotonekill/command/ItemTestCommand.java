package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
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
    public static final String GUI_TITLE = "§e§l🧪 Spezial-Item Test-Menü (11 Items)";

    public ItemTestCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cDieser Befehl ist nur für Spieler verfügbar.");
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage("§cDazu hast du keine Rechte.");
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
        return switch (slot) {
            case 0 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.ENDER_EYE, "§e§l[✦] Radar-Puls (Rechtsklick)", "§7Klick zum Testen");
            case 1 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.TNT, "§c§l[★] Explosiv-Schuss (Rechtsklick)", "§7Klick zum Testen");
            case 2 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.NETHER_STAR, "§b§l[🛡] Reflektor-Schild (Rechtsklick)", "§7Klick zum Testen");
            case 3 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.SNOWBALL, "§f§l[☁] Rauchbombe (Werfen)", "§7Klick zum Testen");
            case 4 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.HEAVY_WEIGHTED_PRESSURE_PLATE, "§8§l[⚙] Bärenfalle (Plazieren)", "§7Klick zum Testen");
            case 5 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.BLAZE_ROD, "§6§l[🔥] Krass Minigun (Rechtsklick)", "§7Klick zum Testen");
            case 6 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.ENDER_PEARL, "§d§l[🌀] Teleport-Granate (Werfen)", "§7Klick zum Testen");
            case 7 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.PHANTOM_MEMBRANE, "§7§l[✦] Unsichtbarkeits-Mantel (Rechtsklick)", "§7Klick zum Testen");
            case 8 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.HEART_OF_THE_SEA, "§9§l[⚓] Pfeil-Magnetfeld (Rechtsklick)", "§7Klick zum Testen");
            case 9 -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.LIGHTNING_ROD, "§e§l[⚡] Kettenblitz-Schuss (Rechtsklick)", "§7Klick zum Testen");
            default -> plugin.getKillstreakManager().createSpecialItem(org.bukkit.Material.FIREWORK_ROCKET, "§c§l[★] Raketen-Sprung (Rechtsklick)", "§7Klick zum Testen");
        };
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(GUI_TITLE)) {
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
