package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player

class ArenaManager(private val plugin: OneShotOneKill) {

    fun getRandomArenaLocation(): Location? {
        val osokWorld = plugin.worldManager.osokWorld ?: return null

        plugin.worldManager.activeMapConfig.getRandomArenaLocation(osokWorld)?.let { return it }

        return plugin.worldManager.spawnLocation ?: osokWorld.spawnLocation
    }

    /**
     * Bestmoeglicher Respawn-Punkt: moeglichst weit weg vom Todespunkt **und** vom naechsten
     * Gegner.
     *
     * Statt den erstbesten ausreichend entfernten Platz zu nehmen, werden [RESPAWN_CANDIDATES]
     * zufaellige Kandidaten gesammelt und bewertet; der beste gewinnt. Weil die Kandidatenmenge
     * jedes Mal neu ausgewuerfelt wird, bleibt der Spawn trotzdem unvorhersehbar - eine reine
     * "maximaler Abstand"-Suche wuerde die Spieler dagegen immer in dieselbe Ecke schicken, die man
     * dann bequem zucampen kann.
     *
     * @param respawning der Spieler, der zurueckkommt - zaehlt nicht als eigener Gegner
     * @param deathLoc Todespunkt, oder `null` wenn unbekannt
     */
    fun getSafestArenaLocation(respawning: Player?, deathLoc: Location?): Location? {
        val osokWorld = plugin.worldManager.osokWorld ?: return null
        val activeMap = plugin.worldManager.activeMapConfig

        val candidates = activeMap.collectArenaSpots(osokWorld, RESPAWN_CANDIDATES)
        if (candidates.isEmpty()) return fallbackSpawn(osokWorld)

        val enemies = collectEnemyPositions(respawning, osokWorld)
        val relevantDeathLoc = deathLoc?.takeIf { osokWorld == it.world }

        return candidates.maxByOrNull { rateSpawn(it, relevantDeathLoc, enemies) }
    }

    /**
     * Bewertet einen Spawnpunkt. Hoeher ist besser, das Ergebnis liegt zwischen 0 und 1.
     *
     * Der Abstand zum naechsten Gegner wiegt schwerer als der zum Todespunkt: Der Todespunkt ist
     * nur ein Anhaltspunkt dafuer, wo der Killer stand - wo die Gegner **jetzt** stehen, ist die
     * genauere Information. Beide Abstaende werden gedeckelt, weil jenseits der Deckel kein
     * spuerbarer Sicherheitsgewinn mehr entsteht und sonst nur noch die Kartenecken gewinnen.
     */
    private fun rateSpawn(candidate: Location, deathLoc: Location?, enemies: List<Location>): Double {
        val nearestEnemy = enemies.fold(ENEMY_DISTANCE_CAP) { nearest, enemy ->
            minOf(nearest, candidate.distance(enemy))
        }
        val enemyScore = nearestEnemy / ENEMY_DISTANCE_CAP

        val deathScore = deathLoc
            ?.let { minOf(candidate.distance(it), DEATH_DISTANCE_CAP) / DEATH_DISTANCE_CAP }
            ?: 1.0

        return enemyScore * ENEMY_WEIGHT + deathScore * DEATH_WEIGHT
    }

    /**
     * Positionen aller Gegner in der Arena.
     *
     * Bewusst ein einzelner Durchlauf ueber die Online-Spieler statt `Location#getNearbyPlayers`:
     * Gesucht sind nicht die Spieler nahe **einem** Punkt, sondern alle - jeder Kandidat wird
     * anschliessend gegen dieselbe Liste geprueft. Eine Umkreissuche pro Kandidat waere hier der
     * teurere Weg.
     */
    private fun collectEnemyPositions(respawning: Player?, osokWorld: World): List<Location> =
        Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != respawning?.uniqueId }
            .filter { osokWorld == it.world }
            .map { it.location }
            .filter { isInArenaArea(it) }

    private fun fallbackSpawn(osokWorld: World): Location =
        plugin.worldManager.spawnLocation ?: osokWorld.spawnLocation

    /**
     * Spawnpunkt fuer Boden-Items: ausschliesslich auf dem Arena-Boden.
     *
     * Liefert `null`, wenn kein Bodenplatz gefunden wurde - dann wird kein Item gespawnt, statt es
     * an einem falschen Ort (z. B. in der Lobby) abzulegen.
     */
    fun getRandomFloorLocation(): Location? {
        val osokWorld = plugin.worldManager.osokWorld ?: return null
        return plugin.worldManager.activeMapConfig.getRandomFloorLocation(osokWorld)
    }

    /**
     * Ist der Spieler unter die Welt gefallen und muss gerettet werden?
     *
     * Ausserhalb der Arena wird jeder Schaden gecancelt - auch Void-Schaden. Ohne diese Pruefung
     * faellt ein Spieler, der neben die Lobby-Plattform tritt, endlos weiter.
     */
    fun isBelowWorld(loc: Location?): Boolean {
        if (loc == null || !isInOsokWorld(loc)) return false
        return loc.y < plugin.worldManager.activeMapConfig.voidRescueY
    }

    /**
     * Prueft die Welt-Zugehoerigkeit gegen die tatsaechlich aktive OSOK-Welt.
     *
     * Ein Vergleich gegen einen festen Weltnamen ist nicht moeglich, da die Welten je nach aktiver
     * Map OSOK_Standard bzw. OSOK_DustPvP heissen.
     */
    fun isInArenaArea(loc: Location?): Boolean {
        if (!isInOsokWorld(loc)) return false
        return plugin.worldManager.activeMapConfig.isInArenaArea(loc)
    }

    private fun isInOsokWorld(loc: Location?): Boolean {
        val locWorld = loc?.world ?: return false
        return locWorld == plugin.worldManager.osokWorld
    }

    private companion object {
        /** So viele Spawnpunkte werden pro Respawn ausgewuerfelt und bewertet. */
        const val RESPAWN_CANDIDATES = 24

        /** Ab diesem Abstand zum naechsten Gegner bringt mehr Abstand keinen Vorteil mehr. */
        const val ENEMY_DISTANCE_CAP = 32.0

        /** Dasselbe fuer den Todespunkt - er wiegt weniger schwer als die Gegnerposition. */
        const val DEATH_DISTANCE_CAP = 24.0

        const val ENEMY_WEIGHT = 0.7
        const val DEATH_WEIGHT = 0.3
    }
}
