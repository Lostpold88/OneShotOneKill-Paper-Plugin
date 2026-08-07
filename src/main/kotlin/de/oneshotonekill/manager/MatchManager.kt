package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.Fireworks
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.title.Title
import net.kyori.adventure.util.Ticks
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.command.CommandSender
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sin
import org.bukkit.Sound as BukkitSound

class MatchManager(private val plugin: OneShotOneKill) {

    var killLimit: Int = 0
        private set

    var timeLimitSeconds: Int = 0
        private set

    var remainingSeconds: Int = 0
        private set

    var isMatchStarted: Boolean = false
        private set

    var isMatchPaused: Boolean = false
        private set

    var isMatchEnded: Boolean = false
        private set

    /**
     * Statistik-Erfassung eingefroren (/osok pausestats). Kills, Tode und Streaks werden nicht
     * gezaehlt, der Match-Timer laeuft nicht weiter und das Scoreboard aktualisiert nicht mehr.
     * Gekaempft werden darf weiterhin - die Treffer zaehlen nur nicht.
     */
    var isStatsPaused: Boolean = false
        private set

    private var timerTask: ScheduledTask? = null
    private var victoryMusicTask: ScheduledTask? = null
    private var victoryEffectsTask: ScheduledTask? = null
    private var victoryTitleTask: ScheduledTask? = null

    /**
     * Schaltet die Statistik-Erfassung um. Im eingefrorenen Zustand zaehlen Kills, Tode und Streaks
     * nicht, der Timer laeuft nicht weiter und das Scoreboard bleibt stehen. Anders als /osok pause
     * bleibt das Match spielbar - es wird nur nichts gewertet.
     */
    fun toggleStatsPause(sender: CommandSender?) {
        isStatsPaused = !isStatsPaused

        if (isStatsPaused) {
            broadcast(
                "<gold>[OSOK] ⏸ <b>STATISTIK EINGEFROREN!</b> <gray>Kills und Zeit werden nicht mehr " +
                    "gewertet, das Scoreboard bleibt stehen.</gray></gold>"
            )
            Bukkit.getServer().playSound(
                Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 1.0f, 0.7f)
            )
        } else {
            broadcast(
                "<green>[OSOK] ▶ <b>STATISTIK LÄUFT WEITER!</b> <gray>Kills und Zeit werden wieder " +
                    "gewertet.</gray></green>"
            )
            Bukkit.getServer().playSound(
                Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.5f)
            )
            // Nach dem Auftauen einmal nachziehen, damit die Anzeige wieder stimmt
            plugin.scoreboardManager.updateAllScoreboards()
        }

        sender?.sendMessage(
            if (isStatsPaused) {
                "<gray>[OSOK] Mit <yellow>/osok pausestats</yellow> wieder fortsetzen.</gray>".mini()
            } else {
                "<gray>[OSOK] Statistik-Erfassung ist wieder aktiv.</gray>".mini()
            }
        )
    }

    fun hasKillLimit(): Boolean = killLimit > 0

    fun hasTimeLimit(): Boolean = timeLimitSeconds > 0 && remainingSeconds > 0

    fun setKillLimit(kills: Int) {
        if (kills <= 0) {
            resetLimits()
            return
        }
        clearLimits()
        killLimit = kills

        plugin.scoreboardManager.updateAllScoreboards()
        broadcast(
            "<yellow>[OSOK] 🎯 Match-Ziel gesetzt: <green><b>$kills Kills</b></green> " +
                "<gray>(wird bei /start aktiv)!</gray></yellow>"
        )
    }

    fun setTimeLimitMinutes(minutes: Int) {
        if (minutes <= 0) {
            resetLimits()
            return
        }
        applyTimeLimit(minutes * 60)
        broadcast(
            "<yellow>[OSOK] ⏱ Match-Zeit gesetzt: <green><b>$minutes Minuten</b></green> " +
                "<gray>(wird bei /start aktiv)!</gray></yellow>"
        )
    }

    fun setTimeLimitSeconds(seconds: Int) {
        if (seconds <= 0) {
            resetLimits()
            return
        }
        applyTimeLimit(seconds)
        broadcast(
            "<yellow>[OSOK] ⏱ Match-Zeit gesetzt: <green><b>${formatTime(seconds)}</b></green> " +
                "<gray>(${seconds}s) (wird bei /start aktiv)!</gray></yellow>"
        )
    }

    private fun applyTimeLimit(seconds: Int) {
        clearLimits()
        timeLimitSeconds = seconds
        remainingSeconds = seconds

        if (isMatchStarted) {
            startTimer()
        }
        plugin.scoreboardManager.updateAllScoreboards()
    }

    /** Gemeinsamer Nullstand von Kill- und Zeitlimit; stoppt laufende Tasks. */
    private fun clearLimits() {
        stopTimer()
        stopVictoryTasks()
        killLimit = 0
        timeLimitSeconds = 0
        remainingSeconds = 0
        isMatchEnded = false
    }

    fun resetLimits() {
        clearLimits()
        plugin.scoreboardManager.updateAllScoreboards()
        broadcast("<yellow>[OSOK] 🔄 Match-Limits (Kills & Zeit) wurden deaktiviert.</yellow>")
    }

    /**
     * Beendet ein laufendes Match ohne Sieger-Zeremonie und ohne Broadcast. Konfigurierte Limits
     * (Kills/Zeit) bleiben erhalten und gelten beim naechsten /osok start. Wird u. a. beim
     * Map-Wechsel verwendet.
     */
    fun stopMatch() {
        stopTimer()
        stopVictoryTasks()
        isMatchStarted = false
        isMatchPaused = false
        isStatsPaused = false
        isMatchEnded = false
        remainingSeconds = timeLimitSeconds
    }

    /**
     * Nimmt alle laufenden Item-Wirkungen zurueck: Frost-Traps, Unsichtbarkeiten, Gleitfluege,
     * Singularitaeten, Camping-Markierungen und Leuchtrahmen.
     *
     * Wird bei Match-Start und Match-Ende gerufen. Ohne diesen Durchgang blieben Druckplatten in
     * der Map liegen und Spieler unsichtbar oder markiert in die naechste Runde hinein.
     */
    private fun clearAllItemEffects() {
        plugin.specialItemListener.clearAllTraps()
        plugin.specialItemListener.clearAllVanish()
        plugin.tacticalItemsManager.clearAll()
        plugin.antiCampManager.reset()
        plugin.glowManager.clearAll()
    }

    /**
     * Beendet das laufende Spiel vollstaendig: Match stoppen, Statistiken zuruecksetzen,
     * Ausruestung einziehen und alle Spieler in die Lobby der aktiven Map teleportieren.
     */
    fun stopGame(sender: Player?) {
        val lobbyLoc = plugin.worldManager.spawnLocation
        if (lobbyLoc == null) {
            sender?.sendMessage("<red>[OSOK] ❌ Die Arena-Welt ist aktuell nicht geladen!</red>".mini())
            return
        }

        stopMatch()
        plugin.killstreakManager.clearAllGroundItems()
        plugin.stealthBomberManager.clearAll()
        plugin.explosivesManager.clearAll()
        clearAllItemEffects()

        // Zusammenfassung VOR dem Zuruecksetzen - danach sind die Statistiken leer
        plugin.matchSummaryManager.broadcastSummary()
        plugin.scoreboardManager.resetAllStats()

        for (player in Bukkit.getOnlinePlayers()) {
            player.fireTicks = 0
            player.freezeTicks = 0
            player.activePotionEffects.toList().forEach { player.removePotionEffect(it.type) }
            plugin.equipmentManager.clearBaseEquipment(player)
            player.teleportAsync(lobbyLoc).thenAccept { success ->
                if (success && player.isOnline) {
                    player.playSound(
                        Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_DIDGERIDOO, Sound.Source.MASTER, 1.0f, 0.8f)
                    )
                }
            }
        }

        plugin.scoreboardManager.updateAllScoreboards()

        broadcast(" ")
        broadcast("<red><b>=======================================</b></red>")
        broadcast("<red><b>   ⏹ SPIEL BEENDET!   </b></red>")
        broadcast("<gray>  Statistiken zurückgesetzt, alle Spieler in der Lobby.</gray>")
        broadcast("<gray>  Neues Match starten mit: <yellow>/osok start</yellow></gray>")
        broadcast("<red><b>=======================================</b></red>")
        broadcast(" ")
    }

    private fun startTimer() {
        stopTimer()
        timerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (isMatchEnded) {
                    task.cancel()
                    timerTask = null
                    return@runAtFixedRate
                }

                // Eingefroren durch /osok pause ODER /osok pausestats: Zeit laeuft nicht weiter
                if (isMatchPaused || isStatsPaused) return@runAtFixedRate

                remainingSeconds--
                plugin.scoreboardManager.updateAllScoreboards()

                when {
                    remainingSeconds <= 0 -> {
                        task.cancel()
                        timerTask = null
                        triggerTimeLimitWinner()
                    }

                    remainingSeconds in COUNTDOWN_ANNOUNCEMENTS || remainingSeconds <= 5 -> {
                        broadcast(
                            "<red>[OSOK] ⏱ Noch <yellow>${formatTime(remainingSeconds)}</yellow> Verbleibend!</red>"
                        )
                        Bukkit.getServer().playSound(
                            Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1.0f, 1.8f)
                        )
                    }
                }
            },
            20L,
            20L,
        )
    }

    private fun stopTimer() {
        timerTask?.cancel()
        timerTask = null
    }

    fun stopVictoryTasks() {
        victoryMusicTask?.cancel()
        victoryMusicTask = null
        victoryEffectsTask?.cancel()
        victoryEffectsTask = null
        victoryTitleTask?.cancel()
        victoryTitleTask = null
    }

    fun checkKillWinner(killer: Player, currentKills: Int) {
        if (isMatchEnded || killLimit <= 0) return

        if (currentKills >= killLimit) {
            celebrateWinner(killer)
        }
    }

    fun triggerTimeLimitWinner() {
        if (isMatchEnded) return

        val winner = Bukkit.getOnlinePlayers().maxByOrNull { plugin.scoreboardManager.getKills(it.uniqueId) }

        if (winner != null) {
            celebrateWinner(winner)
        } else {
            broadcast("<red>[OSOK] ⏱ Die Zeit ist abgelaufen! Keines Match-Ergebnis.</red>")
        }
    }

    fun celebrateWinner(winner: Player) {
        isMatchEnded = true
        stopTimer()
        clearAllItemEffects()

        val winnerKills = plugin.scoreboardManager.getKills(winner.uniqueId)

        // Initialer Sound fuer alle Spieler
        Bukkit.getServer().playSound(
            Sound.sound(BukkitSound.UI_TOAST_CHALLENGE_COMPLETE, Sound.Source.MASTER, 1.0f, 1.0f)
        )

        // Chat-Ankuendigung mit Regenbogen-Gradient
        broadcast(" ")
        broadcast("<gradient:#ff5555:#ffff55:#55ff55:#55ffff:#5555ff:#ff55ff><b>=======================================</b></gradient>")
        broadcast("<rainbow><b>   🏆 MATCH BEENDET - MATCH GEWINNER!   </b></rainbow>")
        broadcast(
            "<white>  Gewinner: <yellow><b>${winner.name}</b></yellow> " +
                "<gray>mit <green><b>$winnerKills Kills</b></green>!</gray></white>"
        )
        broadcast("<gray>  Starte ein neues Match mit: <yellow>/start</yellow></gray>")
        broadcast("<gradient:#ff5555:#ffff55:#55ff55:#55ffff:#5555ff:#ff55ff><b>=======================================</b></gradient>")
        broadcast(" ")

        // Zusammenfassung direkt nach dem Sieger - die Statistiken stehen hier noch
        plugin.matchSummaryManager.broadcastSummary()

        // Gewinner-Effekte
        winner.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, WINNER_EFFECT_TICKS, 0))
        winner.addPotionEffect(PotionEffect(PotionEffectType.SPEED, WINNER_EFFECT_TICKS, 2))

        // Megalovania-Endlosschleife, Regenbogen-Title-Animation & Sieger-Spektakel starten!
        playWinnerCelebrationLoop(winner, winnerKills)
    }

    private fun playWinnerCelebrationLoop(winner: Player, winnerKills: Int) {
        stopVictoryTasks()

        var titleTicks = 0

        // 1. Paper Native Global Region Scheduler: Animierter Regenbogen-Titel auf dem Bildschirm!
        victoryTitleTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (!isMatchEnded) {
                    task.cancel()
                    victoryTitleTask = null
                    return@runAtFixedRate
                }

                val rainbowPhase = titleTicks * 3
                val gradPhase = String.format(Locale.US, "%.2f", sin(titleTicks * 0.15).toFloat())

                val mainTitle = "<rainbow:$rainbowPhase><b>🏆 GEWINNER! 🏆</b></rainbow>".mini()
                val subTitle = (
                    "<gradient:#ff5555:#ffff55:#55ff55:#55ffff:#5555ff:#ff55ff:$gradPhase>" +
                        "<b>${winner.name}</b></gradient> <gray>hat gewonnen! " +
                        "(<green>$winnerKills Kills</green>)</gray>"
                    ).mini()

                val times = Title.Times.times(Ticks.duration(0), Ticks.duration(30), Ticks.duration(10))

                // Server ist eine ForwardingAudience - erreicht alle Spieler ohne Iteration
                Bukkit.getServer().showTitle(Title.title(mainTitle, subTitle, times))

                titleTicks++
            },
            1L,
            2L,
        )

        // 2. Paper Native Global Region Scheduler: Musik-Song (Undertale Megalovania)
        playMegalovaniaSong()

        // 3. Paper Native Global Region Scheduler: Feuerwerk & Partikel-Spektakel
        var effectTicks = 0
        victoryEffectsTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (!isMatchEnded || !winner.isOnline) {
                    task.cancel()
                    victoryEffectsTask = null
                    return@runAtFixedRate
                }

                val world = winner.location.world ?: return@runAtFixedRate
                val effectLoc = winner.location.clone().add(0.0, 1.0, 0.0)
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, effectLoc, 20, 0.5, 1.0, 0.5, 0.2)
                world.spawnParticle(Particle.FIREWORK, effectLoc, 15, 0.5, 1.0, 0.5, 0.1)

                if (effectTicks % 2 == 0) {
                    // Paper spawn(..., Consumer): Effekt und Staerke stehen fest, BEVOR die Rakete
                    // in der Welt erscheint. Kein Cast, kein Meta-Nachtrag.
                    world.spawn(effectLoc, Firework::class.java) { firework ->
                        // Paper DataComponents statt FireworkMeta: Effekt und Flugdauer stehen als
                        // FIREWORKS-Komponente am Raketen-Stack, den die Entity uebernimmt.
                        firework.setItem(
                            ItemStack.of(Material.FIREWORK_ROCKET).apply {
                                setData(
                                    DataComponentTypes.FIREWORKS,
                                    Fireworks.fireworks(
                                        listOf(
                                            FireworkEffect.builder()
                                                .withColor(Color.YELLOW, Color.ORANGE, Color.PURPLE)
                                                .withFade(Color.WHITE)
                                                .with(FireworkEffect.Type.BALL_LARGE)
                                                .withFlicker()
                                                .build()
                                        ),
                                        1,
                                    ),
                                )
                            }
                        )
                    }
                }

                effectTicks++
            },
            1L,
            10L,
        )
    }

    private fun playMegalovaniaSong() {
        val noteClicks = buildMegalovaniaTrack()
        var currentTick = 0

        victoryMusicTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (!isMatchEnded) {
                    task.cancel()
                    victoryMusicTask = null
                    return@runAtFixedRate
                }

                val clicks = noteClicks[currentTick % noteClicks.size]
                if (clicks >= 0) {
                    val pitch = 2.0.pow((clicks - 12.0) / 12.0).toFloat()
                    // Laeuft jeden Tick: ueber die Server-Audience statt drei Aufrufe pro Spieler
                    val server = Bukkit.getServer()
                    server.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_BIT, Sound.Source.MASTER, 1.0f, pitch))
                    server.playSound(Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 0.8f, pitch))
                    server.playSound(
                        Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 1.0f, pitch * 0.5f)
                    )
                }

                currentTick++
            },
            1L,
            1L,
        )
    }

    /**
     * Baut die Notenspur: `-1` bedeutet Pause, jeder andere Wert ist die Anzahl der
     * Notenblock-Klicks, aus der sich die Tonhoehe ergibt.
     */
    private fun buildMegalovaniaTrack(): IntArray {
        val startClicks = intArrayOf(6, 4, 3, 2)
        val offsets = intArrayOf(0, 2, 5, 10, 17, 22, 27, 32, 35, 37)
        val restClicks = intArrayOf(-1, -1, 18, 13, 12, 11, 9, 6, 9, 11)

        val track = IntArray(SONG_LENGTH_TICKS) { -1 }
        for (pass in 0 until SONG_PASSES) {
            val baseTick = pass * SONG_PASS_TICKS
            offsets.forEachIndexed { index, offset ->
                track[baseTick + offset] = if (index < 2) startClicks[pass] else restClicks[index]
            }
        }
        return track
    }

    fun togglePause(sender: Player?) {
        if (!isMatchStarted || isMatchEnded) {
            sender?.sendMessage(
                "<red>[OSOK] ❌ Es läuft aktuell kein aktives Match, das pausiert werden kann!</red>".mini()
            )
            return
        }

        val lobbyLoc = plugin.worldManager.spawnLocation
        if (lobbyLoc == null) {
            sender?.sendMessage("<red>[OSOK] ❌ Die Arena-Welt ist aktuell nicht geladen!</red>".mini())
            return
        }

        isMatchPaused = !isMatchPaused

        if (isMatchPaused) {
            Bukkit.getServer().playSound(
                Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_DIDGERIDOO, Sound.Source.MASTER, 1.0f, 0.8f)
            )
            for (player in Bukkit.getOnlinePlayers()) {
                plugin.equipmentManager.clearBaseEquipment(player)
                player.teleportAsync(lobbyLoc)
            }

            broadcast(" ")
            broadcast("<red><b>=======================================</b></red>")
            broadcast("<red><b>   ⏸ MATCH PAUSIERT!   </b></red>")
            broadcast("<gray>  Spieler wurden in die Lobby teleportiert.</gray>")
            broadcast("<red><b>=======================================</b></red>")
            broadcast(" ")
        } else {
            for (player in Bukkit.getOnlinePlayers()) {
                val targetLoc = plugin.arenaManager.getRandomArenaLocation() ?: lobbyLoc
                player.teleportAsync(targetLoc).thenAccept { success ->
                    if (success && player.isOnline) {
                        plugin.equipmentManager.giveOneShotEquipment(player)
                        player.playSound(
                            Sound.sound(BukkitSound.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1.0f, 1.2f)
                        )
                    }
                }
            }

            broadcast(" ")
            broadcast("<green><b>=======================================</b></green>")
            broadcast("<green><b>   ▶ MATCH FORTGESETZT!   </b></green>")
            broadcast("<gray>  Spieler wurden in die Arena teleportiert!</gray>")
            broadcast("<green><b>=======================================</b></green>")
            broadcast(" ")
        }

        plugin.scoreboardManager.updateAllScoreboards()
    }

    fun restartMatch() {
        stopVictoryTasks()
        stopTimer()
        isMatchStarted = true
        isMatchPaused = false
        isStatsPaused = false
        isMatchEnded = false

        // Standard-Item-Modus bei jedem Start: BOTH (Killstreak-Items + Boden-Item-Boxen).
        // Gilt unabhaengig von der aktiven Arena.
        plugin.killstreakManager.itemMode = KillstreakManager.ItemMode.BOTH

        // Jedes neue Match startet mit leerem Scoreboard
        plugin.scoreboardManager.resetAllStats()
        plugin.killstreakManager.clearAllGroundItems()
        plugin.stealthBomberManager.clearAll()
        plugin.explosivesManager.clearAll()
        clearAllItemEffects()

        val spawn = plugin.worldManager.spawnLocation

        val times = Title.Times.times(Ticks.duration(10), Ticks.duration(40), Ticks.duration(10))
        val newMatchTitle = Title.title(
            "<green><b>NEUES MATCH!</b></green>".mini(),
            "<gray>OneShotOneKill gestartet</gray>".mini(),
            times,
        )

        var count = 0
        for (player in Bukkit.getOnlinePlayers()) {
            val targetLoc = plugin.arenaManager.getRandomArenaLocation() ?: spawn ?: continue

            player.teleportAsync(targetLoc).thenAccept { success ->
                if (success && player.isOnline) {
                    plugin.equipmentManager.giveOneShotEquipment(player)
                    player.showTitle(newMatchTitle)
                    player.playSound(
                        Sound.sound(BukkitSound.ENTITY_ENDER_DRAGON_GROWL, Sound.Source.MASTER, 0.7f, 1.2f)
                    )
                }
            }
            count++
        }

        if (timeLimitSeconds > 0) {
            remainingSeconds = timeLimitSeconds
            startTimer()
        }

        plugin.scoreboardManager.updateAllScoreboards()

        broadcast(" ")
        broadcast("<green><b>=======================================</b></green>")
        broadcast("<yellow><b>   🚀 MATCH NEU GESTARTET!   </b></yellow>")
        broadcast("<gray>$count Spieler wurden zufällig in der Arena platziert!</gray>")
        broadcast("<green><b>=======================================</b></green>")
    }

    fun formatTime(totalSeconds: Int): String =
        String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)

    private fun broadcast(message: String) {
        Bukkit.broadcast(message.mini())
    }

    private companion object {
        /** Bei diesen Restsekunden gibt es eine Ansage (unterhalb von 5s ohnehin jede Sekunde). */
        val COUNTDOWN_ANNOUNCEMENTS = setOf(60, 30, 10)

        /** Leuchten und Tempo des Siegers: 6 Minuten. */
        const val WINNER_EFFECT_TICKS = 7200

        const val SONG_LENGTH_TICKS = 170
        const val SONG_PASSES = 4
        const val SONG_PASS_TICKS = 42
    }
}
