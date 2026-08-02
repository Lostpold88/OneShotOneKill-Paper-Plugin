package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.KillstreakManager;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/**
 * GUI fuer <code>/osok itemgewichtung</code>.
 * <p>
 * Aufbau: Jede Item-Reihe ist von einer Pfeilreihe eingerahmt - <b>darueber</b> der Pfeil zum
 * Erhoehen, <b>darunter</b> der zum Senken. Die 16 Spezial-Items verteilen sich damit auf zwei
 * Bloecke zu je drei Reihen.
 * <pre>
 *   Reihe 0  ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲     Gewicht erhoehen  (Items 1-9)
 *   Reihe 1  ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪     Items 1-9
 *   Reihe 2  ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼     Gewicht senken
 *   Reihe 3  ▲ ▲ ▲ ▲ ▲ ▲ ▲ · ·     Gewicht erhoehen  (Items 10-16)
 *   Reihe 4  ▪ ▪ ▪ ▪ ▪ ▪ ▪ R X     Items 10-16, Reset, Schliessen
 *   Reihe 5  ▼ ▼ ▼ ▼ ▼ ▼ ▼ · ·     Gewicht senken
 * </pre>
 * Linksklick aendert um {@link #SMALL_STEP}, Rechtsklick um {@link #LARGE_STEP}.
 * <p>
 * Die Anzeige wird nach jedem Klick komplett neu aufgebaut, weil sich mit einem einzelnen
 * Gewicht auch die Prozentchancen <b>aller anderen</b> Items verschieben.
 */
public class ItemWeightGui implements Listener {

    public static final Component GUI_TITLE =
            MiniMessage.miniMessage().deserialize("<yellow><b>🎲 Item-Gewichtung</b></yellow>");

    private static final int COLS = 9;
    private static final int SIZE = COLS * 6;

    /** Slots der Item-Reihen; die Pfeile liegen jeweils eine Reihe darueber und darunter. */
    private static final int BLOCK_ONE_ITEM_START = COLS;          // Reihe 1
    private static final int BLOCK_TWO_ITEM_START = COLS * 4;      // Reihe 4
    private static final int BLOCK_ONE_CAPACITY = 9;
    /** Reihe 4 laesst zwei Spalten fuer Reset und Schliessen frei. */
    private static final int BLOCK_TWO_CAPACITY = 7;

    private static final int SLOT_RESET = COLS * 4 + 7;
    private static final int SLOT_CLOSE = COLS * 4 + 8;

    private static final int SMALL_STEP = 1;
    private static final int LARGE_STEP = 5;

    private static final NamespacedKey KEY_ITEM_TARGET = new NamespacedKey("oneshotonekill", "weightgui_target");
    private static final NamespacedKey KEY_DIRECTION = new NamespacedKey("oneshotonekill", "weightgui_direction");
    private static final NamespacedKey KEY_ACTION = new NamespacedKey("oneshotonekill", "weightgui_action");

    private static final String ACTION_RESET = "reset";
    private static final String ACTION_CLOSE = "close";

    private final OneShotOneKill plugin;

    public ItemWeightGui(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public void openGui(Player player) {
        Inventory gui = Bukkit.createInventory(null, SIZE, GUI_TITLE);
        render(gui);
        player.openInventory(gui);
        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_ENDER_CHEST_OPEN, Sound.Source.MASTER, 1.0f, 1.4f));
    }

    // ==================================================================
    // Aufbau
    // ==================================================================

    private void render(Inventory gui) {
        gui.clear();

        List<String> ids = KillstreakManager.SPECIAL_ITEM_IDS;
        for (int index = 0; index < ids.size(); index++) {
            int itemSlot = itemSlot(index);
            if (itemSlot < 0) continue;

            String typeId = ids.get(index);
            gui.setItem(itemSlot - COLS, arrowButton(typeId, true));
            gui.setItem(itemSlot, weightDisplay(index, typeId));
            gui.setItem(itemSlot + COLS, arrowButton(typeId, false));
        }

        gui.setItem(SLOT_RESET, resetButton());
        gui.setItem(SLOT_CLOSE, closeButton());

        // Ungenutzte Randslots fuellen, damit nichts hineingelegt werden kann
        for (int slot = 0; slot < SIZE; slot++) {
            if (gui.getItem(slot) == null) {
                gui.setItem(slot, filler());
            }
        }
    }

    /** Slot der Item-Anzeige, oder -1 wenn der Index nicht ins Raster passt. */
    private int itemSlot(int index) {
        if (index < BLOCK_ONE_CAPACITY) {
            return BLOCK_ONE_ITEM_START + index;
        }
        int local = index - BLOCK_ONE_CAPACITY;
        if (local < BLOCK_TWO_CAPACITY) {
            return BLOCK_TWO_ITEM_START + local;
        }
        return -1;
    }

    /**
     * Anzeige eines Items. Die Stapelgroesse spiegelt das Gewicht wider (auf 1-64 begrenzt),
     * der exakte Wert steht im Namen - ein Gewicht von 0 oder ueber 64 waere als Stapelgroesse
     * gar nicht darstellbar.
     */
    private ItemStack weightDisplay(int index, String typeId) {
        KillstreakManager killstreak = plugin.getKillstreakManager();
        int weight = killstreak.getItemWeight(typeId);

        // Bewusst ein frisches Item statt einer Kopie des Spezial-Items: Die Anzeige soll
        // keinen Spezial-Item-PDC tragen und damit niemals als echtes Item durchgehen.
        ItemStack display = ItemStack.of(killstreak.createSpecificSpecialItem(index).getType(),
                Math.max(1, Math.min(weight, 64)));

        display.setData(DataComponentTypes.CUSTOM_NAME, killstreak.getItemDisplayName(typeId));
        display.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                MiniMessage.miniMessage().deserialize("<gray>Gewicht: <yellow><b>" + weight + "</b></yellow>"
                        + (weight == 0 ? " <red>(spawnt nie)</red>" : "")),
                MiniMessage.miniMessage().deserialize("<gray>Spawnchance: <aqua>"
                        + String.format(Locale.GERMANY, "%.1f", killstreak.getSpawnChance(typeId)) + " %</aqua>"),
                Component.empty(),
                MiniMessage.miniMessage().deserialize("<dark_gray>ID: " + typeId + "</dark_gray>"))));
        return display;
    }

    private ItemStack arrowButton(String typeId, boolean increase) {
        ItemStack button = ItemStack.of(increase ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        button.setData(DataComponentTypes.CUSTOM_NAME, MiniMessage.miniMessage().deserialize(increase
                ? "<green><b>▲ Gewicht erhöhen</b></green>"
                : "<red><b>▼ Gewicht senken</b></red>"));
        button.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                MiniMessage.miniMessage().deserialize("<gray>Linksklick: <yellow>" + SMALL_STEP + "</yellow></gray>"),
                MiniMessage.miniMessage().deserialize("<gray>Rechtsklick: <yellow>" + LARGE_STEP + "</yellow></gray>"))));
        button.editPersistentDataContainer(pdc -> {
            pdc.set(KEY_ITEM_TARGET, PersistentDataType.STRING, typeId);
            pdc.set(KEY_DIRECTION, PersistentDataType.INTEGER, increase ? 1 : -1);
        });
        return button;
    }

    private ItemStack resetButton() {
        ItemStack button = ItemStack.of(Material.BARREL);
        button.setData(DataComponentTypes.CUSTOM_NAME,
                MiniMessage.miniMessage().deserialize("<gold><b>🔄 Zurücksetzen</b></gold>"));
        button.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(MiniMessage.miniMessage().deserialize(
                "<gray>Setzt alle Gewichte auf <yellow>" + KillstreakManager.DEFAULT_ITEM_WEIGHT + "</yellow>.</gray>"))));
        button.editPersistentDataContainer(pdc -> pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_RESET));
        return button;
    }

    private ItemStack closeButton() {
        ItemStack button = ItemStack.of(Material.BARRIER);
        button.setData(DataComponentTypes.CUSTOM_NAME,
                MiniMessage.miniMessage().deserialize("<red><b>✖ Schließen</b></red>"));
        button.editPersistentDataContainer(pdc -> pdc.set(KEY_ACTION, PersistentDataType.STRING, ACTION_CLOSE));
        return button;
    }

    private ItemStack filler() {
        ItemStack pane = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        pane.setData(DataComponentTypes.CUSTOM_NAME, Component.empty());
        return pane;
    }

    // ==================================================================
    // Klicks
    // ==================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(GUI_TITLE)) return;

        // Gilt auch fuer das eigene Inventar: Aus diesem Menue wird nichts herausgetragen
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)
                || (!player.isOp() && !plugin.getAccessManager().isPrivileged(player))) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String action = clicked.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING);
        if (ACTION_CLOSE.equals(action)) {
            player.closeInventory();
            player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_ENDER_CHEST_CLOSE, Sound.Source.MASTER, 1.0f, 1.2f));
            return;
        }
        if (ACTION_RESET.equals(action)) {
            plugin.getKillstreakManager().resetItemWeights();
            render(event.getInventory());
            player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, Sound.Source.MASTER, 1.0f, 1.2f));
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<yellow>[OSOK] 🎲 Alle Item-Gewichte auf <green>"
                            + KillstreakManager.DEFAULT_ITEM_WEIGHT + "</green> zurückgesetzt.</yellow>"));
            return;
        }

        String typeId = clicked.getPersistentDataContainer().get(KEY_ITEM_TARGET, PersistentDataType.STRING);
        Integer direction = clicked.getPersistentDataContainer().get(KEY_DIRECTION, PersistentDataType.INTEGER);
        if (typeId == null || direction == null) return;

        KillstreakManager killstreak = plugin.getKillstreakManager();
        int step = event.isRightClick() ? LARGE_STEP : SMALL_STEP;
        int current = killstreak.getItemWeight(typeId);
        int updated = Math.max(0, Math.min(current + direction * step, KillstreakManager.MAX_ITEM_WEIGHT));

        if (updated == current) {
            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 0.7f, 1.0f));
            return;
        }

        killstreak.setItemWeight(typeId, updated);
        render(event.getInventory());

        // Tonhoehe folgt dem Gewicht - hoeher wird heller
        float pitch = 0.8f + Math.min(updated, 40) * 0.02f;
        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 0.8f, pitch));

        if (killstreak.getTotalItemWeight() <= 0) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>[OSOK] ⚠ Alle Gewichte stehen auf 0 - es können gar keine Spezial-Items mehr erscheinen!</red>"));
        }
    }

    /** Verhindert, dass per Ziehen etwas in das Menue gelegt wird. */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().title().equals(GUI_TITLE)) {
            event.setCancelled(true);
        }
    }
}
