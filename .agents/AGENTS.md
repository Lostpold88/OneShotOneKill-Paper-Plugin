# Projekt-Regeln für Minecraft Plugins (OneShotOneKill)

## 📌 Server-Plattform & Native Paper Plugin Vorgaben
- Das Plugin ist ein **100% natives Paper Plugin** (`paper-plugin.yml` mit `api-version: '1.21'`) speziell für **Paper Server** (Paper 26.1.2+ / Minecraft 1.21.x).
- **CRITICAL**: Alle zukünftigen Erweiterungen, Refactorings, Befehle und Korrekturen **MÜSSEN strikt in nativer Paper Plugin Syntax** verfasst werden:
  - **Paper Plugin Bootstrap & Configuration**: Ausschließlich via `paper-plugin.yml` (keine alte `plugin.yml`).
  - **Paper Lifecycle Commands API**: Registrierung aller Befehle dynamisch über `this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)` und Paper's `BasicCommand` Interface.
  - **PersistentDataContainer (PDC)**: `NamespacedKey` zur Item- und Entitäts-Markierung (0% String-Name-Vergleiche oder Bukkit Metadata).
  - **Paper Entity & Region Schedulers**: `player.getScheduler().runDelayed` / `Bukkit.getGlobalRegionScheduler()` anstelle veralteter `Bukkit.getScheduler()` Aufrufe.
  - **Native Paper Scoreboard API**: (`Objective#numberFormat(NumberFormat.blank())`) ohne NMS-Reflection.
  - **Asynchrone Teleportation**: (`player.teleportAsync(location)`) zur Vermeidung von Main-Thread Lagspikes.
  - **Paper Spatial Entity Search**: (`loc.getNearbyPlayers(radius)`, `location.getNearbyEntitiesByType(...)`, `world.getEntitiesByClass(...)`) anstelle von Schleifen über alle Server-Spieler.
  - **Paper Plugin Chunk Tickets**: (`addPluginChunkTicket` / `removePluginChunkTicket`) zur sauberen Speicher- und Chunk-Verwaltung.
  - **Kyori Adventure Component API**: Für alle Chatnachrichten, Titles, Tablisten, Kicks & GUIs.

## 🎯 Plugin-Funktion (OneShotOneKill)
- **1-Hit Kill Minigame**: Dolch (Eisenschwert) & Bogen-Treffer eliminieren Spieler mit 1 Treffer. Nahkampf mit anderen Items verursacht normalen Schaden. Reflektor-Schild wehrt den nächsten tödlichen Treffer ab.
- **11 Spezial-Items**: Radar-Puls, Explosiv-Schuss, Reflektor-Schild, Rauchbombe, Frost-Trap, Minigun, Teleport-Granate, Unsichtbarkeits-Mantel (Vanish), Pfeil-Magnetfeld, Kettenblitz-Schuss, Raketen-Sprung.
- **Item-Modi & Kopfgeld**: `STREAK`, `SPAWN` (Mario-Kart-Boxen), `BOTH`. 5er Streak setzt ein Kopfgeld `[👑]`.
- **Match-Manager & Leaderboard**: Live-Scoreboard, Match-Limits (Kills/Zeit), Siegeshymne & Feuerwerk.

## 🛠️ Build- & Verpackungsprozess
- Nach jeder Änderung an einem Plugin wird der Code **direkt kompiliert (`javac`)**, als `.jar` verpackt und im Server-Plugins-Ordner platziert.
- Der Build erfolgt über das PowerShell-Skript:
  ```powershell
  powershell -ExecutionPolicy Bypass -File build.ps1
  ```
  *(Skript kompiliert mit `javac` gegen `paper-api-26.1.2.build.74-stable.jar`, kopiert `paper-plugin.yml` & `map.zip`, baut `OneShotOneKill_26.1.2.jar` und kopiert sie nach `Server/plugins/`)*.