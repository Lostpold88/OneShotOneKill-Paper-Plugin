package de.oneshotonekill.manager;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class EquipmentManager {

    public void giveOneShotEquipment(Player player) {
        // Alle aktiven Trankeffekte & Einfrierung aufheben
        new java.util.ArrayList<>(player.getActivePotionEffects()).forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setFreezeTicks(0);

        // OneShot Bogen (Unendlich Pfeil)
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta bowMeta = bow.getItemMeta();
        if (bowMeta != null) {
            bowMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§e§l⚡ OneShot Bogen"));
            bowMeta.setUnbreakable(true);
            bowMeta.addEnchant(Enchantment.INFINITY, 1, true);
            bow.setItemMeta(bowMeta);
        }

        // OneShot Schwert
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta swordMeta = sword.getItemMeta();
        if (swordMeta != null) {
            swordMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§c§l⚔ OneShot Dolch"));
            swordMeta.setUnbreakable(true);
            sword.setItemMeta(swordMeta);
        }

        // Nur Grundausrüstung in Slot 0, 1 und 8 sicherstellen (Spezial-Items bleiben durch keepInventory nativ erhalten!)
        player.getInventory().setItem(0, sword);
        player.getInventory().setItem(1, bow);
        player.getInventory().setItem(8, new ItemStack(Material.ARROW, 1));

        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setLevel(0);
        player.setExp(0.0f);
    }
}
