# Projekt-Regeln für Minecraft Plugins (OneShotOneKill)

## 🔴 Grundsatz 1: Native Paper API

**100% natives Paper Plugin** (`paper-plugin.yml`, `api-version: '26.2'`) für Paper 26.2+.

**MANDATORY:** Jede Änderung – Befehl, Listener, GUI, Fix – benutzt die Paper-API. Existiert eine
Paper-Lösung, hat sie Vorrang. Bukkit-Klassen nur dort, wo Paper **keine** eigene Schnittstelle
anbietet (`org.bukkit.Material`, `org.bukkit.Location`). **Im Zweifel: die modernere,
Component-basierte, asynchrone Variante.**

---

## 🔴 Grundsatz 2: 100 % natives Kotlin

**0 % Java.** Eine `.java`-Datei im Repository ist ein Regelverstoß. „Nativ" heißt: kein
transliteriertes Java. Verbindlich:

| Java-Idiom | Kotlin-Pflichtform |
| :--- | :--- |
| `getFoo()` / `setFoo(x)` | Property: `val foo` / `var foo` (bei Bedarf `private set`) |
| `if (x != null) x.foo();` | `x?.foo()` |
| `x != null ? x : y` | `x ?: y` |
| `new Runnable() { … }` / anonyme Klasse | Lambda / SAM, `Consumer<T>` als `{ t -> … }` |
| `switch` / lange `if-else`-Kette | `when` |
| `public static final X` | `companion object { const val … }` oder Top-Level-`const val` |
| Klasse nur mit statischen Methoden | `object` oder Extension Function |
| Nur-Daten-Klasse mit `equals`/`hashCode` | `data class` |
| `new HashMap<>()` / `new ArrayList<>()` | `mutableMapOf()` / `mutableListOf()` |
| `List.of(...)` / `Map.of(...)` | `listOf(...)` / `mapOf(...)` |
| `StringBuilder` + Schleife | String-Templates, `buildString`, `joinToString` |
| Javadoc mit `@param` | KDoc mit `[Referenz]`-Syntax |

- **`!!` ist verboten**, wo eine Prüfung möglich ist: `?.`, `?:`, `requireNotNull(...)` mit Meldung,
  `checkNotNull(...)` oder `lateinit var`.
- **Nullability wird nicht geraten**, sondern gegen die JAR geprüft (siehe Pflicht-Vorgehen). Der
  Compiler läuft mit `-Xjsr305=strict`.
- **Keine `kotlinx-coroutines`** (Nebenläufigkeit läuft über Papers Scheduler, Vorgabe 3) und
  **keine Kotlin-Wrapper-Bibliotheken** für Bukkit/Paper. `CompletableFuture` aus `teleportAsync`
  bleibt `CompletableFuture`.

Grundsatz 1 bestimmt die **API-Wahl**, Grundsatz 2 die **Sprachform**. Im Konflikt gewinnt Paper.

### Laufzeit-Abhängigkeit: kotlin-stdlib

Paper bringt sie nicht mit, `paper-plugin.yml` hat kein `libraries:`-Feld (verifiziert: `PluginMeta`
hat kein `getLibraries()`). Sie wird über die `bundled`-Konfiguration in `build.gradle.kts` in die
JAR gepackt.

> ⚠️ **`PluginLoader` + `MavenLibraryResolver` geht dafür nicht** (am laufenden Server gemessen):
> Die Loader-Klasse ist selbst Kotlin und wird vom `PaperSimplePluginClassLoader` geladen, der nur
> die Plugin-JAR sieht – sie bräuchte die stdlib, um die stdlib zu laden. Server bricht mit
> `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics` ab. Für **weitere** Bibliotheken bleibt der
> `PluginLoader` richtig, dann ist die stdlib ja schon da.

---

## 📌 Die 20 verbindlichen API-Vorgaben

1. **Bootstrap & Konfiguration**: nur `paper-plugin.yml` (0% `plugin.yml`).
2. **Commands**: `LifecycleEvents.COMMANDS` + `BasicCommand` / `CommandSourceStack` inkl. `canUse`
   und `suggest`. **0% `CommandExecutor`, 0% `TabCompleter`.**
3. **Scheduler**: `player.getScheduler()`, `Bukkit.getGlobalRegionScheduler()`,
   `getRegionScheduler()`, `getAsyncScheduler()`. **0% `Bukkit.getScheduler()` / `BukkitRunnable`.**
4. **PersistentDataContainer**: `NamespacedKey` zur Item-/Entity-Markierung. 0% Namensvergleiche,
   0% Metadata-API, 0% NBT-Reflection.
5. **Scoreboard**: `Criteria.DUMMY`, `Score#customName(Component)`,
   `Objective#numberFormat(NumberFormat.blank())`. 0% NMS-Reflection.
6. **Teleportation**: **Spieler** nur via `player.teleportAsync(loc)` + `.thenAccept(...)`, mehrere
   über `CompletableFuture.allOf(...)`; Feinsteuerung mit `TeleportFlag.Relative` /
   `.EntityState`.
   **Ausnahme (kein Verstoß):** Wird eine Entity **jeden Tick** in derselben Welt umgesetzt
   (Chunk geladen, Main-Thread), ist synchrones `entity.teleport(loc)` richtig – mehrere offene
   `teleportAsync` blockieren die Bewegung.
7. **Spatial Index**: `loc.getNearbyPlayers(r)`, `getNearbyEntitiesByType(...)`,
   `world.getEntitiesByClass(...)`. **0% `getNearbyEntities` + `instanceof`**, 0% Distanzschleifen
   über alle Spieler.
8. **Chunk-Tickets**: `world.addPluginChunkTicket` / `removePluginChunkTicket`.
9. **Adventure & MiniMessage**: alle Texte via `Component`, `MiniMessage.miniMessage()`, `Audience`.
   **0% `ChatColor`, 0% `§`, 0% `LegacyComponentSerializer`.**
10. **ItemMeta**: **Vorgabe 18 hat Vorrang.** `ItemMeta` nur für Daten ohne DataComponent, dann
    Component-basiert und **immer** über `ItemStack#editMeta(...)` – nie `getItemMeta()` →
    `setItemMeta()`. PDC direkt am Stack (`editPersistentDataContainer` / `getPersistentDataContainer`).
11. **GUIs**: `Bukkit.createInventory(owner, size, Component)`, Abgleich über `view.title()`.
12. **ActionBar / Title / Sound**: `Audience#sendActionBar`, `#showTitle`,
    `#playSound(net.kyori.adventure.sound.Sound)` mit `Sound.Source`. **0% `SoundCategory`.**
13. **Events**: Wo ein Paper-exklusives Event existiert, hat es Vorrang (`AsyncChatEvent`,
    `PrePlayerAttackEntityEvent`, `EntityMoveEvent`, `EntityKnockbackEvent`,
    `WorldGameRuleChangeEvent`).
14. **Welt & GameRules**: Paper World API und `org.bukkit.GameRules` (0% `org.bukkit.GameRule`).
15. **0% Legacy**: kein veralteter Bukkit/Spigot-Call, wenn Paper eine Schnittstelle hat.
    Insbesondere **0% `entity.spigot()`**.
16. **Login & Verbindung**: `PlayerConnectionValidateLoginEvent` + `PlayerLoginConnection`
    (**0% `PlayerLoginEvent`** – deprecated for removal). Identität nur über
    `getAuthenticatedProfile()`.
17. **Bans**: `BanListType.PROFILE` / `.IP` und die typisierten Methoden (`pardon(PlayerProfile)`,
    `isBanned(InetAddress)`). **0% String-Überladungen, 0% `BanList.Type`.**
18. **DataComponents**: Item-Daten über `ItemStack#setData/getData/hasData/unsetData` mit
    `DataComponentTypes`.
19. **Registry**: `RegistryAccess.registryAccess().getRegistry(RegistryKey.…)` statt statischer
    Felder, wo ein Datapack den Typ erweitern kann.
20. **Attribute**: `entity.getAttribute(Attribute.…)` / `AttributeInstance`
    (**0% `setMaxHealth(double)`**).

---

## ✅ Pflicht-Vorgehen vor jeder Änderung

**Nicht raten, nachsehen.** Signatur, Nullability, Deprecation **und Wertebereich** immer gegen die
JAR prüfen (im Cache liegt nur die Fassung des aktuellen Servers, `head -1` trifft also immer die
richtige):

```bash
J=$(find ~/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api -name "*.jar" | head -1)
javap -cp $J org.bukkit.entity.Player | grep -i "<methode>"           # Signatur
javap -v -cp $J org.bukkit.entity.Player | grep -B5 "Nullable"         # Nullability
javap -v -cp $J org.bukkit.BanList | grep -A12 "public .* pardon"      # Deprecation
javap -c -cp $J 'org.bukkit.Particle$DustOptions' \
  | grep -B4 -E "requireRange|checkArgument|checkNotNull"              # Wertebereich
unzip -l $J | grep -oE "io/papermc/paper/[a-z0-9/]+/" | sort | uniq -c | sort -rn
```

> ⚠️ **Wertebereiche stehen im Rumpf, nicht in der Signatur.** Wer einen Zahlenwert an eine fremde
> API übergibt, sieht sich deshalb den Konstruktor bzw. Setter mit `javap -c` an – die Grenzen
> stehen als `ldc`-Konstanten direkt vor dem `requireRange`/`checkArgument`-Aufruf.
> `Particle.DustOptions(Color, float)` sieht in der Signatur aus wie „irgendein Float", prüft die
> Größe aber mit `requireRange(size, "size", 0.01F, 4.0F)`. Ein Wert daneben fliegt erst zur
> Laufzeit auf – und steht die Konstante in einem `companion object`, schon beim **Laden der
> Klasse**: Dann aktiviert sich das Plugin gar nicht erst. Genau so ist der Nuke-Nebel einmal am
> Serverstart gescheitert. Ist der exakte Betrag ohnehin Geschmackssache, gehört der Wert zusätzlich
> geklemmt (`coerceIn`), statt sich auf die Grenze zu verlassen.

> ⚠️ **Deprecation immer im Block der einzelnen Methode prüfen** (`grep -A12` auf die Signatur).
> `Deprecated: true` steht im `javap -v`-Strom **nach** der Signatur; ein `grep -B` ordnet es leicht
> der Nachbarmethode zu – das hat hier schon zu einer Fehldiagnose geführt.

Lokaler Testserver: dieselbe JAR unter `Server/libraries/io/papermc/paper/paper-api/…`,
Adventure unter `Server/libraries/net/kyori/adventure-api/…` (26.2 = **Adventure 5.2.0**).

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
| `getItemMeta()` → ändern → `setItemMeta()` | `stack.editMeta(meta -> …)` |
| `playSound(Location, org.bukkit.Sound, SoundCategory, …)` | `Audience#playSound(Sound.sound(…, Sound.Source.…))` |
| `getNearbyEntities(...)` + `instanceof Player` | `loc.getNearbyPlayers(radius)` |
| `org.bukkit.GameRule` / `setGameRuleValue(String, String)` | `world.setGameRule(GameRules.…, wert)` |
| `entity.spigot().respawn()` | GameRule `IMMEDIATE_RESPAWN` |
| `registerNewObjective(name, "dummy", …)` | `registerNewObjective(name, Criteria.DUMMY, Component)` |
| `getType().name().contains("…")` | `Tag.…isTagged(material)` oder `Material`-Vergleich |
| `world.dropItem(loc, stack)` + Setter | `world.dropItem(loc, stack, item -> { … })` |
| `PlayerLoginEvent` | `PlayerConnectionValidateLoginEvent` + `PlayerLoginConnection` |
| `AsyncPlayerChatEvent` | `io.papermc.paper.event.player.AsyncChatEvent` |
| `BanList.Type` / `pardon(String)` | `BanListType.PROFILE` / `pardon(PlayerProfile)` |
| `entity.setMaxHealth(double)` | `entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(…)` |
| `setPlayerListName(String)` / `setDisplayName(String)` | `playerListName(Component)` / `displayName(Component)` |
| `ItemStack(Material, n)` (Konstruktor) | `ItemStack.of(Material, n)` |
| `Bukkit.createInventory(holder, size, String)` | `Bukkit.createInventory(holder, size, Component)` |

---

## 🗺️ Paper-API-Landkarte (Einstiegspunkte)

Gegen die `paper-api` der 26.2-Reihe verifiziert. Wer etwas sucht, schaut hier nach, statt auf eine
Bukkit-Lösung zurückzufallen.

- **Start & Lebenszyklus:** `PluginBootstrap` (einziger Zeitpunkt, an dem Registries erweiterbar
  sind) · `PluginLoader` + `MavenLibraryResolver` · `LifecycleEvents.COMMANDS` / `.TAGS` /
  `.DATAPACK_DISCOVERY` (reloadfest) · `PluginMeta`
- **Befehle:** `BasicCommand` (`execute`, `canUse`, `suggest`) · `Commands.literal/argument` ·
  `Commands.restricted(predicate)` · `CommandSourceStack` (trennt Sender und Executor) ·
  `MessageComponentSerializer` · `ArgumentTypes.` mit `player()`, `entities()`, `itemStack()`,
  `blockPosition()`, `finePosition()`, `rotation()`, `angle()`, `blockState()`, `itemPredicate()`,
  `namedColor()`, `hexColor()`, `component()`, `style()`, `playerProfiles()`
- **Scheduler** (`io.papermc.paper.threadedregions.scheduler`): `GlobalRegionScheduler`
  (serverweit) · `RegionScheduler` (ortsgebunden) · `EntityScheduler` (folgt der Entity, nimmt
  `retired`-Runnable) · `AsyncScheduler` (`TimeUnit` statt Ticks) · Rückgabe `ScheduledTask` mit
  `cancel()` / `getExecutionState()`
- **Text & Dialoge (Adventure 5.2.0):** `MiniMessage` · `Audience` (Player, World, Server) ·
  `Title.title(…, Title.Times.times(…))` · **Dialog API** `Audience#showDialog(DialogLike)` mit
  `NoticeType`/`ConfirmationType`/`MultiActionType`, `TextDialogInput`, `BooleanDialogInput`,
  `NumberRangeDialogInput`, `SingleOptionDialogInput`, `ActionButton` · `ChatRenderer`
  (Format pro Empfänger) · `PaperComponents`
- **Items:** `DataComponentTypes` (`CUSTOM_NAME`, `ITEM_NAME`, `LORE`, `ENCHANTMENTS`,
  `MAX_STACK_SIZE`, `CUSTOM_MODEL_DATA`, `TOOLTIP_DISPLAY`, `ENCHANTMENT_GLINT_OVERRIDE`,
  `CONSUMABLE`, `EQUIPPABLE`, `GLIDER`, `UNBREAKABLE`, …) · `ItemStack.of` · `editMeta` ·
  `PersistentDataContainerView` (lesend, ohne Meta-Kopie) · `ItemRarity`, `TooltipContext`
- **Inventare:** `Bukkit.createInventory(holder, size, Component)` · `MenuType` mit `.builder()` /
  `InventoryViewBuilder` (Amboss, Ofen, Werkbank – was `createInventory` nicht kann) · `view.title()`
- **Scoreboard:** `Criteria.DUMMY` · `Score#customName(Component)` ·
  `io.papermc.paper.scoreboard.numbers.NumberFormat` (`blank()`, `fixed(Component)`, `styled(Style)`)
- **Registry & Tags:** `RegistryAccess.registryAccess().getRegistry(RegistryKey.…)` (43 Schlüssel,
  u. a. `ENCHANTMENT`, `BIOME`, `DAMAGE_TYPE`, `ATTRIBUTE`, `GAME_RULE`, `MOB_EFFECT`,
  `DATA_COMPONENT_TYPE`, `DIALOG`) · `TypedKey` · `RegistryEvents` (nur im Bootstrap) ·
  `io.papermc.paper.tag.EntityTags` · `RegistrySet`
- **Welt & Raytracing:** `io.papermc.paper.math.Position` / `BlockPosition` / `FinePosition`
  (unveränderlich, als Map-Schlüssel sicher) · `Rotation`, `Angle` ·
  `world.rayTrace { builder -> … }` mit `RayTraceTarget` und `BlockCollisionMode` ·
  `addPluginChunkTicket` · `GameRules` · `Bukkit.getDatapackManager()`
- **Entities & Spieler:** `teleportAsync(loc, cause, TeleportFlag…)` ·
  `world.spawn(loc, Klasse, entity -> …)` und `world.dropItem(loc, stack, item -> …)`
  (Konfiguration **vor** dem ersten Tick) · Entity-Traits unter `io.papermc.paper.entity.`
  (`Leashable`, `Shearable` ⚠️ Paper-Import, nicht `org.bukkit.entity.Shearable`, `Bucketable`, …) ·
  `player.lookAt(target, LookAnchor.EYES)` · `entity.setRotation(Angle, Angle)` ·
  `getPing()`, `sendMultiBlockChange(...)`, `sendBlockUpdate(...)`, `openSign(...)`,
  `getClientBrandName()` · `Bukkit.createProfile(uuid, name)`
- **Schaden & Verbindung:** `event.damageSource` (`getCausingEntity`, `getDirectEntity`,
  **`getDamageType`**) · `io.papermc.paper.world.damagesource.CombatTracker` mit `CombatEntry` ·
  `BanListType` · `PlayerLoginConnection`

### Paper-exklusive Events (in Bukkit nicht vorhanden)

**Kampf & Spieler:** `PrePlayerAttackEntityEvent` (vor dem Schaden, kennt `willAttack()`) ·
`PlayerArmSwingEvent` · `PlayerShieldDisableEvent` · `PlayerStopUsingItemEvent` ·
`PlayerFailMoveEvent` · `PlayerInventorySlotChangeEvent` · `PlayerItemCooldownEvent` ·
`PlayerTrackEntityEvent` / `PlayerUntrackEntityEvent` · `AsyncPlayerSpawnLocationEvent` ·
`PlayerCustomClickEvent` (Dialog-Rückmeldungen)

**Entities:** `EntityMoveEvent` (Nicht-Spieler-Bewegung, spart eine Tick-Schleife) ·
`EntityKnockbackEvent` · `EntityEquipmentChangedEvent` · `EntityInsideBlockEvent` ·
`EntityDamageItemEvent` · `EntityIgniteEvent` · `EntityPushedByEntityAttackEvent`

**Welt & Verbindung:** `WorldGameRuleChangeEvent` · `WorldDifficultyChangeEvent` ·
`BlockBreakBlockEvent` · `BlockPreDispenseEvent` · `TargetHitEvent` ·
`ServerResourcesReloadedEvent` · `PlayerConnectionValidateLoginEvent` ·
`AsyncPlayerConnectionConfigureEvent`

---

## ⚠️ Verifizierte Verhaltensfallen

Keine Abweichungen von den 20 Vorgaben, sondern Verhalten von Paper und Vanilla, das die Regeln
nicht abdecken können – **jeder Punkt ist mit einem echten Bug bezahlt**. Alle genannten Aufrufe
sind die aktuelle, nicht-deprecatete API. Wer einen davon umsetzt, kommentiert ihn im Code.

- **`setAI(false)` niemals auf `EnderDragon`.** Das NoAI-Flag geht zum Client; der Drache ist ein
  mehrteiliges Modell, dessen Segmente clientseitig in `aiStep()` nachgeführt werden – das Modell
  bleibt optisch stehen. Stattdessen `setAware(false)`, `setInvulnerable(true)`, Schaden canceln.
- **`EnderDragon#getBossBar()` ist `null`** außerhalb einer End-Welt mit Drachenkampf.
- **`EnderDragon.Phase.HOVER`** verankert den Drachen; nach jedem Teleport neu setzen.
- **Freie Scoreboard-Zeilen brauchen `getScore(String)`** – `getScore(OfflinePlayer)` und
  `getScoreFor(Entity)` taugen dafür nicht. Der Entry-String ist der unsichtbare Schlüssel, die
  sichtbare Zeile entsteht über `Score#customName(Component)`.
- **Eigene Listener blockieren eigene Features.** `CreatureSpawnEvent` global zu canceln verhindert
  auch Plugin-Spawns – `SpawnReason.CUSTOM` ausnehmen.
- **Eine Sprengung ohne Quell-Entity kommt als `DamageCause.CUSTOM` an**, nicht als
  `BLOCK_EXPLOSION`: `CraftEventFactory#handleEntityDamageEvent` fragt zuerst
  `eventEntityDamager() ?: getDirectEntity()`; ist beides `null`, läuft sie in den Zweig ohne Entity
  und ohne Block, und dort wird `DamageTypes.EXPLOSION` **gar nicht geprüft**. Explosionen deshalb
  über den **`DamageType`** aus `event.damageSource` einordnen (`EXPLOSION` / `PLAYER_EXPLOSION`),
  nie über `event.cause` – sonst bleiben Air-Strike- und C4-Kills unzugeordnet.
- **`world.createExplosion` trifft doppelt so weit wie die Sprengkraft.**
  `ServerExplosion#hurtEntities` sucht die Opfer im Umkreis `radius * 2.0`; eigene Buchführung muss
  denselben Radius verwenden.
- **Vanilla verschluckt Folgetreffer.** Nach einem Treffer folgen 10 Ticks Unverwundbarkeit, in
  denen ein gleich starker Treffer **vor** jedem Schadensevent wegfällt. Wer Treffer zählen will
  (Geschützturm), wertet sie im `ProjectileHitEvent` aus: Der läuft davor, und ein Cancel
  überspringt den Treffer komplett (`Projectile#preHitTargetOrDeflectSelf`).
- **`Player#getVelocity` ist bei Spielern nicht die Laufbewegung.** Es liefert die serverseitige
  Delta-Bewegung, die aus Bewegungspaketen gar nicht gespeist wird (kein `setDeltaMovement` im
  `ServerGamePacketListenerImpl`). Für Vorhalt/Prognose die Position über zwei Takte selbst messen.

---

## 🧪 Abnahmekriterien

Eine Änderung ist fertig, wenn **alle** Punkte erfüllt sind:

1. `.\build.ps1` läuft fehlerfrei durch.
2. **Null Warnungen.** `allWarningsAsErrors = true` erzwingt das. Warnungen werden **behoben, nicht
   unterdrückt** – `@Suppress("DEPRECATION")` ist ein Regelverstoß.
3. Grep-Gegenprobe ist leer:
   - **Paper:** `§`, `ChatColor`, `BukkitRunnable`, `Bukkit.getScheduler()`, `SoundCategory`,
     `.spigot()`, `CommandExecutor`, `TabCompleter`, `getNearbyEntities(`, `setItemMeta(`,
     `AsyncPlayerChatEvent`, `PlayerLoginEvent`, `BanList.Type`, `setMaxHealth(`, `GameRule.`,
     `ItemStack(`, `setPlayerListName(`.
   - **Kotlin:** keine `*.java`-Datei, kein `!!`, kein `kotlinx.coroutines`, kein `@Suppress`, kein
     parameterloser Getter (`grep -rE 'fun get[A-Z][A-Za-z]*\(\)' src/main/kotlin`) – der gehört als
     Property geschrieben. Nachschlagemethoden **mit** Parameter (`getKills(uuid)`) und Methoden mit
     wechselndem Ergebnis (`getRandomArenaLocation()`) sind in Ordnung.

   > `setItemMeta` und der `ItemStack`-Konstruktor sind nicht deprecated, nur die schlechtere Wahl –
   > der Compiler fängt sie nicht, dafür ist diese Liste da.
4. Bei Verhaltensänderungen: `Server/logs/latest.log` auf `ERROR`/`Exception` prüfen.

**Bei Fehlverhalten nicht raten.** Erst Belege (Log, notfalls temporäre Diagnose-Ausgaben), dann
fixen, dann Diagnose entfernen. Bei Vanilla-Mechanik ist der Bytecode die Quelle der Wahrheit:

```bash
unzip -o -q Server/versions/26.2/paper-26.2.jar 'net/minecraft/world/level/ServerExplosion.class' -d /tmp/x
javap -p -c /tmp/x/net/minecraft/world/level/ServerExplosion.class | less
```

---

## 🛠️ Build

```powershell
.\build.ps1
```

**MANDATORY: der einzige zulässige Build-Aufruf.** Kein direkter `gradlew.bat`-Aufruf – weder
`build` noch `deployPlugin` noch `jar`. Das Skript baut `OneShotOneKill_26.2.jar` und kopiert sie
nach `Server/plugins/`, wobei es JARs früherer Versionen dort wegräumt. Gebraucht wird nur ein
JDK 25; Gradle und der Kotlin-Compiler kommen über den Wrapper.

**Die paper-api-Version kommt ausschließlich aus `Server/server.jar`** (`META-INF/libraries/…`) –
kein Rückfallwert, kein Pin, keine zweite Wahrheit, die von der Server-JAR abweichen könnte. Fehlt
die JAR, bricht der Build mit einem Hinweis ab. Aus der erkannten Version leiten sich die
compileOnly-Abhängigkeit, `api-version`, `name` und `version` in `paper-plugin.yml` sowie der
JAR-Name ab. **Nirgends steht eine Build-Nummer von Hand**, und ein Server-Update ist damit: neue
`server.jar` hinlegen, `.\build.ps1`.

> **Im Cache liegt nur noch die passende Fassung.** Der Deploy löscht alle übrigen
> `paper-api`-Versionen unter `~/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/`.
> Für das Pflicht-Vorgehen heißt das: Der `javap`-Aufruf trifft immer die richtige JAR – aber eine
> **Sources-JAR gibt es nur, wenn sie für genau diese Version veröffentlicht wurde**. Javadoc aus
> einer Nachbarversion steht nicht mehr bereit; Signatur, Nullability und Deprecation liefert
> `javap -v` ohnehin aus dem Bytecode.

> Der **Configuration Cache muss aus bleiben** (Gradle bewirbt ihn bei jedem Lauf): Die Erkennung
> liest die Server-JAR zur Konfigurationszeit, mit Cache friert der Wert beim ersten Lauf ein.

> Ein laufender Server lädt die JAR **nicht** neu – Neustart nötig.
