package de.oneshotonekill.command;

import de.oneshotonekill.OneShotOneKill;
import de.oneshotonekill.manager.AntiCampManager;
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
 * GUI fuer <code>/osok camper</code>: Zeit, Radius und An/Aus der Camper-Markierung.
 * <p>
 * Aufbau wie bei der Item-Gewichtung - der Wert steht in der Mitte, der Pfeil zum Erhoehen
 * darueber, der zum Senken darunter.
 * <pre>
 *   Reihe 0  · · ▲ · · · ▲ · ·     Zeit +   /   Radius +
 *   Reihe 1  · · ⏱ · ⏻ · ⌖ · ·     Zeit     /  An-Aus  /  Radius
 *   Reihe 2  · · ▼ · ✖ · ▼ · ·     Zeit -   / Schliessen /  Radius -
 * </pre>
 * Linksklick aendert um {@link #SMALL_STEP}, Rechtsklick um {@link #LARGE_STEP}.
 */
public class CamperGui implements Listener {

    public static final Component GUI_TITLE =
            MiniMessage.miniMessage().deserialize("<yellow><b>🏕 Anti-Camping</b></yellow>");

    private static final int COLS = 9;
    private static final int SIZE = COLS * 3;

    private static final int SLOT_TIME_UP = 2;
    private static final int SLOT_TIME_VALUE = COLS + 2;
    private static final int SLOT_TIME_DOWN = COLS * 2 + 2;

    private static final int SLOT_RADIUS_UP = 6;
    private static final int SLOT_RADIUS_VALUE = COLS + 6;
    private static final int SLOT_RADIUS_DOWN = COLS * 2 + 6;

    private static final int SLOT_TOGGLE = COLS + 4;
    private static final int SLOT_CLOSE = COLS * 2 + 4;

    private static final int SMALL_STEP = 1;
    private static final int LARGE_STEP = 5;

    private static final NamespacedKey KEY_ACTION = new NamespacedKey("oneshotonekill", "campergui_action");
    private static final NamespacedKey KEY_DIRECTION = new NamespacedKey("oneshotonekill", "campergui_direction");

    private static final String ACTION_TIME = "time";
    private static final String ACTION_RADIUS = "radius";
    private static final String ACTION_TOGGLE = "toggle";
    private static final String ACTION_CLOSE = "close";

    private final OneShotOneKill plugin;

    public CamperGui(OneShotOneKill plugin) {
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
        AntiCampManager antiCamp = plugin.getAntiCampManager();

        gui.setItem(SLOT_TIME_UP, arrowButton(ACTION_TIME, true, "Sekunden"));
        gui.setItem(SLOT_TIME_VALUE, timeDisplay(antiCamp));
        gui.setItem(SLOT_TIME_DOWN, arrowButton(ACTION_TIME, false, "Sekunden"));

        gui.setItem(SLOT_RADIUS_UP, arrowButton(ACTION_RADIUS, true, "Blöcke"));
        gui.setItem(SLOT_RADIUS_VALUE, radiusDisplay(antiCamp));
        gui.setItem(SLOT_RADIUS_DOWN, arrowButton(ACTION_RADIUS, false, "Blöcke"));

        gui.setItem(SLOT_TOGGLE, toggleButton(antiCamp));
        gui.setItem(SLOT_CLOSE, closeButton());

        for (int slot = 0; slot < SIZE; slot++) {
            if (gui.getItem(slot) == null) {
                gui.setItem(slot, filler());
            }
        }
    }

    private ItemStack timeDisplay(AntiCampManager antiCamp) {
        int seconds = antiCamp.getCampSeconds();
        ItemStack display = ItemStack.of(Material.CLOCK, Math.max(1, Math.min(seconds, 64)));
        display.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize(
                    "<yellow><b>⏱ Zeit: " + seconds + "s</b></yellow>"));
            meta.lore(List.of(
                    MiniMessage.miniMessage().deserialize("<gray>So lange muss ein Spieler auf der Stelle</gray>"),
                    MiniMessage.miniMessage().deserialize("<gray>bleiben, bis er markiert wird.</gray>"),
                    Component.empty(),
                    MiniMessage.miniMessage().deserialize("<dark_gray>Bereich: " + AntiCampManager.MIN_CAMP_SECONDS
                            + "-" + AntiCampManager.MAX_CAMP_SECONDS + "s</dark_gray>")));
        });
        return display;
    }

    private ItemStack radiusDisplay(AntiCampManager antiCamp) {
        double radius = antiCamp.getCampRadius();
        ItemStack display = ItemStack.of(Material.COMPASS, Math.max(1, Math.min((int) Math.round(radius), 64)));
        display.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize(
                    "<aqua><b>⌖ Radius: " + String.format(Locale.GERMANY, "%.1f", radius) + " Blöcke</b></aqua>"));
            meta.lore(List.of(
                    MiniMessage.miniMessage().deserialize("<gray>Innerhalb dieses Umkreises gilt ein</gray>"),
                    MiniMessage.miniMessage().deserialize("<gray>Spieler als \"steht noch da\".</gray>"),
                    Component.empty(),
                    MiniMessage.miniMessage().deserialize("<dark_gray>Bereich: "
                            + String.format(Locale.GERMANY, "%.0f", AntiCampManager.MIN_CAMP_RADIUS) + "-"
                            + String.format(Locale.GERMANY, "%.0f", AntiCampManager.MAX_CAMP_RADIUS) + " Blöcke</dark_gray>")));
        });
        return display;
    }

    private ItemStack toggleButton(AntiCampManager antiCamp) {
        boolean enabled = antiCamp.isEnabled();
        ItemStack button = ItemStack.of(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
        button.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize(enabled
                    ? "<green><b>⏻ Markierung: AN</b></green>"
                    : "<red><b>⏻ Markierung: AUS</b></red>"));
            meta.lore(List.of(
                    MiniMessage.miniMessage().deserialize("<gray>Klicken zum Umschalten.</gray>"),
                    Component.empty(),
                    MiniMessage.miniMessage().deserialize(
                            "<dark_gray>Die Streckenmessung für die Match-</dark_gray>"),
                    MiniMessage.miniMessage().deserialize(
                            "<dark_gray>Zusammenfassung läuft unabhängig weiter.</dark_gray>")));
            meta.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, ACTION_TOGGLE);
        });
        return button;
    }

    private ItemStack arrowButton(String action, boolean increase, String unit) {
        ItemStack button = ItemStack.of(increase ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        button.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize(increase
                    ? "<green><b>▲ Erhöhen</b></green>"
                    : "<red><b>▼ Senken</b></red>"));
            meta.lore(List.of(
                    MiniMessage.miniMessage().deserialize("<gray>Linksklick: <yellow>" + SMALL_STEP + " " + unit + "</yellow></gray>"),
                    MiniMessage.miniMessage().deserialize("<gray>Rechtsklick: <yellow>" + LARGE_STEP + " " + unit + "</yellow></gray>")));
            meta.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, action);
            meta.getPersistentDataContainer().set(KEY_DIRECTION, PersistentDataType.INTEGER, increase ? 1 : -1);
        });
        return button;
    }

    private ItemStack closeButton() {
        ItemStack button = ItemStack.of(Material.BARRIER);
        button.editMeta(meta -> {
            meta.displayName(MiniMessage.miniMessage().deserialize("<red><b>✖ Schließen</b></red>"));
            meta.getPersistentDataContainer().set(KEY_ACTION, PersistentDataType.STRING, ACTION_CLOSE);
        });
        return button;
    }

    private ItemStack filler() {
        ItemStack pane = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        pane.editMeta(meta -> meta.displayName(Component.empty()));
        return pane;
    }

    // ==================================================================
    // Klicks
    // ==================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(GUI_TITLE)) return;

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)
                || (!player.isOp() && !plugin.getAccessManager().isPrivileged(player))) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String action = clicked.getPersistentDataContainer().get(KEY_ACTION, PersistentDataType.STRING);
        if (action == null) return;

        AntiCampManager antiCamp = plugin.getAntiCampManager();

        switch (action) {
            case ACTION_CLOSE -> {
                player.closeInventory();
                player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_ENDER_CHEST_CLOSE, Sound.Source.MASTER, 1.0f, 1.2f));
                return;
            }
            case ACTION_TOGGLE -> {
                boolean enabled = !antiCamp.isEnabled();
                antiCamp.setEnabled(enabled);
                render(event.getInventory());
                player.playSound(Sound.sound(enabled
                        ? org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL
                        : org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 1.0f, enabled ? 1.4f : 0.8f));
                Bukkit.broadcast(MiniMessage.miniMessage().deserialize(enabled
                        ? "<yellow>[OSOK] 🏕 Anti-Camping ist jetzt <green><b>AN</b></green>.</yellow>"
                        : "<yellow>[OSOK] 🏕 Anti-Camping ist jetzt <red><b>AUS</b></red>. <gray>Niemand wird mehr markiert.</gray></yellow>"));
                return;
            }
            default -> { /* Zeit und Radius unten */ }
        }

        Integer direction = clicked.getPersistentDataContainer().get(KEY_DIRECTION, PersistentDataType.INTEGER);
        if (direction == null) return;

        int step = event.isRightClick() ? LARGE_STEP : SMALL_STEP;

        if (ACTION_TIME.equals(action)) {
            int before = antiCamp.getCampSeconds();
            antiCamp.setCampSeconds(before + direction * step);
            feedback(player, event.getInventory(), before != antiCamp.getCampSeconds());
            return;
        }

        if (ACTION_RADIUS.equals(action)) {
            double before = antiCamp.getCampRadius();
            antiCamp.setCampRadius(before + direction * step);
            feedback(player, event.getInventory(), before != antiCamp.getCampRadius());
        }
    }

    /** Baut die Anzeige neu auf und quittiert, ob der Wert sich tatsaechlich geaendert hat. */
    private void feedback(Player player, Inventory gui, boolean changed) {
        if (!changed) {
            // Grenze erreicht - der Wert wurde von setCampSeconds/setCampRadius gekappt
            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 0.7f, 1.0f));
            return;
        }
        render(gui);
        player.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 0.8f, 1.4f));
    }

    /** Verhindert, dass per Ziehen etwas in das Menue gelegt wird. */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().title().equals(GUI_TITLE)) {
            event.setCancelled(true);
        }
    }
}
