package de.oneshotonekill.manager;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;

/**
 * Vergibt und entfernt die Grundausruestung.
 * <p>
 * <b>Performance:</b> Die Ausruestung wird als unveraenderliche Vorlage <b>einmal</b> beim
 * Klassenladen aufgebaut und danach nur noch geklont. Zuvor entstanden bei jedem Respawn
 * drei ItemStacks samt ItemMeta-Kopien und zwei MiniMessage-Parses - auf einem Server mit
 * Sofort-Respawn ist das ein heisser Pfad.
 */
public class EquipmentManager {

    private static final ItemStack SWORD_TEMPLATE = createSword();
    private static final ItemStack BOW_TEMPLATE = createBow();
    private static final ItemStack ARROW_TEMPLATE = ItemStack.of(Material.ARROW, 1);

    private static ItemStack createSword() {
        ItemStack sword = ItemStack.of(Material.IRON_SWORD);
        // Paper editMeta: kein getItemMeta/setItemMeta Umweg, also eine Kopie weniger
        sword.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize("<red><b>⚔ OneShot Dolch</b></red>"));
            meta.setUnbreakable(true);
        });
        return sword;
    }

    private static ItemStack createBow() {
        ItemStack bow = ItemStack.of(Material.BOW);
        bow.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize("<yellow><b>⚡ OneShot Bogen</b></yellow>"));
            meta.setUnbreakable(true);
            meta.addEnchant(Enchantment.INFINITY, 1, true);
        });
        return bow;
    }

    public void giveOneShotEquipment(Player player) {
        // Alle aktiven Trankeffekte & Einfrierung aufheben
        new ArrayList<>(player.getActivePotionEffects()).forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setFreezeTicks(0);

        // Nur Grundausrüstung in Slot 0, 1 und 8 sicherstellen
        // (Spezial-Items bleiben durch keepInventory nativ erhalten!)
        player.getInventory().setItem(0, SWORD_TEMPLATE.clone());
        player.getInventory().setItem(1, BOW_TEMPLATE.clone());
        player.getInventory().setItem(8, ARROW_TEMPLATE.clone());

        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setLevel(0);
        player.setExp(0.0f);
    }

    public void clearBaseEquipment(Player player) {
        player.getInventory().remove(Material.IRON_SWORD);
        player.getInventory().remove(Material.BOW);
        player.getInventory().remove(Material.ARROW);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
    }
}
