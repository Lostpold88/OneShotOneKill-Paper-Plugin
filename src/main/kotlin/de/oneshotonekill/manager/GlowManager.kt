package de.oneshotonekill.manager

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.EnumSet
import java.util.UUID

/**
 * Zentrale Verwaltung des Leuchtrahmens (Glow-Flag).
 *
 * Zwei Features markieren Spieler unabhaengig voneinander: der **Radar-Puls** und die
 * **Anti-Camping-Markierung**. Ohne zentrale Stelle wuerden sie sich gegenseitig ausschalten -
 * wer per Radar markiert ist und gleichzeitig aufhoert zu campen, verloere sonst auch das
 * Radar-Leuchten.
 *
 * Deshalb haelt diese Klasse pro Spieler die **Gruende** fest und schaltet das Flag erst ab, wenn
 * kein Grund mehr besteht.
 *
 * Bewusst ueber `Entity#setGlowing` statt `PotionEffectType.GLOWING`: Ein Potion-Effekt taucht
 * beim Betroffenen immer im Effekt-Fenster des Inventars auf, selbst mit `icon=false`. Ohne
 * Potion-Effekt sieht der Markierte nichts - nur die Gegner sehen den Leuchtrahmen.
 */
class GlowManager {

    enum class GlowReason {
        /** Radar-Puls hat den Spieler enthuellt. */
        RADAR,

        /** Der Spieler steht zu lange an derselben Stelle. */
        CAMPING,
    }

    private val reasons = mutableMapOf<UUID, EnumSet<GlowReason>>()

    /** Markiert einen Spieler aus dem angegebenen Grund. Mehrfachaufrufe sind unschaedlich. */
    fun add(player: Player, reason: GlowReason) {
        reasons.getOrPut(player.uniqueId) { EnumSet.noneOf(GlowReason::class.java) }.add(reason)
        apply(player)
    }

    /** Nimmt einen Grund zurueck. Das Leuchten endet erst, wenn kein Grund mehr uebrig ist. */
    fun remove(player: Player, reason: GlowReason) {
        val active = reasons[player.uniqueId] ?: return

        active.remove(reason)
        if (active.isEmpty()) {
            reasons.remove(player.uniqueId)
        }
        apply(player)
    }

    fun has(uuid: UUID, reason: GlowReason): Boolean = reasons[uuid]?.contains(reason) == true

    /** Loescht alle Gruende eines Spielers - z. B. nach einer Eliminierung. */
    fun clear(player: Player) {
        reasons.remove(player.uniqueId)
        apply(player)
    }

    /** Loescht die Markierung aller Spieler (Match-Ende, Map-Wechsel, Plugin-Stop). */
    fun clearAll() {
        reasons.clear()
        Bukkit.getOnlinePlayers()
            .filter { it.isGlowing }
            .forEach { it.isGlowing = false }
    }

    /** Gibt den Eintrag eines Spielers frei, ohne das Flag zu setzen (bei Quit aufzurufen). */
    fun removePlayer(uuid: UUID) {
        reasons.remove(uuid)
    }

    /** Setzt das Flag nur, wenn es sich tatsaechlich aendert - spart unnoetige Metadaten-Pakete. */
    private fun apply(player: Player) {
        val shouldGlow = player.uniqueId in reasons
        if (player.isGlowing != shouldGlow) {
            player.isGlowing = shouldGlow
        }
    }
}
