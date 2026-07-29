package de.oneshotonekill;

import de.oneshotonekill.command.ItemTestCommand;
import de.oneshotonekill.command.OsokCommand;
import de.oneshotonekill.listener.CombatListener;
import de.oneshotonekill.listener.PlayerConnectionListener;
import de.oneshotonekill.listener.SpecialItemListener;
import de.oneshotonekill.listener.WorldRuleListener;
import de.oneshotonekill.manager.ArenaManager;
import de.oneshotonekill.manager.EliminationManager;
import de.oneshotonekill.manager.EquipmentManager;
import de.oneshotonekill.manager.StealthBomberManager;
import de.oneshotonekill.manager.KillEffectManager;
import de.oneshotonekill.manager.KillstreakManager;
import de.oneshotonekill.manager.MatchManager;
import de.oneshotonekill.manager.ScoreboardManager;
import de.oneshotonekill.manager.WorldManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

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

    @Override
    public void onEnable() {
        // 1. Manager instanziieren
        this.worldManager = new WorldManager(this);
        this.arenaManager = new ArenaManager(this);
        this.equipmentManager = new EquipmentManager();
        this.scoreboardManager = new ScoreboardManager(this);
        this.killstreakManager = new KillstreakManager(this);
        this.killEffectManager = new KillEffectManager();
        this.matchManager = new MatchManager(this);
        this.eliminationManager = new EliminationManager(this);
        this.stealthBomberManager = new StealthBomberManager(this);

        // 2. Map & Welt laden
        this.worldManager.setupWorld();

        // 3. Event-Listener registrieren
        ItemTestCommand itemTestCommand = new ItemTestCommand(this);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new SpecialItemListener(this), this);
        getServer().getPluginManager().registerEvents(new WorldRuleListener(), this);
        getServer().getPluginManager().registerEvents(this.stealthBomberManager, this);
        getServer().getPluginManager().registerEvents(itemTestCommand, this);

        // Serverweit erzwungene GameRules (locator_bar) auf alle bereits geladenen Welten anwenden
        WorldManager.applyGlobalGameRulesToAllWorlds();

        // Beim Start aufraeumen: Drachen und TNT aus einem vorherigen Lauf entfernen
        this.stealthBomberManager.clearAll();

        // 4. Paper Dynamic Lifecycle Command Registration (Brigadier BasicCommand)
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            OsokCommand osokCommand = new OsokCommand(this);
            event.registrar().register("osok", "OneShotOneKill Hauptbefehl", List.of("oneshot"), osokCommand);
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
        if (killstreakManager != null) {
            killstreakManager.clearAllGroundItems();
        }
        if (stealthBomberManager != null) {
            stealthBomberManager.clearAll();
        }
    }

    public EliminationManager getEliminationManager() {
        return eliminationManager;
    }

    public StealthBomberManager getStealthBomberManager() {
        return stealthBomberManager;
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
