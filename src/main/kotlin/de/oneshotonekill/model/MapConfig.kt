package de.oneshotonekill.model

import io.papermc.paper.math.Position
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * Ein Stueck Kampfzone. Mehrere davon ergeben zusammen die Arena.
 *
 * Zwei Formen: [ArenaRegion] fuer rechteckige Arenen, [ArenaPolygon] fuer alles andere. Beide
 * liefern dieselben Grenzwerte, damit der Rest des Plugins sie nicht unterscheiden muss.
 */
sealed interface ArenaShape {

    val minX: Double
    val maxX: Double
    val minY: Double
    val maxY: Double
    val minZ: Double
    val maxZ: Double

    /** Grundflaeche in Bloecken - Gewicht bei der Auswahl eines zufaelligen Spawnbereichs. */
    val footprint: Double

    /** Liegt die Spalte (unabhaengig von der Hoehe) ueber dieser Flaeche? */
    fun containsColumn(x: Double, z: Double): Boolean

    fun contains(x: Double, y: Double, z: Double): Boolean =
        containsColumn(x, z) && y >= minY - ARENA_FLOOR_TOLERANCE && y <= maxY + ARENA_HEADROOM

    companion object {
        /**
         * Spielraum ueber der oberen Kante, damit Spruenge und Knockback einen Spieler nicht
         * kurzzeitig aus der Arena "herausfallen" lassen (was PvP deaktivieren wuerde).
         *
         * Bewusst klein gehalten: Die DustPvP-Lobby liegt bei Y=90 direkt ueber der Arena-Flaeche
         * und muss zuverlaessig ausserhalb der Arena bleiben.
         */
        const val ARENA_HEADROOM = 12.0

        /** Toleranz unterhalb der unteren Kante (Bodenplatte, Stufen, leichte Senken). */
        const val ARENA_FLOOR_TOLERANCE = 2.0
    }
}

/**
 * Ein achsenparalleler Quader der Kampfzone.
 *
 * Angelegt wird er ueber [of] aus **zwei beliebigen Ecken** - so, wie man sie im Spiel abliest.
 * Die Reihenfolge der Ecken ist egal, [of] normalisiert sie.
 */
class ArenaRegion private constructor(
    override val minX: Double,
    override val maxX: Double,
    override val minY: Double,
    override val maxY: Double,
    override val minZ: Double,
    override val maxZ: Double,
) : ArenaShape {

    override val footprint: Double
        get() = (maxX - minX + 1.0) * (maxZ - minZ + 1.0)

    override fun containsColumn(x: Double, z: Double): Boolean =
        x >= minX && x <= maxX && z >= minZ && z <= maxZ

    companion object {
        /** Aus zwei beliebigen Ecken, in beliebiger Reihenfolge. */
        fun of(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): ArenaRegion =
            ArenaRegion(
                minOf(x1, x2), maxOf(x1, x2),
                minOf(y1, y2), maxOf(y1, y2),
                minOf(z1, z2), maxOf(z1, z2),
            )
    }
}

/**
 * Eine Kampfzone, die als **Umriss** vermessen wurde: eine Kette von Eckpunkten einmal um die
 * Arena herum, so wie man sie im Spiel ablaeuft und mit `/punkt` mitschreibt.
 *
 * **Warum eine vorberechnete Maske statt eines Strahlentests pro Abfrage:** `isInArenaArea` haengt
 * in den heissesten Pfaden des Plugins - an jedem Schadensevent, an jeder Bewegung fuer die
 * Camping-Erkennung, an jedem Item-Spawn. Ein Punkt-in-Polygon-Test ueber hundert Ecken bei jedem
 * dieser Aufrufe waere Verschwendung. Stattdessen wird der Umriss **einmal beim Laden** in ein
 * Bitfeld je Spalte gerastert; die Abfrage ist danach ein Array-Zugriff.
 *
 * **Der Rand wird bewusst grosszuegig gerastert.** Wer die Grenze ablaeuft, steht dabei auf dem
 * letzten Block *innerhalb* der Arena - die Kette laeuft also durch die Mittelpunkte der
 * Randbloecke. Ohne Nachbehandlung faellt deren aeussere Haelfte aus der Zone, und ein Spieler am
 * Rand stuende je nach Blickrichtung mal drinnen, mal draussen. Deshalb wird die Maske um eine
 * Blocklage nach aussen verbreitert.
 */
class ArenaPolygon private constructor(
    private val verticesX: DoubleArray,
    private val verticesZ: DoubleArray,
    override val minY: Double,
    override val maxY: Double,
) : ArenaShape {

    override val minX: Double = verticesX.min()
    override val maxX: Double = verticesX.max()
    override val minZ: Double = verticesZ.min()
    override val maxZ: Double = verticesZ.max()

    private val width: Int = (maxX - minX).toInt() + 1
    private val depth: Int = (maxZ - minZ).toInt() + 1

    /** Je Spalte ein Bit: liegt sie in der Arena? */
    private val mask: BooleanArray = buildMask()

    override val footprint: Double = mask.count { it }.toDouble()

    override fun containsColumn(x: Double, z: Double): Boolean {
        val ix = floor(x).toInt() - minX.toInt()
        val iz = floor(z).toInt() - minZ.toInt()
        if (ix < 0 || ix >= width || iz < 0 || iz >= depth) return false

        return mask[ix * depth + iz]
    }

    /** Rastert Flaeche **und** Umrisslinie und verbreitert das Ergebnis um eine Blocklage. */
    private fun buildMask(): BooleanArray {
        val raw = BooleanArray(width * depth)
        for (ix in 0 until width) {
            for (iz in 0 until depth) {
                if (isInsideOutline(minX + ix + 0.5, minZ + iz + 0.5)) {
                    raw[ix * depth + iz] = true
                }
            }
        }

        // Die Linie selbst gehoert dazu: Wer die Grenze ablaeuft, steht auf Bloecken, die zur Arena
        // zaehlen sollen. An einspringenden Ecken liegen die aber ausserhalb der gefuellten Flaeche
        // und fielen sonst heraus - dort stuende man mitten in der Arena "draussen".
        for (i in verticesX.indices) {
            val j = (i + 1) % verticesX.size
            markSegment(raw, verticesX[i], verticesZ[i], verticesX[j], verticesZ[j])
        }

        val grown = BooleanArray(width * depth)
        for (ix in 0 until width) {
            for (iz in 0 until depth) {
                if (!raw[ix * depth + iz]) continue

                for (dx in -1..1) {
                    for (dz in -1..1) {
                        val nx = ix + dx
                        val nz = iz + dz
                        if (nx in 0 until width && nz in 0 until depth) {
                            grown[nx * depth + nz] = true
                        }
                    }
                }
            }
        }
        return grown
    }

    /**
     * Traegt alle Bloecke einer Umrisskante in die Maske ein.
     *
     * Schrittweite ist bewusst ein halber Block: Bei ganzen Schritten koennte eine schraege Kante
     * ueber eine Blockecke springen und eine Luecke in der Linie lassen.
     */
    private fun markSegment(mask: BooleanArray, x1: Double, z1: Double, x2: Double, z2: Double) {
        val steps = (maxOf(abs(x2 - x1), abs(z2 - z1)) * 2).toInt().coerceAtLeast(1)

        for (step in 0..steps) {
            val share = step.toDouble() / steps
            val ix = floor(x1 + (x2 - x1) * share).toInt() - minX.toInt()
            val iz = floor(z1 + (z2 - z1) * share).toInt() - minZ.toInt()
            if (ix in 0 until width && iz in 0 until depth) {
                mask[ix * depth + iz] = true
            }
        }
    }

    /**
     * Punkt-in-Polygon nach der Ungerade-Regel: Ein Strahl nach +X kreuzt den Umriss ungerade oft,
     * wenn der Punkt innen liegt.
     */
    private fun isInsideOutline(x: Double, z: Double): Boolean {
        var inside = false
        var j = verticesX.size - 1

        for (i in verticesX.indices) {
            val zi = verticesZ[i]
            val zj = verticesZ[j]
            if ((zi > z) != (zj > z)) {
                val crossX = (verticesX[j] - verticesX[i]) * (z - zi) / (zj - zi) + verticesX[i]
                if (x < crossX) inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        /**
         * Aus der abgelaufenen Punktkette. [xz] enthaelt abwechselnd X und Z - genau die Reihenfolge,
         * in der `/punkt` sie mitschreibt. Der Umriss wird automatisch geschlossen, ein doppelter
         * Schlusspunkt ist also nicht noetig.
         */
        fun of(minY: Double, maxY: Double, vararg xz: Int): ArenaPolygon {
            require(xz.size >= 6 && xz.size % 2 == 0) {
                "Ein Arena-Umriss braucht mindestens drei Punkte als X/Z-Paare"
            }

            val count = xz.size / 2
            val x = DoubleArray(count) { xz[it * 2].toDouble() }
            val z = DoubleArray(count) { xz[it * 2 + 1].toDouble() }
            return ArenaPolygon(x, z, minOf(minY, maxY), maxOf(minY, maxY))
        }
    }
}

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
    regions: List<ArenaShape>,
) {

    /**
     * Bequemlichkeits-Konstruktor fuer rechteckige Arenen: genau eine Region aus zwei Ecken.
     */
    constructor(
        name: String,
        zipResource: String,
        lobbyLocation: Location?,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        minZ: Double,
        maxZ: Double,
    ) : this(name, zipResource, lobbyLocation, listOf(ArenaRegion.of(minX, minY, minZ, maxX, maxY, maxZ)))

    /**
     * Die Kampfzone als **Vereinigung** mehrerer Quader.
     *
     * Eine Arena ist selten ein Rechteck: Ecken, Vorspruenge und Gassen lassen sich nur als Menge
     * von Quadern beschreiben. Eine einzige umschliessende Box wuerde die Zwischenraeume
     * mitzaehlen - dort waere dann gekaempft und gespawnt worden, wo gar keine Map ist.
     */
    val regions: List<ArenaShape> =
        regions.ifEmpty { error("MapConfig $name braucht mindestens eine Arena-Region") }

    /**
     * Umschliessende Box **aller** Regionen.
     *
     * Alles, was nur einen groben Rahmen braucht - das Air-Strike-Raster, der Bodennullpunkt der
     * Nuke, die Suchfenster - rechnet damit. Ob ein Punkt wirklich **in** der Arena liegt,
     * beantwortet dagegen ausschliesslich [isInArenaArea].
     */
    val minX: Double = this.regions.minOf { it.minX }
    val maxX: Double = this.regions.maxOf { it.maxX }
    val minY: Double = this.regions.minOf { it.minY }
    val maxY: Double = this.regions.maxOf { it.maxY }
    val minZ: Double = this.regions.minOf { it.minZ }
    val maxZ: Double = this.regions.maxOf { it.maxZ }

    /**
     * Maximale Y-Position, auf der eine Boden-Item-Box liegen darf.
     *
     * Begrenzt den Boden-Scan zusaetzlich zu den Arena-Grenzen, damit Items wirklich nur auf der
     * Grundflaeche landen und nicht auf hoeher gelegenen Bereichen.
     */
    var maxItemSpawnY: Double = maxY + 1.0

    /**
     * Y-Hoehe der Arena-Decke. [Double.MAX_VALUE] bedeutet offener Himmel. Fliegende Entities
     * (z. B. der Tarnkappenbomber-Drache) muessen darunter bleiben.
     */
    var ceilingY: Double = Double.MAX_VALUE

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

    /**
     * Prueft, ob ein Punkt innerhalb **einer** Arena-Region liegt. Die Welt-Zugehoerigkeit prueft
     * bewusst der ArenaManager, da nur der die aktive OSOK-Welt kennt.
     */
    fun isInArenaArea(loc: Location?): Boolean {
        if (loc == null) return false
        return regions.any { it.contains(loc.x, loc.y, loc.z) }
    }

    /**
     * Liegt die Spalte (unabhaengig von der Hoehe) ueber der Arena?
     *
     * Fuer alles, was flaechig arbeitet und die Luecken zwischen den Regionen aussparen muss - etwa
     * die Druckwelle der Nuke, die sonst auch dort einebnete, wo gar keine Arena ist.
     */
    fun containsColumn(x: Double, z: Double): Boolean = regions.any { it.containsColumn(x, z) }

    /**
     * Ein Punkt **in** der Arena, moeglichst mittig - der Bodennullpunkt der Nuke und der
     * Zuschauerplatz haengen daran.
     *
     * Die Mitte der umschliessenden Box taugt dafuer nicht: Bei einem Umriss wie dem der BO2-Map
     * liegt sie leicht ausserhalb der Kampfzone, die Nuke detonierte dann neben der Arena. Liegt
     * die Boxmitte nicht drin, wird deshalb die naechstgelegene Spalte gesucht, die drin liegt.
     */
    fun arenaCenter(): Position {
        val centerX = floor((minX + maxX) / 2.0)
        val centerZ = floor((minZ + maxZ) / 2.0)
        if (containsColumn(centerX + 0.5, centerZ + 0.5)) {
            return Position.block(centerX.toInt(), minY.toInt(), centerZ.toInt())
        }

        var bestX = centerX
        var bestZ = centerZ
        var bestDistance = Double.MAX_VALUE

        var x = floor(minX)
        while (x <= maxX) {
            var z = floor(minZ)
            while (z <= maxZ) {
                if (containsColumn(x + 0.5, z + 0.5)) {
                    val distance = (x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestX = x
                        bestZ = z
                    }
                }
                z += 1.0
            }
            x += 1.0
        }
        return Position.block(bestX.toInt(), minY.toInt(), bestZ.toInt())
    }

    /**
     * Zufaellige Spielerspawns: sucht von oben nach unten und liefert damit auch erhoehte
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
     * Die Region wird dabei **nach Grundflaeche gewichtet** gezogen: Wuerde jede Region gleich oft
     * drankommen, waere ein winziger Vorsprung so wahrscheinlich wie die halbe Karte, und die
     * Spieler landeten staendig in derselben Ecke.
     *
     * @param topDown von oben nach unten suchen (Spielerspawn) statt von unten (Boden-Items)
     * @param maxAttempts Obergrenze der XZ-Versuche, damit die Suche in jedem Fall terminiert
     */
    private fun collectSpots(osokWorld: World?, topDown: Boolean, wanted: Int, maxAttempts: Int): List<Location> {
        if (osokWorld == null) return emptyList()

        val totalFootprint = regions.sumOf { it.footprint }
        if (totalFootprint <= 0.0) return emptyList()

        val found = ArrayList<Location>(maxOf(1, wanted))
        var attempts = 0
        while (attempts < maxAttempts && found.size < wanted) {
            attempts++

            val region = pickRegion(totalFootprint)
            val scanMinY = maxOf(floor(region.minY).toInt() - SPAWN_SCAN_BELOW, osokWorld.minHeight)
            var scanMaxY = minOf(floor(region.maxY).toInt() + SPAWN_SCAN_ABOVE, osokWorld.maxHeight - 3)

            if (!topDown) {
                // Boden-Items: Der Bodenblock liegt eine Position unter dem Item selbst.
                scanMaxY = minOf(scanMaxY, floor(maxItemSpawnY).toInt() - 1)
            }
            if (scanMaxY < scanMinY) continue

            val blockX = floor(region.minX + Random.nextDouble() * (region.maxX - region.minX)).toInt()
            val blockZ = floor(region.minZ + Random.nextDouble() * (region.maxZ - region.minZ)).toInt()

            // Gewuerfelt wird in der umschliessenden Box der Region - bei einem Umriss liegt davon
            // ein guter Teil ausserhalb der Karte. Ohne diese Pruefung landen Spielerspawns und
            // Boden-Items dort, wo gar keine Arena ist.
            if (!region.containsColumn(blockX + 0.5, blockZ + 0.5)) continue

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

    /** Zieht eine Region, gewichtet nach ihrer Grundflaeche. */
    private fun pickRegion(totalFootprint: Double): ArenaShape {
        if (regions.size == 1) return regions[0]

        var roll = Random.nextDouble() * totalFootprint
        for (region in regions) {
            roll -= region.footprint
            if (roll <= 0.0) return region
        }
        return regions.last()
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
