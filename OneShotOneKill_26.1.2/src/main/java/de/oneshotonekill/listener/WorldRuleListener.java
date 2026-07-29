package de.oneshotonekill.listener;

import de.oneshotonekill.manager.WorldManager;
import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameRules;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
     * Paper WorldGameRuleChangeEvent: Blockt jeden Versuch, locator_bar wieder einzuschalten.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameRuleChange(WorldGameRuleChangeEvent event) {
        if (event.getGameRule().equals(GameRules.LOCATOR_BAR) && !"false".equalsIgnoreCase(event.getValue())) {
            event.setCancelled(true);
            event.getCommandSender().sendMessage(MiniMessage.miniMessage().deserialize(
                    "<red>[OSOK] 🔒 <b>locator_bar</b> ist serverweit dauerhaft auf <b>false</b> gesetzt und kann nicht aktiviert werden.</red>"));
        }
    }
}
