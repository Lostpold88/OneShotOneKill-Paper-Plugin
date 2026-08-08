package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import net.kyori.adventure.util.Ticks
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.Locale
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

    /** Gibt den After-Action-Report Zeile fuer Zeile aus. */
    private var victoryReportTask: ScheduledTask? = null

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
        // Holt auch Zuschauer aus dem Nuke-Finale zurueck in den Ueberlebensmodus
        plugin.nukeManager.clearAll()
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
        victoryReportTask?.cancel()
        victoryReportTask = null
    }

    /**
     * Das Kill-Ziel beendet das Match **nicht** mehr von selbst: Wer es erreicht, bekommt den
     * Nuke-Auslöser und beendet die Runde damit selbst (siehe [de.oneshotonekill.manager.NukeManager]).
     */
    fun checkKillWinner(killer: Player, currentKills: Int) {
        if (isMatchEnded || killLimit <= 0) return

        if (currentKills >= killLimit) {
            plugin.nukeManager.arm(killer, "$currentKills Kills")
        }
    }

    /**
     * Zeit abgelaufen: Der Fuehrende bekommt den Nuke-Auslöser. Das Match laeuft weiter, bis er ihn
     * benutzt - beendet wird die Runde ausschliesslich durch die Nuke.
     */
    fun triggerTimeLimitWinner() {
        if (isMatchEnded) return

        val leader = Bukkit.getOnlinePlayers().maxByOrNull { plugin.scoreboardManager.getKills(it.uniqueId) }

        if (leader != null) {
            plugin.nukeManager.arm(leader, "Zeit abgelaufen")
        } else {
            broadcast("<red>[OSOK] ⏱ Die Zeit ist abgelaufen! Kein Match-Ergebnis.</red>")
        }
    }

    /**
     * Gemeinsamer Anfang jedes Match-Endes: Kampf aus, Timer aus, Item-Wirkungen weg.
     *
     * Der `NukeManager` ruft das beim Abschuss - ab da soll niemand mehr kaempfen, waehrend das
     * Bombardement laeuft.
     */
    fun beginFinale() {
        isMatchEnded = true
        stopTimer()
        clearAllItemEffects()
    }

    /**
     * Das Ende einer Runde - im Stil eines After-Action-Reports.
     *
     * Bewusst kein Feuerwerk und kein Regenbogen: Nach einer Nuke, die die Arena eingeebnet hat,
     * waere Jahrmarkt der falsche Ton. Der Ablauf ist deshalb gebaut wie der Abspann eines
     * Shooters - erst Stille und Schwarzbild, dann der harte Einschlag mit dem Namen des Siegers,
     * danach der Bericht Zeile fuer Zeile.
     *
     * Aufgerufen wird das ausschliesslich aus dem Nuke-Finale: Kill-Ziel und Zeitablauf schalten nur
     * noch den Ausloeser frei, beendet wird eine Runde nur ueber die Nuke.
     */
    fun celebrateWinner(winner: Player) {
        beginFinale()
        stopVictoryTasks()

        val winnerKills = plugin.scoreboardManager.getKills(winner.uniqueId)

        // 1. Schwarzbild und Stille. Der Bildschirm ist leer, damit der Einschlag danach sitzt.
        Bukkit.getOnlinePlayers().forEach {
            it.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, BLACKOUT_TICKS + 20, 0, false, false))
        }
        Bukkit.getServer().showTitle(
            Title.title(
                Component.empty(),
                Component.empty(),
                Title.Times.times(Ticks.duration(10), Ticks.duration(BLACKOUT_TICKS.toLong()), Ticks.duration(10)),
            )
        )

        // 2. Der Einschlag: Name des Siegers, ein tiefer Schlag, sonst nichts.
        Bukkit.getGlobalRegionScheduler().runDelayed(
            plugin,
            { revealWinner(winner, winnerKills) },
            BLACKOUT_TICKS.toLong(),
        )
    }

    /** Der harte Schnitt: Titel, tiefer Schlag - und danach laeuft der Bericht an. */
    private fun revealWinner(winner: Player, winnerKills: Int) {
        Bukkit.getServer().showTitle(
            Title.title(
                "<white><b>MISSION ABGESCHLOSSEN</b></white>".mini(),
                "<gold><b>${winner.name}</b></gold> <gray>hat die Runde gewonnen</gray>".mini(),
                Title.Times.times(Ticks.duration(5), Ticks.duration(70), Ticks.duration(20)),
            )
        )

        val server = Bukkit.getServer()
        server.playSound(Sound.sound(BukkitSound.BLOCK_BEACON_DEACTIVATE, Sound.Source.MASTER, 1.0f, 0.5f))
        server.playSound(Sound.sound(BukkitSound.ENTITY_WITHER_SPAWN, Sound.Source.MASTER, 0.6f, 0.5f))

        broadcast(" ")
        broadcast("<dark_gray><b>=======================================</b></dark_gray>")
        broadcast("<white><b>   MISSION ABGESCHLOSSEN   </b></white>")
        broadcast(
            "<gray>  Sieger: <gold><b>${winner.name}</b></gold> " +
                "<dark_gray>»</dark_gray> <white>$winnerKills Kills</white></gray>"
        )
        broadcast("<dark_gray><b>=======================================</b></dark_gray>")

        startAfterActionReport()
    }

    /**
     * Der Bericht laeuft Zeile fuer Zeile ein, nicht als Block.
     *
     * Das ist der ganze Trick am Abspann eines Shooters: Jede Zeile bekommt einen eigenen Moment.
     * Die Statistiken stehen zu diesem Zeitpunkt noch - zurueckgesetzt wird erst beim naechsten
     * `/osok start`.
     */
    private fun startAfterActionReport() {
        val lines = plugin.matchSummaryManager.summaryLines()
        if (lines.isEmpty()) {
            broadcast("<gray>  Neues Match starten mit: <yellow>/osok start</yellow></gray>")
            broadcast(" ")
            return
        }

        var index = 0
        victoryReportTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            { task ->
                if (!isMatchEnded || index >= lines.size) {
                    task.cancel()
                    victoryReportTask = null
                    if (isMatchEnded) {
                        broadcast("<gray>  Neues Match starten mit: <yellow>/osok start</yellow></gray>")
                        broadcast(" ")
                    }
                    return@runAtFixedRate
                }

                broadcast(lines[index])
                Bukkit.getServer().playSound(
                    Sound.sound(BukkitSound.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 0.7f, 1.4f)
                )
                index++
            },
            REPORT_LINE_DELAY_TICKS,
            REPORT_LINE_DELAY_TICKS,
        )
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
        // Muss vor dem Teleport laufen: Holt die Zuschauer des letzten Finales zurueck ins Spiel
        plugin.nukeManager.clearAll()
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

        /** Schwarzbild vor dem Siegertitel - die Stille, die den Einschlag traegt. */
        const val BLACKOUT_TICKS = 45

        /** Abstand zwischen zwei Zeilen des After-Action-Reports. */
        const val REPORT_LINE_DELAY_TICKS = 12L
    }
}
