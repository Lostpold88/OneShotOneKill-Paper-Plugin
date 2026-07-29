# 🎯 OneShotOneKill (OSOK) - Native Paper 26.1.2 Plugin & Server

Ein 100% **natives Paper 26.1.2 PvP Minigame Plugin** (1.21.x) mit `paper-plugin.yml`, Paper Lifecycle Commands API, Kyori Adventure Components und High-Performance Asynchronitäts-Features.

---

## 🗺️ Arenen

Zwei eingebaute Maps, umschaltbar im laufenden Betrieb per `/osok map`:

| Map | Welt | Arena-Ecke 1 | Arena-Ecke 2 | Lobby | Max. Item-Höhe |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Standard** | `OSOK_Standard` | `221 / 58 / -50` | `287 / 64 / -106` | `223.5 / 48.0 / 55.5` | `61` |
| **DustPvP** | `OSOK_DustPvP` | `-25 / 70 / 33` | `25 / 70 / -33` | `0.5 / 90.0 / 0.5` | `71` |

Die Reihenfolge der Ecken ist egal – sie werden automatisch normalisiert. Die Arena-Grenzen steuern
Kampfzone, Spielerspawns, Item-Spawns und die Pausensperre.

> **Hinweis zur DustPvP-Lobby**: Sie liegt bei `Y=90` direkt über der Arena-Grundfläche. Damit sie nicht
> als „innerhalb der Arena" gilt, ist der vertikale Spielraum über der Arena-Oberkante auf 12 Blöcke
> begrenzt (Arena-Y effektiv 68–82). Ein Raketen-Sprung (~15 Blöcke) kann diese Kante kurz überschreiten.

---

## ⚡ Features & Spielmechaniken

- **🎯 1-Hit Kill Kampfsystem**:
  - Jeder Treffer mit dem **OneShot-Dolch** (Eisenschwert) oder einem **Bogenpfeil** eliminiert den Gegner sofort mit 1 Schlag.
  - **🩸 Standard Blut-Splash Killeffekt**: Bei jedem Kill wird automatisch ein dichter Blut-Splash Partikeleffekt mit spritzenden Block-Trümmern und Sound-Feedback am Ort des Opfers ausgelöst.
  - Nahkampftreffer mit anderen Gegenständen (Fäuste, Wolle, Bogen-Nahkampf) verursachen normalen Vanilla-Schaden.
  - Treffer mit dem Bogen füllen automatisch 1 Pfeil im Inventar auf.
  - **Kampf nur innerhalb der Arena-Grenzen**: Außerhalb ist jeglicher Schaden deaktiviert – auch Sturzschaden.
  - **Respawn ohne Ladebildschirm**: Ein Treffer tötet den Spieler bewusst *nicht* im Sinne von Minecraft. Der Schaden wird gecancelt, und Statistik, Kill-Effekt und Rückkehr in die Arena wickelt der `EliminationManager` selbst ab. Da kein echter Tod stattfindet, sendet der Server kein Respawn-Paket – der Bildschirm „Welt wird geladen" entfällt vollständig. Auch tödlicher Schaden ohne Angreifer (z. B. Sturz) wird so abgefangen. `PlayerDeathEvent` bleibt nur noch als Auffangnetz für echte Tode (`/kill`, Void) bestehen.
  - **Waffen-Vergabe erst ab `/osok start`**: Vor dem Match-Start oder während einer Pause besitzen Spieler keine Waffen. Bei `/osok pause` werden Dolch, Bogen und Pfeile entfernt – **Spezial-Items bleiben im Inventar erhalten**.
  - **Kein Hunger & Volle Sättigung**: Spieler verlieren keinen Hunger (`FoodLevelChangeEvent` nativ gecancelt).

- **⏸️ Pause-System (`/osok pause`)**:
  - Pausiert / Fortsetzt das laufende Match, der Match-Timer friert ein.
  - Teleportiert alle Spieler in die **Lobby der aktiven Map**.
  - Das Betreten des Arena-Bereichs wird während der Pause blockiert.
  - Beim Fortsetzen werden alle Spieler wieder **zufällig verteilt in die Arena** teleportiert.

- **🎁 11 Spezial-Items (Powerups)**:
  1. 👁️ **Radar-Puls** *(Enderauge)*: Lässt alle Feinde für 30s aufleuchten. **Geheim**: Umgesetzt über das Glow-Flag der Entity statt über `PotionEffectType.GLOWING` – dadurch erscheint beim Betroffenen **kein Eintrag im Effekt-Fenster des Inventars**, kein HUD-Icon und keine Partikel. (Einzige verbleibende Eigenwahrnehmung: der eigene Umriss in der Third-Person-Ansicht `F5`.)
  2. 💣 **Explosiv-Schuss** *(TNT)*: Nächster Pfeil erzeugt eine Explosion am Einschlagort.
  3. 🛡️ **Reflektor-Schild** *(Netherstern)*: Blockiert den nächsten tödlichen Treffer inkl. Schildbruch-Effekt.
  4. 💨 **Rauchbombe** *(Schneeball)*: Erzeugt dichten Lagerfeuer-Rauch und teleportiert zufällig in die Arena.
  5. ❄️ **Frost-Trap** *(Gewichtete Druckplatte)*: Friert den ersten betretenden Spieler für 7s fest.
  6. 🔫 **Minigun** *(Lohenrute)*: Feuert 8 Sekunden lang durchgehend Pfeile ab (alle 2 Ticks).
  7. 🔮 **Teleport-Granate** *(Enderperle)*: Teleportiert und stößt nahestehende Spieler zurück.
  8. 👻 **Unsichtbarkeits-Mantel** *(Phantom-Membran)*: Echter Vanish für 15s (`hidePlayer`).
  9. 🧲 **Pfeil-Magnetfeld** *(Herz des Meeres)*: Lenkt gegnerische Pfeile im Umkreis von 8 Blöcken für 15s ab.
  10. ⚡ **Kettenblitz-Schuss** *(Blitzableiter)*: Nächster Treffer beschwört Blitze und springt auf bis zu 2 nahe Feinde über.
  11. 🚀 **Raketen-Sprung** *(Feuerwerksrakete)*: Katapultiert den Spieler hoch (inkl. 20s Fallschutz & Air-Sprint).
  12. 🐉 **Tarnkappenbomber** *(Drachenkopf)*: Öffnet ein Auswahlmenü mit allen anderen Spielern. Über dem gewählten Ziel erscheint ein **Ender-Drache**, der ihm **10 Sekunden** lang folgt und dabei durchgehend **TNT** abwirft. Der Drache greift niemanden an (AI und Wahrnehmung deaktiviert, sein Schaden wird zusätzlich gecancelt). Das TNT **zerstört keine Blöcke** – die Blockliste der Explosion wird geleert – richtet aber vollen Schaden an und **zündet sofort bei Bodenkontakt** statt per Zeitzünder. Das Item wird erst beim Auswählen eines Ziels verbraucht, nicht beim Öffnen des Menüs.

- **📦 Item-Boxen am Arena-Boden**:
  - Spawnen alle 30 Sekunden als Mario-Kart-artige Boxen mit rotierendem Partikelring.
  - **Leuchten sichtbar** über `Entity#setGlowing(true)` – der Leuchtrahmen ist auch durch Wände erkennbar.
  - Spawnen **ausschließlich auf dem Arena-Boden**: Die Bodensuche scannt von unten nach oben und landet daher nie auf Dächern, Brücken oder Plattformen. Zusätzlich begrenzt eine **maximale Item-Höhe pro Map** (siehe Arena-Tabelle) den Spawn auf die Grundfläche. Findet die Suche keinen freien Bodenplatz, wird der Spawn übersprungen (statt das Item an falscher Stelle abzulegen).
  - Liegen dank aktiver Gravitation flach auf dem Boden auf und schweben nicht.
  - Verschwinden nach 60 Sekunden automatisch.

- **⚙️ Item-Modi (`/osok itemmode <streak|spawn|both>`)**:
  - `STREAK`: Spezial-Item alle 3 Kills.
  - `SPAWN`: 30s-Boden-Item-Boxen.
  - `BOTH`: Beides kombiniert.
  - **`BOTH` ist der Standard und wird bei jedem `/osok start` erzwungen** – für beide Arenen gleichermaßen. Ein manueller Moduswechsel gilt damit nur bis zum nächsten Match-Start.

- **👑 Kopfgeld-System**:
  - Ab einer 5er Killstreak erhält der Spieler ein Kopfgeld `[👑]` mit Blitzschlag-Ankündigung.
  - Wer das Kopfgeld holt, erhält 2 zufällige Spezial-Items als Belohnung.

- **📊 Native Paper Scoreboard & Tabliste**:
  - Live Leaderboard mit Kills, K/D Ratio, Streak, Highscore und Kopfgeld-Marker.
  - Zeilen werden vollständig als Kyori `Component` über `Score#customName(Component)` gerendert (0% Legacy-`§`-Codes).
  - Ausblendung der roten Sidebar-Zahlen nativ über `Objective#numberFormat(NumberFormat.blank())` (0% NMS-Reflection).
  - Tablisten-Namen mit Live-Stats über `Player#playerListName(Component)`.

- **🏆 Match-Manager & Dauer-Einstellung**:
  - `/osok start` setzt vor jedem Match das Scoreboard zurück und räumt alte Item-Boxen und Bomber weg.
  - `/osok stop` beendet das Spiel: Statistiken zurückgesetzt, Ausrüstung und Effekte entfernt, alle Spieler in der Lobby der aktiven Map.
  - Konfigurierbare Kills-, Minuten- und Sekunden-Limits (`/osok dauer kills <n>`, `/osok dauer minuten <n>`, `/osok dauer sekunden <n>`, `/osok dauer off`).
  - **Verzögerter Start**: Festgelegte Limits werden gespeichert und erst beim Ausführen von `/osok start` im Scoreboard sichtbar & als Timer gestartet.
  - Gewinner-Titel, Siegeshymne (Notenblock-Song) & Feuerwerksspektakel.

- **🌍 Welt-Handling & GameRules**:
  - Automatische Extraktion von `Standard.zip` bzw. `DustPvP.zip` aus den Plugin-Ressourcen in die Server-Welt.
  - **`locator_bar` ist serverweit dauerhaft auf `false`**: gesetzt beim Start auf allen geladenen Welten, bei `WorldInitEvent`/`WorldLoadEvent` für später geladene Welten, und ein Reaktivieren per `/gamerule` wird über den Paper `WorldGameRuleChangeEvent` blockiert.
  - Arena-GameRules über die moderne `org.bukkit.GameRules` Registry: `IMMEDIATE_RESPAWN`, `KEEP_INVENTORY`, `SPAWN_MOBS=false`, `SPAWN_PATROLS=false`, `SPAWN_WANDERING_TRADERS=false`.
  - **Map-Wechsel ohne Neustart**: Das laufende Match wird sauber gestoppt, Boden-Items samt Chunk-Tickets werden freigegeben, und die alte Welt wird erst entladen, wenn **alle** `teleportAsync`-Vorgänge nachweislich abgeschlossen sind (`CompletableFuture.allOf`). Parallele Wechsel sind gesperrt.

---

## 🚀 Native Paper 26.1.2 Highlights

1. **Native Paper Plugin Architecture**: `paper-plugin.yml` (`api-version: '1.21'`) für direkte Einordnung unter **Paper Plugins** bei `/pl`.
2. **Paper Lifecycle Commands API & Brigadier**: Registrierung über `LifecycleEvents.COMMANDS` mit `BasicCommand`, `canUse` und `suggest` (ausschließlich `/osok`). Keine Bukkit `CommandExecutor`/`TabCompleter`.
3. **Paper Persistent Data Container (PDC)**: Typsichere Identifizierung aller Spezial-Items via `NamespacedKey` – keine Anzeigenamen-Vergleiche.
4. **Paper Entity & Region Schedulers**: Thread-safe Aufgabenverwaltung via `player.getScheduler()` und `GlobalRegionScheduler` (0% `BukkitRunnable`).
5. **Paper Spatial Entity Index Engine**: Räumliche Suchen per `loc.getNearbyPlayers()` und `getNearbyEntitiesByType()`.
6. **Asynchrone Teleportation (`player.teleportAsync`)**: Hintergrund-Preloading von Ziel-Chunks mit `.thenAccept(...)` Callbacks.
7. **Paper Plugin Chunk Tickets (`addPluginChunkTicket`)**: Hält Chunks mit Boden-Items geladen.
8. **Kyori Adventure Component & Sound API**: Alle Texte, Titles, Tablisten über `Component`/MiniMessage; sämtliche Sounds über `Audience#playSound(net.kyori.adventure.sound.Sound)` mit `Sound.Source` statt `SoundCategory`.
9. **Moderne GameRules-Registry**: `org.bukkit.GameRules` statt des in 26.1.2 als *deprecated for removal* markierten `org.bukkit.GameRule`.

> Der Quellcode ist frei von Deprecation- und Removal-Warnungen:
> `javac -Xlint:deprecation,removal` meldet über alle Quelldateien null Warnungen.

---

## ⚙️ Requirements & Server-Setup

- **Server-Software**: [Paper 26.1.2](https://papermc.io/) (oder neuer)
- **Java**: Java 21+ (Java 25 unterstützt)
- **Minecraft Client**: 1.21.x

Ein fertiger, vorkonfigurierter Paper-Server inklusive Plugins & Welt befindet sich im Ordner **`Server/`**.

---

## 🛠️ Kompilierung & Deployment

Das Plugin kann direkt über das mitgelieferte PowerShell-Build-Skript kompiliert und verpackt werden:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

**Was das Skript macht:**
1. Kompiliert den Java-Quellcode mit `javac` (UTF-8) gegen die Paper 26.1.2 API.
2. Kopiert `paper-plugin.yml` sowie `Standard.zip` und `DustPvP.zip`.
3. Verpackt das Plugin in `OneShotOneKill_26.1.2/OneShotOneKill_26.1.2.jar`.
4. Kopiert das Artefakt automatisch nach `Server/plugins/OneShotOneKill_26.1.2.jar`.

Alle Pfade werden relativ zum Skript-Verzeichnis (`$PSScriptRoot`) aufgelöst; das Repository kann
also beliebig verschoben oder umbenannt werden. Bricht `javac` oder `jar` ab, schlägt der Build fehl.

---

## 💻 Befehle & Unterbefehle (`/osok <unterbefehl>`)

| Befehl | Beschreibung | Berechtigung |
| :--- | :--- | :--- |
| `/osok` | OSOK Hauptbefehl & Übersicht aller Unterbefehle | Operator (OP) |
| `/osok start` | Setzt das Scoreboard zurück, teleportiert alle Spieler zufällig in die Arena, startet ein neues Match & setzt den Item-Modus auf `BOTH` | Operator (OP) |
| `/osok stop` | Beendet das Spiel, setzt das Scoreboard zurück & teleportiert alle Spieler in die Lobby der aktiven Map | Operator (OP) |
| `/osok pause` | Pausiert / Fortsetzt das aktuelle Match & teleportiert zur Lobby | Operator (OP) |
| `/osok map <Standard\|DustPvP>` | Dynamischer Map-Wechsel ohne Server-Neustart | Operator (OP) |
| `/osok dauer kills <n>` | Setzt ein Kill-Ziel (z. B. `/osok dauer kills 20`, aktiv ab `/osok start`) | Operator (OP) |
| `/osok dauer minuten <n>` | Setzt ein Zeit-Limit in Minuten (aktiv ab `/osok start`) | Operator (OP) |
| `/osok dauer sekunden <n>` | Setzt ein Zeit-Limit in Sekunden (aktiv ab `/osok start`) | Operator (OP) |
| `/osok dauer off` | Deaktiviert Match-Limits | Operator (OP) |
| `/osok itemmode <mode>` | Wechselt zwischen `STREAK`, `SPAWN` und `BOTH` (gilt bis zum nächsten `/osok start`) | Operator (OP) |
| `/osok itemtest` | Öffnet Admin-Test-GUI für alle 11 Spezial-Items | Operator (OP) |
| `/osok clearpfeile` | Entfernt herumliegende Pfeile in allen Welten | Operator (OP) |
| `/osok setspawn` | Setzt den Lobby-Spawnpunkt der aktiven Map | Operator (OP) |
| `/osok resetstats` | Setzt Kills, Tode & Scoreboard-Statistiken zurück | Operator (OP) |
| `/osok resetmap` | Entpackt die saubere Map aus der JAR & startet den Server neu | Operator (OP) |

---

## 📄 Lizenz & Credits
Entwickelt als 100% natives Paper 26.1.2 Plugin.
