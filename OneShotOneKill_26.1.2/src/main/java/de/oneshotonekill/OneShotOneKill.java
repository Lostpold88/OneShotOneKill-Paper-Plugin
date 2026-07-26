package de.oneshotonekill;

import de.oneshotonekill.command.ClearPfeileCommand;
import de.oneshotonekill.command.ItemTestCommand;
import de.oneshotonekill.command.OsokCommand;
import de.oneshotonekill.command.StartCommand;
import de.oneshotonekill.listener.CombatListener;
import de.oneshotonekill.listener.PlayerConnectionListener;
import de.oneshotonekill.listener.SpecialItemListener;
import de.oneshotonekill.manager.ArenaManager;
import de.oneshotonekill.manager.EquipmentManager;
import de.oneshotonekill.manager.KillstreakManager;
import de.oneshotonekill.manager.ScoreboardManager;
import de.oneshotonekill.manager.WorldManager;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public class OneShotOneKill extends JavaPlugin {

    private WorldManager worldManager;
    private ArenaManager arenaManager;
    private EquipmentManager equipmentManager;
    private ScoreboardManager scoreboardManager;
    private KillstreakManager killstreakManager;

    @Override
    public void onEnable() {
        // 1. Manager instanziieren
        this.worldManager = new WorldManager(this);
        this.arenaManager = new ArenaManager(this);
        this.equipmentManager = new EquipmentManager();
        this.scoreboardManager = new ScoreboardManager();
        this.killstreakManager = new KillstreakManager(this);

        // 2. Map & Welt laden
        this.worldManager.setupWorld();

        // 3. Event-Listener registrieren
        ItemTestCommand itemTestCommand = new ItemTestCommand(this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new SpecialItemListener(this), this);
        getServer().getPluginManager().registerEvents(itemTestCommand, this);

        // 4. Befehle registrieren
        OsokCommand osokCommand = new OsokCommand(this);
        registerCommand("oneshot", osokCommand, osokCommand);
        registerCommand("osok", osokCommand, osokCommand);
        registerCommand("start", new StartCommand(this), new StartCommand(this));
        registerCommand("resetstats", osokCommand, osokCommand);
        registerCommand("itemmode", osokCommand, osokCommand);
        registerCommand("itemmodus", osokCommand, osokCommand);
        registerCommand("mode", osokCommand, osokCommand);
        registerCommand("itemtest", itemTestCommand, null);
        registerCommand("testgui", itemTestCommand, null);
        registerCommand("clearpfeile", new ClearPfeileCommand(this), null);

        // 5. Scoreboards für alle bereits verbundenen Spieler aktualisieren
        this.scoreboardManager.updateAllScoreboards();

        getLogger().info("=========================================");
        getLogger().info("  ONESHOT-ONEKILL PLUGIN EMBEDDED MAP    ");
        getLogger().info("=========================================");
    }

    private void registerCommand(String cmdName, CommandExecutor executor, TabCompleter completer) {
        if (getCommand(cmdName) != null) {
            getCommand(cmdName).setExecutor(executor);
            if (completer != null) {
                getCommand(cmdName).setTabCompleter(completer);
            }
        }
    }

    @Override
    public void onDisable() {
        if (killstreakManager != null) {
            killstreakManager.clearAllGroundItems();
        }
        getLogger().info("OneShotOneKill Plugin wurde deaktiviert.");
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
}
