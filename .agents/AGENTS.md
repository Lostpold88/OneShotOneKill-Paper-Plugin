# Projekt-Regeln für Minecraft Plugins (OneShotOneKill)

## 📌 Strikte Server-Plattform & Native Paper API Vorgaben (100% Paper)
- Das Plugin ist ein **100% natives Paper Plugin** (`paper-plugin.yml` mit `api-version: '1.21'`) speziell für **Paper Server** (Paper 26.1.2+ / Minecraft 1.21.x).
- **MANDATORY / ABSOLUTER ZWANG**: Jede Erweiterung, Refactoring, Befehl, Listener, GUI und Korrektur **MUSS ausnahmslos in 100% nativer Paper API & Syntax** verfasst werden. Wann immer eine Paper-Methode oder ein Paper-Feature existiert, **MUSS** diese gegenüber alten Bukkit/Spigot-Lösungen zwingend eingesetzt werden:
  1. **Paper Plugin Bootstrap & Configuration**: Ausschließlich via `paper-plugin.yml` (0% alte `plugin.yml`).
  2. **Paper Lifecycle Commands API & Brigadier**: Registrierung aller Befehle dynamisch über `this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)` und Paper's `BasicCommand` / `CommandSourceStack` Interface (inkl. `canUse` und `suggest` für Instant-Client-Autovervollständigung).
  3. **Paper Entity & Region Schedulers**: Thread-safe Aufgabenverwaltung ausschließlich über `player.getScheduler()`, `Bukkit.getGlobalRegionScheduler()` und `Bukkit.getRegionScheduler()` (0% veraltete `Bukkit.getScheduler()` / `BukkitRunnable` Aufrufe).
  4. **PersistentDataContainer (PDC)**: `NamespacedKey` zur Item- und Entitäts-Markierung (0% String-Name-Vergleiche, 0% Bukkit Metadata API, 0% NBT-Reflection).
  5. **Native Paper Scoreboard API**: Formatspezifisches Rendering via `Objective#numberFormat(NumberFormat.blank())` und `Score#numberFormat(NumberFormat.blank())` (0% NMS-Reflection, 0% veraltete Score-Tricks).
  6. **Asynchrone Teleportation**: Preloading und Teleportation ausschließlich via `player.teleportAsync(location)` zur Vermeidung von Main-Thread Lagspikes.
  7. **Paper Spatial Entity Index Engine**: Räumliche Abfragen ausschließlich via `loc.getNearbyPlayers(radius)`, `location.getNearbyEntitiesByType(...)` und `world.getEntitiesByClass(...)` (0% Schleifen über alle Server-Spieler).
  8. **Paper Plugin Chunk Tickets**: Speicher- und Chunk-Verwaltung via `world.addPluginChunkTicket` / `removePluginChunkTicket`.
  9. **Kyori Adventure & MiniMessage Component API**: Alle Chatnachrichten, Titles, Tablisten, Kicks & Emojis ausschließlich via Kyori `Component`, `MiniMessage.miniMessage().deserialize(...)` und `Audience` (0% `ChatColor`, 0% legacy `§` Paragraphen-Zeichen in Java-Strings).
  10. **Kyori Component ItemMeta API**: Item-Namen & Lores ausschließlich via `ItemMeta#displayName(Component)` und `ItemMeta#lore(List<Component>)` (0% legacy String ItemMeta).
  11. **Paper Custom Inventory & GUI Titles**: GUIs & Menüs ausschließlich via `Bukkit.createInventory(owner, size, Component)` mit Kyori `Component` Titeln.
  12. **Kyori ActionBars, Titles & Sound API**: Benachrichtigungen & Audio-Playback ausschließlich via `Audience#sendActionBar(Component)`, `Audience#showTitle(...)` und `Audience#playSound(...)`.
  13. **Paper Event Listening & Cancellations**: Event-Handling strikt mit Paper Event-Methoden und Kyori Components (z. B. `FoodLevelChangeEvent`, `PlayerDeathEvent`, `PlayerKickEvent`).
  14. **Paper World & GameRule Engine**: Weltenverwaltung ausschließlich über Paper World API und moderne GameRules (0% Legacy-Ticks).
  15. **0% Bukkit/Spigot Legacy Code**: Kein einziger veralteter Bukkit/Spigot Call, wenn eine native Paper API Schnittstelle existiert.

## 🛠️ Build- & Verpackungsprozess
- Nach jeder Änderung an einem Plugin wird der Code **direkt kompiliert (`javac`)**, als `.jar` verpackt und im Server-Plugins-Ordner platziert.
- Der Build erfolgt über das PowerShell-Skript:
  ```powershell
  powershell -ExecutionPolicy Bypass -File build.ps1
  ```
  *(Skript kompiliert mit `javac` gegen `paper-api-26.1.2.build.74-stable.jar`, kopiert `paper-plugin.yml` & `map.zip`, baut `OneShotOneKill_26.1.2.jar` und kopiert sie nach `Server/plugins/`)*.