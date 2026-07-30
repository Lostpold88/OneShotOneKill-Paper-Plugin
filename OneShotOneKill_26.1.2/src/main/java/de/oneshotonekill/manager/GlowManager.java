package de.oneshotonekill.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Zentrale Verwaltung des Leuchtrahmens (Glow-Flag).
 * <p>
 * Drei Features markieren Spieler unabhaengig voneinander: der <b>Radar-Puls</b>, die
 * <b>Anti-Camping-Markierung</b> und der <b>Sudden Death</b>. Ohne zentrale Stelle wuerden
 * sie sich gegenseitig ausschalten - wer per Radar markiert ist und gleichzeitig aufhoert
 * zu campen, verlaere sonst auch das Radar-Leuchten.
 * <p>
 * Deshalb haelt diese Klasse pro Spieler die <b>Gruende</b> fest und schaltet das Flag erst
 * ab, wenn kein Grund mehr besteht.
 * <p>
 * Bewusst ueber {@code Entity#setGlowing} statt {@code PotionEffectType.GLOWING}: Ein
 * Potion-Effekt taucht beim Betroffenen immer im Effekt-Fenster des Inventars auf, selbst
 * mit {@code icon=false}. Ohne Potion-Effekt sieht der Markierte nichts - nur die Gegner
 * sehen den Leuchtrahmen.
 */
public class GlowManager {

    public enum GlowReason {
        /** Radar-Puls hat den Spieler enthuellt. */
        RADAR,
        /** Der Spieler steht zu lange an derselben Stelle. */
        CAMPING,
        /** Sudden Death: In der Endphase leuchtet jeder. */
        SUDDEN_DEATH
    }

    private final Map<UUID, EnumSet<GlowReason>> reasons = new HashMap<>();

    /** Markiert einen Spieler aus dem angegebenen Grund. Mehrfachaufrufe sind unschaedlich. */
    public void add(Player player, GlowReason reason) {
        if (player == null) return;
        reasons.computeIfAbsent(player.getUniqueId(), id -> EnumSet.noneOf(GlowReason.class)).add(reason);
        apply(player);
    }

    /** Nimmt einen Grund zurueck. Das Leuchten endet erst, wenn kein Grund mehr uebrig ist. */
    public void remove(Player player, GlowReason reason) {
        if (player == null) return;
        EnumSet<GlowReason> active = reasons.get(player.getUniqueId());
        if (active == null) return;

        active.remove(reason);
        if (active.isEmpty()) {
            reasons.remove(player.getUniqueId());
        }
        apply(player);
    }

    public boolean has(UUID uuid, GlowReason reason) {
        EnumSet<GlowReason> active = reasons.get(uuid);
        return active != null && active.contains(reason);
    }

    /** Loescht alle Gruende eines Spielers - z. B. nach einer Eliminierung. */
    public void clear(Player player) {
        if (player == null) return;
        reasons.remove(player.getUniqueId());
        apply(player);
    }

    /** Loescht die Markierung aller Spieler (Match-Ende, Map-Wechsel, Plugin-Stop). */
    public void clearAll() {
        reasons.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isGlowing()) {
                player.setGlowing(false);
            }
        }
    }

    /** Gibt den Eintrag eines Spielers frei, ohne das Flag zu setzen (bei Quit aufzurufen). */
    public void removePlayer(UUID uuid) {
        reasons.remove(uuid);
    }

    /** Setzt das Flag nur, wenn es sich tatsaechlich aendert - spart unnoetige Metadaten-Pakete. */
    private void apply(Player player) {
        boolean shouldGlow = reasons.containsKey(player.getUniqueId());
        if (player.isGlowing() != shouldGlow) {
            player.setGlowing(shouldGlow);
        }
    }
}
