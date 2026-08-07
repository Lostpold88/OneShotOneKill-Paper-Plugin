package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.util.mini
import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import java.util.Locale
import java.util.UUID
import org.bukkit.Sound as BukkitSound

/**
 * Verwaltet Statistiken, Sidebar und Tabliste.
 *
 * **Performance:** Das Scoreboard wird pro Spieler **einmal** angelegt und danach nur noch
 * inhaltlich aktualisiert. Ein kompletter Neuaufbau pro Update (neues `Scoreboard`, neues
 * Objective, alle Zeilen neu geparst, alle Team-Entries neu gesetzt) war der teuerste Pfad im
 * Plugin - er lief bis zu fuenfmal pro Kill fuer jeden Online-Spieler.
 *
 * Statische Zeilen liegen als vorgeparste [Component]-Konstanten bereit, damit MiniMessage nicht
 * bei jedem Update erneut denselben String zerlegt.
 */
class ScoreboardManager(private val plugin: OneShotOneKill) {

    private val killsMap = mutableMapOf<UUID, Int>()
    private val deathsMap = mutableMapOf<UUID, Int>()
    private val streakMap = mutableMapOf<UUID, Int>()
    private val highestStreakMap = mutableMapOf<UUID, Int>()

    /** Eingesammelte Spezial-Items (Boden-Boxen, Killstreak- und Kopfgeld-Belohnungen). */
    private val itemsCollectedMap = mutableMapOf<UUID, Int>()

    /** Zurueckgelegte Strecke in Bloecken, gemessen vom AntiCampManager. */
    private val distanceMap = mutableMapOf<UUID, Double>()

    private val bountyTargets = mutableSetOf<UUID>()

    /** Pro Spieler ein dauerhaft wiederverwendetes Board. */
    private val boards = mutableMapOf<UUID, Scoreboard>()

    fun updateAllScoreboards() {
        val frozen = plugin.matchManager.isStatsPaused

        // Rangliste einmal berechnen statt pro Spieler erneut
        val ranking = sortedRanking()

        for (player in Bukkit.getOnlinePlayers()) {
            // Eingefroren (/osok pausestats): bestehende Boards bleiben unangetastet.
            // Wer erst jetzt verbindet, bekommt aber eines - sonst haette er keine Sidebar.
            if (frozen && player.uniqueId in boards) continue

            updateScoreboard(player, ranking)
            updateTabListName(player)
            player.sendPlayerListHeaderAndFooter(TAB_HEADER, TAB_FOOTER)
        }
    }

    /** Gibt das gecachte Board eines Spielers frei (bei Quit aufzurufen). */
    fun removePlayer(uuid: UUID) {
        boards.remove(uuid)
    }

    fun isBountyTarget(uuid: UUID): Boolean = uuid in bountyTargets

    fun removeBountyTarget(uuid: UUID): Boolean = bountyTargets.remove(uuid)

    fun updateScoreboard(player: Player) {
        updateScoreboard(player, sortedRanking())
    }

    private fun updateScoreboard(player: Player, ranking: List<Player>) {
        val objective = obtainObjective(player)
        applyLines(objective, buildLines(ranking))
        objective.scoreboard?.let { syncNameTagTeam(it) }
    }

    private fun sortedRanking(): List<Player> =
        Bukkit.getOnlinePlayers().sortedByDescending { getKills(it.uniqueId) }

    /**
     * Holt das Board des Spielers oder legt es einmalig an. Danach wird es nur noch befuellt, nicht
     * mehr neu erzeugt.
     */
    private fun obtainObjective(player: Player): Objective {
        val board = boards.getOrPut(player.uniqueId) { Bukkit.getScoreboardManager().newScoreboard }

        val objective = board.getObjective(OBJECTIVE_NAME)
            ?: board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, TITLE).apply {
                displaySlot = DisplaySlot.SIDEBAR
                numberFormat(NumberFormat.blank())
            }

        // Nur setzen, wenn der Spieler noch ein anderes Board hat - sonst unnoetige Pakete
        if (board != player.scoreboard) {
            player.scoreboard = board
        }
        return objective
    }

    private fun buildLines(ranking: List<Player>): List<Component> {
        val lines = ArrayList<Component>(MAX_LINES)
        lines += SEPARATOR

        val match = plugin.matchManager
        if (!match.isMatchEnded) {
            when {
                match.hasKillLimit() -> {
                    lines += "<yellow><b>🎯 MATCH ZIEL:</b></yellow> <white>${match.killLimit} Kills</white>".mini()
                    lines += SEPARATOR
                }

                match.timeLimitSeconds > 0 -> {
                    val shownSeconds =
                        if (match.isMatchStarted) match.remainingSeconds else match.timeLimitSeconds
                    lines += ("<yellow><b>⏱ VERBLEIBEND:</b></yellow> " +
                        "<white>${match.formatTime(shownSeconds)}</white>").mini()
                    lines += SEPARATOR
                }
            }
        }

        lines += HEADING_RANKING

        for ((index, player) in ranking.take(MAX_RANKING_ENTRIES).withIndex()) {
            // Platz fuer den abschliessenden Trenner freihalten
            if (lines.size >= MAX_LINES - 1) break
            lines += rankingLine(index, player)
        }

        if (ranking.isEmpty()) {
            lines += NO_PLAYERS
        }

        lines += SEPARATOR
        return lines
    }

    private fun rankingLine(index: Int, player: Player): Component {
        val kills = getKills(player.uniqueId)
        val streak = getStreak(player.uniqueId)
        val highest = getHighestStreak(player.uniqueId)
        val kd = getKDRatio(player.uniqueId)

        val color = RANK_COLORS.getOrElse(index) { "white" }
        val bountyTag = if (isBountyTarget(player.uniqueId)) "<yellow>[👑] </yellow>" else ""

        return ("<$color>#${index + 1} </$color>$bountyTag<white>${player.name}</white> " +
            "<gray>»</gray> <green>${kills}K</green> <gray>|</gray> <aqua>$kd</aqua> " +
            "<gray>|</gray> <yellow>⚡$streak</yellow> <gold>(★$highest)</gold>").mini()
    }

    /**
     * Schreibt die Zeilen in feste Entry-Schluessel. Ueberzaehlige Zeilen aus einem vorherigen
     * Update werden entfernt, statt das Board zu verwerfen.
     */
    private fun applyLines(objective: Objective, lines: List<Component>) {
        lines.forEachIndexed { index, line ->
            // Freie Textzeilen brauchen den String-Entry: getScore(OfflinePlayer)/getScoreFor(Entity)
            // taugen dafuer nicht. Der Entry ist ein unsichtbarer Schluessel, die sichtbare Zeile
            // entsteht ueber Score#customName - das ist die aktuelle API, kein Legacy-Rueckfall.
            objective.getScore(LINE_ENTRY_PREFIX + index).apply {
                score = lines.size - index
                customName(line)
                numberFormat(NumberFormat.blank())
            }
        }

        val board = objective.scoreboard ?: return
        for (index in lines.size until MAX_LINES) {
            val entry = LINE_ENTRY_PREFIX + index
            if (objective.getScore(entry).isScoreSet) {
                board.resetScores(entry)
            }
        }
    }

    /** Blendet Nametags aus. Entries werden nur bei Aenderung angefasst, nicht bei jedem Update. */
    private fun syncNameTagTeam(board: Scoreboard) {
        val team = board.getTeam(NAMETAG_TEAM)
            ?: board.registerNewTeam(NAMETAG_TEAM).apply {
                setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
            }

        val online = Bukkit.getOnlinePlayers().map { it.name }.toSet()
        online.filterNot { team.hasEntry(it) }.forEach { team.addEntry(it) }
        team.entries.toList().filterNot { it in online }.forEach { team.removeEntry(it) }
    }

    fun resetAllStats() {
        killsMap.clear()
        deathsMap.clear()
        streakMap.clear()
        highestStreakMap.clear()
        itemsCollectedMap.clear()
        distanceMap.clear()
        bountyTargets.clear()
        updateAllScoreboards()
    }

    /** Kyori Component Tabliste: Anzeigename des Spielers inkl. Live-Stats. */
    fun updateTabListName(player: Player) {
        val playerId = player.uniqueId
        val bountyTag = if (isBountyTarget(playerId)) "<yellow>[👑] </yellow>" else ""

        player.playerListName(
            ("$bountyTag<white>${player.name}</white>" +
                " <gray>|</gray> <green>K: ${getKills(playerId)}</green>" +
                " <gray>|</gray> <red>D: ${getDeaths(playerId)}</red>" +
                " <gray>|</gray> <aqua>K/D: ${getKDRatio(playerId)}</aqua>" +
                " <gray>|</gray> <yellow>⚡${getStreak(playerId)}</yellow>" +
                " <gold>(★${getHighestStreak(playerId)})</gold>").mini()
        )
    }

    fun updateTabListHeaderFooter(player: Player) {
        player.sendPlayerListHeaderAndFooter(TAB_HEADER, TAB_FOOTER)
    }

    // ------------------------------------------------------------------
    // Statistiken. Bewusst OHNE internes updateAllScoreboards: Der Aufrufer
    // aktualisiert einmal, nachdem alle Werte gesetzt sind.
    // ------------------------------------------------------------------

    fun addKill(uuid: UUID): Int {
        val kills = getKills(uuid) + 1
        killsMap[uuid] = kills
        return kills
    }

    fun addDeath(uuid: UUID): Int {
        val deaths = getDeaths(uuid) + 1
        deathsMap[uuid] = deaths
        return deaths
    }

    fun addStreak(uuid: UUID): Int {
        val streak = getStreak(uuid) + 1
        streakMap[uuid] = streak

        if (streak > getHighestStreak(uuid)) {
            highestStreakMap[uuid] = streak
        }

        if (streak == BOUNTY_STREAK) {
            bountyTargets.add(uuid)
            val player = Bukkit.getPlayer(uuid)
            val name = player?.name ?: "Ein Spieler"
            Bukkit.broadcast(
                ("<red><b>[👑 KOPFGELD]</b> <yellow><b>$name</b> hat eine <b>${BOUNTY_STREAK}er Streak!</b> " +
                    "Wer ihn tötet erhält 2 Bonus-Items!</yellow></red>").mini()
            )
            player?.let {
                it.world.strikeLightningEffect(it.location)
                it.playSound(
                    Sound.sound(BukkitSound.ENTITY_LIGHTNING_BOLT_THUNDER, Sound.Source.MASTER, 0.6f, 1.8f)
                )
            }
        }
        return streak
    }

    fun resetStreak(uuid: UUID) {
        streakMap[uuid] = 0
        bountyTargets.remove(uuid)
    }

    fun getKills(uuid: UUID): Int = killsMap[uuid] ?: 0

    fun getDeaths(uuid: UUID): Int = deathsMap[uuid] ?: 0

    fun getStreak(uuid: UUID): Int = streakMap[uuid] ?: 0

    fun getHighestStreak(uuid: UUID): Int = highestStreakMap[uuid] ?: 0

    /** Zaehlt eingesammelte Spezial-Items fuer die Match-Zusammenfassung. */
    fun addItemsCollected(uuid: UUID, amount: Int): Int {
        val total = getItemsCollected(uuid) + amount
        itemsCollectedMap[uuid] = total
        return total
    }

    fun getItemsCollected(uuid: UUID): Int = itemsCollectedMap[uuid] ?: 0

    /** Summiert zurueckgelegte Bloecke fuer die Match-Zusammenfassung. */
    fun addDistance(uuid: UUID, blocks: Double) {
        distanceMap[uuid] = getDistance(uuid) + blocks
    }

    fun getDistance(uuid: UUID): Double = distanceMap[uuid] ?: 0.0

    fun getKDRatio(uuid: UUID): String = String.format(Locale.US, "%.1f", getKDRatioValue(uuid))

    /** K/D als Zahl - fuer Vergleiche in der Match-Zusammenfassung. */
    fun getKDRatioValue(uuid: UUID): Double {
        val kills = getKills(uuid)
        val deaths = getDeaths(uuid)
        return if (deaths == 0) kills.toDouble() else kills.toDouble() / deaths.toDouble()
    }

    private companion object {
        const val OBJECTIVE_NAME = "oneshot"
        const val NAMETAG_TEAM = "no_nametag"

        /** Feste Entry-Schluessel; nie sichtbar, da die Zeile ueber Score#customName gerendert wird. */
        const val LINE_ENTRY_PREFIX = "osok_line_"

        const val MAX_LINES = 16
        const val MAX_RANKING_ENTRIES = 10

        /** Ab dieser Streak wird ein Kopfgeld auf den Spieler ausgesetzt. */
        const val BOUNTY_STREAK = 5

        // Vorgeparste Komponenten - MiniMessage laeuft dafuer nur einmal beim Klassenladen.
        val TITLE: Component = "<red><b>🎯 OSOK</b></red>".mini()
        val SEPARATOR: Component = "<gray>-------------------</gray>".mini()
        val HEADING_RANKING: Component = "<yellow><b>🏆 TOP RANKING:</b></yellow>".mini()
        val NO_PLAYERS: Component = "<gray>Keine Spieler online</gray>".mini()
        val TAB_HEADER: Component =
            "\n<red><b>🎯 OSOK</b></red> <gray>|</gray> <red>MATCH STATS</red>\n".mini()
        val TAB_FOOTER: Component = "\n<gray>Scoreboard & Leaderboard</gray>\n".mini()

        val RANK_COLORS = listOf("yellow", "gray", "red")
    }
}
