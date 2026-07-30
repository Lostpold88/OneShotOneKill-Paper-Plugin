# 🎯 OneShotOneKill (OSOK) - Native Paper 26.1.2 Plugin & Server

Ein 100% **natives Paper 26.1.2 PvP Minigame Plugin** (1.21.x) mit `paper-plugin.yml`, Paper Lifecycle Commands API, Kyori Adventure Components und High-Performance Asynchronitäts-Features.

---

## 🗺️ Arenen

Zwei eingebaute Maps, umschaltbar im laufenden Betrieb per `/osok map`:

| Map | Welt | Arena-Ecke 1 | Arena-Ecke 2 | Lobby | Max. Item-Höhe | Decke |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Standard** | `OSOK_Standard` | `221 / 58 / -50` | `287 / 64 / -106` | `223.5 / 48.0 / 55.5` | `61` | `69` |
| **DustPvP** | `OSOK_DustPvP` | `-25 / 70 / 33` | `25 / 70 / -33` | `0.5 / 90.0 / 0.5` | `71` | offen |

Die **Decke** begrenzt fliegende Entities: Der Tarnkappenbomber-Drache schwebt normalerweise
12 Blöcke über seinem Ziel, bleibt auf überdachten Maps aber immer einen Block unter der Decke
(auf Standard also maximal `Y 68`). Auf DustPvP gilt keine Begrenzung.

Die Reihenfolge der Ecken ist egal – sie werden automatisch normalisiert. Die Arena-Grenzen steuern
Kampfzone, Spielerspawns, Item-Spawns und die Pausensperre.

> **Hinweis zur DustPvP-Lobby**: Sie liegt bei `Y=90` direkt über der Arena-Grundfläche. Damit sie nicht
> als „innerhalb der Arena" gilt, ist der vertikale Spielraum über der Arena-Oberkante auf 12 Blöcke
> begrenzt (Arena-Y effektiv 68–82).

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
  - **Eine einzige Buchführung**: Regulär eliminierte Spieler und echte Tode (`/kill`) laufen über dieselbe Methode im `EliminationManager`. Der `PlayerDeathEvent`-Handler führte früher eine eigene, parallele Statistik, die weder `/osok pausestats` noch die Match-Ziel-Erinnerung kannte – bei eingefrorener Wertung wurden Kills dort trotzdem gezählt. Der Handler bucht jetzt ausschließlich (kein Reflektor-Schild, da sich ein echter Tod in `PlayerDeathEvent` nicht mehr verhindern lässt und das Schild sonst wirkungslos verbraucht würde) und überlässt den Respawn dem `PlayerRespawnEvent` – der bei laufendem Match direkt zurück in die Arena führt.
  - **🪂 Rettung aus dem Void**: Außerhalb der Arena ist jeder Schaden deaktiviert – **auch Void-Schaden**. Da die Lobby bewusst außerhalb der Arena-Grenzen liegt, führte ein Fehltritt neben die Plattform in einen endlosen Fall, aus dem nur ein Rejoin half. Unterhalb von 20 Blöcken unter der tiefsten relevanten Kante (Arena-Unterkante bzw. Lobby) wird der Spieler jetzt zurückgeholt – in die Arena, wenn ein Match läuft, sonst in die Lobby. Der Sturz zählt bewusst **nicht** als Tod.
  - **Spezial-Items überleben den Respawn**: Die Grundausrüstung belegt fest die Slots 0, 1 und 8. Lag dort ein Spezial-Item, wurde es früher beim nächsten Respawn kommentarlos überschrieben – bei Sofort-Respawn also im Sekundentakt. Es wird jetzt auf einen freien Platz gerettet; ist das Inventar voll, gibt es eine Warnung statt eines stillen Verlusts.

- **⏸️ Pause-System (`/osok pause`)**:
  - Pausiert / Fortsetzt das laufende Match, der Match-Timer friert ein.
  - Teleportiert alle Spieler in die **Lobby der aktiven Map**.
  - Das Betreten des Arena-Bereichs wird während der Pause blockiert.
  - Beim Fortsetzen werden alle Spieler wieder **zufällig verteilt in die Arena** teleportiert.

- **🎁 16 Spezial-Items (Powerups)**:
  1. 👁️ **Radar-Puls** *(Enderauge)*: Lässt alle Feinde für 30s aufleuchten. **Geheim**: Umgesetzt über das Glow-Flag der Entity statt über `PotionEffectType.GLOWING` – dadurch erscheint beim Betroffenen **kein Eintrag im Effekt-Fenster des Inventars**, kein HUD-Icon und keine Partikel. (Einzige verbleibende Eigenwahrnehmung: der eigene Umriss in der Third-Person-Ansicht `F5`.)
  2. 💣 **Explosiv-Schuss** *(TNT)*: Nächster Pfeil erzeugt eine Explosion am Einschlagort.
  3. 🛡️ **Reflektor-Schild** *(Netherstern)*: Blockiert den nächsten tödlichen Treffer inkl. Schildbruch-Effekt. Die Prüfung sitzt zentral im `EliminationManager` und wirkt daher gegen **jede** Todesursache – auch Kettenblitz, Explosiv-Pfeil, Bomber-TNT, Air-Strike, C4 und Sturzschaden.
  4. 💨 **Rauchbombe** *(Schneeball)*: Erzeugt dichten Lagerfeuer-Rauch und teleportiert zufällig in die Arena.
  5. ❄️ **Frost-Trap** *(Gewichtete Druckplatte)*: Friert den ersten betretenden Spieler für 7s fest. **Verfällt nach 45 Sekunden auch ohne Auslösung** – ohne diese Grenze blieben Platten, auf die nie jemand tritt, dauerhaft in der Map liegen und sammelten sich über ein Match hinweg an. Bei Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop werden alle noch liegenden Platten eingesammelt; beim Map-Wechsel zwingend **vor** dem Entladen der alten Welt.
  6. 🔫 **Minigun** *(Lohenrute)*: Feuert 8 Sekunden lang durchgehend Pfeile ab (alle 2 Ticks).
  7. 🔮 **Teleport-Granate** *(Enderperle)*: Teleportiert und stößt nahestehende Spieler zurück.
  8. 👻 **Unsichtbarkeits-Mantel** *(Phantom-Membran)*: Echter Vanish für 15s (`hidePlayer`). **Endet sofort bei einer Eliminierung** sowie bei Match-Ende, Map-Wechsel und Plugin-Stop: Der Mantel hängt nicht am Potion-Effekt, sondern an `hidePlayer` – ohne diesen ausdrücklichen Abbruch blieb ein eliminierter Spieler bis zum Ablauf seines Timers für alle unsichtbar, auch in der Lobby.
  9. 🧲 **Pfeil-Magnetfeld** *(Herz des Meeres)*: Lenkt gegnerische Pfeile im Umkreis von 8 Blöcken für 15s ab.
  10. ⚡ **Kettenblitz-Schuss** *(Blitzableiter)*: Nächster Treffer beschwört Blitze und springt auf bis zu 2 nahe Feinde über.
  11. 🐉 **Tarnkappenbomber** *(Drachenkopf)*: Öffnet ein Auswahlmenü mit allen anderen Spielern. Über dem gewählten Ziel erscheint ein **Ender-Drache**, der ihm **10 Sekunden** lang folgt und dabei durchgehend **TNT** abwirft. Der Drache greift niemanden an: Wahrnehmung deaktiviert, unverwundbar, und sein gesamter Schaden wird gecancelt. Er wird jeden Tick über das Ziel teleportiert; ein Reset der `HOVER`-Phase verhindert, dass er gegen den Teleport zurückfliegt. Das TNT **zerstört keine Blöcke** – die Blockliste der Explosion wird geleert – und **zündet sofort bei Bodenkontakt** statt per Zeitzünder. Sein Schaden ist auf **3 Herzen gedeckelt**, es tötet also ausdrücklich nicht mit einem Treffer.

  12. 🛰 **Air-Strike** *(Karte)*: Öffnet eine **Karte der aktiven Arena** – ein 9×6-Raster über die XZ-Grenzen der Map, auf dem alle Spieler in der Arena als Kopf auf ihrem Sektor eingezeichnet sind (der eigene in Blau, Gegner in Rot). Ein Klick markiert das Ziel, eine Partikelsäule kündigt den Einschlag an, und nach ~2 s gehen 8 Bomben auf den Sektor nieder. Die Abwurfhöhe respektiert die **Decke der Map** (auf Standard also maximal `Y 68`). Das Item wird erst bei der Zielauswahl verbraucht.
  13. 💥 **C4** *(TNT-Lore)*: Wird per Rechtsklick auf einen Block **platziert** und liegt dort als TNT-Block ohne Leuchtrahmen, ist also nicht durch Waende sichtbar – umgesetzt als `BlockDisplay`, die Map bleibt also völlig unberührt. Beim Platzieren erhält man automatisch einen **Fernzünder** (Hebel), der per Rechtsklick **alle eigenen Ladungen gleichzeitig** auslöst. Mehrere Ladungen lassen sich vorher verteilen.

  14. 🔭 **Railgun** *(Fernrohr)*: Rechtsklick startet eine **Ladephase von 1 Sekunde**, in der ein Partikelstrahl die aktuelle Blickrichtung für **alle sichtbar** nachzeichnet – das Ziel kann also ausweichen. Danach schlägt ein Hitscan-Strahl ein: Wer auf der Sichtlinie steht, ist sofort eliminiert. Der Treffer wird mit **einem** `World#rayTrace`-Aufruf ermittelt, der Blöcke und Entities gemeinsam prüft und den nächstgelegenen Treffer liefert – eine Wand blockt den Schuss damit zuverlässig, ohne dass Block- und Entity-Raytrace von Hand verglichen werden müssen. Reichweite 64 Blöcke, ein Schuss pro Item, Fehlschuss inklusive.

  15. 🕳 **Singularität** *(Echo-Scherbe)*: Wurfgeschoss (optisch eine Echo-Scherbe über `ThrowableProjectile#setItem`). Beim Einschlag öffnet sich für **4 Sekunden** ein Sog, der alle Spieler im Umkreis von 8 Blöcken zum Zentrum reißt – **auch den Werfer**. Richtet keinen Schaden an: Die Singularität ist ein **Aufbau-Item** für Air-Strike, C4 und Tarnkappenbomber. Der Sog wirkt nur auf Spieler *innerhalb* der Arena.

  16. 🦅 **Gleitflug** *(Elytra)*: **8 Sekunden** Flug mit Startschub und regelmäßigen Schubstößen. Für die Flugdauer erhält der Spieler Leih-Schwingen im Brustslot, die danach restlos wieder eingesammelt werden. Die Flughöhe respektiert die **Decke der Map** und die Arena-Oberkante – auf der offenen DustPvP-Map könnte man sonst über die Arena hinaussteigen, und außerhalb der Arena ist jeder Kampf deaktiviert. Am Ende gibt es **Sanfter Fall** für die Landung, denn Sturzschaden wäre in der Arena tödlich.

  > **Das Gleitflug-Item lässt sich nicht anziehen.** Die Elytra in der Hotbar bekommt über die Paper Data Components ausdrücklich `EQUIPPABLE` und `GLIDER` **entfernt** (`ItemStack#unsetData`). Ohne das könnte man sie einfach in den Brustslot ziehen und hätte unbegrenzten Flug statt der acht Sekunden. Die eigentlichen Schwingen setzen `GLIDER` umgekehrt **explizit**, damit das Flugverhalten nicht von der Standardbelegung des Materials abhängt.

  > **Sprengkraft von Air-Strike und C4**: Beide nutzen `createExplosion(…, breakBlocks = false)` mit Stärke `8.0` für eine Air-Strike-Bombe und `12.0` für eine C4-Ladung (Vanilla-TNT liegt bei `4.0`). Die Explosion ist damit gewaltig und im Zentrum tödlich, kann die Map aber **grundsätzlich nicht** beschädigen – es werden gar keine Blöcke angetastet, statt eine Blockliste nachträglich zu leeren.
  >
  > Beide treffen **jeden** Spieler in Reichweite, **auch den Auslöser selbst**. Dafür wird bewusst *keine* Verursacher-Entity übergeben: Minecraft ermittelt die Explosionsopfer über `getEntities(source, box)`, und diese Abfrage schließt die Quell-Entity aus – der Auslöser wäre also von seiner eigenen Sprengung ausgenommen. Für die Kill-Zuordnung hält der `ExplosivesManager` den Auslöser stattdessen nur für die Dauer der Sprengung fest; das ist zuverlässig, weil `createExplosion` synchron läuft und die Schadensevents unmittelbar auslöst.

  > Wichtig: `setAI(false)` darf hier **nicht** gesetzt werden. Das NoAI-Flag wird zum Client synchronisiert, und der Drache ist ein mehrteiliges Modell, dessen Segmente clientseitig in `aiStep()` nachgeführt werden – mit NoAI bleibt das Modell optisch stehen, obwohl die Entity serverseitig korrekt mitwandert. Das Item wird erst beim Auswählen eines Ziels verbraucht, nicht beim Öffnen des Menüs.

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

- **☠ Sudden Death (Endphase bei Zeitlimit)**:
  - Startet automatisch bei **60 verbleibenden Sekunden**. Bei kürzeren Limits (z. B. `/osok dauer sekunden 30`) greift die Phase ab dem ersten Timer-Tick.
  - **Jeder leuchtet** – Verstecken ist vorbei.
  - **Der Ring schrumpft** von 100 % auf 45 % der Arena-Kantenlänge und wird als rote Partikelwand gezeichnet. Wer länger als **5 Sekunden** draußen bleibt, wird eliminiert; ein Countdown in der Actionbar warnt vorher.
  - **Doppelte Item-Rate**: Boden-Item-Boxen spawnen alle 15 statt alle 30 Sekunden.
  - Die Arena-Grenzen der `MapConfig` bleiben **unangetastet**. Sie steuern Kampfzone, Pausensperre und Item-Spawns – sie zu verkleinern würde außerhalb des Rings jeden Kampf deaktivieren. Der Ring ist deshalb eine eigene, zusätzliche Grenze.
  - **Respawns landen garantiert im Ring**: `getRandomArenaLocation` beschränkt die Suchfläche auf den aktuellen Ring. Ohne das könnte ein Respawn direkt in der Todeszone landen und eine Endlosschleife auslösen.
  - Der Ring friert bei `/osok pause` und `/osok pausestats` mit dem Timer ein.

- **🏕 Anti-Camping**:
  - Wer **20 Sekunden** im Umkreis von 5 Blöcken bleibt, leuchtet für alle auf – bis er sich wieder bewegt.
  - **5 Sekunden vorher** gibt es eine Actionbar-Vorwarnung mit Signalton.
  - Zählt nur innerhalb der Arena und nur bei laufendem Match.

- **📋 Match-Zusammenfassung**:
  - Wird nach dem Sieger und bei `/osok stop` ausgegeben – jeweils **vor** dem Zurücksetzen der Statistiken.
  - Ausgewertet werden **MVP** (Kills ×3 + beste Streak ×2 − Tode), **meiste Kills**, **beste K/D**, **meiste Tode**, **meiste eingesammelte Items** und **längste zurückgelegte Strecke**.
  - Die Strecke misst der `AntiCampManager` über `PlayerMoveEvent#hasChangedPosition()`. Sprünge über 8 Blöcke gelten als Teleport (Respawn, Rauchbombe, Teleport-Granate) und zählen nicht – sonst würde ein Respawn quer durch die Arena die Statistik verfälschen.
  - Gezählt werden Items aus **Boden-Boxen, Killstreak- und Kopfgeld-Belohnungen**; das Admin-Testmenü zählt bewusst nicht mit.
  - Ausgewertet werden nur **verbundene** Spieler – eine UUID ohne Online-Spieler aufzulösen würde eine blockierende Profilabfrage auslösen. Die Live-Rangliste im Scoreboard arbeitet aus demselben Grund so.

- **💡 Zentrale Leuchtrahmen-Verwaltung**:
  - Radar-Puls, Anti-Camping und Sudden Death markieren Spieler unabhängig voneinander. Der `GlowManager` hält deshalb pro Spieler die **Gründe** fest und schaltet `Entity#setGlowing` erst ab, wenn kein Grund mehr besteht – sonst würde ein auslaufender Camping-Status das Radar-Leuchten mit abschalten.

- **📊 Native Paper Scoreboard & Tabliste**:
  - Live Leaderboard mit Kills, K/D Ratio, Streak, Highscore und Kopfgeld-Marker.
  - Zeilen werden vollständig als Kyori `Component` über `Score#customName(Component)` gerendert (0% Legacy-`§`-Codes).
  - **Board wird pro Spieler gecacht** und nur inhaltlich aktualisiert, statt bei jedem Update neu aufgebaut zu werden. Statische Zeilen liegen als vorgeparste `Component`-Konstanten bereit, die Rangliste wird pro Update einmal sortiert statt pro Spieler.
  - Ausblendung der roten Sidebar-Zahlen nativ über `Objective#numberFormat(NumberFormat.blank())` (0% NMS-Reflection).
  - Tablisten-Namen mit Live-Stats über `Player#playerListName(Component)`.

- **🏆 Match-Manager & Dauer-Einstellung**:
  - **Match-Ziel immer sichtbar**: Sobald ein Kill- oder Zeitlimit konfiguriert ist, steht es auf dem Scoreboard **jedes** Spielers.
  - **Wertung einfrieren (`/osok pausestats`)**: Kills, Tode und Streaks werden nicht mehr gezählt, der Match-Timer läuft nicht weiter und das Scoreboard bleibt stehen. Anders als `/osok pause` bleibt das Match spielbar – Treffer wirken normal (Effekt, Respawn), zählen aber nicht. Ein erneuter Aufruf setzt die Wertung fort.
  - **Endspurt-Hinweis**: Ab **5 verbleibenden Kills** bekommt der Spieler nach jedem Kill eine Nachricht samt Actionbar und Signalton, wie viele Kills ihm noch zum Sieg fehlen.
  - `/osok start` setzt vor jedem Match das Scoreboard zurück und räumt alte Item-Boxen und Bomber weg.
  - `/osok stop` beendet das Spiel: Statistiken zurückgesetzt, Ausrüstung und Effekte entfernt, alle Spieler in der Lobby der aktiven Map.
  - Konfigurierbare Kills-, Minuten- und Sekunden-Limits (`/osok dauer kills <n>`, `/osok dauer minuten <n>`, `/osok dauer sekunden <n>`, `/osok dauer off`).
  - **Verzögerter Start**: Festgelegte Limits werden gespeichert und erst beim Ausführen von `/osok start` im Scoreboard sichtbar & als Timer gestartet.
  - Gewinner-Titel, Siegeshymne (Notenblock-Song) & Feuerwerksspektakel.

- **🌍 Welt-Handling & GameRules**:
  - Automatische Extraktion von `Standard.zip` bzw. `DustPvP.zip` aus den Plugin-Ressourcen in die Server-Welt.
  - **`locator_bar` ist serverweit dauerhaft auf `false`**: gesetzt beim Start auf allen geladenen Welten, bei `WorldInitEvent`/`WorldLoadEvent` für später geladene Welten, und ein Reaktivieren per `/gamerule` wird über den Paper `WorldGameRuleChangeEvent` blockiert.
  - Arena-GameRules über die moderne `org.bukkit.GameRules` Registry: `IMMEDIATE_RESPAWN`, `KEEP_INVENTORY`, `SPAWN_MOBS=false`, `SPAWN_PATROLS=false`, `SPAWN_WANDERING_TRADERS=false`.
  - **Map-Wechsel ohne Neustart**: Das laufende Match wird sauber gestoppt, Boden-Items samt Chunk-Tickets sowie Drachen, fallende Bomben und platzierte C4-Ladungen werden freigegeben, und die alte Welt wird erst entladen, wenn **alle** `teleportAsync`-Vorgänge nachweislich abgeschlossen sind (`CompletableFuture.allOf`). Parallele Wechsel sind gesperrt. Der Wechsel läuft bewusst **ohne Sound-Quittung**.

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
| `/osok pausestats` | Friert die Kill- und Zeitwertung ein / setzt sie fort; das Scoreboard bleibt stehen | Operator (OP) |
| `/osok map <Standard\|DustPvP>` | Dynamischer Map-Wechsel ohne Server-Neustart | Operator (OP) |
| `/osok dauer kills <n>` | Setzt ein Kill-Ziel (z. B. `/osok dauer kills 20`, aktiv ab `/osok start`) | Operator (OP) |
| `/osok dauer minuten <n>` | Setzt ein Zeit-Limit in Minuten (aktiv ab `/osok start`) | Operator (OP) |
| `/osok dauer sekunden <n>` | Setzt ein Zeit-Limit in Sekunden (aktiv ab `/osok start`) | Operator (OP) |
| `/osok dauer off` | Deaktiviert Match-Limits | Operator (OP) |
| `/osok itemmode <mode>` | Wechselt zwischen `STREAK`, `SPAWN` und `BOTH` (gilt bis zum nächsten `/osok start`) | Operator (OP) |
| `/osok itemtest` | Öffnet Admin-Test-GUI für alle 16 Spezial-Items | Operator (OP) |
| `/osok clearpfeile` | Entfernt herumliegende Pfeile in allen Welten | Operator (OP) |
| `/osok setspawn` | Setzt den Lobby-Spawnpunkt der aktiven Map | Operator (OP) |
| `/osok resetstats` | Setzt Kills, Tode & Scoreboard-Statistiken zurück | Operator (OP) |
| `/osok resetmap` | Entpackt die saubere Map aus der JAR & startet den Server neu | Operator (OP) |

---

## 📄 Lizenz & Credits
Entwickelt als 100% natives Paper 26.1.2 Plugin.
