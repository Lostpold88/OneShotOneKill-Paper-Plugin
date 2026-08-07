package de.oneshotonekill.model

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import java.util.EnumSet
import kotlin.math.floor
import kotlin.random.Random

/**
 * Geometrie einer Arena: Grenzen, Lobby und die Suche nach begehbaren Punkten darin.
 *
 * Kennt bewusst nur die Geometrie - wo Spieler stehen, weiss die MapConfig nicht. Die Bewertung
 * von Spawnkandidaten macht der `ArenaManager`.
 */
class MapConfig(
    val name: String,
    val zipResource: String,
    var lobbyLocation: Location?,
    minX: Double,
    maxX: Double,
    minY: Double,
    maxY: Double,
    minZ: Double,
    maxZ: Double,
) {

    var minX: Double = 0.0
        private set
    var maxX: Double = 0.0
        private set
    var minY: Double = 0.0
        private set
    var maxY: Double = 0.0
        private set
    var minZ: Double = 0.0
        private set
    var maxZ: Double = 0.0
        private set

    /**
     * Maximale Y-Position, auf der eine Boden-Item-Box liegen darf.
     *
     * Begrenzt den Boden-Scan zusaetzlich zu den Arena-Grenzen, damit Items wirklich nur auf der
     * Grundflaeche landen und nicht auf hoeher gelegenen Bereichen.
     */
    var maxItemSpawnY: Double = 0.0

    /**
     * Y-Hoehe der Arena-Decke. [Double.MAX_VALUE] bedeutet offener Himmel. Fliegende Entities
     * (z. B. der Tarnkappenbomber-Drache) muessen darunter bleiben.
     */
    var ceilingY: Double = Double.MAX_VALUE

    init {
        setArenaBounds(minX, maxX, minY, maxY, minZ, maxZ)
        maxItemSpawnY = this.maxY + 1.0
    }

    val hasCeiling: Boolean
        get() = ceilingY != Double.MAX_VALUE

    /**
     * Hoechste Y-Position, die eine fliegende Entity einnehmen darf: einen Block unterhalb der
     * Decke, damit sie nicht in ihr steckt. Ohne Decke unbegrenzt.
     */
    val maxFlyY: Double
        get() = if (hasCeiling) ceilingY - 1.0 else Double.MAX_VALUE

    /**
     * Y-Hoehe, unterhalb derer ein Spieler als "aus der Welt gefallen" gilt.
     *
     * Notwendig, weil jeder Schaden ausserhalb der Arena-Grenzen gecancelt wird - inklusive
     * Void-Schaden. Ohne diese Grenze wuerde ein Sturz aus der Lobby (die bewusst ausserhalb der
     * Arena liegt) in einen endlosen Fall muenden, aus dem der Spieler nicht mehr herauskommt.
     */
    val voidRescueY: Double
        get() = minOf(minY, lobbyLocation?.y ?: minY) - 20.0

    /** Normalisiert die beiden Eckpunkte, damit die Reihenfolge der Ecken egal ist. */
    fun setArenaBounds(minX: Double, maxX: Double, minY: Double, maxY: Double, minZ: Double, maxZ: Double) {
        this.minX = minOf(minX, maxX)
        this.maxX = maxOf(minX, maxX)
        this.minY = minOf(minY, maxY)
        this.maxY = maxOf(minY, maxY)
        this.minZ = minOf(minZ, maxZ)
        this.maxZ = maxOf(minZ, maxZ)
    }

    /**
     * Prueft, ob eine Position innerhalb der Arena liegt. Die Welt-Zugehoerigkeit prueft bewusst
     * der ArenaManager, da nur der die aktive OSOK-Welt kennt.
     */
    fun isInArenaArea(loc: Location?): Boolean {
        if (loc == null) return false
        return loc.x >= minX && loc.x <= maxX &&
            loc.y >= minY - ARENA_FLOOR_TOLERANCE && loc.y <= maxY + ARENA_HEADROOM &&
            loc.z >= minZ && loc.z <= maxZ
    }

    /**
     * Zufaelliger Spielerspawn: sucht von oben nach unten und liefert damit auch erhoehte
     * Plattformen und Bruecken der Arena.
     */
    fun getRandomArenaLocation(osokWorld: World?): Location? =
        findRandomSpot(osokWorld, topDown = true) ?: fallbackLocation(osokWorld)

    /**
     * Sammelt mehrere gueltige Spielerspawnpunkte auf einmal.
     *
     * Grundlage fuer die Respawn-Bewertung im `ArenaManager`: Der bewertet die Kandidaten nach
     * Abstand zum Todespunkt und zum naechsten Gegner und nimmt den besten.
     *
     * Kann weniger als [wanted] Punkte liefern, wenn die Arena kaum begehbare Flaeche hat.
     */
    fun collectArenaSpots(osokWorld: World?, wanted: Int): List<Location> =
        collectSpots(osokWorld, topDown = true, wanted = wanted, maxAttempts = wanted * 15)

    /**
     * Zufaelliger Spawn fuer Boden-Items: sucht von unten nach oben und liefert damit
     * ausschliesslich den Arena-Boden - niemals Daecher, Bruecken oder Plattformen.
     *
     * Liefert bewusst `null`, wenn kein Bodenplatz gefunden wurde, damit der Aufrufer den Spawn
     * ueberspringen kann statt ein Item in der Lobby abzulegen.
     */
    fun getRandomFloorLocation(osokWorld: World?): Location? = findRandomSpot(osokWorld, topDown = false)

    private fun findRandomSpot(osokWorld: World?, topDown: Boolean): Location? =
        collectSpots(osokWorld, topDown, wanted = 1, maxAttempts = 200).firstOrNull()

    /**
     * Sucht bis zu [wanted] begehbare Punkte an zufaelligen XZ-Positionen.
     *
     * @param topDown von oben nach unten suchen (Spielerspawn) statt von unten (Boden-Items)
     * @param maxAttempts Obergrenze der XZ-Versuche, damit die Suche in jedem Fall terminiert
     */
    private fun collectSpots(osokWorld: World?, topDown: Boolean, wanted: Int, maxAttempts: Int): List<Location> {
        if (osokWorld == null) return emptyList()

        val scanMinY = maxOf(floor(minY).toInt() - SPAWN_SCAN_BELOW, osokWorld.minHeight)
        var scanMaxY = minOf(floor(maxY).toInt() + SPAWN_SCAN_ABOVE, osokWorld.maxHeight - 3)

        if (!topDown) {
            // Boden-Items: Der Bodenblock liegt eine Position unter dem Item selbst.
            scanMaxY = minOf(scanMaxY, floor(maxItemSpawnY).toInt() - 1)
        }
        if (scanMaxY < scanMinY) return emptyList()

        val found = ArrayList<Location>(maxOf(1, wanted))
        var attempts = 0
        while (attempts < maxAttempts && found.size < wanted) {
            attempts++
            val blockX = floor(minX + Random.nextDouble() * (maxX - minX)).toInt()
            val blockZ = floor(minZ + Random.nextDouble() * (maxZ - minZ)).toInt()

            for (step in 0..(scanMaxY - scanMinY)) {
                val y = if (topDown) scanMaxY - step else scanMinY + step
                if (isStandableAt(osokWorld, blockX, y, blockZ)) {
                    val randomYaw = Random.nextFloat() * 360f - 180f
                    found += Location(osokWorld, blockX + 0.5, y + 1.0, blockZ + 0.5, randomYaw, 0f)
                    break
                }
            }
        }
        return found
    }

    /** Boden tragfaehig, Fuss- und Kopfhoehe begehbar und frei von Fluessigkeit. */
    private fun isStandableAt(world: World, x: Int, y: Int, z: Int): Boolean {
        val ground: Block = world.getBlockAt(x, y, z)
        val feet: Block = world.getBlockAt(x, y + 1, z)
        val head: Block = world.getBlockAt(x, y + 2, z)

        return ground.type.isSolid &&
            ground.type !in BLOCKED_SPAWN_GROUND &&
            !ground.isLiquid &&
            ground.type != Material.LAVA &&
            ground.type != Material.FIRE &&
            feet.isPassable && !feet.isLiquid &&
            head.isPassable && !head.isLiquid
    }

    private fun fallbackLocation(osokWorld: World?): Location? {
        if (osokWorld == null) return null
        val lobby = lobbyLocation ?: return osokWorld.spawnLocation
        return lobby.clone().apply { world = osokWorld }
    }

    private companion object {
        /**
         * Spielraum ueber der oberen Arena-Kante, damit Spruenge und Knockback einen Spieler nicht
         * kurzzeitig aus der Arena "herausfallen" lassen (was PvP deaktivieren wuerde).
         *
         * Bewusst klein gehalten: Die DustPvP-Lobby liegt bei Y=90 direkt ueber der Arena-Flaeche
         * und muss zuverlaessig ausserhalb der Arena bleiben.
         */
        const val ARENA_HEADROOM = 12.0

        /** Toleranz unterhalb der unteren Kante (Bodenplatte, Stufen, leichte Senken). */
        const val ARENA_FLOOR_TOLERANCE = 2.0

        /** Suchfenster fuer Spawnpunkte relativ zu den Arena-Grenzen. */
        const val SPAWN_SCAN_BELOW = 2
        const val SPAWN_SCAN_ABOVE = 4

        /**
         * Bloecke, auf denen nicht gespawnt werden darf (Dach-/Randmarkierungen der Maps).
         * Einmalig aufgebaut, damit der Spawn-Scan ohne String-Operationen auskommt.
         */
        val BLOCKED_SPAWN_GROUND: Set<Material> =
            Material.entries.filterTo(EnumSet.of(Material.BLACK_WOOL)) { "BRICK" in it.name }
    }
}
