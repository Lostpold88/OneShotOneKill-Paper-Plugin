package de.oneshotonekill.manager

import de.oneshotonekill.OneShotOneKill
import de.oneshotonekill.model.ArenaPolygon
import de.oneshotonekill.model.MapConfig
import de.oneshotonekill.util.mini
import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class WorldManager(private val plugin: OneShotOneKill) {

    private val availableMapsByKey = mutableMapOf<String, MapConfig>()

    var activeMapConfig: MapConfig
        private set

    @Volatile
    private var switchInProgress = false

    private var cachedWorld: World? = null

    init {
        // Standard Map (OSOK_Standard) - Arena-Ecken: (221, 58, -50) / (287, 64, -106)
        val standardMap = MapConfig(
            name = "Standard",
            zipResource = "Standard.zip",
            lobbyLocation = Location(null, 223.5, 48.0, 55.5),
            minX = 221.0, maxX = 287.0, minY = 58.0, maxY = 64.0, minZ = -106.0, maxZ = -50.0,
        ).apply {
            // Boden-Item-Boxen duerfen auf Standard hoechstens auf Y=61 liegen
            maxItemSpawnY = 61.0
            // Standard ist ueberdacht: Die Decke liegt auf Y=69
            ceilingY = 69.0
        }
        availableMapsByKey["standard"] = standardMap

        // DustPvP Map (OSOK_DustPvP) - Arena-Ecken: (-25, 70, 33) / (25, 70, -33)
        availableMapsByKey["dustpvp"] = MapConfig(
            name = "DustPvP",
            zipResource = "DustPvP.zip",
            lobbyLocation = Location(null, 0.5, 90.0, 0.5),
            minX = -25.0, maxX = 25.0, minY = 70.0, maxY = 70.0, minZ = -33.0, maxZ = 33.0,
        ).apply {
            // DustPvP ist eine flache Ebene auf Y=70, die Box liegt also auf Y=71
            maxItemSpawnY = 71.0
        }

        // BO2 Map (OSOK_BO2)
        //
        // Die Kampfzone ist kein Rechteck, sondern ein abgelaufener Umriss: 105 Eckpunkte einmal um
        // die Arena herum, mit /punkt mitgeschrieben. Der Umriss liegt vollstaendig auf Y 63; die
        // Spielhoehe darueber deckt der Kopfraum der Arena ab (12 Bloecke).
        availableMapsByKey["bo2"] = MapConfig(
            name = "BO2",
            zipResource = "BO2.zip",
            lobbyLocation = Location(null, -1045.5, 63.0, 352.5),
            regions = listOf(
                ArenaPolygon.of(
                    63.0, 63.0,
                    -950, 369, -950, 386, -957, 386, -957, 384, -965, 384, -965, 396,
                    -962, 396, -962, 403, -964, 403, -964, 406, -962, 406, -962, 414,
                    -971, 414, -971, 411, -977, 411, -977, 414, -1007, 414, -1018, 429,
                    -1023, 424, -1024, 424, -1024, 427, -1019, 432, -1027, 440, -1045, 440,
                    -1045, 438, -1052, 438, -1052, 436, -1056, 436, -1056, 435, -1058, 435,
                    -1058, 433, -1062, 433, -1063, 433, -1063, 429, -1065, 429, -1068, 432,
                    -1069, 430, -1069, 429, -1072, 429, -1072, 428, -1080, 428, -1080, 430,
                    -1106, 430, -1106, 424, -1115, 424, -1115, 430, -1135, 430, -1135, 409,
                    -1133, 409, -1133, 406, -1128, 406, -1128, 402, -1137, 402, -1137, 401,
                    -1139, 401, -1139, 405, -1147, 405, -1147, 377, -1139, 377, -1139, 379,
                    -1137, 379, -1137, 377, -1126, 377, -1126, 379, -1123, 379, -1123, 377,
                    -1114, 377, -1114, 379, -1111, 379, -1111, 377, -1109, 377, -1109, 382,
                    -1105, 382, -1105, 377, -1094, 377, -1094, 379, -1091, 379, -1091, 377,
                    -1070, 377, -1070, 379, -1066, 379, -1066, 381, -1061, 381, -1061, 379,
                    -1060, 379, -1060, 361, -1029, 361, -1029, 374, -1028, 374, -1028, 376,
                    -1024, 376, -1024, 374, -1015, 374, -1015, 369, -980, 369, -980, 375,
                    -974, 375, -974, 369, -960, 369, -960, 370, -960, 371, -958, 371,
                    -958, 370, -957, 370, -957, 369,
                ),
            ),
        )

        activeMapConfig = standardMap
    }

    val osokWorld: World?
        get() {
            if (cachedWorld == null) {
                cachedWorld = Bukkit.getWorld(worldNameOf(activeMapConfig))
            }
            return cachedWorld
        }

    private var currentSpawn: Location? = null

    /**
     * Setzen verschiebt bewusst **auch** die Lobby der aktiven Map (das ist der Zweck von
     * `/osok setspawn`). Der Map-Wechsel schreibt deshalb [currentSpawn] direkt, statt ueber diese
     * Property zu gehen - er soll die konfigurierte Lobby nicht ueberschreiben.
     */
    var spawnLocation: Location?
        get() {
            if (currentSpawn == null) {
                osokWorld?.let { world ->
                    currentSpawn = activeMapConfig.lobbyLocation?.clone()?.apply { this.world = world }
                }
            }
            return currentSpawn
        }
        set(value) {
            currentSpawn = value
            activeMapConfig.lobbyLocation = value
            if (value != null) {
                osokWorld?.setSpawnLocation(value)
            }
        }

    val availableMaps: Collection<MapConfig>
        get() = availableMapsByKey.values

    fun setupWorld() {
        val worldName = worldNameOf(activeMapConfig)

        plugin.logger.info("Entpacke initiale Map $worldName aus der JAR...")
        try {
            wipeMapFolders(worldName)
            extractEmbeddedMap(activeMapConfig.zipResource, mapFolder(worldName))
            plugin.logger.info("Map $worldName erfolgreich entpackt!")
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "Fehler beim Entpacken der Map $worldName", e)
        }

        val world = createWorld(worldName) ?: return
        cachedWorld = world

        // Setzt ueber applyGlobalGameRules auch Tageszeit und Wetter fest
        applyPaperGameRules(world)
        removeNonPlayerEntities(world)

        val lobby = activeMapConfig.lobbyLocation?.clone()?.apply { this.world = world }
        if (lobby != null) {
            currentSpawn = lobby
            world.setSpawnLocation(lobby)
        }
        plugin.logger.info("Map $worldName geladen & Lobby-Spawn gesetzt!")
    }

    fun switchMap(mapName: String): Boolean {
        val targetConfig = availableMapsByKey[mapName.lowercase()] ?: return false

        if (switchInProgress) {
            broadcast("<red>[OSOK] ⏳ Es laeuft bereits ein Map-Wechsel. Bitte warten!</red>")
            return true
        }
        switchInProgress = true

        plugin.logger.info("[OSOK] Starte dynamischen Map-Wechsel zu: ${worldNameOf(targetConfig)}")

        // 1. Laufendes Match sauber beenden (kein Kampf waehrend/nach dem Wechsel)
        plugin.matchManager.stopMatch()

        // 2. Boden-Items der alten Map inkl. Chunk-Tickets freigeben, dazu Drachen,
        //    fallende Bomben und platzierte C4-Ladungen der alten Welt
        plugin.killstreakManager.clearAllGroundItems()
        plugin.stealthBomberManager.clearAll()
        plugin.explosivesManager.clearAll()
        // Gaswolken und Nuke-TNT haengen ebenfalls an der alten Welt; der Aufruf holt zugleich
        // Zuschauer eines laufenden Finales zurueck in den Ueberlebensmodus
        plugin.nukeManager.clearAll()
        // Frost-Traps MUESSEN vor dem Entladen weg - danach ist die alte Welt nicht mehr
        // erreichbar und die gespeicherten Locations zeigen ins Leere
        plugin.specialItemListener.clearAllTraps()
        plugin.specialItemListener.clearAllVanish()
        plugin.tacticalItemsManager.clearAll()
        plugin.antiCampManager.reset()
        plugin.glowManager.clearAll()

        // 3. Paper Async Teleportation: Alle Spieler aus der OSOK-Welt heraus sichern.
        //    Die Welt darf erst entladen werden, wenn ALLE Teleports abgeschlossen sind.
        val fallbackLoc = Bukkit.getWorlds()[0].spawnLocation

        val teleports = Bukkit.getOnlinePlayers().map { player ->
            player.sendMessage(
                ("<yellow>[OSOK] 🔄 Wechsel auf Map <b>${targetConfig.name}</b>... " +
                    "Bitte warten!</yellow>").mini()
            )
            plugin.equipmentManager.clearBaseEquipment(player)
            player.teleportAsync(fallbackLoc)
        }

        // 4. Nach Abschluss aller Teleports zurueck auf den Main-Thread wechseln:
        //    Welt-Entladen, Loeschen, Entpacken und Laden sind nicht thread-safe.
        CompletableFuture.allOf(*teleports.toTypedArray()).whenComplete { _, _ ->
            Bukkit.getGlobalRegionScheduler().run(plugin) { finishMapSwitch(targetConfig) }
        }

        return true
    }

    private fun finishMapSwitch(targetConfig: MapConfig) {
        val targetWorldName = worldNameOf(targetConfig)
        try {
            // Alte OSOK-Welt entladen (Spieler sind jetzt nachweislich draussen)
            cachedWorld?.let { old ->
                if (!Bukkit.unloadWorld(old, false)) {
                    plugin.logger.warning("[OSOK] Alte Welt ${old.name} konnte nicht entladen werden.")
                }
                cachedWorld = null
            }
            Bukkit.unloadWorld(targetWorldName, false)

            // Ziel-Ordner & migrierte Dimension loeschen (frische Map aus JAR)
            wipeMapFolders(targetWorldName)
            extractEmbeddedMap(targetConfig.zipResource, mapFolder(targetWorldName))

            val world = createWorld(targetWorldName)
            if (world == null) {
                plugin.logger.severe("[OSOK] Welt $targetWorldName konnte nicht geladen werden!")
                broadcast("<red>[OSOK] ❌ Map-Wechsel fehlgeschlagen: Welt konnte nicht geladen werden.</red>")
                return
            }
            cachedWorld = world

            // Setzt ueber applyGlobalGameRules auch Tageszeit und Wetter fest
            applyPaperGameRules(world)
            removeNonPlayerEntities(world)

            // Erst jetzt ist die neue Map aktiv - Arena-Grenzen wechseln synchron mit der Welt
            activeMapConfig = targetConfig
            val lobby = targetConfig.lobbyLocation?.clone()?.apply { this.world = world }
            if (lobby != null) {
                currentSpawn = lobby
                world.setSpawnLocation(lobby)
                teleportEveryoneToLobby(lobby, targetConfig)
            }

            plugin.scoreboardManager.updateAllScoreboards()
            plugin.logger.info("[OSOK] Map-Wechsel zu $targetWorldName abgeschlossen.")
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "[OSOK] Map-Wechsel fehlgeschlagen", e)
            broadcast("<red>[OSOK] ❌ Map-Wechsel fehlgeschlagen. Details siehe Server-Log.</red>")
        } finally {
            switchInProgress = false
        }
    }

    private fun teleportEveryoneToLobby(lobby: Location, targetConfig: MapConfig) {
        for (player in Bukkit.getOnlinePlayers()) {
            player.teleportAsync(lobby).thenAccept { success ->
                if (!success || !player.isOnline) return@thenAccept

                plugin.equipmentManager.clearBaseEquipment(player)
                // Bewusst ohne Sound: Der Map-Wechsel soll nicht mit einem lauten
                // Fanfaren-Jingle quittiert werden.
                player.sendMessage(
                    ("<green>[OSOK] 🗺 Map erfolgreich zu <b>${targetConfig.name}</b> gewechselt! " +
                        "<gray>Starte mit /osok start.</gray></green>").mini()
                )
            }
        }
    }

    private fun createWorld(worldName: String): World? =
        Bukkit.createWorld(WorldCreator(worldName).environment(World.Environment.NORMAL))

    private fun removeNonPlayerEntities(world: World) {
        world.entities.filterNot { it is Player }.forEach { it.remove() }
    }

    private fun broadcast(miniMessage: String) {
        Bukkit.broadcast(miniMessage.mini())
    }

    private fun applyPaperGameRules(world: World) {
        // Moderne Paper GameRules-Registry (org.bukkit.GameRule ist deprecated for removal)
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true)
        world.setGameRule(GameRules.KEEP_INVENTORY, true)
        world.setGameRule(GameRules.SPAWN_MOBS, false)
        world.setGameRule(GameRules.SPAWN_PATROLS, false)
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false)
        // Zusaetzlicher Schutz der Map: kein Entity soll Bloecke veraendern koennen
        world.setGameRule(GameRules.MOB_GRIEFING, false)
        applyGlobalGameRules(world)
    }

    // ------------------------------------------------------------------
    // Dateisystem
    // ------------------------------------------------------------------

    private fun mapFolder(worldName: String) = File(Bukkit.getWorldContainer(), worldName)

    private fun migratedFolder(worldName: String) =
        File(Bukkit.getWorldContainer(), "world/dimensions/minecraft/${worldName.lowercase()}")

    private fun wipeMapFolders(worldName: String) {
        mapFolder(worldName).deleteRecursively()
        migratedFolder(worldName).deleteRecursively()
    }

    private fun extractEmbeddedMap(zipResourceName: String, targetDir: File) {
        val resource = plugin.getResource(zipResourceName) ?: plugin.getResource("Standard.zip")
        if (resource == null) {
            plugin.logger.severe("$zipResourceName wurde im Plugin-Resource Stream nicht gefunden!")
            return
        }

        targetDir.mkdirs()

        ZipInputStream(resource).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                extractEntry(zip, entry, targetDir)
                zip.closeEntry()
            }
        }
    }

    private fun extractEntry(zip: ZipInputStream, entry: ZipEntry, targetDir: File) {
        val name = entry.name
        val file = File(targetDir, name)

        val isDirectory = entry.isDirectory || name.endsWith("/") || name.endsWith("\\") ||
            // Leere Eintraege ohne Dateiendung sind in diesen Archiven Ordner
            (entry.size == 0L && "." !in name)

        if (isDirectory) {
            file.mkdirs()
            return
        }

        file.parentFile?.mkdirs()
        file.outputStream().use { output -> zip.copyTo(output) }
    }

    companion object {
        /** Tageszeit, auf der jede Welt festgehalten wird: Mittag. */
        const val MIDDAY_TICKS = 6000L

        private fun worldNameOf(config: MapConfig) = "OSOK_${config.name}"

        /**
         * GameRules, die auf JEDER Welt des Servers gelten muessen - unabhaengig davon, ob es eine
         * OSOK-Arena ist. Wird zusaetzlich beim Serverstart und bei WorldLoadEvent angewendet
         * (siehe WorldRuleListener).
         */
        fun applyGlobalGameRules(world: World) {
            world.setGameRule(GameRules.LOCATOR_BAR, false)
            applyFixedDaylightAndWeather(world)
        }

        /**
         * Friert Tageszeit und Wetter fest: immer Mittag, immer klar.
         *
         * Die beiden GameRules allein reichen nicht - sie halten nur den Fortlauf an. Steht die
         * Welt beim Anwenden gerade auf Nacht oder Regen, bliebe sie genau so stehen. Deshalb
         * werden Zeit und Wetter zusaetzlich einmal auf den gewuenschten Stand gesetzt.
         */
        fun applyFixedDaylightAndWeather(world: World) {
            world.setGameRule(GameRules.ADVANCE_TIME, false)
            world.setGameRule(GameRules.ADVANCE_WEATHER, false)

            // Nether und End haben eine im DimensionType festgenagelte Tageszeit und damit keine
            // Weltuhr. CraftWorld#setFullTime wirft dort IllegalArgumentException ("Cannot set
            // time in world without world clock") - das hat beim Serverstart das komplette
            // onEnable abgebrochen. Solche Welten brauchen die Korrektur ohnehin nicht: ihre Zeit
            // steht schon.
            if (!world.isFixedTime) {
                world.time = MIDDAY_TICKS
            }
            world.setStorm(false)
            world.isThundering = false
            world.weatherDuration = 0
            world.thunderDuration = 0
            world.clearWeatherDuration = Int.MAX_VALUE
        }

        /** Wendet die globalen GameRules auf alle aktuell geladenen Welten an. */
        fun applyGlobalGameRulesToAllWorlds() {
            Bukkit.getWorlds().forEach { applyGlobalGameRules(it) }
        }
    }
}
