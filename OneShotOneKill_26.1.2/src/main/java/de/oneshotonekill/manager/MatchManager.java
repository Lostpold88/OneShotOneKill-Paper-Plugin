package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;

public class MatchManager {

    private final OneShotOneKill plugin;
    private int killLimit = 0;
    private int timeLimitSeconds = 0;
    private int remainingSeconds = 0;
    private BukkitTask timerTask = null;
    private BukkitTask victoryMusicTask = null;
    private BukkitTask victoryEffectsTask = null;
    private boolean matchEnded = false;

    public MatchManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public boolean isMatchEnded() {
        return matchEnded;
    }

    public int getKillLimit() {
        return killLimit;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    public boolean hasKillLimit() {
        return killLimit > 0;
    }

    public boolean hasTimeLimit() {
        return timeLimitSeconds > 0 && timerTask != null;
    }

    public void setKillLimit(int kills) {
        stopTimer();
        stopVictoryTasks();
        this.killLimit = kills;
        this.timeLimitSeconds = 0;
        this.remainingSeconds = 0;
        this.matchEnded = false;

        plugin.getScoreboardManager().updateAllScoreboards();
        Bukkit.broadcastMessage("§e[OSOK] 🎯 Match-Ziel gesetzt: §a§l" + kills + " Kills§7!");
    }

    public void setTimeLimitMinutes(int minutes) {
        stopTimer();
        stopVictoryTasks();
        this.killLimit = 0;
        this.timeLimitSeconds = minutes * 60;
        this.remainingSeconds = this.timeLimitSeconds;
        this.matchEnded = false;

        startTimer();
        plugin.getScoreboardManager().updateAllScoreboards();
        Bukkit.broadcastMessage("§e[OSOK] ⏱ Match-Zeit gesetzt: §a§l" + minutes + " Minuten§7!");
    }

    public void resetLimits() {
        stopTimer();
        stopVictoryTasks();
        this.killLimit = 0;
        this.timeLimitSeconds = 0;
        this.remainingSeconds = 0;
        this.matchEnded = false;

        plugin.getScoreboardManager().updateAllScoreboards();
        Bukkit.broadcastMessage("§e[OSOK] 🔄 Match-Limits (Kills & Zeit) wurden deaktiviert.");
    }

    private void startTimer() {
        timerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (matchEnded) {
                    cancel();
                    return;
                }

                remainingSeconds--;
                plugin.getScoreboardManager().updateAllScoreboards();

                if (remainingSeconds <= 0) {
                    cancel();
                    triggerTimeLimitWinner();
                } else if (remainingSeconds == 60 || remainingSeconds == 30 || remainingSeconds == 10 || remainingSeconds <= 5) {
                    Bukkit.broadcastMessage("§c[OSOK] ⏱ Noch §e" + formatTime(remainingSeconds) + " §cVerbleibend!");
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.8f);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void stopTimer() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    public void stopVictoryTasks() {
        if (victoryMusicTask != null) {
            victoryMusicTask.cancel();
            victoryMusicTask = null;
        }
        if (victoryEffectsTask != null) {
            victoryEffectsTask.cancel();
            victoryEffectsTask = null;
        }
    }

    public void checkKillWinner(Player killer, int currentKills) {
        if (matchEnded || killLimit <= 0) return;

        if (currentKills >= killLimit) {
            celebrateWinner(killer);
        }
    }

    public void triggerTimeLimitWinner() {
        if (matchEnded) return;

        Player winner = Bukkit.getOnlinePlayers().stream()
                .max(Comparator.comparingInt(p -> plugin.getScoreboardManager().getKills(p.getUniqueId())))
                .orElse(null);

        if (winner != null) {
            celebrateWinner(winner);
        } else {
            Bukkit.broadcastMessage("§c[OSOK] ⏱ Die Zeit ist abgelaufen! Keines Match-Ergebnis.");
        }
    }

    public void celebrateWinner(Player winner) {
        matchEnded = true;
        stopTimer();

        int winnerKills = plugin.getScoreboardManager().getKills(winner.getUniqueId());

        // Bildschirm-Banner für ALLE Spieler
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§e§l🏆 GEWINNER!", "§f" + winner.getName() + " §7hat gewonnen! (§a" + winnerKills + " Kills§7)", 10, 200, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);
        }

        // Chat Ankündigung & Rangliste
        Bukkit.broadcastMessage(" ");
        Bukkit.broadcastMessage("§a§l=======================================");
        Bukkit.broadcastMessage("§e§l   🏆 MATCH BEENDET - MATCH GEWINNER!   ");
        Bukkit.broadcastMessage("§f  Gewinner: §e§l" + winner.getName() + " §7mit §a§l" + winnerKills + " Kills§7!");
        Bukkit.broadcastMessage("§7  Starte ein neues Match mit: §e/start");
        Bukkit.broadcastMessage("§a§l=======================================");
        Bukkit.broadcastMessage(" ");

        // Gewinner Effekte
        winner.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 7200, 0));
        winner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 7200, 2));

        // Megalovania Endlosschleife & Sieger-Spektakel starten!
        playWinnerCelebrationLoop(winner);
    }

    private void playWinnerCelebrationLoop(Player winner) {
        stopVictoryTasks();
        playMegalovaniaSong();

        victoryEffectsTask = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!matchEnded || winner == null || !winner.isOnline()) {
                    cancel();
                    return;
                }

                Location wLoc = winner.getLocation();
                wLoc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, wLoc.clone().add(0, 1, 0), 20, 0.5, 1.0, 0.5, 0.2);
                wLoc.getWorld().spawnParticle(Particle.FIREWORK, wLoc.clone().add(0, 1, 0), 15, 0.5, 1.0, 0.5, 0.1);

                if (ticks % 2 == 0) {
                    wLoc.getWorld().strikeLightningEffect(wLoc);

                    Firework fw = (Firework) wLoc.getWorld().spawnEntity(wLoc.clone().add(0, 1, 0), EntityType.FIREWORK_ROCKET);
                    FireworkMeta fwm = fw.getFireworkMeta();
                    fwm.addEffect(FireworkEffect.builder()
                            .withColor(Color.YELLOW, Color.ORANGE, Color.PURPLE)
                            .withFade(Color.WHITE)
                            .with(FireworkEffect.Type.BALL_LARGE)
                            .withFlicker()
                            .build());
                    fwm.setPower(1);
                    fw.setFireworkMeta(fwm);
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void playMegalovaniaSong() {
        // Exakte Megalovania Noteblock-Töne & Redstone Delays laut Noten-Diagramm
        // Hinweismeldung: 1 Redstone-Repeater-Tick = 2 Game-Ticks (100ms)
        int[] startClicks = new int[]{ 6, 4, 3, 2 }; // 1. D,D | 2. C,C | 3. H,H | 4. B,B
        int[] noteClicks = new int[136];
        java.util.Arrays.fill(noteClicks, -1);

        int[] offsets = new int[]{ 0, 2, 4, 8, 14, 18, 22, 26, 28, 30 };
        int[] restClicks = new int[]{ -1, -1, 18, 13, 12, 11, 9, 6, 9, 11 };

        for (int pass = 0; pass < 4; pass++) {
            int baseTick = pass * 34;
            for (int i = 0; i < offsets.length; i++) {
                int tick = baseTick + offsets[i];
                int clicks = (i < 2) ? startClicks[pass] : restClicks[i];
                noteClicks[tick] = clicks;
            }
        }

        victoryMusicTask = new BukkitRunnable() {
            int currentTick = 0;

            @Override
            public void run() {
                if (!matchEnded) {
                    cancel();
                    return;
                }

                int clicks = noteClicks[currentTick % 136];
                if (clicks >= 0) {
                    float pitch = (float) Math.pow(2.0, (clicks - 12.0) / 12.0);
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        Location loc = player.getLocation();
                        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.MASTER, 1.0f, pitch);
                        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.8f, pitch);
                        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MASTER, 1.0f, pitch * 0.5f);
                    }
                }

                currentTick++;
            }
        }.runTaskTimer(plugin, 0L, 1L); // 1-Tick Präzision mit echter Redstone-Repeater Verzögerung!
    }

    public void restartMatch(Player sender) {
        stopVictoryTasks();
        stopTimer();
        this.matchEnded = false;

        Location spawn = plugin.getWorldManager().getSpawnLocation();
        int count = 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
            Location targetLoc = (randomLoc != null) ? randomLoc : spawn;
            if (targetLoc != null) {
                p.teleport(targetLoc);
                plugin.getEquipmentManager().giveOneShotEquipment(p);
                p.sendTitle("§a§lNEUES MATCH!", "§7OneShotOneKill gestartet", 10, 40, 10);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 0.5f, 1.5f);
                count++;
            }
        }

        if (timeLimitSeconds > 0) {
            remainingSeconds = timeLimitSeconds;
            startTimer();
        }

        plugin.getScoreboardManager().updateAllScoreboards();

        Bukkit.broadcastMessage(" ");
        Bukkit.broadcastMessage("§a§l=======================================");
        Bukkit.broadcastMessage("§e§l   🚀 MATCH NEU GESTARTET!   ");
        Bukkit.broadcastMessage("§7" + count + " Spieler wurden zufällig in der Arena platziert!");
        Bukkit.broadcastMessage("§a§l=======================================");
    }

    public String formatTime(int totalSeconds) {
        int mins = totalSeconds / 60;
        int secs = totalSeconds % 60;
        return String.format("%02d:%02d", mins, secs);
    }
}
