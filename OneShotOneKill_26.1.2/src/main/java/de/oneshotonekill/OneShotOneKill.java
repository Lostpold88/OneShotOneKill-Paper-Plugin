package de.oneshotonekill;

import de.oneshotonekill.command.ClearPfeileCommand;
import de.oneshotonekill.command.ItemTestCommand;
import de.oneshotonekill.command.KillEffectCommand;
import de.oneshotonekill.command.OsokCommand;
import de.oneshotonekill.command.StartCommand;
import de.oneshotonekill.listener.CombatListener;
import de.oneshotonekill.listener.PlayerConnectionListener;
import de.oneshotonekill.listener.SpecialItemListener;
import de.oneshotonekill.manager.ArenaManager;
import de.oneshotonekill.manager.EquipmentManager;
import de.oneshotonekill.manager.KillEffectManager;
import de.oneshotonekill.manager.KillstreakManager;
import de.oneshotonekill.manager.MatchManager;
import de.oneshotonekill.manager.ScoreboardManager;
import de.oneshotonekill.manager.WorldManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;

public class OneShotOneKill extends JavaPlugin {

    private WorldManager worldManager;
    private ArenaManager arenaManager;
    private EquipmentManager equipmentManager;
    private ScoreboardManager scoreboardManager;
    private KillstreakManager killstreakManager;
    private KillEffectManager killEffectManager;
    private MatchManager matchManager;

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

        // 2. Map & Welt laden
        this.worldManager.setupWorld();

        // 3. Event-Listener registrieren
        ItemTestCommand itemTestCommand = new ItemTestCommand(this);
        KillEffectCommand killEffectCommand = new KillEffectCommand(this);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new SpecialItemListener(this), this);
        getServer().getPluginManager().registerEvents(itemTestCommand, this);
        getServer().getPluginManager().registerEvents(killEffectCommand, this);

        // 4. Paper Dynamic Lifecycle Command Registration (paper-plugin.yml)
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            OsokCommand osokCommand = new OsokCommand(this);
            StartCommand startCommand = new StartCommand(this);
            ClearPfeileCommand clearPfeileCommand = new ClearPfeileCommand(this);

            registerBasic(registrar, "oneshot", "OSOK Hauptbefehl", List.of("osok"), osokCommand, osokCommand);
            registerBasic(registrar, "pause", "OSOK Match pausieren/fortsetzen", List.of(), osokCommand, osokCommand);
            registerBasic(registrar, "itemmode", "OSOK Item-Modus", List.of("itemmodus", "mode"), osokCommand, osokCommand);
            registerBasic(registrar, "resetstats", "OSOK Statistiken zurücksetzen", List.of("resetboard"), osokCommand, osokCommand);
            registerBasic(registrar, "start", "OSOK Match starten", List.of(), startCommand, startCommand);
            registerBasic(registrar, "itemtest", "OSOK Spezial-Item Testmenü", List.of("testgui"), itemTestCommand, null);
            registerBasic(registrar, "clearpfeile", "OSOK Pfeile entfernen", List.of(), clearPfeileCommand, null);
            registerBasic(registrar, "killeffect", "OSOK Killeffekte Menü", List.of("effects"), killEffectCommand, killEffectCommand);
        });

        // 5. Scoreboards für alle bereits verbundenen Spieler aktualisieren
        this.scoreboardManager.updateAllScoreboards();

        getLogger().info("=========================================");
        getLogger().info("  ONESHOT-ONEKILL NATIVE PAPER PLUGIN    ");
        getLogger().info("=========================================");
    }

    private void registerBasic(Commands registrar, String name, String desc, List<String> aliases, CommandExecutor executor, TabCompleter completer) {
        registrar.register(name, desc, aliases, new BasicCommand() {
            @Override
            public void execute(CommandSourceStack stack, String[] args) {
                Command dummyCmd = new Command(name) {
                    @Override
                    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                        return false;
                    }
                };
                executor.onCommand(stack.getSender(), dummyCmd, name, args);
            }

            @Override
            public Collection<String> suggest(CommandSourceStack stack, String[] args) {
                if (completer != null) {
                    String[] effectiveArgs = (args == null || args.length == 0) ? new String[]{""} : args;
                    Command dummyCmd = new Command(name) {
                        @Override
                        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                            return false;
                        }
                    };
                    List<String> list = completer.onTabComplete(stack.getSender(), dummyCmd, name, effectiveArgs);
                    return list != null ? list : List.of();
                }
                return List.of();
            }

            @Override
            public boolean canUse(CommandSender sender) {
                return true;
            }
        });
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

    public KillEffectManager getKillEffectManager() {
        return killEffectManager;
    }

    public MatchManager getMatchManager() {
        return matchManager;
    }
}
