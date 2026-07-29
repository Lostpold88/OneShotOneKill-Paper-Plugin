# 🎯 OneShotOneKill (OSOK) - Native Paper 26.1.2 Plugin & Server

Ein 100% **natives Paper 26.1.2 PvP Minigame Plugin** (1.21.x) mit `paper-plugin.yml`, Paper Lifecycle Commands API, Kyori Adventure Components und High-Performance Asynchronitäts-Features.

---

## ⚡ Features & Spielmechaniken

- **🎯 1-Hit Kill Kampfsystem**:
  - Jeder Treffer mit dem **OneShot-Dolch** (Eisenschwert) oder einem **Bogenpfeil** eliminiert den Gegner sofort.
  - Nahkampftreffer mit anderen Gegenständen (Fäuste, Wolle, Bogen-Nahkampf) verursachen normalen Vanilla-Schaden.
  - Treffer mit dem Bogen füllen automatisch 1 Pfeil im Inventar auf.
  - **Waffen-Vergabe erst ab `/osok start`**: Vor dem Match-Start oder während einer Pause besitzen Spieler keine Waffen. Bei `/osok pause` werden Dolch, Bogen und Pfeile entfernt – **Spezial-Items bleiben im Inventar erhalten**.
  - **Kampfsperre & Chat-Meldungen**: Vor `/osok start` und während `/osok pause` ist Schaden deaktiviert. Bei einem Angriff erscheint eine Chat-Nachricht inklusive Sound.
  - **Kein Hunger & Volle Sättigung**: Spieler verlieren im gesamten Spiel keinen Hunger (`FoodLevelChangeEvent` ist nativ gecancelt; Hunger und Sättigung bleiben auf Maximum).
- **⏸️ Pause-System (`/osok pause`)**:
  - Pausiert/Fortsetzt das laufende Match.
  - Teleportiert bei Pausierung **alle Spieler umgehend zur Lobby** (`223.5, 48.0, 55.5`).
  - Der Match-Timer friert ein.
  - Das Betreten des Arena-Bereichs wird während der Pause blockiert (Spieler werden zur Lobby zurückteleportiert).
  - Beim Fortsetzen werden alle Spieler wieder **zufällig verteilt in die Arena** teleportiert.
- **🛡️ Reflektor-Schild**:
  - Wehrt den nächsten tödlichen Treffer ab und löst Schildbruch-Sound- und Partikeleffekte aus.
- **🎁 11 Spezial-Items (Powerups)**:
  1. 👁️ **Radar-Puls** *(Enderauge)*: Lässt alle Feinde in der Arena für 30s aufleuchten (Glowing). **Geheim**: Opfer sehen *weder* Partikel *noch* ein Potion-Icon im HUD!
  2. 💣 **Explosiv-Schuss** *(TNT)*: Nächster Pfeil erzeugt eine Explosion am Einschlagort.
  3. 🛡️ **Reflektor-Schild** *(Netherstern)*: Wehrt den nächsten tödlichen Treffer ab.
  4. 💨 **Rauchbombe** *(Schneeball)*: Erzeugt dichten Lagerfeuer-Rauch und teleportiert zufällig in die Arena.
  5. ❄️ **Frost-Trap** *(Gewichtete Druckplatte)*: Friert den ersten betretenden Spieler für 7s fest (mit echter Paper Eis-Vignette am Bildschirm!).
  6. 🔫 **Minigun** *(Lohenrute)*: Feuert 8 Sekunden lang durchgehend Pfeile ab (alle 2 Ticks).
  7. 🔮 **Teleport-Granate** *(Enderperle)*: Teleportiert und stößt nahestehende Spieler zurück.
  8. 👻 **Unsichtbarkeits-Mantel** *(Phantom-Membran)*: Echter Vanish für 15s (`hidePlayer`).
  9. 🧲 **Pfeil-Magnetfeld** *(Herz des Meeres)*: Lenkt gegnerische Pfeile im Umkreis von 8 Blöcken für 15s ab.
  10. ⚡ **Kettenblitz-Schuss** *(Blitzableiter)*: Nächster Treffer beschwört Blitze und springt auf bis zu 2 nahe Feinde über.
  11. 🚀 **Raketen-Sprung** *(Feuerwerksrakete)*: Katapultiert den Spieler 15 Blöcke hoch (inkl. 20s Fallschutz & Air-Sprint).
- **📦 Item-Modi (`/osok itemmode <streak|spawn|both>`)**:
  - `STREAK`: Spezial-Item alle 3 Kills.
  - `SPAWN`: 30s-Map-Spawns mit Mario-Kart-Partikelboxen (aktiv erst nach `/osok start`).
  - `BOTH`: Kombinationsmodus (Standard).
- **👑 Kopfgeld-System**:
  - Ab einer 5er Killstreak erhält der Spieler ein Kopfgeld `[👑]` mit Blitzschlag-Ankündigung.
  - Wer das Kopfgeld holt, erhält 2 zufällige Spezial-Items als Belohnung.
- **📊 Native Paper Scoreboard & Tabliste**:
  - Live Leaderboard mit Kills, K/D Ratio, Streak, Highscore und Kopfgeld-Marker.
  - Ausblendung der roten Sidebar-Zahlen nativ über `Objective#numberFormat(NumberFormat.blank())` (0% NMS-Reflection).
  - Langsame, flüssige Regenbogen-Title- & Tablist-Animation (`<rainbow>🎯 OSOK</rainbow>`).
- **🏆 Match-Manager & Dauer-Einstellung**:
  - Konfigurierbare Kills-, Minuten- und Sekunden-Limits (`/osok dauer kills <n>`, `/osok dauer minuten <n>`, `/osok dauer sekunden <n>`, `/osok dauer off`).
  - Kurzformen: `/osok dauer 20k`, `/osok dauer 10m`, `/osok dauer 45s`.
  - **Verzögerter Start**: Festgelegte Limits werden gespeichert und erst beim Ausführen von `/osok start` im Scoreboard sichtbar & als Timer gestartet!
  - Gewinner-Titel, Siegeshymne (Notenblock-Song) & Feuerwerksspektakel.
- **🎆 Kill-Effekte GUI (`/osok killeffect`)**:
  - Auswahl persönlicher Kill-Animationen: *Lightning, Firework, Blood, Ender, Totem, None*.
- **🗺️ Welt-Extraktion**:
  - Automatische Extraktion der arenafertigen `map.zip` aus den Plugin-Ressourcen in die Server-Welt.

---

## 🚀 Native Paper 26.1.2 Highlights

1. **Native Paper Plugin Architecture**: Erstellt mit `paper-plugin.yml` (`api-version: '1.21'`) für direkte Einordnung unter **Paper Plugins** bei `/pl`.
2. **Paper Lifecycle Commands API**: Dynamische, überschneidungsfreie Hauptbefehl-Registrierung über `LifecycleEvents.COMMANDS` (ausschließlich `/osok`).
3. **Paper Persistent Data Container (PDC)**: Typsichere Identifizierung aller Spezial-Items und Entitäten via `NamespacedKey("oneshotonekill", "special_item_type")`.
4. **Paper Entity & Region Schedulers**: Thread-safe Aufgabenverwaltung via `player.getScheduler()` und `GlobalRegionScheduler`.
5. **Paper Spatial Entity Index Engine**: Blitzschnelle räumliche Suchen per `loc.getNearbyPlayers()` und `getNearbyEntitiesByType()`.
6. **Asynchrone Teleportation (`player.teleportAsync`)**: Hintergrund-Preloading von Ziel-Chunks mit `.thenAccept(...)` Callbacks für lagfreie Teleporte.
7. **Paper Plugin Chunk Tickets (`addPluginChunkTicket`)**: Verhindert das Einfrieren von Kisten am Boden bei Chunk-Entladungen.
8. **Kyori Adventure & Component API**: Performance-optimiertes Rendering aller Texte, Titles, Tablisten und Emojis.

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
1. Kompiliert den Java-Quellcode mit `javac` gegen die Paper 26.1.2 API.
2. Kopiert `paper-plugin.yml` und die gepatchte `map.zip`.
3. Verpackt das Plugin in `OneShotOneKill_26.1.2/OneShotOneKill_26.1.2.jar`.
4. Kopiert das Artefakt automatisch in den Server-Ordner `Server/plugins/OneShotOneKill_26.1.2.jar`.

---

## 💻 Befehle & Unterbefehle (`/osok <unterbefehl>`)

| Befehl | Beschreibung | Berechtigung |
| :--- | :--- | :--- |
| `/osok` | OSOK Hauptbefehl & Übersicht aller Unterbefehle | Jeder |
| `/osok start` | Teleportiert alle Spieler zufällig in die Arena & startet ein neues Match | Operator |
| `/osok pause` | Pausiert / Fortsetzt das aktuelle Match & teleportiert zur Lobby | Operator |
| `/osok dauer kills <n>` | Setzt ein Kill-Ziel (z. B. `/osok dauer 20k`, aktiv ab `/osok start`) | Operator |
| `/osok dauer minuten <n>` | Setzt ein Zeit-Limit in Minuten (z. B. `/osok dauer 10m`, aktiv ab `/osok start`) | Operator |
| `/osok dauer sekunden <n>` | Setzt ein Zeit-Limit in Sekunden (z. B. `/osok dauer 45s`, aktiv ab `/osok start`) | Operator |
| `/osok dauer off` | Deaktiviert Match-Limits | Operator |
| `/osok itemmode <mode>` | Wechselt zwischen `STREAK`, `SPAWN` und `BOTH` | Operator |
| `/osok killeffect` | Öffnet das GUI für Kill-Animationen | Jeder |
| `/osok itemtest` | Öffnet Admin-Test-GUI für alle 11 Spezial-Items | Operator |
| `/osok clearpfeile` | Entfernt herumliegende Pfeile in der Arena | Operator |
| `/osok setspawn` | Setzt den aktuellen Spawnpunkt der Map | Operator |
| `/osok resetstats` | Setzt Kills, Tode & Scoreboard-Statistiken zurück | Operator |
| `/osok resetmap` | Entpackt die saubere Map aus der JAR & startet den Server neu | Operator |

---

## 📄 Lizenz & Credits
Entwickelt als 100% natives Paper 26.1.2 Plugin.
