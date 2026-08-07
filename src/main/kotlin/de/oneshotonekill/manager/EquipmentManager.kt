package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemEnchantments
import net.kyori.adventure.sound.Sound
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.Sound as BukkitSound

/**
 * Vergibt und entfernt die Grundausruestung.
 *
 * **Performance:** Die Ausruestung wird als unveraenderliche Vorlage **einmal** beim Klassenladen
 * aufgebaut und danach nur noch geklont. Zuvor entstanden bei jedem Respawn drei ItemStacks samt
 * ItemMeta-Kopien und zwei MiniMessage-Parses - auf einem Server mit Sofort-Respawn ist das ein
 * heisser Pfad.
 *
 * **Spezial-Items:** Die Grundausruestung belegt fest die Slots 0, 1 und 8. Lag dort ein
 * Spezial-Item, wurde es frueher beim naechsten Respawn kommentarlos ueberschrieben - bei
 * Sofort-Respawn also im Sekundentakt. Jetzt wird es vorher auf einen freien Platz gerettet.
 */
class EquipmentManager(private val plugin: OneShotOneKill) {

    fun giveOneShotEquipment(player: Player) {
        // Alle aktiven Trankeffekte & Einfrierung aufheben
        player.activePotionEffects.toList().forEach { player.removePotionEffect(it.type) }
        player.freezeTicks = 0

        // Nur Grundausruestung in Slot 0, 1 und 8 sicherstellen
        // (Spezial-Items bleiben durch keepInventory nativ erhalten!)
        setBaseItem(player, SLOT_SWORD, SWORD_TEMPLATE)
        setBaseItem(player, SLOT_BOW, BOW_TEMPLATE)
        setBaseItem(player, SLOT_ARROW, ARROW_TEMPLATE)

        player.health = 20.0
        player.foodLevel = 20
        player.saturation = 20.0f
        player.level = 0
        player.exp = 0.0f
    }

    /**
     * Setzt ein Grundausruestungs-Teil in seinen festen Slot und rettet ein dort liegendes
     * Spezial-Item auf einen freien Platz.
     *
     * Reihenfolge ist wichtig: Erst wird die Vorlage gesetzt, danach das gerettete Item
     * einsortiert - sonst wuerde `addItem` es sofort wieder in denselben, gerade frei gewordenen
     * Slot legen.
     */
    private fun setBaseItem(player: Player, slot: Int, template: ItemStack) {
        val inventory = player.inventory
        val previous = inventory.getItem(slot)

        inventory.setItem(slot, template.clone())

        if (previous == null || !isSpecialItem(previous)) return

        if (inventory.addItem(previous).isNotEmpty()) {
            // Inventar voll: Das Item ginge sonst stillschweigend verloren
            player.sendMessage("<red>[OSOK] ⚠ Dein Inventar ist voll - ein Spezial-Item ging verloren!</red>".mini())
            player.playSound(Sound.sound(BukkitSound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f))
        }
    }

    /** Erkennt Spezial-Items ausschliesslich am PersistentDataContainer, nie am Anzeigenamen. */
    private fun isSpecialItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        // Paper: PersistentDataContainerView direkt am ItemStack - liest ohne ItemMeta-Kopie
        return stack.persistentDataContainer
            .has(plugin.killstreakManager.specialItemKey, PersistentDataType.STRING)
    }

    fun clearBaseEquipment(player: Player) {
        player.inventory.remove(Material.IRON_SWORD)
        player.inventory.remove(Material.BOW)
        player.inventory.remove(Material.ARROW)
        player.health = 20.0
        player.foodLevel = 20
        player.saturation = 20.0f
    }

    private companion object {
        const val SLOT_SWORD = 0
        const val SLOT_BOW = 1
        const val SLOT_ARROW = 8

        val SWORD_TEMPLATE: ItemStack = createSword()
        val BOW_TEMPLATE: ItemStack = createBow()
        val ARROW_TEMPLATE: ItemStack = ItemStack.of(Material.ARROW, 1)

        private fun createSword(): ItemStack = ItemStack.of(Material.IRON_SWORD).apply {
            // Paper DataComponents: schreibt direkt in die Vanilla-Komponenten, ohne Meta-Kopie
            setData(DataComponentTypes.CUSTOM_NAME, "<red><b>⚔ OneShot Dolch</b></red>".mini())
            // UNBREAKABLE ist NonValued - es wird gesetzt, nicht mit einem Wert belegt
            setData(DataComponentTypes.UNBREAKABLE)
        }

        private fun createBow(): ItemStack = ItemStack.of(Material.BOW).apply {
            setData(DataComponentTypes.CUSTOM_NAME, "<yellow><b>⚡ OneShot Bogen</b></yellow>".mini())
            setData(DataComponentTypes.UNBREAKABLE)
            setData(
                DataComponentTypes.ENCHANTMENTS,
                ItemEnchantments.itemEnchantments(mapOf(Enchantment.INFINITY to 1)),
            )
        }
    }
}
