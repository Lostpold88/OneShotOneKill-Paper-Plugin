import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "de.oneshotonekill"

// =============================================================================================
// Paper-API-Version: einzig und allein aus Server/server.jar
//
// Gebaut wird gegen genau die paper-api, die der Server auch faehrt. Sonst kompiliert man gegen
// eine andere API als die, gegen die das Plugin spaeter laeuft - und Abweichungen fallen erst zur
// Laufzeit auf, im schlimmsten Fall als NoSuchMethodError.
//
// Die Paperclip-JAR fuehrt ihre Bibliotheken unter META-INF/libraries/... mit; die Version steht
// also in der Server-JAR selbst, OHNE dass der Server je gestartet worden sein muss. Eine neue
// Server-JAR reicht damit vollstaendig aus: Gradle holt die passende paper-api beim naechsten
// Build aus repo.papermc.io, und api-version, Plugin-Version und JAR-Name ziehen automatisch mit.
//
// **Bewusst ohne Rueckfallwert.** Ein zweiter, gepinnter Wert kann von der Server-JAR abweichen -
// und dann baut man unbemerkt gegen etwas anderes, als spaeter laeuft. Genau das soll diese
// Mechanik verhindern. Fehlt die Server-JAR, bricht der Build mit einem Hinweis ab, statt eine
// moeglicherweise falsche Version zu raten.
//
// ⚠️ Die Erkennung liest die JAR zur Konfigurationszeit. Das ist korrekt, solange der
// Configuration Cache aus ist (Gradle-Standard, hier nicht aktiviert): Der Build wertet das Skript
// dann bei jedem Lauf neu aus. Wird der Cache jemals eingeschaltet, muss dieser Block auf eine
// ValueSource umgestellt werden - sonst friert die Version beim ersten Lauf ein und ein
// Server-Update ginge unbemerkt an der Erkennung vorbei. (Der Cache scheitert derzeit ohnehin an
// tasks.jar, das fuer das stdlib-Buendeln eine Skript-Referenz haelt.)
// =============================================================================================

val paperApiPath = "io/papermc/paper/paper-api"

/** Liest die paper-api-Version aus den mitgefuehrten Bibliotheken der Paperclip-JAR. */
fun paperApiFromServerJar(): String? {
    val serverJar = layout.projectDirectory.file("Server/server.jar").asFile
    if (!serverJar.isFile) return null

    val pattern = Regex("^META-INF/libraries/$paperApiPath/([^/]+)/")
    return ZipFile(serverJar).use { zip ->
        zip.entries().asSequence().firstNotNullOfOrNull { pattern.find(it.name)?.groupValues?.get(1) }
    }
}

val paperApiVersion: String = paperApiFromServerJar() ?: throw GradleException(
    """
    Server/server.jar fehlt oder fuehrt keine paper-api mit.

    Der Build richtet sich ausschliesslich nach der Server-JAR, damit er gegen genau die API
    kompiliert, die der Server spaeter faehrt - einen Rueckfallwert gibt es bewusst nicht.

    Paper herunterladen und als Server/server.jar ablegen:
        https://papermc.io/downloads/paper
    Gestartet werden muss der Server dafuer nicht.
    """.trimIndent()
)

/** Aus "26.2.build.111-stable" wird "26.2" - der Wert fuer api-version in paper-plugin.yml. */
val minecraftVersion: String = paperApiVersion.substringBefore(".build.")

version = "1.0.0-$minecraftVersion"

logger.lifecycle("Paper-API: $paperApiVersion  (aus Server/server.jar)")

// ---------------------------------------------------------------------------------------------
// kotlin-stdlib wandert in die JAR
//
// Paper stellt die stdlib nicht bereit und paper-plugin.yml hat kein libraries-Feld. Der naechste
// Gedanke - Papers PluginLoader mit MavenLibraryResolver - fuehrt in ein Henne-Ei-Problem: Die
// Loader-Klasse ist selbst Kotlin und laeuft im PaperSimplePluginClassLoader, der ausschliesslich
// die Plugin-JAR sieht. Sie braucht die stdlib also bereits, um die stdlib nachladen zu koennen,
// und stirbt mit NoClassDefFoundError: kotlin/jvm/internal/Intrinsics. Am laufenden Server
// verifiziert - siehe .agents/AGENTS.md, Grundsatz 2.
//
// Deshalb: eine eigene Konfiguration, die in die JAR entpackt wird. Kein Shadow-Plugin noetig, es
// geht um genau eine Abhaengigkeit ohne Paketumbenennung.
// ---------------------------------------------------------------------------------------------
val bundled: Configuration = configurations.create("bundled")

dependencies {
    // Die Version kommt aus der Erkennung oben und steht deshalb bewusst nicht im
    // Versionskatalog: Gradle loest damit bei jeder neuen Server-JAR automatisch die passende
    // paper-api auf und laedt sie aus repo.papermc.io nach.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // In die JAR gebuendelt und gleichzeitig der Compile-Klassenpfad
    bundled(libs.kotlin.stdlib)
    compileOnly(libs.kotlin.stdlib)
}

kotlin {
    compilerOptions {
        // Java 25 ist Pflicht, nicht Geschmackssache: die paper-api deklariert
        // org.gradle.jvm.version = 25, ein niedrigeres Ziel laesst Gradle die Abhaengigkeit gar
        // nicht erst aufloesen. Deckt sich mit java_version: 25 aus der version.json der
        // Server-JAR.
        jvmTarget = JvmTarget.JVM_25
        // Abnahmekriterium 2: Jede Warnung - insbesondere Deprecation und Removal - bricht den
        // Build ab. Unterdruecken ist ein Regelverstoss, nicht die Loesung.
        allWarningsAsErrors = true
        // Papers @NotNull/@Nullable werden verbindlich, statt als Plattformtyp durchgewunken.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// Traegt die Minecraft-Version im Namen und folgt damit automatisch dem Server.
val pluginJarName = "OneShotOneKill_$minecraftVersion.jar"

tasks.processResources {
    // paper-plugin.yml zieht version und api-version aus dem Build, damit beide nicht doppelt
    // gepflegt werden. Die Werte werden zur Konfigurationszeit festgehalten - ein project-Zugriff
    // zur Ausfuehrungszeit ist mit dem Configuration Cache unvereinbar (und in Gradle 10 ein
    // Fehler).
    val pluginVersion = project.version.toString()
    val apiVersion = minecraftVersion
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion, "apiVersion" to apiVersion)
    }
}

tasks.jar {
    archiveFileName = pluginJarName

    from(bundled.elements.map { jars -> jars.map { zipTree(it) } }) {
        // Signaturen fremder JARs machen die eigene JAR ungueltig; module-info kollidiert.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ---------------------------------------------------------------------------------------------
// deployPlugin: bauen und in den Testserver kopieren - der bisherige Ein-Befehl-Workflow.
//
// Raeumt vorher JARs frueherer Minecraft-Versionen weg. Der Dateiname traegt die Version
// (OneShotOneKill_26.2.jar), nach einem Server-Update entsteht also ein neuer Name - der alte
// bliebe liegen, und Paper laedt dann **beide** Plugins mit demselben Namen. Genau die Sorte
// Ueberbleibsel, die eine automatische Versionsfuehrung sonst wieder von Hand einzusammeln gibt.
// ---------------------------------------------------------------------------------------------
tasks.register<Copy>("deployPlugin") {
    group = "distribution"
    description = "Baut die Plugin-JAR und kopiert sie nach Server/plugins/."
    from(tasks.jar)

    val pluginsDir = layout.projectDirectory.dir("Server/plugins")
    into(pluginsDir)

    doFirst {
        pluginsDir.asFile
            .listFiles { file -> file.isFile && file.name.startsWith("OneShotOneKill_") }
            ?.filter { it.name != pluginJarName }
            ?.forEach { stale ->
                if (stale.delete()) {
                    logger.lifecycle("Alte Plugin-JAR entfernt: ${stale.name}")
                } else {
                    logger.warn("Alte Plugin-JAR ${stale.name} liess sich nicht entfernen - laeuft der Server?")
                }
            }
    }

    doLast {
        logger.lifecycle("Plugin deployt nach Server/plugins/$pluginJarName")
        logger.lifecycle("Ein laufender Server laedt die JAR nicht neu - Neustart noetig.")
    }

    finalizedBy("pruneApiCache")
}

// ---------------------------------------------------------------------------------------------
// pruneApiCache: alte paper-api-Fassungen aus dem Gradle-Cache werfen
//
// Der Cache haelt jede je gezogene Version dauerhaft vor. Gebraucht wird aber immer nur die eine,
// die zur Server-JAR passt - jede weitere ist ein Zweitbestand, in den man beim Nachschlagen
// versehentlich hineingreift und dann eine Signatur aus der falschen Fassung liest.
//
// Drei Absicherungen:
//   - Die erkannte Version bleibt **immer** stehen; geloescht wird ausschliesslich daneben.
//   - Angefasst wird nur der paper-api-Ordner, nichts sonst im Cache.
//   - Laesst sich ein Ordner nicht entfernen (Windows-Sperre, weil ein anderer Gradle-Daemon die
//     JAR offen haelt), gibt es eine Warnung statt eines Abbruchs. Fehlt eine Fassung spaeter doch,
//     laedt Gradle sie ohnehin neu.
//
// Haengt als Finalizer an deployPlugin und ist nie "up to date": Ob der Cache aufgeraeumt gehoert,
// haengt nicht daran, ob sich am Code etwas geaendert hat.
// ---------------------------------------------------------------------------------------------
tasks.register("pruneApiCache") {
    group = "build setup"
    description = "Entfernt alle paper-api-Fassungen im Gradle-Cache ausser der aus Server/server.jar."
    outputs.upToDateWhen { false }

    // Achtung, andere Ablage als in der JAR: Der Cache legt die Group-ID mit Punkten ab
    // (io.papermc.paper/paper-api), nicht als Pfad (io/papermc/paper/paper-api).
    val cacheDir = gradle.gradleUserHomeDir.resolve("caches/modules-2/files-2.1/io.papermc.paper/paper-api")
    val keep = paperApiVersion

    doLast {
        val versions = cacheDir.listFiles { file -> file.isDirectory }
        if (versions == null) {
            // Kein stiller Rueckzug: Ein falscher Pfad saehe sonst aus wie "nichts aufzuraeumen".
            logger.warn("paper-api-Cache nicht gefunden: $cacheDir")
            return@doLast
        }

        versions.filter { it.name != keep }.forEach { old ->
            if (old.deleteRecursively()) {
                logger.lifecycle("Alte paper-api aus dem Cache entfernt: ${old.name}")
            } else {
                logger.warn("paper-api ${old.name} liess sich nicht entfernen - haelt ein anderer Build sie offen?")
            }
        }
    }
}
