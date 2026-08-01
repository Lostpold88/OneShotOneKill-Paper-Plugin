package de.oneshotonekill.listener;

import de.oneshotonekill.manager.WorldManager;
import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameRules;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Stellt sicher, dass die serverweit erzwungenen GameRules (u. a. locator_bar=false)
 * auf JEDER Welt gelten - auch auf Welten, die erst nach dem Serverstart geladen werden
 * (z. B. beim Map-Wechsel oder durch andere Plugins) und auch dann, wenn jemand versucht,
 * sie per Befehl wieder zu aktivieren.
 */
public class WorldRuleListener implements Listener {

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        WorldManager.applyGlobalGameRules(event.getWorld());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        WorldManager.applyGlobalGameRules(event.getWorld());
    }

    /**
     * Paper WorldGameRuleChangeEvent: Blockt jeden Versuch, eine der erzwungenen Regeln
     * wieder einzuschalten - locator_bar sowie den Tages- und Wetterfortlauf.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameRuleChange(WorldGameRuleChangeEvent event) {
        if ("false".equalsIgnoreCase(event.getValue())) {
            return; // Ausschalten ist immer erlaubt, erzwungen ist ja genau das
        }

        String ruleName = null;
        if (event.getGameRule().equals(GameRules.LOCATOR_BAR)) {
            ruleName = "locator_bar";
        } else if (event.getGameRule().equals(GameRules.ADVANCE_TIME)) {
            ruleName = "advance_time";
        } else if (event.getGameRule().equals(GameRules.ADVANCE_WEATHER)) {
            ruleName = "advance_weather";
        }
        if (ruleName == null) return;

        event.setCancelled(true);
        event.getCommandSender().sendMessage(MiniMessage.miniMessage().deserialize(
                "<red>[OSOK] 🔒 <b>" + ruleName + "</b> ist serverweit dauerhaft auf <b>false</b> gesetzt und kann nicht aktiviert werden.</red>"));
    }

    /**
     * Zusaetzliche Absicherung gegen Wetterwechsel.
     * <p>
     * {@code advance_weather=false} haelt nur den natuerlichen Fortlauf an. Ein
     * {@code /weather rain} oder ein anderes Plugin kann das Wetter trotzdem umstellen -
     * hier wird jeder Wechsel weg von "klar" abgelehnt.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent event) {
        if (event.toThunderState()) {
            event.setCancelled(true);
        }
    }
}
