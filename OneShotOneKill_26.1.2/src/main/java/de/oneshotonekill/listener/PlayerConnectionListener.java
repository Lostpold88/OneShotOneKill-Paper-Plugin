package de.oneshotonekill.listener;

import de.oneshotonekill.OneShotOneKill;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.kyori.adventure.sound.Sound;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public class PlayerConnectionListener implements Listener {

    private final OneShotOneKill plugin;

    public PlayerConnectionListener(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(MiniMessage.miniMessage().deserialize("<green>[✦] <white>" + player.getName() + "</white> <gray>hat <yellow><b>OSOK</b></yellow> betreten!</gray></green>"));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, Sound.Source.MASTER, 1.0f, 1.5f));
        }

        player.getScheduler().runDelayed(plugin, task -> {
            World targetWorld = plugin.getWorldManager().getOsokWorld();
            if (targetWorld != null && player.isOnline()) {
                Location spawnLoc = plugin.getWorldManager().getSpawnLocation();
                Location loc = (spawnLoc != null) ? spawnLoc : new Location(targetWorld, 223.5, 48.0, 55.5);
                
                // Paper API: Asynchrones Teleportieren mit pre-loading
                player.teleportAsync(loc).thenAccept(success -> {
                    if (success && player.isOnline()) {
                        if (plugin.getMatchManager().isMatchStarted() && !plugin.getMatchManager().isMatchPaused()) {
                            plugin.getEquipmentManager().giveOneShotEquipment(player);
                        } else {
                            plugin.getEquipmentManager().clearBaseEquipment(player);
                        }
                        plugin.getScoreboardManager().updateAllScoreboards();
                        plugin.getLogger().info("Spieler " + player.getName() + " wurde auf OSOK Arena (223.5, 48.0, 55.5) teleportiert!");
                    }
                });
            }
        }, null, 5L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.quitMessage(MiniMessage.miniMessage().deserialize("<red>[❌] <white>" + player.getName() + "</white> <gray>hat <yellow><b>OSOK</b></yellow> verlassen.</gray></red>"));

        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            plugin.getScoreboardManager().updateAllScoreboards();
        }, 2L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        World osokWorld = plugin.getWorldManager().getOsokWorld();
        Location spawnLoc = plugin.getWorldManager().getSpawnLocation();

        if (osokWorld != null) {
            event.setRespawnLocation(spawnLoc != null ? spawnLoc : new Location(osokWorld, 223.5, 48.0, 55.5));
        }

        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> {
            if (plugin.getMatchManager().isMatchStarted() && !plugin.getMatchManager().isMatchPaused()) {
                plugin.getEquipmentManager().giveOneShotEquipment(player);
            } else {
                plugin.getEquipmentManager().clearBaseEquipment(player);
            }
            plugin.getScoreboardManager().updateAllScoreboards();
        }, null, 2L);
    }

    @EventHandler
    public void onPlayerMove(org.bukkit.event.player.PlayerMoveEvent event) {
        if (plugin.getMatchManager().isMatchPaused()) {
            Player player = event.getPlayer();
            if (plugin.getArenaManager().isInArenaArea(event.getTo())) {
                Location spawnLoc = plugin.getWorldManager().getSpawnLocation();
                Location fallback = (spawnLoc != null) ? spawnLoc : new Location(plugin.getWorldManager().getOsokWorld(), 223.5, 48.0, 55.5);
                player.teleportAsync(fallback);
                player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ⏸ Das Match ist pausiert! Du kannst die Arena aktuell nicht betreten.</red>"));
                player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            }
        }
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!plugin.getMatchManager().isMatchStarted() || plugin.getMatchManager().isMatchEnded()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Das Spiel wurde noch nicht gestartet! Warte auf /start.</red>"));
            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            return;
        }

        if (plugin.getMatchManager().isMatchPaused()) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ⏸ Das Match ist aktuell pausiert!</red>"));
            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            return;
        }

        if (plugin.getArenaManager().isInArenaArea(player.getLocation())) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Du bist bereits in der Arena!</red>"));
            player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_VILLAGER_NO, Sound.Source.MASTER, 1.0f, 1.0f));
            return;
        }

        Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
        if (randomLoc != null) {
            // Paper API: Asynchrones Teleportieren ohne Main-Thread Lags
            player.teleportAsync(randomLoc).thenAccept(success -> {
                if (success && player.isOnline()) {
                    plugin.getEquipmentManager().giveOneShotEquipment(player);
                    plugin.getScoreboardManager().updateAllScoreboards();
                    player.playSound(Sound.sound(org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 1.0f, 1.2f));
                }
            });
        } else {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] Arena-Welt ist aktuell nicht geladen.</red>"));
        }
    }
}
