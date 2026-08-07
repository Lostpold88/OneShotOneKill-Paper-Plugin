import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "de.oneshotonekill"
version = "1.0.0-26.2"

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
    compileOnly(libs.paper.api)

    // In die JAR gebuendelt und gleichzeitig der Compile-Klassenpfad
    bundled(libs.kotlin.stdlib)
    compileOnly(libs.kotlin.stdlib)
}

kotlin {
    compilerOptions {
        // Java 25 ist Pflicht, nicht Geschmackssache: paper-api 26.2 deklariert
        // org.gradle.jvm.version = 25, ein niedrigeres Ziel laesst Gradle die Abhaengigkeit gar
        // nicht erst aufloesen. Passt zur installierten Zulu 25, es wird keine zweite JDK gebraucht.
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

// Der Dateiname ist an vielen Stellen dokumentiert (README, Server/plugins) und bleibt stabil.
val pluginJarName = "OneShotOneKill_26.2.jar"

tasks.processResources {
    // paper-plugin.yml zieht die Version aus dem Build, damit sie nicht doppelt gepflegt wird.
    // Der Wert wird zur Konfigurationszeit festgehalten - ein project-Zugriff zur Ausfuehrungszeit
    // ist mit dem Configuration Cache unvereinbar (und in Gradle 10 ein Fehler).
    val pluginVersion = project.version.toString()
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
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
