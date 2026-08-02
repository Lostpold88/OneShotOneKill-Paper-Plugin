@echo off
title Minecraft Server (Paper Java 25)

REM ===================================================================
REM  Die Plugin-JAR ist ein Build-Artefakt und liegt bewusst NICHT im
REM  Repository (20 MB pro Commit, siehe .gitignore). Nach einem frischen
REM  Clone fehlt sie also - ohne diesen Hinweis startet der Server
REM  kommentarlos ohne OneShotOneKill.
REM ===================================================================
if not exist "%~dp0plugins\OneShotOneKill_26.2.jar" (
  echo.
  echo ###################################################################
  echo  HINWEIS: plugins\OneShotOneKill_26.2.jar fehlt.
  echo.
  echo  Die JAR wird nicht versioniert, sondern gebaut:
  echo      powershell -ExecutionPolicy Bypass -File "%~dp0..\build.ps1"
  echo.
  echo  Der Build braucht Server\libraries - diesen Ordner laedt Paper
  echo  beim ersten Start selbst herunter. Beim allerersten Mal also:
  echo  Server jetzt starten, wieder beenden, bauen, erneut starten.
  echo ###################################################################
  echo.
  pause
)

REM ===================================================================
REM  Aikar-Flags fuer Paper - auf Performance optimiert.
REM
REM  Grundprinzip: FESTER Heap. Xms und Xmx sind identisch und
REM  AlwaysPreTouch fasst den Speicher beim Start einmal komplett an.
REM  Dadurch gibt es im laufenden Betrieb keine Heap-Groessenaenderungen
REM  und keine Page-Faults - also keine TPS-Dellen aus dieser Richtung.
REM
REM  Der Server belegt damit dauerhaft 6 GB. Das ist beabsichtigt und bei
REM  31 GB System-RAM unproblematisch.
REM
REM  Die G1-Werte unten gelten fuer Heaps UNTER 12 GB. Wer auf 12 GB oder
REM  mehr geht, muss sie anpassen: G1NewSizePercent=40,
REM  G1MaxNewSizePercent=50, G1HeapRegionSize=16M, G1ReservePercent=15,
REM  InitiatingHeapOccupancyPercent=20.
REM
REM  -XX:+UnlockExperimentalVMOptions MUSS vor G1NewSizePercent und
REM  G1MaxNewSizePercent stehen, sonst weist die JVM sie ab.
REM ===================================================================

java ^
  -Xms6G -Xmx6G ^
  --add-modules=jdk.incubator.vector ^
  -XX:+UseG1GC ^
  -XX:+ParallelRefProcEnabled ^
  -XX:MaxGCPauseMillis=200 ^
  -XX:+UnlockExperimentalVMOptions ^
  -XX:+DisableExplicitGC ^
  -XX:+AlwaysPreTouch ^
  -XX:G1NewSizePercent=30 ^
  -XX:G1MaxNewSizePercent=40 ^
  -XX:G1HeapRegionSize=8M ^
  -XX:G1ReservePercent=20 ^
  -XX:G1HeapWastePercent=5 ^
  -XX:G1MixedGCCountTarget=4 ^
  -XX:InitiatingHeapOccupancyPercent=15 ^
  -XX:G1MixedGCLiveThresholdPercent=90 ^
  -XX:G1RSetUpdatingPauseTimePercent=5 ^
  -XX:SurvivorRatio=32 ^
  -XX:+PerfDisableSharedMem ^
  -XX:MaxTenuringThreshold=1 ^
  -Dusing.aikars.flags=https://mcflags.emc.gs ^
  -Daikars.new.flags=true ^
  -jar server.jar nogui

pause
