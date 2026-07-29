$ErrorActionPreference = "Stop"

# Alle Pfade relativ zum Skript-Verzeichnis (kein hart kodierter Repository-Pfad).
$rootDir = $PSScriptRoot
$projectDir = Join-Path $rootDir "OneShotOneKill_26.1.2"
$binDir = Join-Path $projectDir "bin"
$srcDir = Join-Path $projectDir "src\main\java"
$resDir = Join-Path $projectDir "src\main\resources"
$jarTarget = Join-Path $projectDir "OneShotOneKill_26.1.2.jar"
$serverDir = Join-Path $rootDir "Server"
$pluginsDir = Join-Path $serverDir "plugins"
$pluginTarget = Join-Path $pluginsDir "OneShotOneKill_26.1.2.jar"

Write-Host "==============================================="
Write-Host " Building OneShotOneKill for Paper 26.1.2 ... "
Write-Host "==============================================="

$serverJars = Get-ChildItem -Path (Join-Path $serverDir "libraries") -Recurse -Filter "*.jar" | Select-Object -ExpandProperty FullName

# Optionale Zusatz-JARs (z. B. JetBrains Annotations) nur einbinden, wenn vorhanden.
$extraJars = @(
    "C:\Users\Leopold\.gradle\caches\modules-2\files-2.1\org.jetbrains\annotations\26.0.2\c7ce3cdeda3d18909368dfe5977332dfad326c6d\annotations-26.0.2.jar"
) | Where-Object { Test-Path $_ }

$cp = ($serverJars + $extraJars) -join ";"
$sources = Get-ChildItem -Path $srcDir -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName

if (Test-Path $binDir) {
    Remove-Item -Path $binDir -Recurse -Force
}
New-Item -ItemType Directory -Path $binDir | Out-Null

Write-Host "1. Compiling Java source files..."
javac -encoding UTF-8 -cp $cp -d $binDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

Write-Host "2. Copying resources (paper-plugin.yml, Standard.zip, DustPvP.zip)..."
if (Test-Path $resDir) {
    Copy-Item -Path "$resDir\*" -Destination $binDir -Recurse -Force
}

Write-Host "3. Packaging into JAR file..."
Push-Location -Path $binDir
try {
    jar -cf $jarTarget *
    if ($LASTEXITCODE -ne 0) {
        throw "jar failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Host "4. Deploying JAR to Server/plugins/..."
if (!(Test-Path $pluginsDir)) {
    New-Item -ItemType Directory -Path $pluginsDir | Out-Null
}
Copy-Item -Path $jarTarget -Destination $pluginTarget -Force

Write-Host "==============================================="
Write-Host " BUILD & PACKAGING SUCCESSFUL! "
Write-Host " Artifact: $jarTarget "
Write-Host " Plugin:   $pluginTarget "
Write-Host "==============================================="
