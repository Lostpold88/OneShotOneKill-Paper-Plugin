# Projekt-Regeln für Minecraft Plugins (OneShotOneKill)

## 🔴 Grundsatz (gilt ausnahmslos für jede Änderung)

Dieses Projekt ist ein **100% natives Paper Plugin** (`paper-plugin.yml`, `api-version: '1.21'`)
für **Paper 26.1.2+ / Minecraft 1.21.x**.

**MANDATORY:** Jede Erweiterung, jedes Refactoring, jeder Befehl, Listener, jede GUI und jede
Korrektur **MUSS in 100% nativer Paper API und Paper-Syntax** verfasst werden. Wann immer eine
Paper-Methode oder ein Paper-Feature existiert, **MUSS** diese gegenüber älteren Bukkit-/Spigot-
Lösungen eingesetzt werden. Bukkit-Klassen sind nur dort erlaubt, wo Paper **keine** eigene
Schnittstelle anbietet (z. B. `org.bukkit.Material`, `org.bukkit.Location`).

**Im Zweifel gilt: die modernere, Paper-eigene, Component-basierte, asynchrone Variante.**

---

## 📌 Die 15 verbindlichen API-Vorgaben

1. **Paper Plugin Bootstrap & Configuration**: Ausschließlich via `paper-plugin.yml` (0% alte `plugin.yml`).
2. **Paper Lifecycle Commands API & Brigadier**: Registrierung aller Befehle dynamisch über `this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)` und Paper's `BasicCommand` / `CommandSourceStack` Interface (inkl. `canUse` und `suggest` für Instant-Client-Autovervollständigung). **0% `CommandExecutor`, 0% `TabCompleter`.**
3. **Paper Entity & Region Schedulers**: Thread-safe Aufgabenverwaltung ausschließlich über `player.getScheduler()`, `Bukkit.getGlobalRegionScheduler()` und `Bukkit.getRegionScheduler()` (0% veraltete `Bukkit.getScheduler()` / `BukkitRunnable` Aufrufe).
4. **PersistentDataContainer (PDC)**: `NamespacedKey` zur Item- und Entitäts-Markierung (0% String-Name-Vergleiche, 0% Bukkit Metadata API, 0% NBT-Reflection).
5. **Native Paper Scoreboard API**: `Criteria.DUMMY` statt String-Kriterien, Zeilen als Component über `Score#customName(Component)`, Zahlen ausblenden via `Objective#numberFormat(NumberFormat.blank())` und `Score#numberFormat(NumberFormat.blank())` (0% NMS-Reflection, 0% veraltete Score-Tricks).
6. **Asynchrone Teleportation**: Spieler-Teleports ausschließlich via `player.teleportAsync(location)` zur Vermeidung von Main-Thread Lagspikes. Ergebnisse über `.thenAccept(...)` verarbeiten. Mehrere Teleports abwarten via `CompletableFuture.allOf(...)`.
7. **Paper Spatial Entity Index Engine**: Räumliche Abfragen ausschließlich via `loc.getNearbyPlayers(radius)`, `location.getNearbyEntitiesByType(...)` und `world.getEntitiesByClass(...)` (0% `getNearbyEntities` + `instanceof`, 0% Schleifen über alle Server-Spieler zum Filtern nach Distanz).
8. **Paper Plugin Chunk Tickets**: Speicher- und Chunk-Verwaltung via `world.addPluginChunkTicket` / `removePluginChunkTicket`.
9. **Kyori Adventure & MiniMessage Component API**: Alle Chatnachrichten, Titles, Tablisten, Kicks & Emojis ausschließlich via Kyori `Component`, `MiniMessage.miniMessage().deserialize(...)` und `Audience` (0% `ChatColor`, 0% legacy `§` Paragraphen-Zeichen in Java-Strings, 0% `LegacyComponentSerializer`).
10. **Kyori Component ItemMeta API**: Item-Namen & Lores ausschließlich via `ItemMeta#displayName(Component)` und `ItemMeta#lore(List<Component>)` (0% legacy String ItemMeta).
11. **Paper Custom Inventory & GUI Titles**: GUIs & Menüs ausschließlich via `Bukkit.createInventory(owner, size, Component)` mit Kyori `Component` Titeln; Abgleich über `event.getView().title()`.
12. **Kyori ActionBars, Titles & Sound API**: Benachrichtigungen & Audio-Playback ausschließlich via `Audience#sendActionBar(Component)`, `Audience#showTitle(...)` und `Audience#playSound(net.kyori.adventure.sound.Sound)` mit `Sound.Source` (0% `SoundCategory`, 0% `playSound(Location, org.bukkit.Sound, ...)`).
13. **Paper Event Listening & Cancellations**: Event-Handling strikt mit Paper Event-Methoden und Kyori Components (z. B. `FoodLevelChangeEvent`, `PlayerDeathEvent`, `PlayerKickEvent`, `WorldGameRuleChangeEvent`).
14. **Paper World & GameRule Engine**: Weltenverwaltung über die Paper World API und die moderne `org.bukkit.GameRules` Registry (0% `org.bukkit.GameRule`, 0% Legacy-Ticks).
15. **0% Bukkit/Spigot Legacy Code**: Kein einziger veralteter Bukkit/Spigot Call, wenn eine native Paper API Schnittstelle existiert. Insbesondere **0% `entity.spigot()`**.

---

## ✅ Pflicht-Vorgehen vor jeder Änderung

**Nicht raten, nachsehen.** Die Paper-API ändert sich zwischen Versionen; Methoden aus dem
Gedächtnis sind regelmäßig falsch. Vor der Verwendung einer API **immer** gegen die tatsächliche
JAR prüfen:

```bash
J=Server/libraries/io/papermc/paper/paper-api/26.1.2.build.74-stable/paper-api-26.1.2.build.74-stable.jar
javap -cp $J org.bukkit.entity.Player | grep -i "<methode>"
```

Das deckt in Sekunden auf, ob eine Methode existiert, welche Überladungen es gibt und ob sie
veraltet ist. Adventure-Klassen liegen in `Server/libraries/net/kyori/adventure-api/...`.

---

## 🔍 Nachschlagetabelle: Legacy → Paper

| Statt (Legacy) | Verwende (Paper / Adventure) |
| :--- | :--- |
| `plugin.yml` | `paper-plugin.yml` |
| `CommandExecutor` / `TabCompleter` | `BasicCommand` + `LifecycleEvents.COMMANDS` |
| `BukkitRunnable`, `Bukkit.getScheduler()` | `player.getScheduler()`, `Bukkit.getGlobalRegionScheduler()` |
| `ChatColor`, `§`-Codes | `Component`, `MiniMessage` |
| `LegacyComponentSerializer` zum Item-Erkennen | `PersistentDataContainer` + `NamespacedKey` |
| `setDisplayName(String)` / `setLore(List<String>)` | `displayName(Component)` / `lore(List<Component>)` |
| `playSound(Location, org.bukkit.Sound, SoundCategory, …)` | `Audience#playSound(Sound.sound(…, Sound.Source.…))` |
| `getNearbyEntities(...)` + `instanceof Player` | `loc.getNearbyPlayers(radius)` |
| `org.bukkit.GameRule` | `org.bukkit.GameRules` |
| `entity.spigot().respawn()` | GameRule `IMMEDIATE_RESPAWN` |
| `registerNewObjective(name, "dummy", …)` | `registerNewObjective(name, Criteria.DUMMY, Component)` |
| `getType().name().contains("…")` | `Tag.…isTagged(material)` oder direkter `Material`-Vergleich |
| `world.dropItem(loc, stack)` + nachträgliche Setter | `world.dropItem(loc, stack, item -> { … })` |

---

## ⚠️ Dokumentierte Ausnahmen

Diese Regeln **blind** anzuwenden hat im Projekt nachweislich Bugs erzeugt. Abweichungen sind hier
erlaubt und **müssen im Code kommentiert werden**:

- **Regel 6 gilt Spielern, nicht jeder Entity.** Wird eine Entity jeden Tick in derselben Welt
  umpositioniert (Zielchunk garantiert geladen, Aufruf bereits auf dem Main-Thread), ist
  `entity.teleport(loc)` korrekt. `teleportAsync` mehrfach anzustoßen, bevor der vorherige Teleport
  aufgelöst ist, blockiert die Bewegung.
- **`setAI(false)` niemals auf `EnderDragon`.** Das NoAI-Flag wird zum Client synchronisiert; der
  Drache ist ein mehrteiliges Modell, dessen Segmente clientseitig in `aiStep()` nachgeführt werden.
  Mit NoAI bleibt das Modell optisch stehen, obwohl die Entity serverseitig korrekt wandert.
  Aggression stattdessen über `setAware(false)`, `setInvulnerable(true)` und Cancelling des Schadens.
- **`EnderDragon#getBossBar()` ist `null`** außerhalb einer End-Welt mit Drachenkampf. Immer prüfen.
- **`EnderDragon.Phase.HOVER`** verankert den Drachen an einem festen Schwebepunkt. Nach einem
  Teleport muss die Phase neu gesetzt werden, sonst fliegt er zurück.
- **`Objective#getScore` nimmt nur `String`.** Component-Zeilen entstehen über
  `Score#customName(Component)`; der Entry-String dient nur als unsichtbarer Schlüssel.
- **Eigene Listener können eigene Features blockieren.** Beispiel: `CreatureSpawnEvent` global zu
  canceln verhindert auch plugin-eigene Spawns – `SpawnReason.CUSTOM` ausnehmen.

---

## 🧪 Abnahmekriterien

Eine Änderung gilt erst als fertig, wenn **alle** Punkte erfüllt sind:

1. `build.ps1` läuft fehlerfrei durch.
2. **Null Deprecation-Warnungen** über den gesamten Quellcode:
   ```powershell
   javac -encoding UTF-8 -Xlint:deprecation,removal -cp <alle Server/libraries JARs> -d <temp> <alle .java>
   ```
   Jede Warnung ist ein Regelverstoß und muss behoben werden, nicht unterdrückt.
3. Grep-Gegenprobe ist leer: `§`, `ChatColor`, `BukkitRunnable`, `Bukkit.getScheduler()`,
   `SoundCategory`, `.spigot()`, `CommandExecutor`, `TabCompleter`, `getNearbyEntities(`.
4. Bei Verhaltensänderungen: `Server/logs/latest.log` nach dem Start auf `ERROR`/`Exception` prüfen.

**Bei Fehlverhalten im Spiel nicht raten.** Erst Belege sammeln (Log lesen, notfalls temporäre
Diagnose-Ausgaben einbauen), dann fixen, danach die Diagnose wieder entfernen. Zwei falsche
Vermutungen kosten mehr Zeit als eine Messung.

---

## 🛠️ Build- & Verpackungsprozess

Nach jeder Änderung wird der Code **direkt kompiliert (`javac`)**, als `.jar` verpackt und im
Server-Plugins-Ordner platziert:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Das Skript kompiliert mit `javac` (UTF-8) gegen `paper-api-26.1.2.build.74-stable.jar`, kopiert
`paper-plugin.yml`, `Standard.zip` und `DustPvP.zip`, baut `OneShotOneKill_26.1.2.jar` und kopiert
sie nach `Server/plugins/`. Alle Pfade sind relativ zu `$PSScriptRoot`; das Repository darf
verschoben oder umbenannt werden. Bricht `javac` oder `jar` ab, schlägt der Build fehl.

> Ein laufender Server lädt die JAR **nicht** neu – Änderungen sind erst nach einem Neustart aktiv.
