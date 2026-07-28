$ErrorActionPreference = "Stop"

$projectDir = "e:\OneShotOneKill\OneShotOneKill_26.1.2"
$binDir = "$projectDir\bin"
$srcDir = "$projectDir\src\main\java"
$resDir = "$projectDir\src\main\resources"
$jarTarget = "$projectDir\OneShotOneKill_26.1.2.jar"
$pluginTarget = "e:\OneShotOneKill\Server\plugins\OneShotOneKill_26.1.2.jar"

Write-Host "==============================================="
Write-Host " Building OneShotOneKill for Paper 26.1.2 ... "
Write-Host "==============================================="

$serverJars = Get-ChildItem -Path "e:\OneShotOneKill\Server\libraries" -Recurse -Filter "*.jar" | Select-Object -ExpandProperty FullName
$extraJars = @(
    "C:\Users\Leopold\.gradle\caches\modules-2\files-2.1\org.jetbrains\annotations\26.0.2\c7ce3cdeda3d18909368dfe5977332dfad326c6d\annotations-26.0.2.jar"
)

$cp = ($serverJars + $extraJars) -join ";"
$sources = Get-ChildItem -Path $srcDir -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName

if (Test-Path $binDir) {
    Remove-Item -Path $binDir -Recurse -Force
}
New-Item -ItemType Directory -Path $binDir | Out-Null

Write-Host "1. Compiling Java source files..."
javac -cp $cp -d $binDir $sources

Write-Host "2. Copying resources (paper-plugin.yml, plugin.yml, map.zip)..."
if (Test-Path $resDir) {
    Copy-Item -Path "$resDir\*" -Destination $binDir -Recurse -Force
}

Write-Host "3. Packaging into JAR file..."
Set-Location -Path $binDir
jar -cf $jarTarget *

Write-Host "4. Deploying JAR to Server/plugins/..."
if (!(Test-Path "e:\OneShotOneKill\Server\plugins")) {
    New-Item -ItemType Directory -Path "e:\OneShotOneKill\Server\plugins" | Out-Null
}
Copy-Item -Path $jarTarget -Destination $pluginTarget -Force
Set-Location -Path "e:\OneShotOneKill"

Write-Host "==============================================="
Write-Host " BUILD & PACKAGING SUCCESSFUL! "
Write-Host " Artifact: $jarTarget "
Write-Host " Plugin:   $pluginTarget "
Write-Host "==============================================="
