package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillEffectManager;
import de.oneshotonekill.manager.KillEffectManager.KillEffect;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KillEffectCommand implements CommandExecutor, Listener, TabCompleter {

    private final OneShotOneKill plugin;
    public static final String GUI_TITLE = "§e§l💥 Wähle deinen Kill-Effekt";

    public KillEffectCommand(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cDieser Befehl ist nur für Spieler verfügbar.");
            return true;
        }

        openEffectGui(player);
        return true;
    }

    public void openEffectGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, GUI_TITLE);

        KillEffect selected = plugin.getKillEffectManager().getSelectedEffect(player.getUniqueId());

        gui.setItem(0, createGuiItem(Material.LIGHTNING_ROD, KillEffect.LIGHTNING.getDisplayName(), selected == KillEffect.LIGHTNING));
        gui.setItem(1, createGuiItem(Material.FIREWORK_ROCKET, KillEffect.FIREWORK.getDisplayName(), selected == KillEffect.FIREWORK));
        gui.setItem(2, createGuiItem(Material.REDSTONE_BLOCK, KillEffect.BLOOD.getDisplayName(), selected == KillEffect.BLOOD));
        gui.setItem(3, createGuiItem(Material.ENDER_PEARL, KillEffect.ENDER.getDisplayName(), selected == KillEffect.ENDER));
        gui.setItem(4, createGuiItem(Material.TOTEM_OF_UNDYING, KillEffect.TOTEM.getDisplayName(), selected == KillEffect.TOTEM));
        gui.setItem(8, createGuiItem(Material.BARRIER, KillEffect.NONE.getDisplayName(), selected == KillEffect.NONE));

        player.openInventory(gui);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.5f);
    }

    private ItemStack createGuiItem(Material mat, String name, boolean isSelected) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (isSelected) {
                meta.setLore(Arrays.asList("§a§l✔ AKTIV AUSGEWÄHLT", "§7Klick zum Auswählen"));
            } else {
                meta.setLore(Collections.singletonList("§7Klick zum Auswählen"));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(GUI_TITLE)) {
            event.setCancelled(true);

            if (event.getWhoClicked() instanceof Player player) {
                int slot = event.getRawSlot();
                KillEffect chosen = null;

                switch (slot) {
                    case 0 -> chosen = KillEffect.LIGHTNING;
                    case 1 -> chosen = KillEffect.FIREWORK;
                    case 2 -> chosen = KillEffect.BLOOD;
                    case 3 -> chosen = KillEffect.ENDER;
                    case 4 -> chosen = KillEffect.TOTEM;
                    case 8 -> chosen = KillEffect.NONE;
                }

                if (chosen != null) {
                    plugin.getKillEffectManager().setSelectedEffect(player.getUniqueId(), chosen);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 1.0f, 1.5f);
                    player.sendMessage("§a[OSOK] 💥 Kill-Effekt geändert zu: " + chosen.getDisplayName());
                    openEffectGui(player);
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
