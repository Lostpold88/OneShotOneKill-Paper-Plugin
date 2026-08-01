package de.oneshotonekill;

import de.oneshotonekill.command.CamperGui;
import de.oneshotonekill.command.ItemTestCommand;
import de.oneshotonekill.command.ItemWeightGui;
import de.oneshotonekill.command.OsokCommand;
import de.oneshotonekill.listener.CombatListener;
import de.oneshotonekill.listener.PlayerConnectionListener;
import de.oneshotonekill.listener.SpecialItemListener;
import de.oneshotonekill.listener.WorldRuleListener;
import de.oneshotonekill.manager.AntiCampManager;
import de.oneshotonekill.manager.ArenaManager;
import de.oneshotonekill.manager.EliminationManager;
import de.oneshotonekill.manager.EquipmentManager;
import de.oneshotonekill.manager.ExplosivesManager;
import de.oneshotonekill.manager.GlowManager;
import de.oneshotonekill.manager.MatchSummaryManager;
import de.oneshotonekill.manager.TacticalItemsManager;
import de.oneshotonekill.manager.StealthBomberManager;
import de.oneshotonekill.manager.KillEffectManager;
import de.oneshotonekill.manager.KillstreakManager;
import de.oneshotonekill.manager.MatchManager;
import de.oneshotonekill.manager.ScoreboardManager;
import de.oneshotonekill.manager.WorldManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;


public class OneShotOneKill extends JavaPlugin {

    private WorldManager worldManager;
    private ArenaManager arenaManager;
    private EquipmentManager equipmentManager;
    private ScoreboardManager scoreboardManager;
    private KillstreakManager killstreakManager;
    private KillEffectManager killEffectManager;
    private MatchManager matchManager;
    private EliminationManager eliminationManager;
    private StealthBomberManager stealthBomberManager;
    private ExplosivesManager explosivesManager;
    private GlowManager glowManager;
    private TacticalItemsManager tacticalItemsManager;
    private AntiCampManager antiCampManager;
    private MatchSummaryManager matchSummaryManager;
    private SpecialItemListener specialItemListener;
    private ItemWeightGui itemWeightGui;
    private CamperGui camperGui;

    @Override
    public void onEnable() {
        // 1. Manager instanziieren
        this.worldManager = new WorldManager(this);
        this.arenaManager = new ArenaManager(this);
        this.equipmentManager = new EquipmentManager(this);
        this.scoreboardManager = new ScoreboardManager(this);
        this.glowManager = new GlowManager();
        this.killstreakManager = new KillstreakManager(this);
        this.killEffectManager = new KillEffectManager();
        this.matchManager = new MatchManager(this);
        this.eliminationManager = new EliminationManager(this);
        this.stealthBomberManager = new StealthBomberManager(this);
        this.explosivesManager = new ExplosivesManager(this);
        this.tacticalItemsManager = new TacticalItemsManager(this);
        this.antiCampManager = new AntiCampManager(this);
        this.matchSummaryManager = new MatchSummaryManager(this);

        // 2. Map & Welt laden
        this.worldManager.setupWorld();

        // 3. Event-Listener registrieren
        ItemTestCommand itemTestCommand = new ItemTestCommand(this);
        this.specialItemListener = new SpecialItemListener(this);
        this.itemWeightGui = new ItemWeightGui(this);
        this.camperGui = new CamperGui(this);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(this.specialItemListener, this);
        getServer().getPluginManager().registerEvents(new WorldRuleListener(), this);
        getServer().getPluginManager().registerEvents(this.stealthBomberManager, this);
        getServer().getPluginManager().registerEvents(this.explosivesManager, this);
        getServer().getPluginManager().registerEvents(this.tacticalItemsManager, this);
        getServer().getPluginManager().registerEvents(this.antiCampManager, this);
        getServer().getPluginManager().registerEvents(itemTestCommand, this);
        getServer().getPluginManager().registerEvents(this.itemWeightGui, this);
        getServer().getPluginManager().registerEvents(this.camperGui, this);

        // Dauerlauf der Anti-Camping- und Streckenmessung
        this.antiCampManager.start();

        // Serverweit erzwungene GameRules (locator_bar) auf alle bereits geladenen Welten anwenden
        WorldManager.applyGlobalGameRulesToAllWorlds();

        // Beim Start aufraeumen: Drachen und TNT aus einem vorherigen Lauf entfernen
        this.stealthBomberManager.clearAll();
        this.explosivesManager.clearAll();

        // 4. Paper Dynamic Lifecycle Command Registration (Brigadier BasicCommand)
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            OsokCommand osokCommand = new OsokCommand(this);
            // Bewusst ohne Aliase: Alle Befehle sind ausschliesslich ueber /osok erreichbar.
            event.registrar().register("osok", "OneShotOneKill Hauptbefehl", osokCommand);
        });

        // 5. Scoreboards für alle bereits verbundenen Spieler aktualisieren
        this.scoreboardManager.updateAllScoreboards();

        getLogger().info("=========================================");
        getLogger().info("  ONESHOT-ONEKILL NATIVE PAPER PLUGIN    ");
        getLogger().info("=========================================");
    }

    @Override
    public void onDisable() {
        if (scoreboardManager != null) {
            scoreboardManager.resetAllStats();
        }
        if (matchManager != null) {
            matchManager.stopVictoryTasks();
        }
        if (antiCampManager != null) {
            antiCampManager.stop();
        }
        if (killstreakManager != null) {
            killstreakManager.clearAllGroundItems();
        }
        if (stealthBomberManager != null) {
            stealthBomberManager.clearAll();
        }
        if (explosivesManager != null) {
            explosivesManager.clearAll();
        }
        if (tacticalItemsManager != null) {
            tacticalItemsManager.clearAll();
        }
        // Frost-Traps zuletzt: Sie veraendern Bloecke und muessen weg, bevor die Welt gesichert wird
        if (specialItemListener != null) {
            specialItemListener.clearAllTraps();
            specialItemListener.clearAllVanish();
        }
        if (glowManager != null) {
            glowManager.clearAll();
        }
    }

    public GlowManager getGlowManager() {
        return glowManager;
    }

    public TacticalItemsManager getTacticalItemsManager() {
        return tacticalItemsManager;
    }

    public AntiCampManager getAntiCampManager() {
        return antiCampManager;
    }

    public MatchSummaryManager getMatchSummaryManager() {
        return matchSummaryManager;
    }

    public SpecialItemListener getSpecialItemListener() {
        return specialItemListener;
    }

    public ItemWeightGui getItemWeightGui() {
        return itemWeightGui;
    }

    public CamperGui getCamperGui() {
        return camperGui;
    }

    public EliminationManager getEliminationManager() {
        return eliminationManager;
    }

    public StealthBomberManager getStealthBomberManager() {
        return stealthBomberManager;
    }

    public ExplosivesManager getExplosivesManager() {
        return explosivesManager;
    }

    public WorldManager getWorldManager() {
        return worldManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public EquipmentManager getEquipmentManager() {
        return equipmentManager;
    }

    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public KillstreakManager getKillstreakManager() {
        return killstreakManager;
    }

    public KillEffectManager getKillEffectManager() {
        return killEffectManager;
    }

    public MatchManager getMatchManager() {
        return matchManager;
    }
}
