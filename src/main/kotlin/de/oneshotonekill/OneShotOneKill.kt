package de.oneshotonekill

import de.oneshotonekill.command.CamperGui
import de.oneshotonekill.command.ItemTestCommand
import de.oneshotonekill.command.ItemWeightGui
import de.oneshotonekill.command.OsokCommand
import de.oneshotonekill.command.PunktCommand
import de.oneshotonekill.listener.CombatListener
import de.oneshotonekill.listener.PlayerConnectionListener
import de.oneshotonekill.listener.SpecialItemListener
import de.oneshotonekill.listener.WorldRuleListener
import de.oneshotonekill.manager.AccessManager
import de.oneshotonekill.manager.AntiCampManager
import de.oneshotonekill.manager.ArenaManager
import de.oneshotonekill.manager.EliminationManager
import de.oneshotonekill.manager.EquipmentManager
import de.oneshotonekill.manager.ExplosivesManager
import de.oneshotonekill.manager.GlowManager
import de.oneshotonekill.manager.KillEffectManager
import de.oneshotonekill.manager.KillstreakManager
import de.oneshotonekill.manager.MatchManager
import de.oneshotonekill.manager.MatchSummaryManager
import de.oneshotonekill.manager.NukeManager
import de.oneshotonekill.manager.ScoreboardManager
import de.oneshotonekill.manager.StealthBomberManager
import de.oneshotonekill.manager.TacticalItemsManager
import de.oneshotonekill.manager.WorldManager
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

class OneShotOneKill : JavaPlugin() {

    // Alle Manager werden in onEnable gesetzt und danach nie wieder ausgetauscht. lateinit statt
    // nullable: Ein Zugriff vor onEnable waere ein Programmierfehler, kein Laufzeitzustand.
    lateinit var accessManager: AccessManager
        private set
    lateinit var worldManager: WorldManager
        private set
    lateinit var arenaManager: ArenaManager
        private set
    lateinit var equipmentManager: EquipmentManager
        private set
    lateinit var scoreboardManager: ScoreboardManager
        private set
    lateinit var glowManager: GlowManager
        private set
    lateinit var killstreakManager: KillstreakManager
        private set
    lateinit var killEffectManager: KillEffectManager
        private set
    lateinit var matchManager: MatchManager
        private set
    lateinit var eliminationManager: EliminationManager
        private set
    lateinit var stealthBomberManager: StealthBomberManager
        private set
    lateinit var explosivesManager: ExplosivesManager
        private set
    lateinit var tacticalItemsManager: TacticalItemsManager
        private set
    lateinit var antiCampManager: AntiCampManager
        private set
    lateinit var matchSummaryManager: MatchSummaryManager
        private set
    lateinit var nukeManager: NukeManager
        private set
    lateinit var specialItemListener: SpecialItemListener
        private set
    lateinit var itemWeightGui: ItemWeightGui
        private set
    lateinit var camperGui: CamperGui
        private set

    /**
     * `onEnable` ist vollstaendig durchgelaufen.
     *
     * Paper ruft `onDisable` **auch dann**, wenn `onEnable` mittendrin abbricht - und dann sind
     * die spaeteren `lateinit`-Manager noch nicht gesetzt. Frueher stand hier die Pruefung auf
     * einen einzelnen Manager; die haelt nur so lange, wie er der letzte in der Reihe ist. Ein
     * neuer Manager dahinter (der `NukeManager`) hat genau das aufgedeckt: Der eigentliche
     * Startfehler ging in einer zweiten, irrefuehrenden `UninitializedPropertyAccessException`
     * unter. Dieses Flag haengt an keiner Reihenfolge.
     */
    private var enableCompleted = false

    override fun onEnable() {
        // 1. Manager instanziieren
        accessManager = AccessManager(this)
        worldManager = WorldManager(this)
        arenaManager = ArenaManager(this)
        equipmentManager = EquipmentManager(this)
        scoreboardManager = ScoreboardManager(this)
        glowManager = GlowManager()
        killstreakManager = KillstreakManager(this)
        killEffectManager = KillEffectManager()
        matchManager = MatchManager(this)
        eliminationManager = EliminationManager(this)
        stealthBomberManager = StealthBomberManager(this)
        explosivesManager = ExplosivesManager(this)
        tacticalItemsManager = TacticalItemsManager(this)
        antiCampManager = AntiCampManager(this)
        matchSummaryManager = MatchSummaryManager(this)
        nukeManager = NukeManager(this)

        // 2. Map & Welt laden
        worldManager.setupWorld()

        // 3. Event-Listener registrieren
        val itemTestCommand = ItemTestCommand(this)
        specialItemListener = SpecialItemListener(this)
        itemWeightGui = ItemWeightGui(this)
        camperGui = CamperGui(this)

        registerListeners(
            PlayerConnectionListener(this),
            CombatListener(this),
            specialItemListener,
            WorldRuleListener(),
            accessManager,
            stealthBomberManager,
            explosivesManager,
            tacticalItemsManager,
            antiCampManager,
            nukeManager,
            itemTestCommand,
            itemWeightGui,
            camperGui,
        )

        // Dauerlauf der Anti-Camping- und Streckenmessung
        antiCampManager.start()

        // Dauerhafte Rechtevergabe fuer das privilegierte Konto (periodische Wiederherstellung) und
        // einmalige Sofortvergabe an bereits verbundene Spieler, falls das Plugin zur Laufzeit neu
        // geladen wurde.
        accessManager.start()
        server.onlinePlayers.forEach { accessManager.grant(it) }

        // Serverweit erzwungene GameRules (locator_bar) auf alle bereits geladenen Welten anwenden
        WorldManager.applyGlobalGameRulesToAllWorlds()

        // Beim Start aufraeumen: Drachen, TNT und Nuke-Reste aus einem vorherigen Lauf entfernen
        stealthBomberManager.clearAll()
        explosivesManager.clearAll()
        nukeManager.clearAll()

        // 4. Paper Dynamic Lifecycle Command Registration (Brigadier BasicCommand)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            // Bewusst ohne Aliase: Der Spielbetrieb laeuft ausschliesslich ueber /osok.
            event.registrar().register("osok", "OneShotOneKill Hauptbefehl", OsokCommand(this))
            // Werkzeug zum Einmessen neuer Arenen - kein Spielinhalt, deshalb ein eigener Befehl
            event.registrar().register("punkt", "Koordinaten in punkte.txt sichern", PunktCommand(this))
        }

        // 5. Scoreboards fuer alle bereits verbundenen Spieler aktualisieren
        scoreboardManager.updateAllScoreboards()

        enableCompleted = true

        logger.info("=========================================")
        logger.info("  ONESHOT-ONEKILL NATIVE PAPER PLUGIN    ")
        logger.info("=========================================")
    }

    override fun onDisable() {
        // Kein lateinit-Zugriff ohne vollstaendigen Start - siehe enableCompleted
        if (!enableCompleted) return

        scoreboardManager.resetAllStats()
        matchManager.stopVictoryTasks()
        antiCampManager.stop()
        killstreakManager.clearAllGroundItems()
        stealthBomberManager.clearAll()
        explosivesManager.clearAll()
        tacticalItemsManager.clearAll()
        nukeManager.clearAll()
        // Frost-Traps zuletzt: Sie veraendern Bloecke und muessen weg, bevor die Welt gesichert wird
        specialItemListener.clearAllTraps()
        specialItemListener.clearAllVanish()
        glowManager.clearAll()
    }

    private fun registerListeners(vararg listeners: Listener) {
        listeners.forEach { server.pluginManager.registerEvents(it, this) }
    }
}
