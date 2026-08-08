# 🎯 OneShotOneKill (OSOK) - Native Paper 26.2 Plugin

Ein 100% **natives Paper 26.2 PvP Minigame Plugin**, vollständig in **Kotlin** geschrieben, mit `paper-plugin.yml`, Paper Lifecycle Commands API, Kyori Adventure Components und High-Performance Asynchronitäts-Features.

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
  - **↔ Respawn weit weg vom Todespunkt und von Gegnern**: Der Spawn wird **bewertet**, nicht nur gewürfelt. Pro Respawn sammelt `MapConfig#collectArenaSpots` **24 zufällige begehbare Kandidaten**, und `ArenaManager#getSafestArenaLocation` bewertet jeden nach zwei Kriterien:
      - Abstand zum **nächsten Gegner** (Gewicht `0.7`, gedeckelt bei 32 Blöcken)
      - Abstand zum **Todespunkt** (Gewicht `0.3`, gedeckelt bei 24 Blöcken)

    Der Gegnerabstand wiegt schwerer, weil der Todespunkt nur ein Anhaltspunkt dafür ist, wo der Killer *stand* – wo die Gegner **jetzt** stehen, ist die genauere Information. Beide Abstände sind gedeckelt: Jenseits der Deckel entsteht kein spürbarer Sicherheitsgewinn mehr, und ohne Deckel würden immer nur die Kartenecken gewinnen. Da die Kandidatenmenge jedes Mal neu ausgewürfelt wird, bleibt der Spawn trotz Bewertung unvorhersehbar – eine reine „maximaler Abstand"-Suche wäre deterministisch und damit bequem zu campen. Gilt für die reguläre Eliminierung *und* für echte Tode über den `PlayerRespawnEvent`.
  - **Spezial-Items überleben den Respawn**: Die Grundausrüstung belegt fest die Slots 0, 1 und 8. Lag dort ein Spezial-Item, wurde es früher beim nächsten Respawn kommentarlos überschrieben – bei Sofort-Respawn also im Sekundentakt. Es wird jetzt auf einen freien Platz gerettet; ist das Inventar voll, gibt es eine Warnung statt eines stillen Verlusts.

- **⏸️ Pause-System (`/osok pause`)**:
  - Pausiert / Fortsetzt das laufende Match, der Match-Timer friert ein.
  - Teleportiert alle Spieler in die **Lobby der aktiven Map**.
  - Das Betreten des Arena-Bereichs wird während der Pause blockiert.
  - Beim Fortsetzen werden alle Spieler wieder **zufällig verteilt in die Arena** teleportiert.

- **🎁 17 Spezial-Items (Powerups)**:
  1. 👁️ **Radar-Puls** *(Enderauge)*: Lässt alle Feinde für 30s aufleuchten. **Geheim**: Umgesetzt über das Glow-Flag der Entity statt über `PotionEffectType.GLOWING` – dadurch erscheint beim Betroffenen **kein Eintrag im Effekt-Fenster des Inventars**, kein HUD-Icon und keine Partikel. (Einzige verbleibende Eigenwahrnehmung: der eigene Umriss in der Third-Person-Ansicht `F5`.)
  2. 💣 **Explosiv-Schuss** *(TNT)*: Nächster Pfeil erzeugt eine Explosion am Einschlagort.
  3. 🛡️ **Reflektor-Schild** *(Netherstern)*: Blockiert den nächsten tödlichen Treffer inkl. Schildbruch-Effekt. Die Prüfung sitzt zentral im `EliminationManager` und wirkt daher gegen **jede** Todesursache – auch Kettenblitz, Explosiv-Pfeil, Bomber-TNT, Air-Strike, C4 und Sturzschaden.
  4. 💨 **Rauchbombe** *(Schneeball)*: Erzeugt dichten Lagerfeuer-Rauch und teleportiert zufällig in die Arena.
  5. ❄️ **Frost-Trap** *(Gewichtete Druckplatte)*: Friert den ersten betretenden Spieler für 7s fest. Die Platte **bleibt liegen, bis jemand hineintritt** – sie verfällt nicht von selbst. Damit sich trotzdem nichts ansammelt, werden bei Match-Start, Match-Ende, Map-Wechsel und Plugin-Stop alle noch liegenden Platten eingesammelt; beim Map-Wechsel zwingend **vor** dem Entladen der alten Welt.
      - **Das Einfrieren hält wirklich fest.** `SLOWNESS` allein senkt nur die Laufgeschwindigkeit – ein Sprung trug den Getroffenen weiterhin mehrere Blöcke weit. Zusätzlich wird deshalb die Bewegung im `PlayerMoveEvent` auf die Ausgangsposition zurückgesetzt und die laufende Geschwindigkeit sofort genullt, damit auch ein bereits begonnener Sprung abbricht. Umsehen bleibt erlaubt, sonst fühlt es sich wie ein Verbindungsabbruch an.
      - Teleports sind davon nicht betroffen: `PlayerTeleportEvent` hat in Paper eine eigene HandlerList, ein `PlayerMoveEvent`-Handler sieht sie also gar nicht. Ein Respawn während der Vereisung funktioniert normal.
  6. 🔫 **Minigun** *(Lohenrute)*: Feuert 8 Sekunden lang durchgehend Pfeile ab (alle 2 Ticks).
  7. 🔮 **Teleport-Granate** *(Enderperle)*: Teleportiert und stößt nahestehende Spieler zurück.
  8. 👻 **Unsichtbarkeits-Mantel** *(Phantom-Membran)*: Echter Vanish für 15s (`hidePlayer`). **Endet sofort bei einer Eliminierung** sowie bei Match-Ende, Map-Wechsel und Plugin-Stop: Der Mantel hängt nicht am Potion-Effekt, sondern an `hidePlayer` – ohne diesen ausdrücklichen Abbruch blieb ein eliminierter Spieler bis zum Ablauf seines Timers für alle unsichtbar, auch in der Lobby.
  9. 🧲 **Pfeil-Magnetfeld** *(Herz des Meeres)*: Lenkt gegnerische Pfeile im Umkreis von 8 Blöcken für 15s ab.
  10. ⚡ **Kettenblitz-Schuss** *(Blitzableiter)*: Nächster Treffer beschwört Blitze und springt auf bis zu 2 nahe Feinde über.
  11. 🐉 **Tarnkappenbomber** *(Drachenkopf)*: Öffnet ein Auswahlmenü mit allen anderen Spielern. Über dem gewählten Ziel erscheint ein **Ender-Drache**, der ihm **10 Sekunden** lang folgt und dabei durchgehend **TNT** abwirft. Der Drache greift niemanden an: Wahrnehmung deaktiviert, unverwundbar, und sein gesamter Schaden wird gecancelt. Er wird jeden Tick über das Ziel teleportiert; ein Reset der `HOVER`-Phase verhindert, dass er gegen den Teleport zurückfliegt. Das TNT **zerstört keine Blöcke** – die Blockliste der Explosion wird geleert – und **zündet sofort bei Bodenkontakt** statt per Zeitzünder. Sein Schaden ist auf **3 Herzen gedeckelt**, es tötet also ausdrücklich nicht mit einem Treffer.

  12. 🛰 **Air-Strike** *(Karte)*: Öffnet eine **Karte der aktiven Arena** – ein 9×6-Raster über die XZ-Grenzen der Map, auf dem alle Spieler in der Arena als Kopf auf ihrem Sektor eingezeichnet sind (der eigene in Blau, Gegner in Rot). Ein Klick markiert das Ziel, eine Partikelsäule kündigt den Einschlag an, und nach ~2 s gehen 8 Bomben auf den Sektor nieder. Die Abwurfhöhe respektiert die **Decke der Map** (auf Standard also maximal `Y 68`). Das Item wird erst bei der Zielauswahl verbraucht. Die Bomben bekommen einen **Anschub nach unten** (`-0.9` statt der reinen Schwerkraft): Auf der überdachten Standard-Map sind es ohnehin nur rund zehn Blöcke Fallhöhe, und die Bomben trudelten sonst träge herunter.
  13. 💥 **C4** *(TNT-Lore)*: Wird per Rechtsklick auf einen Block **platziert** und liegt dort als TNT-Block ohne Leuchtrahmen, ist also nicht durch Waende sichtbar – umgesetzt als `BlockDisplay`, die Map bleibt also völlig unberührt. Beim Platzieren erhält man automatisch einen **Fernzünder** (Hebel), der per Rechtsklick **alle eigenen Ladungen gleichzeitig** auslöst. Mehrere Ladungen lassen sich vorher verteilen.
      - **Wieder aufheben**: Rechtsklick auf den **Trägerblock** gibt die Ladung zurück ins Inventar. Die Ladung selbst ist ein `BlockDisplay` und damit nicht anklickbar – deshalb wird der Block darunter angeklickt und geprüft, ob direkt darüber eine eigene Ladung sitzt.
      - Mit einer C4 oder dem Fernzünder in der Hand greift das bewusst **nicht**: Damit wird platziert bzw. gezündet.
      - **Nur der Platzierer kann seine Ladung aufheben.** Der Handler durchsucht ausschließlich die eigene Ladungsliste des Klickenden, *und* jede Ladung trägt ihren Besitzer zusätzlich im `PersistentDataContainer`. Die Regel hängt damit am Objekt selbst und nicht allein am Nachschlagepfad.
      - Der Aufheben-Handler läuft zwingend auf `EventPriority.LOWEST`. Der `SpecialItemListener` platziert die C4 auf `NORMAL` und verbraucht sie danach per `subtract(1)`; bei genau **einer** C4 im Stapel ist `event.getItem()` anschließend leer, und die Prüfung hätte den Platzierungsvorgang nicht mehr als solchen erkannt – die eben gesetzte Ladung wäre im selben Klick wieder eingesammelt worden.
      - **Mit der letzten Ladung verschwindet auch der Fernzünder** – ohne Ladung hat er keine Funktion mehr. Dasselbe gilt beim Aufräumen zum Match-Ende.

  14. 🔭 **Railgun** *(Fernrohr)*: Rechtsklick feuert **sofort** – keine Ladephase, keine Vorwarnung. Der Hitscan-Strahl schlägt im selben Tick ein: Wer auf der Sichtlinie steht, ist eliminiert. Der Treffer wird mit **einem** `World#rayTrace`-Aufruf ermittelt, der Blöcke und Entities gemeinsam prüft und den nächstgelegenen Treffer liefert – eine Wand blockt den Schuss damit zuverlässig, ohne dass Block- und Entity-Raytrace von Hand verglichen werden müssen. Reichweite 64 Blöcke, ein Schuss pro Item, Fehlschuss inklusive.

  > **`Particle.FLASH` braucht zwingend ein `Color`-Datenobjekt.** Ohne das wirft `CraftParticle` ein `IllegalArgumentException: missing required data class org.bukkit.Color`, und der Schuss bricht mittendrin ab: Der Strahl wird noch gezeichnet, aber der Treffer nie ausgewertet. Ob ein Partikel Daten braucht, verrät `Particle#getDataType()` – bei den hier verwendeten Partikeln gilt das außer für `FLASH` nur noch für `DUST` (`DustOptions`) und `BLOCK` (`BlockData`).

  15. 🕳 **Singularität** *(Echo-Scherbe)*: Wurfgeschoss (optisch eine Echo-Scherbe über `ThrowableProjectile#setItem`). Beim Einschlag öffnet sich für **4 Sekunden** ein Sog, der Gegner im Umkreis von 8 Blöcken zum Zentrum reißt. Richtet keinen Schaden an: Die Singularität ist ein **Aufbau-Item** für Air-Strike, C4 und Tarnkappenbomber. Der Sog wirkt nur auf Spieler *innerhalb* der Arena.
      - **Der Werfer selbst wird nicht erfasst.**
      - **Wer eliminiert wird, ist für den Rest der Laufzeit raus.** Der Sog hängt nur an der Position, nicht daran, ob es noch derselbe „Anlauf" ist – ohne diesen Ausschluss würde ein Gegner, der beim Respawn zufällig wieder in Reichweite landet, sofort erneut eingesogen.
      - Jede Singularität führt ihre **eigene** Ausschlussliste, damit zwei gleichzeitig offene sich nicht gegenseitig beeinflussen.

  16. 🦅 **Gleitflug** *(Elytra)*: **8 Sekunden** Flug mit Startschub und regelmäßigen Schubstößen. Für die Flugdauer erhält der Spieler Leih-Schwingen im Brustslot, die danach restlos wieder eingesammelt werden. Die Flughöhe respektiert die **Decke der Map** und die Arena-Oberkante – auf der offenen DustPvP-Map könnte man sonst über die Arena hinaussteigen, und außerhalb der Arena ist jeder Kampf deaktiviert. Am Ende gibt es **Sanfter Fall** für die Landung, denn Sturzschaden wäre in der Arena tödlich.

  > **Der Flug endet bei der Landung, nicht erst nach acht Sekunden.** Ohne diese Prüfung liefen Partikel und Flugsound am Boden weiter. Zusätzlich wird `item.elytra.flying` beim Beenden ausdrücklich per `Audience#stopSound` abgewürgt: Der Client spielt den Flugsound als eigene, laufende Soundinstanz, solange er den Spieler für gleitend hält – ein bloßes `setGliding(false)` ließ ihn noch sekundenlang nachklingen, sowohl bei der Landung als auch nach Ablauf der Flugzeit.

  > **Das Gleitflug-Item lässt sich nicht anziehen.** Die Elytra in der Hotbar bekommt über die Paper Data Components ausdrücklich `EQUIPPABLE` und `GLIDER` **entfernt** (`ItemStack#unsetData`). Ohne das könnte man sie einfach in den Brustslot ziehen und hätte unbegrenzten Flug statt der acht Sekunden. Die eigentlichen Schwingen setzen `GLIDER` umgekehrt **explizit**, damit das Flugverhalten nicht von der Standardbelegung des Materials abhängt.

  17. 🤖 **Geschützturm** *(Spender)*: Wird per Rechtsklick auf einen Block in der Arena aufgestellt und beschießt **20 Sekunden** lang selbstständig jeden Gegner in Sichtlinie – alle 0,4 s ein Pfeil, Reichweite 14 Blöcke. Optisch ein Spender auf einem unsichtbaren Ständer, dessen Rohr sich sichtbar zum Ziel neigt; der Restzeit-Zähler steht über ihm. Er endet vorzeitig bei Match-Ende, Pause und wenn sein Besitzer den Server verlässt.
      - **Drei Treffer töten, nicht einer.** Als einzige Waffe im Spiel tötet der Turm nicht mit einem Schlag: Er zielt automatisch und ohne Fehler – mit Sofort-Kill wäre jede Deckung, die er einsieht, schlicht unbetretbar. Der Getroffene sieht seinen Stand (`1/3`, `2/3`) in der Actionbar und bekommt einen leichten Rückstoß. Nach **8 Sekunden** ohne Turmtreffer verfällt das Konto, und jeder Respawn setzt es zurück – sonst summierten sich Streifschüsse über ein ganzes Match zu einer Eliminierung.
      - **Der Turm hält sein Ziel fest**, solange es gültig bleibt, statt pro Schuss neu zu wählen. Ohne das verteilt er seine Treffer auf alle Gegner in Reichweite und kommt bei niemandem auf drei. Nebeneffekt: Der Sichtlinien-Strahl läuft nur, wenn wirklich neu gesucht werden muss.
      - **Er zielt vor und rechnet den Pfeilabfall ein.** Der Vorhalt entsteht aus der selbst gemessenen Positionsdifferenz zweier Takte – `Player#getVelocity` taugt dafür nicht, weil die serverseitige Delta-Bewegung bei Spielern gar nicht aus den Bewegungspaketen gespeist wird. Die Leuchtspur zeichnet dieselbe Flugbahn nach, die der Pfeil wirklich fliegt (Schwerkraft `0.05`/Tick, Luftreibung `0.99`), statt eine gerade Linie zu behaupten.
      - **Unsichtbare Spieler nimmt er nicht ins Visier** – der Unsichtbarkeits-Mantel wäre wertlos, wenn ein Automat weiter zielsicher darauf schösse. Ebenso ausgenommen: der eigene Besitzer, und zwar auch bei einem Streuschuss, der ihn zufällig trifft.

  > **Turmtreffer werden im `ProjectileHitEvent` gezählt, nicht im Schadensweg.** Vanilla lässt nach einem Treffer 10 Ticks Unverwundbarkeit folgen und verschluckt gleich starke Folgetreffer **vor** jedem Schadensevent – bei 0,4 s Feuertakt wäre damit jeder zweite Turmtreffer verloren und drei Treffer kaum erreichbar. `Projectile#preHitTargetOrDeflectSelf` feuert den Treffer-Event dagegen davor und überspringt bei einem Cancel den gesamten Treffer, der Pfeil richtet also garantiert keinen Schaden an. Der `CombatListener` erkennt Turmpfeile trotzdem an ihrem PDC-Marker und lässt sie ausdrücklich am 1-Hit-Zweig vorbei – sonst würde ein durchkommendes Schadensevent sofort eliminieren und dem Besitzer nebenbei einen Pfeil nachfüllen.

  > **Kill-Zuordnung bei Sprengungen läuft über den `DamageType`, nicht über `DamageCause`.** Eine Sprengung ohne Quell-Entity – und genau so sprengen Air-Strike und C4, damit auch der Auslöser getroffen wird – kommt als `DamageCause.CUSTOM` an: `CraftEventFactory` fragt zuerst nach Verursacher- und Direkt-Entity, landet ohne beide im Zweig ohne Entity und ohne Block, und dort wird `DamageTypes.EXPLOSION` gar nicht geprüft. Kills durch Air-Strike und C4 fielen dadurch aus der Zuordnung und wurden nur als „ist gestorben" gemeldet, statt dem Auslöser gutgeschrieben zu werden. Der `DamageType` (`EXPLOSION` / `PLAYER_EXPLOSION`) hängt direkt an der Schadensquelle und geht auf diesem Weg nicht verloren.

  > **Kill-Zuordnung bei Sprengungen**: Der Auslöser wird nicht nur während der Explosion festgehalten, sondern für **6 Sekunden** je getroffenem Spieler vermerkt. Das ist nötig, weil eine Sprengung nicht nur direkt tötet: Eine C4 mit Stärke 12 schleudert Getroffene weit nach oben, und wer den Treffer knapp überlebt, stirbt Sekunden später am Aufprall. Dieser Schaden trägt die Ursache `FALL` und gar keine Verursacher-Entity – der Kill blieb dadurch unzugeordnet und wurde nur als „ist gestorben" gemeldet.

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

- **🎲 Item-Gewichtung (`/osok itemgewichtung`)** – reines GUI, kein Textbefehl:
  - Jedes der 17 Spezial-Items hat ein **Spawngewicht** (Standard `10`). Die Ziehung ist gewichtet: Ein Item mit Gewicht `20` kommt doppelt so oft wie eines mit `10`.
  - Gewicht **`0` nimmt ein Item vollständig aus dem Spiel**, ohne es aus dem Code zu entfernen – praktisch, um einzelne Items für eine Runde zu sperren.
  - Die Gewichte gelten für **beide Quellen**: Boden-Item-Boxen *und* Killstreak-/Kopfgeld-Belohnungen.
  - Stehen alle Gewichte auf `0`, wird weder eine Box gespawnt noch eine Streak-Belohnung vergeben; das Menü warnt davor.
  - **Aufbau**: Jede Item-Reihe ist von Pfeilen eingerahmt – **darüber** ▲ erhöhen, **darunter** ▼ senken. Die 17 Items verteilen sich auf zwei Blöcke zu je drei Reihen:

    ```
    Reihe 0  ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲     erhöhen   (Items 1–9)
    Reihe 1  ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪     Items 1–9
    Reihe 2  ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼     senken
    Reihe 3  ▲ ▲ ▲ ▲ ▲ ▲ ▲ ▲ ·     erhöhen   (Items 10–17)
    Reihe 4  ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ 🔄    Items 10–17, Zurücksetzen
    Reihe 5  ▼ ▼ ▼ ▼ ▼ ▼ ▼ ▼ ✖     senken, Schließen
    ```
  - **Linksklick ±1, Rechtsklick ±5.** Jedes Item zeigt Gewicht und resultierende Prozentchance; die Stapelgröße spiegelt das Gewicht (auf 1–64 begrenzt, der exakte Wert steht im Namen – `0` und Werte über 64 wären als Stapelgröße gar nicht darstellbar).
  - Nach jedem Klick wird das **gesamte** Menü neu aufgebaut: Ein einzelnes geändertes Gewicht verschiebt die Prozentchance *aller* anderen Items.

- **👑 Kopfgeld-System**:
  - Ab einer 5er Killstreak erhält der Spieler ein Kopfgeld `[👑]` mit Blitzschlag-Ankündigung.
  - Wer das Kopfgeld holt, erhält 2 zufällige Spezial-Items als Belohnung.
- **🏕 Anti-Camping (`/osok camper`)** – reines GUI, kein Textbefehl:
  - Wer **20 Sekunden** (einstellbar) im Umkreis von **5 Blöcken** (einstellbar) bleibt, leuchtet für alle auf – bis er sich wieder bewegt.
  - **5 Sekunden vorher** gibt es eine Actionbar-Vorwarnung mit Signalton.
  - Zählt nur innerhalb der Arena und nur bei laufendem Match.
  - **Aufbau** – gleiche Logik wie bei der Item-Gewichtung, Wert in der Mitte, ▲ darüber, ▼ darunter:

    ```
    Reihe 0  · · ▲ · · · ▲ · ·     Zeit +   /  Radius +
    Reihe 1  · · ⏱ · ⏻ · ⌖ · ·     Zeit  / An-Aus /  Radius
    Reihe 2  · · ▼ · ✖ · ▼ · ·     Zeit −  / Schließen / Radius −
    ```
  - **Linksklick ±1, Rechtsklick ±5.** Zeit 3–600 s, Radius 1–64 Blöcke. Am Anschlag quittiert ein Fehlton.
  - Eine Änderung setzt laufende Zähler zurück – sie gelten für den alten Wert und wären sonst falsch.
  - **Die Streckenmessung für die Match-Zusammenfassung läuft unabhängig weiter**, auch wenn die Markierung ausgeschaltet ist: Das ist eine eigene Aufgabe und hängt nicht an dieser Einstellung.

- **📋 Match-Zusammenfassung**:
  - Wird nach dem Sieger und bei `/osok stop` ausgegeben – jeweils **vor** dem Zurücksetzen der Statistiken.
  - Ausgewertet werden **MVP** (Kills ×3 + beste Streak ×2 − Tode), **meiste Kills**, **beste K/D**, **meiste Tode**, **meiste eingesammelte Items** und **längste zurückgelegte Strecke**.
  - Die Strecke misst der `AntiCampManager` über `PlayerMoveEvent#hasChangedPosition()`. Sprünge über 8 Blöcke gelten als Teleport (Respawn, Rauchbombe, Teleport-Granate) und zählen nicht – sonst würde ein Respawn quer durch die Arena die Statistik verfälschen.
  - Gezählt werden Items aus **Boden-Boxen, Killstreak- und Kopfgeld-Belohnungen**; das Admin-Testmenü zählt bewusst nicht mit.
  - Ausgewertet werden nur **verbundene** Spieler – eine UUID ohne Online-Spieler aufzulösen würde eine blockierende Profilabfrage auslösen. Die Live-Rangliste im Scoreboard arbeitet aus demselben Grund so.

- **💡 Zentrale Leuchtrahmen-Verwaltung**:
  - Radar-Puls und Anti-Camping markieren Spieler unabhängig voneinander. Der `GlowManager` hält deshalb pro Spieler die **Gründe** fest und schaltet `Entity#setGlowing` erst ab, wenn kein Grund mehr besteht – sonst würde ein auslaufender Camping-Status das Radar-Leuchten mit abschalten.

- **📊 Native Paper Scoreboard & Tabliste**:
  - Live Leaderboard mit Kills, K/D Ratio, Streak, Highscore und Kopfgeld-Marker.
  - Zeilen werden vollständig als Kyori `Component` über `Score#customName(Component)` gerendert (0% Legacy-`§`-Codes).
  - **Board wird pro Spieler gecacht** und nur inhaltlich aktualisiert, statt bei jedem Update neu aufgebaut zu werden. Statische Zeilen liegen als vorgeparste `Component`-Konstanten bereit, die Rangliste wird pro Update einmal sortiert statt pro Spieler.
  - Ausblendung der roten Sidebar-Zahlen nativ über `Objective#numberFormat(NumberFormat.blank())` (0% NMS-Reflection).
  - Tablisten-Namen mit Live-Stats über `Player#playerListName(Component)`.

- **☢ Nuke-Finale – so endet eine Runde**:
  - **Das Match-Ziel gewinnt die Runde nicht mehr von selbst.** Wer das Kill-Limit erreicht – oder bei Ablauf der Zeit vorne liegt – bekommt den **Nuke-Auslöser** ins Inventar. Das Match läuft normal weiter, bis er ihn benutzt; beendet wird die Runde ausschließlich durch die Nuke.
  - Freigeschaltet ist immer nur **einer**: Erreichen zwei Spieler das Ziel kurz hintereinander, bleibt es beim Ersten. Verlässt der Freigeschaltete den Server, wandert der Auslöser an den aktuell Führenden – sonst hinge die Runde fest.
  - **Freigabemenü mit vierstelligem Code**: Rechtsklick öffnet ein Menü, in dem der Code steht und über Zifferntasten abgetippt werden muss. Darunter liegen **Abbrechen** und **Bestätigen**; bestätigen lässt sich erst, wenn vier Ziffern eingegeben sind, und ein falscher Code setzt die Eingabe zurück. Der Code wird bei jedem Öffnen neu gewürfelt, *Abbrechen* behält den Auslöser.

    ```
    Reihe 0  · · C C C C · · ·     Code zum Abtippen
    Reihe 1  · · E E E E · · ·     Eingabe
    Reihe 2  · · · · · · · · ·
    Reihe 3  1 2 3 4 5 6 7 8 9     Zifferntasten
    Reihe 4  · · ⌫ · 0 · · · ·     Löschen / Null
    Reihe 5  · · ✖ · · · ✔ · ·     Abbrechen / Bestätigen
    ```
  - **Ablauf des Angriffs**: Über der ganzen Arena geht **TNT** nieder – 12 Wellen, alle halbe Sekunde. Die Blockliste jeder Explosion wird geleert, die **Map bleibt unversehrt**, und **sterben kann daran niemand**: Solange das Finale läuft, ist jeder Schadensweg abgeschaltet. Das Bombardement ist Kulisse, getötet wird ausschließlich vom Gas.
  - **Dann tritt Giftgas aus** – dichte, giftgrüne Schwaden über der ganzen Karte. Die Optik entsteht auf zwei Wegen:
      - **Volumen um jeden Betrachter**: Fünfmal pro Sekunde bekommt jeder Spieler seinen eigenen Gasquader gelegt (±14 Blöcke breit, 5 hoch), dazu ein dichter Bodenteppich an seinen Füßen. Gezeichnet wird über `Particle.DUST` mit `DustOptions` – nur damit lässt sich ein Partikel wirklich einfärben – in **zwei Grüntönen** übereinander, damit die Wolke Tiefe bekommt statt wie eine flache Wand zu wirken, plus etwas Rauch für Struktur.
      - **Warum pro Spieler und nicht über die Welt?** `World#spawnParticle` schickt jede Schwade an alle Umstehenden; bei fünf Spielern käme dieselbe Wolke fünffach an. `Player#spawnParticle` zeichnet nur für den einen Betrachter – und weil das Volumen ohnehin um ihn herum liegt, sieht er exakt dasselbe. Dazu kommt: Die Streuwerte spannen einen Quader auf, in dem **der Client** die hunderte Partikel selbst verteilt. Aus *einem* Paket wird die ganze Wolke, der Server zählt sie nicht einmal.
      - **Fernwirkung** über ein überlappendes Raster aus `AreaEffectCloud`-Schwaden (Abstand 10, Radius 10) in derselben Farbe – damit die Karte auch dort vergast aussieht, wo gerade niemand steht.
      - Die Schwaden laufen **über das Rundenende hinaus**: Die Zuschauer sollen auf eine vergaste Karte blicken, nicht auf klare Luft. Erst der nächste `/osok start` räumt sie weg.
  - Die *Wirkung* hängt bewusst **nicht** an den Wolken (deren Trefferprüfung ist flach und endet an Wänden), sondern an einer eigenen Dosis-Buchführung: Jede Sekunde steigt die Dosis, Sicht und Tempo brechen ein, die Lebensanzeige sinkt sichtbar mit – und nach **12 Sekunden** erstickt der Spieler. Kurz vor Schluss wird es zusätzlich schwarz vor Augen.
  - Die Lebensanzeige geht dabei **nie auf null**: Ein echter Tod würde den Respawn-Bildschirm zeigen und an der Buchführung vorbeilaufen. Wer erstickt, wandert sofort in den **Zuschauermodus**.
  - **Erst wenn alle erstickt sind**, werden alle Zuschauer in die Mitte der vergasten Arena gesetzt – und **danach** wird der Sieger ausgerufen. Die Ausrufung wartet ausdrücklich auf die Teleports (`CompletableFuture.allOf`), sonst stünde der Siegertext im Chat, während die Spieler noch auf ihrem Sterbepunkt hängen.
  - Der nächste `/osok start` (oder `/osok stop`, oder ein Map-Wechsel) räumt alles ab: Gaswolken, liegengebliebenes TNT, den Auslöser – und holt **nur die Zuschauer zurück, die das Finale selbst gesetzt hat**. Wer freiwillig zuschaut, bleibt Zuschauer.

- **🏆 Match-Manager & Dauer-Einstellung**:
  - **Match-Ziel immer sichtbar**: Sobald ein Kill- oder Zeitlimit konfiguriert ist, steht es auf dem Scoreboard **jedes** Spielers.
  - **Wertung einfrieren (`/osok pausestats`)**: Kills, Tode und Streaks werden nicht mehr gezählt, der Match-Timer läuft nicht weiter und das Scoreboard bleibt stehen. Anders als `/osok pause` bleibt das Match spielbar – Treffer wirken normal (Effekt, Respawn), zählen aber nicht. Ein erneuter Aufruf setzt die Wertung fort.
  - **Endspurt-Hinweis**: Ab **5 verbleibenden Kills** bekommt der Spieler nach jedem Kill eine Nachricht samt Actionbar und Signalton, wie viele Kills ihm noch zum Sieg fehlen.
  - `/osok start` setzt vor jedem Match das Scoreboard zurück und räumt alte Item-Boxen und Bomber weg.
  - `/osok stop` beendet das Spiel: Statistiken zurückgesetzt, Ausrüstung und Effekte entfernt, alle Spieler in der Lobby der aktiven Map.
  - Konfigurierbare Kills-, Minuten- und Sekunden-Limits (`/osok dauer kills <n>`, `/osok dauer minuten <n>`, `/osok dauer sekunden <n>`, `/osok dauer off`).
  - **Verzögerter Start**: Festgelegte Limits werden gespeichert und erst beim Ausführen von `/osok start` im Scoreboard sichtbar & als Timer gestartet.
  - **Erreichtes Kill-Limit und abgelaufene Zeit schalten die Nuke frei** (siehe oben) statt sofort einen Sieger auszurufen.
  - Gewinner-Titel & Feuerwerksspektakel – **ohne Musik**: Der Notenblock-Song lief früher in Dauerschleife bis zum nächsten Match-Start.

- **🌍 Welt-Handling & GameRules**:
  - Automatische Extraktion von `Standard.zip` bzw. `DustPvP.zip` aus den Plugin-Ressourcen in die Server-Welt.
  - **`locator_bar` ist serverweit dauerhaft auf `false`**: gesetzt beim Start auf allen geladenen Welten, bei `WorldInitEvent`/`WorldLoadEvent` für später geladene Welten, und ein Reaktivieren per `/gamerule` wird über den Paper `WorldGameRuleChangeEvent` blockiert.
  - Arena-GameRules über die moderne `org.bukkit.GameRules` Registry: `IMMEDIATE_RESPAWN`, `KEEP_INVENTORY`, `SPAWN_MOBS=false`, `SPAWN_PATROLS=false`, `SPAWN_WANDERING_TRADERS=false`.
  - **☀ Immer Mittag, immer klar**: `ADVANCE_TIME` und `ADVANCE_WEATHER` stehen serverweit auf `false`, dazu werden Zeit und Wetter einmal explizit gesetzt. Die GameRules allein reichen nicht – sie halten nur den Fortlauf an, eine Welt mitten in der Nacht bliebe genau so stehen. Ein Reaktivieren per `/gamerule` wird über den `WorldGameRuleChangeEvent` abgelehnt, und `WeatherChangeEvent`/`ThunderChangeEvent` fangen zusätzlich jeden Wechsel weg von „klar" ab – etwa durch `/weather rain` oder ein anderes Plugin.
  - **Map-Wechsel ohne Neustart**: Das laufende Match wird sauber gestoppt, Boden-Items samt Chunk-Tickets sowie Drachen, fallende Bomben und platzierte C4-Ladungen werden freigegeben, und die alte Welt wird erst entladen, wenn **alle** `teleportAsync`-Vorgänge nachweislich abgeschlossen sind (`CompletableFuture.allOf`). Parallele Wechsel sind gesperrt. Der Wechsel läuft bewusst **ohne Sound-Quittung**.

---

## 🚀 Native Paper 26.2 Highlights

1. **Native Paper Plugin Architecture**: `paper-plugin.yml` (`api-version: '26.2'`) für direkte Einordnung unter **Paper Plugins** bei `/pl`.
2. **Paper Lifecycle Commands API & Brigadier**: Registrierung über `LifecycleEvents.COMMANDS` mit `BasicCommand`, `canUse` und `suggest` (ausschließlich `/osok`). Keine Bukkit `CommandExecutor`/`TabCompleter`.
3. **Paper Persistent Data Container (PDC)**: Typsichere Identifizierung aller Spezial-Items via `NamespacedKey` – keine Anzeigenamen-Vergleiche.
4. **Paper Entity & Region Schedulers**: Thread-safe Aufgabenverwaltung via `player.getScheduler()` und `GlobalRegionScheduler` (0% `BukkitRunnable`).
5. **Paper Spatial Entity Index Engine**: Räumliche Suchen per `loc.getNearbyPlayers()` und `getNearbyEntitiesByType()`.
6. **Asynchrone Teleportation (`player.teleportAsync`)**: Hintergrund-Preloading von Ziel-Chunks mit `.thenAccept(...)` Callbacks.
7. **Paper Plugin Chunk Tickets (`addPluginChunkTicket`)**: Hält Chunks mit Boden-Items geladen.
8. **Kyori Adventure Component & Sound API**: Alle Texte, Titles, Tablisten über `Component`/MiniMessage; sämtliche Sounds über `Audience#playSound(net.kyori.adventure.sound.Sound)` mit `Sound.Source` statt `SoundCategory`.
9. **Moderne GameRules-Registry**: `org.bukkit.GameRules` statt des in Paper 26.x als *deprecated for removal* markierten `org.bukkit.GameRule`.

10. **100 % Kotlin**: Der gesamte Quellcode ist in nativem Kotlin geschrieben — 0 % Java. Details
    und die verbindlichen Sprachregeln stehen in [`.agents/AGENTS.md`](.agents/AGENTS.md).

> Der Quellcode ist frei von Deprecation- und Removal-Warnungen. Der Build erzwingt das selbst:
> `allWarningsAsErrors = true` lässt jede Compiler-Warnung den Build abbrechen.

---

## ⚙️ Requirements & Server-Setup

- **Server-Software**: [Paper 26.2](https://papermc.io/) (oder neuer)
- **Java**: Java 25 (Pflicht — `paper-api` 26.2 deklariert `org.gradle.jvm.version = 25`)
- **Minecraft Client**: 26.2

Das Repository enthält **den Quellcode des Plugins** — dazu als einzige Server-Datei
`Server/start.bat`, ein Startskript mit den Aikar-Flags (fester 6-GB-Heap, auf Paper abgestimmte
G1-Werte). Der Paper-Server selbst, Welten, Logs und Konfigurationen gehören nicht dazu.

So kommst du zu einem laufenden Testserver:

1. [Paper 26.2](https://papermc.io/downloads/paper) herunterladen und als `Server/server.jar` ablegen.
2. `.\build.ps1` — baut die JAR und legt sie in `Server/plugins/`.
3. `Server/start.bat` ausführen. Beim ersten Start legt Paper `eula.txt` an; darin `eula=true`
   setzen und erneut starten.

`start.bat` prüft beides und sagt dir, was fehlt.

Kotlin und Gradle müssen **nicht** installiert werden. Der Gradle-Wrapper liegt im Repository und
lädt beim ersten Aufruf sowohl Gradle als auch den Kotlin-Compiler selbst nach; gebraucht wird nur
ein JDK 25.

---

## 🛠️ Kompilierung & Deployment

```bash
./gradlew build
```

Die fertige `build/libs/OneShotOneKill_26.2.jar` in den `plugins/`-Ordner des Servers kopieren und
den Server neu starten — ein laufender Server lädt die JAR nicht neu.

**Was der Build macht:**
1. Ermittelt die `paper-api`-Version **ausschließlich aus `Server/server.jar`** und kompiliert gegen
   genau die API, die dein Server fährt (siehe unten). Ohne Server-JAR bricht er ab.
2. Bündelt die `kotlin-stdlib` in die JAR — Paper bringt sie nicht mit, und `paper-plugin.yml` hat
   kein `libraries:`-Feld.
3. Packt `paper-plugin.yml` sowie `Standard.zip` und `DustPvP.zip` dazu.

### 🔄 Paper-Version: keine Handarbeit

Gebaut wird immer gegen die API, die der Server tatsächlich benutzt — sonst fallen Abweichungen
erst zur Laufzeit auf, im schlimmsten Fall als `NoSuchMethodError`. Die **einzige** Quelle dafür ist
`Server/server.jar`: Die Paperclip-JAR führt ihre Bibliotheken unter `META-INF/libraries/…` mit, die
Version steht also in der Server-JAR selbst. **Gestartet werden muss der Server dafür nie.**

**Es gibt bewusst keinen Rückfallwert.** Ein zweiter, gepinnter Wert kann von der Server-JAR
abweichen — und dann baut man unbemerkt gegen etwas anderes, als später läuft. Genau das soll die
Mechanik verhindern. Fehlt die Server-JAR, bricht der Build mit einem Hinweis ab, statt eine
möglicherweise falsche Version zu raten.

Der Build sagt bei jedem Lauf, was er benutzt:

```
Paper-API: 26.2.build.111-stable  (aus Server/server.jar)
```

**Ein Server-Update besteht damit aus genau zwei Schritten**: neue `server.jar` nach `Server/`
legen, `.\build.ps1` ausführen. Alles Weitere zieht mit:

- Gradle löst `io.papermc.paper:paper-api` auf die erkannte Version auf und lädt sie aus
  `repo.papermc.io` nach.
- `api-version`, `name` und `version` in `paper-plugin.yml` sowie der JAR-Dateiname leiten sich aus
  ihr ab — bei einem Sprung auf 26.3 entsteht also automatisch `OneShotOneKill_26.3.jar` mit
  `api-version: '26.3'`.
- Der Deploy räumt JARs früherer Versionen aus `Server/plugins/` weg. Ohne das läge die alte neben
  der neuen, und Paper lüde **beide** Plugins.
- Im Gradle-Cache bleibt ebenfalls nur die passende Fassung stehen: `deployPlugin` löscht alle
  anderen `paper-api`-Versionen unter
  `~/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/`. Der Cache hält sonst jede je
  gezogene Version dauerhaft vor — und beim Nachschlagen einer Signatur greift man dann leicht in
  die falsche Fassung. Die aktuelle bleibt immer stehen; lässt sich ein Ordner nicht entfernen
  (weil ein anderer Build ihn offen hält), gibt es eine Warnung statt eines Abbruchs, und Gradle
  lädt Fehlendes ohnehin neu.

Zwei Dinge, die dabei zu erwarten sind: Deprecatet die neue API etwas, das hier benutzt wird,
**bricht der Build** — `allWarningsAsErrors` ist Absicht, so fällt es beim Bauen auf statt zur
Laufzeit. Und der **Configuration Cache muss aus bleiben** (Gradle bewirbt ihn bei jedem Lauf): Die
Erkennung liest die Server-JAR zur Konfigurationszeit, mit Cache friert der Wert beim ersten Lauf
ein und genau ein Server-Update ginge daran vorbei.

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
| `/osok itemgewichtung` | Öffnet das **Menü** für die Spawnwahrscheinlichkeit je Spezial-Item | Operator (OP) |
| `/osok camper` | Öffnet das **Menü** für Anti-Camping: an/aus, Zeit und Radius | Operator (OP) |
| `/osok itemtest` | Öffnet Admin-Test-GUI für alle 17 Spezial-Items | Operator (OP) |
| `/osok clearpfeile` | Entfernt herumliegende Pfeile in allen Welten | Operator (OP) |
| `/osok setspawn` | Setzt den Lobby-Spawnpunkt der aktiven Map | Operator (OP) |
| `/osok resetstats` | Setzt Kills, Tode & Scoreboard-Statistiken zurück | Operator (OP) |
| `/osok resetmap` | Entpackt die saubere Map aus der JAR & startet den Server neu | Operator (OP) |

---

## 📄 Lizenz & Credits
Entwickelt als 100% natives Paper 26.2 Plugin in 100% nativem Kotlin.

---

## 👥 Contributors & Mitwirkende

- **Lostpold** ([@Lostpold88](https://github.com/Lostpold88)) – Owner & Lead Developer
- **jonasmzz** ([@jonasmzzz](https://github.com/jonasmzzz)) – Contributor & Server Admin

