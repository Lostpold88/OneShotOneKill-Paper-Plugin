@echo off
title Minecraft Server (Paper Java 25)

REM ===================================================================
REM  Aus dem Server-Ordner ist nur dieses Skript versioniert (siehe
REM  .gitignore). Nach einem frischen Clone fehlen daher sowohl der
REM  Paper-Server als auch die Plugin-JAR - ohne diese Hinweise bricht
REM  der Start kommentarlos ab bzw. laeuft ohne OneShotOneKill.
REM ===================================================================
if not exist "%~dp0server.jar" (
  echo.
  echo ###################################################################
  echo  ABBRUCH: server.jar fehlt.
  echo.
  echo  Der Paper-Server gehoert nicht ins Repository. Lade ihn hier
  echo  herunter und lege ihn als server.jar in diesen Ordner:
  echo      https://papermc.io/downloads/paper
  echo.
  echo  Benoetigt wird Paper 26.2 oder neuer und ein JDK 25.
  echo  Beim ersten Start legt Paper eula.txt an - darin eula=true
  echo  setzen, dann erneut starten.
  echo ###################################################################
  echo.
  pause
  exit /b 1
)

REM  Bewusst mit Platzhalter: Der JAR-Name traegt die Minecraft-Version und
REM  wechselt mit dem Server automatisch mit (OneShotOneKill_26.2.jar,
REM  _26.3.jar, ...). Ein fester Name wuerde nach dem naechsten Paper-Update
REM  faelschlich melden, das Plugin fehle.
if not exist "%~dp0plugins\OneShotOneKill_*.jar" (
  echo.
  echo ###################################################################
  echo  HINWEIS: plugins\OneShotOneKill_^<version^>.jar fehlt.
  echo.
  echo  Die JAR ist ein Build-Artefakt - 23 MB, weil die beiden Maps
  echo  eingebettet sind - und wird nicht versioniert. Bauen und
  echo  hierher kopieren im Projektverzeichnis mit:
  echo      .\build.ps1
  echo.
  echo  Der Build braucht kein laufendes Paper: Die API kommt von
  echo  repo.papermc.io, Gradle und den Kotlin-Compiler holt sich der
  echo  mitgelieferte Wrapper selbst. Noetig ist nur ein JDK 25.
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

java -Xms6144M -Xmx6144M -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+ParallelRefProcEnabled -XX:+PerfDisableSharedMem -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1HeapRegionSize=8M -XX:G1HeapWastePercent=5 -XX:G1MaxNewSizePercent=40 -XX:G1MixedGCCountTarget=4 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1NewSizePercent=30 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -XX:MaxGCPauseMillis=200 -XX:MaxTenuringThreshold=1 -XX:SurvivorRatio=32 -Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true -jar server.jar --nogui

pause
