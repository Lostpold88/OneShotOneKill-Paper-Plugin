package de.oneshotonekill.listener

import de.oneshotonekill.manager.WorldManager
import de.oneshotonekill.util.mini
import io.papermc.paper.event.world.WorldGameRuleChangeEvent
import org.bukkit.GameRules
import org.bukkit.entity.Mob
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.weather.ThunderChangeEvent
import org.bukkit.event.weather.WeatherChangeEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.WorldInitEvent
import org.bukkit.event.world.WorldLoadEvent

/**
 * Stellt sicher, dass die serverweit erzwungenen GameRules (u. a. locator_bar=false) auf JEDER Welt
 * gelten - auch auf Welten, die erst nach dem Serverstart geladen werden (z. B. beim Map-Wechsel
 * oder durch andere Plugins) und auch dann, wenn jemand versucht, sie per Befehl wieder zu
 * aktivieren.
 */
class WorldRuleListener : Listener {

    @EventHandler
    fun onWorldInit(event: WorldInitEvent) {
        WorldManager.applyGlobalGameRules(event.world)
    }

    /**
     * Raeumt Tiere und Mobs weg, die **in der Map gespeichert** sind.
     *
     * `SPAWN_MOBS=false` verhindert nur neue Spawns, und das Aufraeumen beim Laden der Welt sieht
     * ausschliesslich die Entities in bereits geladenen Chunks - beim Serverstart also nur die
     * Spawn-Chunks. Alles, was in den Regionsdateien der Map schlummert, taucht deshalb erst auf,
     * wenn ein Spieler in dessen Chunk laeuft: genau die Huehner auf BO2.
     *
     * Der `EntitiesLoadEvent` greift an der richtigen Stelle - dem Moment, in dem die Entities eines
     * Chunks in die Welt kommen.
     *
     * Entfernt werden ausschliesslich [Mob]s, also Tiere und Monster. **Nicht** angetastet wird die
     * Deko der Map: Ruestungsstaender, Bilderrahmen und Item-Displays sind keine Mobs - eine
     * pauschale Entity-Keule wuerde eine nachgebaute Karte ausraeumen.
     *
     * **Eigene Mobs bleiben ebenfalls stehen**: Der Bomber-Drache traegt einen Schluessel im eigenen
     * Namensraum. Ohne diese Ausnahme koennte ein Chunk-Reload ihn mitten im Angriff loeschen.
     */
    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (!event.world.name.startsWith(WorldManager.WORLD_PREFIX)) return

        event.entities
            .filterIsInstance<Mob>()
            .filterNot { mob -> mob.persistentDataContainer.keys.any { it.namespace == NAMESPACE } }
            .forEach { it.remove() }
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        WorldManager.applyGlobalGameRules(event.world)
    }

    /**
     * Paper WorldGameRuleChangeEvent: Blockt jeden Versuch, eine der erzwungenen Regeln wieder
     * einzuschalten - locator_bar sowie den Tages- und Wetterfortlauf.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onGameRuleChange(event: WorldGameRuleChangeEvent) {
        // Ausschalten ist immer erlaubt, erzwungen ist ja genau das
        if (event.value.equals("false", ignoreCase = true)) return

        val ruleName = when (event.gameRule) {
            GameRules.LOCATOR_BAR -> "locator_bar"
            GameRules.ADVANCE_TIME -> "advance_time"
            GameRules.ADVANCE_WEATHER -> "advance_weather"
            else -> return
        }

        event.isCancelled = true
        // Der Absender fehlt, wenn die Regel nicht per Befehl geaendert wurde
        event.commandSender?.sendMessage(
            ("<red>[OSOK] 🔒 <b>$ruleName</b> ist serverweit dauerhaft auf <b>false</b> gesetzt " +
                "und kann nicht aktiviert werden.</red>").mini()
        )
    }

    /**
     * Zusaetzliche Absicherung gegen Wetterwechsel.
     *
     * `advance_weather=false` haelt nur den natuerlichen Fortlauf an. Ein `/weather rain` oder ein
     * anderes Plugin kann das Wetter trotzdem umstellen - hier wird jeder Wechsel weg von "klar"
     * abgelehnt.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onWeatherChange(event: WeatherChangeEvent) {
        if (event.toWeatherState()) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onThunderChange(event: ThunderChangeEvent) {
        if (event.toThunderState()) {
            event.isCancelled = true
        }
    }

    private companion object {
        /** Namensraum aller eigenen PDC-Schluessel. */
        const val NAMESPACE = "oneshotonekill"
    }
}
