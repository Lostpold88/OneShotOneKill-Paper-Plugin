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
        this.killLimit = kills;
        this.timeLimitSeconds = 0;
        this.remainingSeconds = 0;
        this.matchEnded = false;

        plugin.getScoreboardManager().updateAllScoreboards();
        Bukkit.broadcastMessage("§e[OneShot] 🎯 Match-Ziel gesetzt: §a§l" + kills + " Kills§7!");
    }

    public void setTimeLimitMinutes(int minutes) {
        stopTimer();
        this.killLimit = 0;
        this.timeLimitSeconds = minutes * 60;
        this.remainingSeconds = this.timeLimitSeconds;
        this.matchEnded = false;

        startTimer();
        plugin.getScoreboardManager().updateAllScoreboards();
        Bukkit.broadcastMessage("§e[OneShot] ⏱ Match-Zeit gesetzt: §a§l" + minutes + " Minuten§7!");
    }

    public void resetLimits() {
        stopTimer();
        this.killLimit = 0;
        this.timeLimitSeconds = 0;
        this.remainingSeconds = 0;
        this.matchEnded = false;

        plugin.getScoreboardManager().updateAllScoreboards();
        Bukkit.broadcastMessage("§e[OneShot] 🔄 Match-Limits (Kills & Zeit) wurden deaktiviert.");
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
                    Bukkit.broadcastMessage("§c[OneShot] ⏱ Noch §e" + formatTime(remainingSeconds) + " §cVerbleibend!");
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
            Bukkit.broadcastMessage("§c[OneShot] ⏱ Die Zeit ist abgelaufen! Keines Match-Ergebnis.");
        }
    }

    public void celebrateWinner(Player winner) {
        matchEnded = true;
        stopTimer();

        int winnerKills = plugin.getScoreboardManager().getKills(winner.getUniqueId());

        // Bildschirm-Banner für ALLE Spieler
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§e§l🏆 GEWINNER!", "§f" + winner.getName() + " §7hat gewonnen! (§a" + winnerKills + " Kills§7)", 10, 100, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 1.0f, 1.2f);
        }

        // Chat Ankündigung & Rangliste
        Bukkit.broadcastMessage(" ");
        Bukkit.broadcastMessage("§a§l=======================================");
        Bukkit.broadcastMessage("§e§l   🏆 MATCH BEENDET - MATCH GEWINNER!   ");
        Bukkit.broadcastMessage("§f  Gewinner: §e§l" + winner.getName() + " §7mit §a§l" + winnerKills + " Kills§7!");
        Bukkit.broadcastMessage("§a§l=======================================");
        Bukkit.broadcastMessage(" ");

        // Gewinner Effekte
        winner.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 120, 0));
        winner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 2));

        // Noteblock Sieges-Melodie (Fanfare) abspielen!
        playVictorySong();

        // Feuerwerk & Spektakel Task über 5 Sekunden
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks >= 10 || !winner.isOnline()) {
                    cancel();

                    // Nach 5s Teleport zurück zum Spawn und Vorbereitung der nächsten Runde
                    Location spawn = plugin.getWorldManager().getSpawnLocation();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (spawn != null) p.teleport(spawn);
                        plugin.getEquipmentManager().giveOneShotEquipment(p);
                    }
                    matchEnded = false;
                    plugin.getScoreboardManager().updateAllScoreboards();
                    return;
                }

                Location wLoc = winner.getLocation();
                wLoc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, wLoc.clone().add(0, 1, 0), 40, 0.5, 1.0, 0.5, 0.2);
                wLoc.getWorld().spawnParticle(Particle.FIREWORK, wLoc.clone().add(0, 1, 0), 30, 0.5, 1.0, 0.5, 0.1);
                wLoc.getWorld().strikeLightningEffect(wLoc);

                // Feuerwerk zünden
                Firework fw = (Firework) wLoc.getWorld().spawnEntity(wLoc.clone().add(0, 1, 0), EntityType.FIREWORK_ROCKET);
                FireworkMeta fwm = fw.getFireworkMeta();
                fwm.addEffect(FireworkEffect.builder()
                        .withColor(Color.YELLOW, Color.ORANGE, Color.GREEN)
                        .withFade(Color.WHITE)
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withFlicker()
                        .build());
                fwm.setPower(1);
                fw.setFireworkMeta(fwm);

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void playVictorySong() {
        // Multi-Instrument Noteblock Fanfare
        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (step > 20) {
                    cancel();
                    return;
                }

                switch (step) {
                    case 0 -> playNoteChord(0.71f, 0.89f, 1.06f); // C4 Major
                    case 3 -> playNoteChord(0.89f, 1.06f, 1.41f); // E4 Major
                    case 6 -> playNoteChord(1.06f, 1.41f, 1.78f); // G4 Major
                    case 9 -> playNoteChord(1.41f, 1.78f, 2.00f); // High C5 Triumph
                    case 12 -> playNoteChord(1.78f, 2.00f, 1.41f); // E5 High Peak
                    case 15 -> playNoteChord(2.00f, 2.00f, 2.00f); // Ausklang Glanz
                }

                step++;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void playNoteChord(float p1, float p2, float p3) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_HARP, SoundCategory.MASTER, 1.0f, p1);
            player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.MASTER, 0.8f, p2);
            player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.9f, p3);
            player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.MASTER, 0.7f, p1);
        }
    }

    public String formatTime(int totalSeconds) {
        int mins = totalSeconds / 60;
        int secs = totalSeconds % 60;
        return String.format("%02d:%02d", mins, secs);
    }
}
