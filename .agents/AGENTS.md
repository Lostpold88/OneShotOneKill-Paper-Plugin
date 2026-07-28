# Projekt-Regeln für Minecraft Plugins (OneShotOneKill)

## 📌 Server-Plattform & API-Vorgaben
- Das Plugin ist speziell für **Paper Server** (Paper 26.1.2+ / Minecraft 1.21.x) entwickelt.
- Bei allen Erweiterungen, Refactorings und Korrekturen **müssen bevorzugt die Vorzüge der Paper API** genutzt werden:
  - **PersistentDataContainer (PDC)** mit `NamespacedKey` zur Item-/Entity-Markierung (keine fehleranfällige String-Namensprüfung oder Bukkit Metadata).
  - **Native Paper Scoreboard API** (`Objective#numberFormat(NumberFormat.blank())`) ohne NMS-Reflection.
  - **Asynchrone Teleportation** (`player.teleportAsync(location)`) zur Vermeidung von Main-Thread Lagspikes.
  - **Paper Plugin Chunk Tickets** (`addPluginChunkTicket` / `removePluginChunkTicket`) zur sauberen Speicher- und Chunk-Verwaltung.
  - **Kyori Adventure Component API** für alle Chatnachrichten, Titles, Tablisten & Guis.

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
  *(Skript kompiliert mit `javac` gegen `paper-api-26.1.2.build.74-stable.jar`, kopiert `plugin.yml` & `map.zip`, baut `OneShotOneKill_26.1.2.jar` und kopiert sie nach `Server/plugins/`)*.