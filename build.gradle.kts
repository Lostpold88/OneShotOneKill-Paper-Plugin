import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "de.oneshotonekill"

// =============================================================================================
// Paper-API-Version: automatisch aus dem lokalen Server
//
// Gebaut wird gegen genau die paper-api, die der Server auch faehrt. Sonst kompiliert man gegen
// eine andere API als die, gegen die das Plugin spaeter laeuft - und Abweichungen fallen erst zur
// Laufzeit auf, im schlimmsten Fall als NoSuchMethodError.
//
// Quellen in dieser Reihenfolge:
//   1. Server/server.jar - die Paperclip-JAR fuehrt ihre Bibliotheken unter
//      META-INF/libraries/... mit. Das ist die Quelle der Wahrheit und funktioniert, OHNE dass
//      der Server je gestartet wurde.
//   2. Server/libraries/... - der Ordner, den Paper beim ersten Start anlegt. Greift, wenn nur
//      die entpackten Bibliotheken vorliegen.
//   3. gradle/libs.versions.toml - die gepinnte Fassung. Sie gilt nach einem frischen Clone, wo
//      es gar keinen Server gibt; der Build laeuft damit auch ohne lokalen Paper-Server.
//
// Weicht 1./2. vom Pin ab, meldet der Build das und verweist auf ./gradlew syncPaperVersion.
//
// ⚠️ Die Erkennung liest Dateien zur Konfigurationszeit. Das ist korrekt, solange der
// Configuration Cache aus ist (Gradle-Standard, hier nicht aktiviert): Der Build wertet das Skript
// dann bei jedem Lauf neu aus. Wird der Cache jemals eingeschaltet, muss dieser Block auf eine
// ValueSource umgestellt werden - sonst friert die Version beim ersten Lauf ein und ein
// Server-Update ginge unbemerkt an der Erkennung vorbei. (Der Cache scheitert derzeit ohnehin an
// tasks.jar, das fuer das stdlib-Buendeln eine Skript-Referenz haelt.)
// =============================================================================================

/** Version plus Fundort, damit der Build sagen kann, woher der Wert stammt. */
data class PaperApiSource(val version: String, val origin: String)

val paperApiPath = "io/papermc/paper/paper-api"

fun paperApiFromServerJar(): PaperApiSource? {
    val serverJar = layout.projectDirectory.file("Server/server.jar").asFile
    if (!serverJar.isFile) return null

    val pattern = Regex("^META-INF/libraries/$paperApiPath/([^/]+)/")
    return ZipFile(serverJar).use { zip ->
        zip.entries().asSequence()
            .firstNotNullOfOrNull { pattern.find(it.name)?.groupValues?.get(1) }
            ?.let { PaperApiSource(it, "Server/server.jar") }
    }
}

fun paperApiFromServerLibraries(): PaperApiSource? =
    layout.projectDirectory.dir("Server/libraries/$paperApiPath").asFile
        .listFiles { file -> file.isDirectory }
        ?.maxByOrNull { it.name }
        ?.let { PaperApiSource(it.name, "Server/libraries") }

val pinnedPaperApi: String = libs.versions.paper.get()

val paperApi: PaperApiSource =
    paperApiFromServerJar()
        ?: paperApiFromServerLibraries()
        ?: PaperApiSource(pinnedPaperApi, "gradle/libs.versions.toml (kein lokaler Server)")

/** Aus "26.2.build.110-stable" wird "26.2" - der Wert fuer api-version in paper-plugin.yml. */
val minecraftVersion: String = paperApi.version.substringBefore(".build.")

version = "1.0.0-$minecraftVersion"

logger.lifecycle("Paper-API: ${paperApi.version}  (aus ${paperApi.origin})")
if (paperApi.version != pinnedPaperApi) {
    logger.lifecycle(
        "  Der Pin in gradle/libs.versions.toml steht auf $pinnedPaperApi. " +
            "Uebernehmen mit: ./gradlew syncPaperVersion"
    )
}

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
    // Bewusst nicht libs.paper.api: Die Version kommt aus der Erkennung oben, damit gegen genau
    // die API kompiliert wird, die der Server faehrt.
    compileOnly("io.papermc.paper:paper-api:${paperApi.version}")

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
// syncPaperVersion: erkannte Version in den Catalog schreiben
//
// Der Build kompiliert ohnehin schon gegen die erkannte Version. Dieser Task haelt zusaetzlich den
// Pin nach, der nach einem frischen Clone ohne Server greift - bewusst als eigener Aufruf, denn
// ein Build soll keine Quelldateien im Arbeitsbaum veraendern.
// ---------------------------------------------------------------------------------------------
tasks.register("syncPaperVersion") {
    group = "build setup"
    description = "Schreibt die aus dem Server erkannte paper-api-Version in gradle/libs.versions.toml."

    val catalogFile = layout.projectDirectory.file("gradle/libs.versions.toml").asFile
    val detected = paperApi.version
    val origin = paperApi.origin
    val pinned = pinnedPaperApi

    doLast {
        if (detected == pinned) {
            logger.lifecycle("Pin ist bereits aktuell: $pinned")
            return@doLast
        }
        if (origin.startsWith("gradle/")) {
            logger.lifecycle("Kein lokaler Server gefunden - es gibt nichts zu uebernehmen.")
            return@doLast
        }

        val text = catalogFile.readText()
        val updated = text.replace(Regex("""(?m)^paper\s*=\s*".*"$"""), "paper = \"$detected\"")
        if (updated == text) {
            throw GradleException("Zeile 'paper = \"…\"' in ${catalogFile.name} nicht gefunden.")
        }
        catalogFile.writeText(updated)
        logger.lifecycle("gradle/libs.versions.toml: paper $pinned -> $detected (aus $origin)")
    }
}

// ---------------------------------------------------------------------------------------------
// deployPlugin: bauen und in den Testserver kopieren - der bisherige Ein-Befehl-Workflow.
// ---------------------------------------------------------------------------------------------
tasks.register<Copy>("deployPlugin") {
    group = "distribution"
    description = "Baut die Plugin-JAR und kopiert sie nach Server/plugins/."
    from(tasks.jar)
    into(layout.projectDirectory.dir("Server/plugins"))

    doLast {
        logger.lifecycle("Plugin deployt nach Server/plugins/$pluginJarName")
        logger.lifecycle("Ein laufender Server laedt die JAR nicht neu - Neustart noetig.")
    }
}
