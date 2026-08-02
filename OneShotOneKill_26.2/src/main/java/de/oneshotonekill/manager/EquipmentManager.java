package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Map;

/**
 * Vergibt und entfernt die Grundausruestung.
 * <p>
 * <b>Performance:</b> Die Ausruestung wird als unveraenderliche Vorlage <b>einmal</b> beim
 * Klassenladen aufgebaut und danach nur noch geklont. Zuvor entstanden bei jedem Respawn
 * drei ItemStacks samt ItemMeta-Kopien und zwei MiniMessage-Parses - auf einem Server mit
 * Sofort-Respawn ist das ein heisser Pfad.
 * <p>
 * <b>Spezial-Items:</b> Die Grundausruestung belegt fest die Slots 0, 1 und 8. Lag dort ein
 * Spezial-Item, wurde es frueher beim naechsten Respawn kommentarlos ueberschrieben - bei
 * Sofort-Respawn also im Sekundentakt. Jetzt wird es vorher auf einen freien Platz gerettet.
 */
public class EquipmentManager {

    private static final int SLOT_SWORD = 0;
    private static final int SLOT_BOW = 1;
    private static final int SLOT_ARROW = 8;

    private static final ItemStack SWORD_TEMPLATE = createSword();
    private static final ItemStack BOW_TEMPLATE = createBow();
    private static final ItemStack ARROW_TEMPLATE = ItemStack.of(Material.ARROW, 1);

    private final OneShotOneKill plugin;

    public EquipmentManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    private static ItemStack createSword() {
        ItemStack sword = ItemStack.of(Material.IRON_SWORD);
        // Paper DataComponents: schreibt direkt in die Vanilla-Komponenten, ohne Meta-Kopie
        sword.setData(DataComponentTypes.CUSTOM_NAME,
                MiniMessage.miniMessage().deserialize("<red><b>⚔ OneShot Dolch</b></red>"));
        // UNBREAKABLE ist NonValued - es wird gesetzt, nicht mit einem Wert belegt
        sword.setData(DataComponentTypes.UNBREAKABLE);
        return sword;
    }

    private static ItemStack createBow() {
        ItemStack bow = ItemStack.of(Material.BOW);
        bow.setData(DataComponentTypes.CUSTOM_NAME,
                MiniMessage.miniMessage().deserialize("<yellow><b>⚡ OneShot Bogen</b></yellow>"));
        bow.setData(DataComponentTypes.UNBREAKABLE);
        bow.setData(DataComponentTypes.ENCHANTMENTS,
                ItemEnchantments.itemEnchantments(Map.of(Enchantment.INFINITY, 1)));
        return bow;
    }

    public void giveOneShotEquipment(Player player) {
        // Alle aktiven Trankeffekte & Einfrierung aufheben
        new ArrayList<>(player.getActivePotionEffects()).forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setFreezeTicks(0);

        // Nur Grundausrüstung in Slot 0, 1 und 8 sicherstellen
        // (Spezial-Items bleiben durch keepInventory nativ erhalten!)
        setBaseItem(player, SLOT_SWORD, SWORD_TEMPLATE);
        setBaseItem(player, SLOT_BOW, BOW_TEMPLATE);
        setBaseItem(player, SLOT_ARROW, ARROW_TEMPLATE);

        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setLevel(0);
        player.setExp(0.0f);
    }

    /**
     * Setzt ein Grundausruestungs-Teil in seinen festen Slot und rettet ein dort liegendes
     * Spezial-Item auf einen freien Platz.
     * <p>
     * Reihenfolge ist wichtig: Erst wird die Vorlage gesetzt, danach das gerettete Item
     * einsortiert - sonst wuerde {@code addItem} es sofort wieder in denselben, gerade frei
     * gewordenen Slot legen.
     */
    private void setBaseItem(Player player, int slot, ItemStack template) {
        PlayerInventory inventory = player.getInventory();
        ItemStack previous = inventory.getItem(slot);

        inventory.setItem(slot, template.clone());

        if (!isSpecialItem(previous)) {
            return;
        }

        if (!inventory.addItem(previous).isEmpty()) {
            // Inventar voll: Das Item ginge sonst stillschweigend verloren
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>[OSOK] ⚠ Dein Inventar ist voll - ein Spezial-Item ging verloren!</red>"));
            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
        }
    }

    /** Erkennt Spezial-Items ausschliesslich am PersistentDataContainer, nie am Anzeigenamen. */
    private boolean isSpecialItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        // Paper: PersistentDataContainerView direkt am ItemStack - liest ohne ItemMeta-Kopie
        return stack.getPersistentDataContainer()
                .has(plugin.getKillstreakManager().getSpecialItemKey(), PersistentDataType.STRING);
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
