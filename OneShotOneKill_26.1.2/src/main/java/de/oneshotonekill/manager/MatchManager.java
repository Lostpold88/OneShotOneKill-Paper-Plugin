package de.oneshotonekill.manager;

import de.oneshotonekill.OneShotOneKill;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
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

import java.util.Comparator;
import java.util.function.Consumer;

public class MatchManager {

    private final OneShotOneKill plugin;
    private int killLimit = 0;
    private int timeLimitSeconds = 0;
    private int remainingSeconds = 0;
    private ScheduledTask timerTask = null;
    private ScheduledTask victoryMusicTask = null;
    private ScheduledTask victoryEffectsTask = null;
    private ScheduledTask victoryTitleTask = null;
    private boolean matchStarted = false;
    private boolean matchPaused = false;
    private boolean matchEnded = false;

    public MatchManager(OneShotOneKill plugin) {
        this.plugin = plugin;
    }

    public int getKillLimit() {
        return killLimit;
    }

    public int getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    public boolean isMatchEnded() {
        return matchEnded;
    }

    public boolean isMatchStarted() {
        return matchStarted;
    }

    public boolean isMatchPaused() {
        return matchPaused;
    }

    public boolean hasKillLimit() {
        return killLimit > 0;
    }

    public boolean hasTimeLimit() {
        return timeLimitSeconds > 0 && remainingSeconds > 0;
    }

    public void setKillLimit(int kills) {
        if (kills <= 0) {
            resetLimits();
            return;
        }
        stopTimer();
        stopVictoryTasks();
        this.killLimit = kills;
        this.timeLimitSeconds = 0;
        this.remainingSeconds = 0;
        this.matchEnded = false;

        plugin.getScoreboardManager().updateAllScoreboards();
        broadcast("<yellow>[OSOK] 🎯 Match-Ziel gesetzt: <green><b>" + kills + " Kills</b></green> <gray>(wird bei /start aktiv)!</gray></yellow>");
    }

    public void setTimeLimitMinutes(int minutes) {
        if (minutes <= 0) {
            resetLimits();
            return;
        }
        stopTimer();
        stopVictoryTasks();
        this.killLimit = 0;
        this.timeLimitSeconds = minutes * 60;
        this.remainingSeconds = this.timeLimitSeconds;
        this.matchEnded = false;

        if (matchStarted) {
            startTimer();
        }
        plugin.getScoreboardManager().updateAllScoreboards();
        broadcast("<yellow>[OSOK] ⏱ Match-Zeit gesetzt: <green><b>" + minutes + " Minuten</b></green> <gray>(wird bei /start aktiv)!</gray></yellow>");
    }

    public void setTimeLimitSeconds(int seconds) {
        if (seconds <= 0) {
            resetLimits();
            return;
        }
        stopTimer();
        stopVictoryTasks();
        this.killLimit = 0;
        this.timeLimitSeconds = seconds;
        this.remainingSeconds = this.timeLimitSeconds;
        this.matchEnded = false;

        if (matchStarted) {
            startTimer();
        }
        plugin.getScoreboardManager().updateAllScoreboards();
        broadcast("<yellow>[OSOK] ⏱ Match-Zeit gesetzt: <green><b>" + formatTime(seconds) + "</b></green> <gray>(" + seconds + "s) (wird bei /start aktiv)!</gray></yellow>");
    }

    public void resetLimits() {
        stopTimer();
        stopVictoryTasks();
        this.killLimit = 0;
        this.timeLimitSeconds = 0;
        this.remainingSeconds = 0;
        this.matchEnded = false;

        plugin.getScoreboardManager().updateAllScoreboards();
        broadcast("<yellow>[OSOK] 🔄 Match-Limits (Kills & Zeit) wurden deaktiviert.</yellow>");
    }

    private void startTimer() {
        stopTimer();
        timerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (matchEnded) {
                task.cancel();
                timerTask = null;
                return;
            }

            if (matchPaused) {
                return;
            }

            remainingSeconds--;
            plugin.getScoreboardManager().updateAllScoreboards();

            if (remainingSeconds <= 0) {
                task.cancel();
                timerTask = null;
                triggerTimeLimitWinner();
            } else if (remainingSeconds == 60 || remainingSeconds == 30 || remainingSeconds == 10 || remainingSeconds <= 5) {
                broadcast("<red>[OSOK] ⏱ Noch <yellow>" + formatTime(remainingSeconds) + "</yellow> Verbleibend!</red>");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 1.0f, 1.8f);
                }
            }
        }, 20L, 20L);
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
        if (victoryTitleTask != null) {
            victoryTitleTask.cancel();
            victoryTitleTask = null;
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
            broadcast("<red>[OSOK] ⏱ Die Zeit ist abgelaufen! Keines Match-Ergebnis.</red>");
        }
    }

    public void celebrateWinner(Player winner) {
        matchEnded = true;
        stopTimer();

        int winnerKills = plugin.getScoreboardManager().getKills(winner.getUniqueId());

        // Initialer Sound für alle Spieler
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);
        }

        // Chat Ankündigung mit Regenbogen-Gradient
        broadcast(" ");
        broadcast("<gradient:#ff5555:#ffff55:#55ff55:#55ffff:#5555ff:#ff55ff><b>=======================================</b></gradient>");
        broadcast("<rainbow><b>   🏆 MATCH BEENDET - MATCH GEWINNER!   </b></rainbow>");
        broadcast("<white>  Gewinner: <yellow><b>" + winner.getName() + "</b></yellow> <gray>mit <green><b>" + winnerKills + " Kills</b></green>!</gray></white>");
        broadcast("<gray>  Starte ein neues Match mit: <yellow>/start</yellow></gray>");
        broadcast("<gradient:#ff5555:#ffff55:#55ff55:#55ffff:#5555ff:#ff55ff><b>=======================================</b></gradient>");
        broadcast(" ");

        // Gewinner Effekte
        winner.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 7200, 0));
        winner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 7200, 2));

        // Megalovania Endlosschleife, Regenbogen-Title-Animation & Sieger-Spektakel starten!
        playWinnerCelebrationLoop(winner, winnerKills);
    }

    private void playWinnerCelebrationLoop(Player winner, int winnerKills) {
        stopVictoryTasks();

        String winnerNameClean = winner != null ? winner.getName().replaceAll("§[0-9a-fk-orA-FK-OR]", "") : "Spieler";

        // 1. Paper Native Global Region Scheduler: Animierter Regenbogen-Titel auf dem Bildschirm!
        victoryTitleTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int titleTicks = 0;

            @Override
            public void accept(ScheduledTask task) {
                if (!matchEnded) {
                    task.cancel();
                    victoryTitleTask = null;
                    return;
                }

                int rainbowPhase = titleTicks * 3;
                float gradPhase = (float) Math.sin(titleTicks * 0.15);
                String gradPhaseStr = String.format(java.util.Locale.US, "%.2f", gradPhase);

                Component mainTitle = MiniMessage.miniMessage().deserialize(
                        "<rainbow:" + rainbowPhase + "><b>🏆 GEWINNER! 🏆</b></rainbow>"
                );
                Component subTitle = MiniMessage.miniMessage().deserialize(
                        "<gradient:#ff5555:#ffff55:#55ff55:#55ffff:#5555ff:#ff55ff:" + gradPhaseStr + "><b>" + winnerNameClean + "</b></gradient> <gray>hat gewonnen! (<green>" + winnerKills + " Kills</green>)</gray>"
                );

                Title.Times times = Title.Times.times(Ticks.duration(0), Ticks.duration(30), Ticks.duration(10));
                Title animatedTitle = Title.title(mainTitle, subTitle, times);

                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(animatedTitle);
                }

                titleTicks++;
            }
        }, 1L, 2L);

        // 2. Paper Native Global Region Scheduler: Musik-Song (Undertale Megalovania)
        playMegalovaniaSong();

        // 3. Paper Native Global Region Scheduler: Feuerwerk & Partikel-Spektakel
        victoryEffectsTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int ticks = 0;

            @Override
            public void accept(ScheduledTask task) {
                if (!matchEnded || winner == null || !winner.isOnline()) {
                    task.cancel();
                    victoryEffectsTask = null;
                    return;
                }

                Location wLoc = winner.getLocation();
                wLoc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, wLoc.clone().add(0, 1, 0), 20, 0.5, 1.0, 0.5, 0.2);
                wLoc.getWorld().spawnParticle(Particle.FIREWORK, wLoc.clone().add(0, 1, 0), 15, 0.5, 1.0, 0.5, 0.1);

                if (ticks % 2 == 0) {
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
        }, 1L, 10L);
    }

    private void playMegalovaniaSong() {
        int[] startClicks = new int[]{ 6, 4, 3, 2 };
        int[] noteClicks = new int[170];
        java.util.Arrays.fill(noteClicks, -1);

        int[] offsets = new int[]{ 0, 2, 5, 10, 17, 22, 27, 32, 35, 37 };
        int[] restClicks = new int[]{ -1, -1, 18, 13, 12, 11, 9, 6, 9, 11 };

        for (int pass = 0; pass < 4; pass++) {
            int baseTick = pass * 42;
            for (int i = 0; i < offsets.length; i++) {
                int tick = baseTick + offsets[i];
                int clicks = (i < 2) ? startClicks[pass] : restClicks[i];
                noteClicks[tick] = clicks;
            }
        }

        victoryMusicTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, new Consumer<ScheduledTask>() {
            int currentTick = 0;

            @Override
            public void accept(ScheduledTask task) {
                if (!matchEnded) {
                    task.cancel();
                    victoryMusicTask = null;
                    return;
                }

                int clicks = noteClicks[currentTick % 170];
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
        }, 1L, 1L);
    }

    public void togglePause(Player sender) {
        if (!matchStarted || matchEnded) {
            if (sender != null) {
                sender.sendMessage(MiniMessage.miniMessage().deserialize("<red>[OSOK] ❌ Es läuft aktuell kein aktives Match, das pausiert werden kann!</red>"));
            }
            return;
        }

        matchPaused = !matchPaused;

        Location spawnLoc = plugin.getWorldManager().getSpawnLocation();
        Location fallback = (spawnLoc != null) ? spawnLoc : new Location(plugin.getWorldManager().getOsokWorld(), 223.5, 48.0, 55.5);

        if (matchPaused) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, SoundCategory.MASTER, 1.0f, 0.8f);
                plugin.getEquipmentManager().clearBaseEquipment(p);
                p.teleportAsync(fallback);
            }

            broadcast(" ");
            broadcast("<red><b>=======================================</b></red>");
            broadcast("<red><b>   ⏸ MATCH PAUSIERT!   </b></red>");
            broadcast("<gray>  Spieler wurden in die Lobby teleportiert.</gray>");
            broadcast("<red><b>=======================================</b></red>");
            broadcast(" ");
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 1.0f, 1.2f);
                plugin.getEquipmentManager().giveOneShotEquipment(p);
                Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
                Location targetLoc = (randomLoc != null) ? randomLoc : fallback;
                p.teleportAsync(targetLoc);
            }

            broadcast(" ");
            broadcast("<green><b>=======================================</b></green>");
            broadcast("<green><b>   ▶ MATCH FORTGESETZT!   </b></green>");
            broadcast("<gray>  Spieler wurden in die Arena teleportiert!</gray>");
            broadcast("<green><b>=======================================</b></green>");
            broadcast(" ");
        }

        plugin.getScoreboardManager().updateAllScoreboards();
    }

    public void restartMatch(Player sender) {
        stopVictoryTasks();
        stopTimer();
        this.matchStarted = true;
        this.matchPaused = false;
        this.matchEnded = false;

        Location spawn = plugin.getWorldManager().getSpawnLocation();
        int count = 0;

        Title.Times times = Title.Times.times(Ticks.duration(10), Ticks.duration(40), Ticks.duration(10));
        Title newMatchTitle = Title.title(
                MiniMessage.miniMessage().deserialize("<green><b>NEUES MATCH!</b></green>"),
                MiniMessage.miniMessage().deserialize("<gray>OneShotOneKill gestartet</gray>"),
                times
        );

        for (Player p : Bukkit.getOnlinePlayers()) {
            Location randomLoc = plugin.getArenaManager().getRandomArenaLocation();
            Location targetLoc = (randomLoc != null) ? randomLoc : spawn;
            if (targetLoc != null) {
                p.teleportAsync(targetLoc).thenAccept(success -> {
                    if (success && p.isOnline()) {
                        plugin.getEquipmentManager().giveOneShotEquipment(p);
                        p.showTitle(newMatchTitle);
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 0.7f, 1.2f);
                    }
                });
                count++;
            }
        }

        if (timeLimitSeconds > 0) {
            remainingSeconds = timeLimitSeconds;
            startTimer();
        }

        plugin.getScoreboardManager().updateAllScoreboards();

        broadcast(" ");
        broadcast("<green><b>=======================================</b></green>");
        broadcast("<yellow><b>   🚀 MATCH NEU GESTARTET!   </b></yellow>");
        broadcast("<gray>" + count + " Spieler wurden zufällig in der Arena platziert!</gray>");
        broadcast("<green><b>=======================================</b></green>");
    }

    public String formatTime(int totalSeconds) {
        int mins = totalSeconds / 60;
        int secs = totalSeconds % 60;
        return String.format("%02d:%02d", mins, secs);
    }

    private void broadcast(String message) {
        Bukkit.broadcast(MiniMessage.miniMessage().deserialize(message));
    }
}
