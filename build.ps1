$ErrorActionPreference = "Stop"

# ===================================================================
#  Duennes Wrapper-Skript.
#
#  Gebaut wird mit Gradle (siehe build.gradle.kts). Dieses Skript
#  existiert nur, weil "build.ps1" in README und .agents/AGENTS.md an
#  vielen Stellen als Build-Befehl steht - es reicht den Aufruf an den
#  Gradle-Wrapper durch. Eine Gradle- oder Kotlin-Installation im PATH
#  ist nicht noetig, nur eine JDK 21+.
# ===================================================================

& (Join-Path $PSScriptRoot "gradlew.bat") deployPlugin
if ($LASTEXITCODE -ne 0) {
    throw "Gradle-Build fehlgeschlagen (Exit-Code $LASTEXITCODE)."
}
