package de.oneshotonekill.listener;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public class PlayerConnectionListener implements Listener {

    private final OneShotOneKill plugin;

    public PlayerConnectionListener(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.setJoinMessage("§a[✦] §f" + player.getName() + " §7hat §e§lOSOK §7betreten!");

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, 1.0f, 1.5f);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World targetWorld = plugin.getWorldManager().getOsokWorld();
            if (targetWorld != null && player.isOnline()) {
                Location spawnLoc = plugin.getWorldManager().getSpawnLocation();
                Location loc = (spawnLoc != null) ? spawnLoc : new Location(targetWorld, 223.5, 48.0, 55.5);
                player.teleport(loc);
                plugin.getEquipmentManager().giveOneShotEquipment(player);
                plugin.getScoreboardManager().updateAllScoreboards();
                plugin.getLogger().info("Spieler " + player.getName() + " wurde auf OSOK Arena (223.5, 48.0, 55.5) teleportiert!");
            }
        }, 5L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.setQuitMessage("§c[❌] §f" + player.getName() + " §7hat §e§lOSOK §7verlassen.");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getEquipmentManager().giveOneShotEquipment(player);
            plugin.getScoreboardManager().updateAllScoreboards();
        }, 2L);
    }

    @EventHandler
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (plugin.getArenaManager().isInArenaArea(player.getLocation())) {
            player.sendMessage("§c[OSOK] ❌ Du bist bereits im Bereich der Arena! Random-TP ist während des Kampfs deaktiviert.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 1.0f, 1.0f);
            return;
        }

        Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
        if (randomLoc != null) {
            player.teleport(randomLoc);
            plugin.getEquipmentManager().giveOneShotEquipment(player);
            plugin.getScoreboardManager().updateAllScoreboards();
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 1.0f, 1.2f);
        } else {
            player.sendMessage("§c[OSOK] Arena-Welt ist aktuell nicht geladen.");
        }
    }
}
