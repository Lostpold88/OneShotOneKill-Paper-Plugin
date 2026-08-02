# Projekt-Regeln für Minecraft Plugins (OneShotOneKill)

## 🔴 Grundsatz (gilt ausnahmslos für jede Änderung)

Dieses Projekt ist ein **100% natives Paper Plugin** (`paper-plugin.yml`, `api-version: '26.2'`)
für **Paper 26.2+ / Minecraft 26.2**.

**MANDATORY:** Jede Erweiterung, jedes Refactoring, jeder Befehl, Listener, jede GUI und jede
Korrektur **MUSS in 100% nativer Paper API und Paper-Syntax** verfasst werden. Wann immer eine
Paper-Methode oder ein Paper-Feature existiert, **MUSS** diese gegenüber älteren Bukkit-/Spigot-
Lösungen eingesetzt werden. Bukkit-Klassen sind nur dort erlaubt, wo Paper **keine** eigene
Schnittstelle anbietet (z. B. `org.bukkit.Material`, `org.bukkit.Location`).

**Im Zweifel gilt: die modernere, Paper-eigene, Component-basierte, asynchrone Variante.**

> Die Paper-API umfasst in 26.2 **641 eigene Klassen** unter `io.papermc.paper.*` in rund 85
> Paketen. Die Landkarte weiter unten führt sie nach Einsatzgebiet auf – wer etwas sucht, schaut
> dort nach, statt auf eine Bukkit-Lösung zurückzufallen.

---

## 📌 Die 20 verbindlichen API-Vorgaben

1. **Paper Plugin Bootstrap & Configuration**: Ausschließlich via `paper-plugin.yml` (0% alte `plugin.yml`).
2. **Paper Lifecycle Commands API & Brigadier**: Registrierung aller Befehle dynamisch über `this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)` und Paper's `BasicCommand` / `CommandSourceStack` Interface (inkl. `canUse` und `suggest` für Instant-Client-Autovervollständigung). **0% `CommandExecutor`, 0% `TabCompleter`.**
3. **Paper Entity & Region Schedulers**: Thread-safe Aufgabenverwaltung ausschließlich über `player.getScheduler()`, `Bukkit.getGlobalRegionScheduler()`, `Bukkit.getRegionScheduler()` und `Bukkit.getAsyncScheduler()` (0% veraltete `Bukkit.getScheduler()` / `BukkitRunnable` Aufrufe).
4. **PersistentDataContainer (PDC)**: `NamespacedKey` zur Item- und Entitäts-Markierung (0% String-Name-Vergleiche, 0% Bukkit Metadata API, 0% NBT-Reflection).
5. **Native Paper Scoreboard API**: `Criteria.DUMMY` statt String-Kriterien, Zeilen als Component über `Score#customName(Component)`, Zahlen ausblenden via `Objective#numberFormat(NumberFormat.blank())` und `Score#numberFormat(NumberFormat.blank())` (0% NMS-Reflection, 0% veraltete Score-Tricks).
6. **Asynchrone Teleportation**: **Spieler**-Teleports ausschließlich via `player.teleportAsync(location)` zur Vermeidung von Main-Thread Lagspikes. Ergebnisse über `.thenAccept(...)` verarbeiten. Mehrere Teleports abwarten via `CompletableFuture.allOf(...)`. Feinsteuerung über `TeleportFlag.Relative` (`X`, `Y`, `Z`, `YAW`, `PITCH`, `VELOCITY_*`) und `TeleportFlag.EntityState` (`RETAIN_PASSENGERS`, `RETAIN_VEHICLE`, `RETAIN_OPEN_INVENTORY`).
   **Geltungsbereich:** Die Regel gilt Spielern. Wird eine Entity **jeden Tick** in derselben Welt umpositioniert (Zielchunk garantiert geladen, Aufruf bereits auf dem Main-Thread), ist das synchrone `entity.teleport(loc)` die richtige Wahl – `teleportAsync` mehrfach anzustoßen, bevor der vorherige Teleport aufgelöst ist, blockiert die Bewegung. Werden dabei Flags gebraucht, liefert sie Papers Überladung `teleport(loc, TeleportFlag…)`. Beides ist regelkonform, keine Ausnahme.
7. **Paper Spatial Entity Index Engine**: Räumliche Abfragen ausschließlich via `loc.getNearbyPlayers(radius)`, `location.getNearbyEntitiesByType(...)` und `world.getEntitiesByClass(...)` (0% `getNearbyEntities` + `instanceof`, 0% Schleifen über alle Server-Spieler zum Filtern nach Distanz).
8. **Paper Plugin Chunk Tickets**: Speicher- und Chunk-Verwaltung via `world.addPluginChunkTicket` / `removePluginChunkTicket`.
9. **Kyori Adventure & MiniMessage Component API**: Alle Chatnachrichten, Titles, Tablisten, Kicks & Emojis ausschließlich via Kyori `Component`, `MiniMessage.miniMessage().deserialize(...)` und `Audience` (0% `ChatColor`, 0% legacy `§` Paragraphen-Zeichen in Java-Strings, 0% `LegacyComponentSerializer`).
10. **Kyori Component ItemMeta API**: Item-Namen & Lores ausschließlich via `ItemMeta#displayName(Component)` und `ItemMeta#lore(List<Component>)` (0% legacy String ItemMeta). Meta-Änderungen **immer** über `ItemStack#editMeta(...)`, nie über `getItemMeta()` → ändern → `setItemMeta()`.
11. **Paper Custom Inventory & GUI Titles**: GUIs & Menüs ausschließlich via `Bukkit.createInventory(owner, size, Component)` mit Kyori `Component` Titeln; Abgleich über `event.getView().title()`.
12. **Kyori ActionBars, Titles & Sound API**: Benachrichtigungen & Audio-Playback ausschließlich via `Audience#sendActionBar(Component)`, `Audience#showTitle(...)` und `Audience#playSound(net.kyori.adventure.sound.Sound)` mit `Sound.Source` (0% `SoundCategory`, 0% `playSound(Location, org.bukkit.Sound, ...)`).
13. **Paper Event Listening & Cancellations**: Event-Handling strikt mit Paper Event-Methoden und Kyori Components. Wo ein Paper-exklusives Event existiert, hat es Vorrang vor dem Bukkit-Pendant (z. B. `AsyncChatEvent` statt `AsyncPlayerChatEvent`, `PrePlayerAttackEntityEvent`, `EntityMoveEvent`, `EntityKnockbackEvent`, `WorldGameRuleChangeEvent`).
14. **Paper World & GameRule Engine**: Weltenverwaltung über die Paper World API und die moderne `org.bukkit.GameRules` Registry (0% `org.bukkit.GameRule`, 0% Legacy-Ticks).
15. **0% Bukkit/Spigot Legacy Code**: Kein einziger veralteter Bukkit/Spigot Call, wenn eine native Paper API Schnittstelle existiert. Insbesondere **0% `entity.spigot()`**.
16. **Paper Connection & Login API**: Login-, Bann- und Verbindungslogik ausschließlich über `io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent` und `io.papermc.paper.connection.PlayerLoginConnection` (**0% `org.bukkit.event.player.PlayerLoginEvent` – deprecated for removal**). Identität immer über `getAuthenticatedProfile()`, nie `getUnsafeProfile()`.
17. **Paper Ban API**: Bannlisten ausschließlich über `io.papermc.paper.ban.BanListType` (`PROFILE` / `IP`) und die **typisierten** Methoden `pardon(PlayerProfile)`, `isBanned(InetAddress)`, `getEntries()`, `BanEntry#getBanTarget()`. **0% String-Überladungen** von `BanList` und **0% `BanList.Type`** – beide deprecated.
18. **Paper DataComponents API**: Item-Daten, für die ein `DataComponentType` existiert, werden über `ItemStack#setData/getData/hasData/unsetData` mit `io.papermc.paper.datacomponent.DataComponentTypes` gesetzt. Gilt für **neuen** Code; Bestandscode siehe Bestandsschutz unten.
19. **Paper Registry API**: Dynamische Nachschlagevorgänge über `RegistryAccess.registryAccess().getRegistry(RegistryKey.…)` statt statischer Enum-Felder, wo der Typ über ein Datapack erweiterbar ist (`ENCHANTMENT`, `BIOME`, `DAMAGE_TYPE`, `ATTRIBUTE`, `DIALOG`, …).
20. **Paper Attribute API**: Attributwerte ausschließlich über `entity.getAttribute(Attribute.…)` und `AttributeInstance` (**0% `setMaxHealth(double)`** – deprecated).

### 🛡️ Bestandsschutz zu Regel 18

Regel 18 gilt für **neuen** Code und für Stellen, die ohnehin angefasst werden. Der bestehende
ItemMeta-basierte Code ist **nicht** automatisch regelwidrig und darf **nicht** ohne gesonderten
Auftrag umgebaut werden – `ItemMeta#displayName(Component)` und `#lore(List<Component>)` sind in
26.2 nicht deprecated. Eine flächendeckende Migration ist eine eigene, bewusst zu treffende
Entscheidung, kein Nebeneffekt einer Fehlerbehebung.

---

## ✅ Pflicht-Vorgehen vor jeder Änderung

**Nicht raten, nachsehen.** Die Paper-API ändert sich zwischen Versionen; Methoden aus dem
Gedächtnis sind regelmäßig falsch. Vor der Verwendung einer API **immer** gegen die tatsächliche
JAR prüfen:

```bash
J=Server/libraries/io/papermc/paper/paper-api/26.2.build.87-stable/paper-api-26.2.build.87-stable.jar
javap -cp $J org.bukkit.entity.Player | grep -i "<methode>"
```

Prüfen, ob eine Methode **deprecated** ist (jede Warnung ist ein Regelverstoß):

```bash
javap -v -cp $J org.bukkit.BanList | grep -B12 "Deprecated: true" | grep "public abstract"
```

> ⚠️ **Fallstrick bei dieser Prüfung:** `grep`/`awk` ordnen `Deprecated: true` leicht der falschen
> Methode zu, weil die Annotation im `javap -v`-Ausgabestrom **nach** der Signatur steht und
> Nachbarmethoden dazwischenfunken. Im Zweifel die Methode einzeln ansehen
> (`javap -v … | grep -A12 "public boolean setItemMeta"`) und prüfen, ob `Deprecated: true`
> **innerhalb** ihres Blocks steht. Ein falsch gemeldetes „deprecated" hat hier schon zu einer
> Fehldiagnose geführt.

Paket-Übersicht der Paper-API verschaffen:

```bash
unzip -l $J | grep -oE "io/papermc/paper/[a-z0-9/]+/" | sort | uniq -c | sort -rn
```

Adventure-Klassen liegen in `Server/libraries/net/kyori/adventure-api/...` – in Paper 26.2 ist das
**Adventure 5.2.0**, nicht mehr 4.x.

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
| `getItemMeta()` → ändern → `setItemMeta()` | `stack.editMeta(meta -> …)` bzw. `editMeta(SkullMeta.class, …)` |
| `playSound(Location, org.bukkit.Sound, SoundCategory, …)` | `Audience#playSound(Sound.sound(…, Sound.Source.…))` |
| `getNearbyEntities(...)` + `instanceof Player` | `loc.getNearbyPlayers(radius)` |
| `org.bukkit.GameRule` | `org.bukkit.GameRules` |
| `world.setGameRuleValue(String, String)` | `world.setGameRule(GameRules.…, wert)` |
| `entity.spigot().respawn()` | GameRule `IMMEDIATE_RESPAWN` |
| `registerNewObjective(name, "dummy", …)` | `registerNewObjective(name, Criteria.DUMMY, Component)` |
| `getType().name().contains("…")` | `Tag.…isTagged(material)` oder direkter `Material`-Vergleich |
| `world.dropItem(loc, stack)` + nachträgliche Setter | `world.dropItem(loc, stack, item -> { … })` |
| `org.bukkit.event.player.PlayerLoginEvent` | `PlayerConnectionValidateLoginEvent` + `PlayerLoginConnection` |
| `AsyncPlayerChatEvent` | `io.papermc.paper.event.player.AsyncChatEvent` |
| `BanList.Type` / `banList.pardon(String)` | `BanListType.PROFILE` / `pardon(PlayerProfile)` |
| `entity.setMaxHealth(double)` | `entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(…)` |
| `player.setPlayerListName(String)` | `player.playerListName(Component)` |
| `player.setDisplayName(String)` | `player.displayName(Component)` |
| `new ItemStack(Material, n)` | `ItemStack.of(Material, n)` |
| `Bukkit.createInventory(holder, size, String)` | `Bukkit.createInventory(holder, size, Component)` |

---

## 🗺️ Paper-API-Landkarte

Alles hier ist gegen `paper-api-26.2.build.87-stable.jar` verifiziert. Reihenfolge: Einsatzgebiet →
Einstiegspunkt → **Vorzug** gegenüber der Bukkit-Lösung.

### 1. Plugin-Start & Lebenszyklus

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **PluginBootstrap** | `io.papermc.paper.plugin.bootstrap.PluginBootstrap`, `BootstrapContext` | Läuft **vor** dem Server-Start – der einzige Zeitpunkt, an dem Registries noch erweiterbar sind. `JavaPlugin#onLoad` ist dafür zu spät. |
| **PluginLoader** | `io.papermc.paper.plugin.loader.PluginLoader`, `library.impl.MavenLibraryResolver` | Externe Bibliotheken zur Laufzeit nachladen, statt sie in die JAR zu shaden – kleinere Artefakte, keine Klassenkonflikte. |
| **Lifecycle-Events** | `LifecycleEvents.COMMANDS`, `.TAGS`, `.DATAPACK_DISCOVERY` | Registrierung an dem Punkt, an dem der Server sie tatsächlich einliest, und **reloadfest** – anders als einmalige Registrierung in `onEnable`. |
| **PluginMeta** | `io.papermc.paper.plugin.configuration.PluginMeta` | Typisierter Zugriff auf die eigene `paper-plugin.yml` statt String-Gefummel. |

### 2. Befehle (Brigadier)

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **BasicCommand** | `implements BasicCommand` mit `execute`, `canUse`, `suggest` | Der schlanke Weg für flache Befehle. `canUse` blendet den Befehl clientseitig komplett aus. |
| **Volles Brigadier** | `Commands.literal("x").then(Commands.argument("y", …))` | Echte Befehls*bäume* mit Client-Validierung: Falsche Eingaben werden **rot im Chat** markiert, bevor sie abgeschickt werden. |
| **ArgumentTypes** | `ArgumentTypes.player()`, `.entities()`, `.itemStack()`, `.blockPosition()`, `.finePosition()`, `.rotation()`, `.angle()`, `.blockState()`, `.itemPredicate()`, `.namedColor()`, `.hexColor()`, `.component()`, `.style()`, `.playerProfiles()`, `.axes()`, `.columnBlockPosition()`, `.blockInWorldPredicate()` | Vanilla-Parsing **und** Vanilla-Autovervollständigung geschenkt: Selektoren wie `@a[distance=..5]` funktionieren ohne eine Zeile eigenen Code. |
| **CommandSourceStack** | `getSender()`, `getExecutor()`, `getLocation()`, `withLocation()`, `withExecutor()` | Unterscheidet **Absender** und **Ausführenden** – nötig für `/execute as … run …`. `CommandSender` kann das nicht. |
| **Restricted** | `Commands.restricted(predicate)` | Befehle an OP-Level/Bedingungen knüpfen, ohne eigene Prüfung im Body. |
| **MessageComponentSerializer** | `io.papermc.paper.command.brigadier.MessageComponentSerializer` | Übersetzt zwischen Brigadier-`Message` und Adventure-`Component` – Fehlertexte in MiniMessage. |

### 3. Scheduler (4 Varianten, alle in `io.papermc.paper.threadedregions.scheduler`)

| Scheduler | Einstieg | Wofür / Vorzug |
| :--- | :--- | :--- |
| **GlobalRegionScheduler** | `Bukkit.getGlobalRegionScheduler()` | Serverweiter Zustand (Wetter, Zeit, Rundenlogik). `run`, `runDelayed`, `runAtFixedRate`. |
| **RegionScheduler** | `Bukkit.getRegionScheduler()` | An **Ort** gebunden: `run(plugin, location, task)`. Läuft im richtigen Regions-Thread – unter Folia der einzig korrekte Weg. |
| **EntityScheduler** | `entity.getScheduler()` | Folgt der Entity **über Regionsgrenzen und Teleports hinweg**. Nimmt einen `retired`-Runnable für den Fall, dass die Entity verschwindet – kein Task-Leak. |
| **AsyncScheduler** | `Bukkit.getAsyncScheduler()` | Echte Nebenläufigkeit mit `TimeUnit` statt Ticks – für I/O und Rechenarbeit ohne Server-API-Zugriff. |
| **ScheduledTask** | Rückgabewert aller drei | `cancel()`, `getExecutionState()` – sauberes Beenden statt Task-IDs zu merken. |

### 4. Text, Chat & Dialoge (Adventure 5.2.0)

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **Component / MiniMessage** | `MiniMessage.miniMessage().deserialize("<red>…")` | Farben, Hover, Klick, Fonts, Übersetzungen – alles, was `§`-Codes nie konnten. |
| **Audience** | `Player`, `World`, `Server` sind alle `Audience` | Ein Interface für Einzelspieler, Welt und Server: `sendMessage`, `sendActionBar`, `showTitle`, `playSound`, `showDialog`. |
| **Title** | `Title.title(main, sub, Title.Times.times(…))` | Ein- und Ausblendzeiten typisiert statt drei lose `int`. |
| **Dialog API** | `Audience#showDialog(DialogLike)`, `io.papermc.paper.registry.data.dialog.*` | **Echte serverseitige Dialogfenster** statt Chest-GUI-Missbrauch: `NoticeType`, `ConfirmationType`, `MultiActionType`, `DialogListType`, `ServerLinksType`; Eingaben über `TextDialogInput`, `BooleanDialogInput`, `NumberRangeDialogInput`, `SingleOptionDialogInput`; Knöpfe via `ActionButton`. |
| **AsyncChatEvent** | `io.papermc.paper.event.player.AsyncChatEvent` | Component-basiert und asynchron. `AsyncPlayerChatEvent` ist deprecated. |
| **ChatRenderer** | `io.papermc.paper.chat.ChatRenderer` | Chatformat **pro Empfänger** rendern – etwa Namen nur für Teammitglieder einfärben. |
| **PaperComponents** | `resolveWithContext(...)`, `plainTextSerializer()`, `gsonSerializer()` | Löst Selektoren und Übersetzungen in Components auf; serverseitige Serializer ohne eigene Adventure-Abhängigkeit. |

### 5. Items

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **DataComponents** | `stack.setData(DataComponentTypes.LORE, ItemLore.lore(list))`, `getData`, `hasData`, `unsetData`, `getDataOrDefault`, `getDataTypes` | Direkter Zugriff auf das Vanilla-Komponentenmodell von 1.21.5+. Typisiert, ohne ItemMeta-Umweg. Verfügbar u. a.: `CUSTOM_NAME`, `ITEM_NAME`, `LORE`, `ENCHANTMENTS`, `MAX_STACK_SIZE`, `MAX_DAMAGE`, `DAMAGE`, `CUSTOM_MODEL_DATA`, `TOOLTIP_DISPLAY`, `ENCHANTMENT_GLINT_OVERRIDE`, `CONSUMABLE`, `DAMAGE_RESISTANT`, `ENCHANTABLE` (93 Klassen unter `datacomponent/item`). |
| **editMeta** | `stack.editMeta(meta -> …)`, `editMeta(SkullMeta.class, meta -> …)` | Ein Durchgang statt Kopie-ändern-Zurückschreiben; die typisierte Variante spart das `instanceof`. |
| **ItemStack.of** | `ItemStack.of(Material, amount)` | Statische Factory statt Konstruktor – konsistent mit dem Rest der modernen API. |
| **PDC** | `stack.getPersistentDataContainer()` | Eigene Marker, die Vanilla nicht anfasst – die einzig verlässliche Item-Erkennung. |
| **PersistentDataContainerView** | `io.papermc.paper.persistence.PersistentDataContainerView` | **Lesender** Zugriff ohne die Meta zu kopieren – deutlich billiger in heißen Schleifen. |
| **ItemRarity / TooltipContext** | `io.papermc.paper.inventory.ItemRarity`, `io.papermc.paper.inventory.tooltip.TooltipContext` | Seltenheitsfarbe und Tooltip-Kontext (Advanced/Creative) auslesen. |

### 6. Inventare & GUIs

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **createInventory** | `Bukkit.createInventory(holder, size, Component)` | Component-Titel; die `String`-Überladungen sind deprecated. |
| **MenuType** | `MenuType.GENERIC_9X6`, `.builder()`, `InventoryViewBuilder`, `LocationInventoryViewBuilder` | Typisierte Views inkl. **Amboss, Braustand, Ofen, Werkbank** – Menüs, die `createInventory` gar nicht erzeugen kann. Ortsgebundene Varianten binden das Menü an einen Block. |
| **View-Titel** | `event.getView().title()` | Component-Vergleich statt String-Vergleich – immun gegen Formatierungsverluste. |

### 7. Scoreboard

`Criteria.DUMMY` · `Score#customName(Component)` · `Objective#numberFormat(NumberFormat.blank())` ·
`Score#numberFormat(...)` · `io.papermc.paper.scoreboard.numbers.NumberFormat` (auch
`fixed(Component)` und `styled(Style)`).

**Vorzug:** Beliebige Component-Zeilen ohne Team-Prefix-Tricks und ohne die 16-Zeichen-Grenze;
Zahlen lassen sich ausblenden oder durch eigene Components ersetzen – beides war früher nur per
NMS möglich.

### 8. Registry & Tags

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **RegistryAccess** | `RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)` | Findet auch **Datapack-Einträge**, die als statisches Enum-Feld gar nicht existieren. |
| **RegistryKey** | 43 Schlüssel, u. a. `BLOCK`, `ITEM`, `ENTITY_TYPE`, `ATTRIBUTE`, `ENCHANTMENT`, `BIOME`, `DAMAGE_TYPE`, `GAME_RULE`, `MOB_EFFECT`, `SOUND_EVENT`, `PARTICLE_TYPE`, `POTION`, `STRUCTURE`, `DIALOG`, `MENU`, `DATA_COMPONENT_TYPE`, `PAINTING_VARIANT`, `JUKEBOX_SONG`, `INSTRUMENT`, diverse `*_VARIANT` | Typsicherer Zugriff; `TypedKey` erlaubt Referenzen auf noch nicht geladene Einträge. |
| **RegistryEvents** | `io.papermc.paper.registry.event.*` | Registries **erweitern oder umschreiben**, bevor sie eingefroren werden (nur im Bootstrap). |
| **Tags** | `io.papermc.paper.tag.EntityTags`, `TagEntry`, `PreFlattenTagRegistrar`, `PostFlattenTagRegistrar` | Eigene Tags registrieren statt Material-Listen im Code zu pflegen. |
| **RegistrySet** | `io.papermc.paper.registry.set.RegistrySet` | Mengen von Registry-Einträgen typisiert übergeben (z. B. für DataComponents). |

### 9. Paper-exklusive Events

Diese Events gibt es in Bukkit **nicht**. Wo einer passt, erspart er eine selbstgebaute
Tick-Schleife oder Zustandsverwaltung.

**Kampf & Spieler:** `PrePlayerAttackEntityEvent` (greift **vor** dem Schaden, kennt
`willAttack()`) · `PlayerArmSwingEvent` · `PlayerShieldDisableEvent` · `PlayerStopUsingItemEvent` ·
`PlayerFailMoveEvent` (warum eine Bewegung abgelehnt wurde) · `PlayerInventorySlotChangeEvent` ·
`PlayerSwapWithEquipmentSlotEvent` · `PlayerItemCooldownEvent` / `PlayerItemGroupCooldownEvent` ·
`PlayerPickItemEvent` / `PlayerPickBlockEvent` / `PlayerPickEntityEvent` ·
`PlayerTrackEntityEvent` / `PlayerUntrackEntityEvent` (Sichtbarkeitsbereich) ·
`AsyncPlayerSpawnLocationEvent` · `PlayerClientLoadedWorldEvent` · `PlayerCustomClickEvent`
(Dialog-Rückmeldungen) · `PlayerServerFullCheckEvent` · `PlayerDeepSleepEvent` ·
`PlayerNameEntityEvent` · `PlayerPurchaseEvent` / `PlayerTradeEvent`

**Entities:** `EntityMoveEvent` (**Nicht-Spieler-Bewegung** – ohne diesen Event braucht es einen
Tick-Task) · `EntityKnockbackEvent` (Rückstoß abfangen/ändern, u. a. bei Explosionen) ·
`EntityEquipmentChangedEvent` · `EntityInsideBlockEvent` · `EntityDamageItemEvent` ·
`EntityEffectTickEvent` · `EntityIgniteEvent` · `EntityPushedByEntityAttackEvent` ·
`EntityAttemptSmashAttackEvent` / `EntityAttemptSpinAttackEvent` · `EntityLungeEvent` ·
`EntityPortalReadyEvent` · `EntityToggleSitEvent` · `TameableDeathMessageEvent` ·
`WardenAngerChangeEvent` · `ElderGuardianAppearanceEvent`

**Welt & Blöcke:** `WorldGameRuleChangeEvent` · `WorldDifficultyChangeEvent` ·
`BlockBreakBlockEvent` · `BlockBreakProgressUpdateEvent` · `BlockPreDispenseEvent` /
`BlockFailedDispenseEvent` · `BlockLockCheckEvent` · `BeaconActivatedEvent` /
`BeaconDeactivatedEvent` · `BellRingEvent` / `BellRevealRaiderEvent` · `TargetHitEvent` ·
`VaultChangeStateEvent` · `DragonEggFormEvent` · `StructuresLocateEvent` ·
`ServerResourcesReloadedEvent` · `WhitelistStateUpdateEvent` · `world.border.*`

**Verbindung:** `PlayerConnectionValidateLoginEvent` · `AsyncPlayerConnectionConfigureEvent` ·
`PlayerConnectionInitialConfigureEvent` · `PlayerConnectionReconfigureEvent` ·
`PlayerCodeOfConductSendEvent`

### 10. Welt, Position & Raytracing

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **Position** | `io.papermc.paper.math.Position`, `BlockPosition`, `FinePosition` | **Unveränderlich** und ohne Welt-Referenz – anders als `Location` sicher als Map-Schlüssel und über Threads hinweg. |
| **Rotation / Angle** | `io.papermc.paper.math.Rotation`, `Rotations`, `Angle` | Typisierte Winkel statt roher `float`-Paare. |
| **RayTrace-Builder** | `world.rayTrace(builder -> builder.start(…).direction(…).maxDistance(…).targets(RayTraceTarget.…).blockCollisionMode(…).entityFilter(…))` | Ein lesbarer Aufruf statt sieben Positionsparameter; `BlockCollisionMode` unterscheidet Kollisions- von Umriss-Geometrie. |
| **Chunk-Tickets** | `world.addPluginChunkTicket(x, z, plugin)` / `remove…` | Hält Chunks gezielt geladen und gibt sie wieder frei – kein „Chunk entladen, Entity weg". |
| **GameRules** | `world.setGameRule(GameRules.…, wert)` | Typisierte Registry; die `GameRule`-Konstanten sind `@Deprecated(forRemoval, since 1.21.11)`. |
| **FeatureFlags** | `io.papermc.paper.world.flag.FeatureDependant` | Prüfen, ob ein Inhalt im aktuellen Weltzustand überhaupt verfügbar ist. |
| **Datapacks** | `Bukkit.getDatapackManager()`, `Datapack`, `DiscoveredDatapack`, `DatapackSource` | Datapacks zur Laufzeit auflisten und schalten. |

### 11. Entities & Spieler

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **teleportAsync + Flags** | `teleportAsync(loc, cause, TeleportFlag…)` | Lädt Zielchunks asynchron; Flags erhalten z. B. Blickrichtung, Passagiere oder Fahrzeug. |
| **Spawn mit Consumer** | `world.spawn(loc, Klasse.class, entity -> …)` | Entity wird **vor** dem ersten Tick konfiguriert – kein Flackern durch nachträgliche Setter. |
| **dropItem mit Consumer** | `world.dropItem(loc, stack, item -> …)` | Dasselbe für Drops: Aufhebeverzögerung/Besitzer sofort gesetzt. |
| **Entity-Traits** | `io.papermc.paper.entity.` + `Leashable`, `Shearable`, `Bucketable`, `CollarColorable`, `Frictional`, `SchoolableFish` | Fähigkeiten typisiert abfragen statt lange `instanceof`-Ketten. ⚠️ `Shearable` gibt es **zweimal** – `org.bukkit.entity.Shearable` ist der ältere Zwilling; hier gilt der Paper-Import. |
| **LookAnchor** | `player.lookAt(target, LookAnchor.EYES)` | Blick serverseitig ausrichten – Augen oder Füße als Bezug. |
| **Spatial Index** | `loc.getNearbyPlayers(r)`, `getNearbyEntitiesByType(...)`, `world.getEntitiesByClass(...)` | Nutzt den räumlichen Index; `getNearbyEntities` + `instanceof` läuft dagegen über alle Kandidaten. |
| **Spieler-Extras** | `getPing()`, `sendMultiBlockChange(Map<Position, BlockData>)`, `sendBlockUpdate(loc, TileState)`, `openSign(sign)`, `sendHealthUpdate()`, `getClientBrandName()`, `allowsListing()` | Clientseitige Illusionen ohne echte Weltänderung – der günstigste Weg für spielerspezifische Anzeigen. |
| **PlayerProfile** | `Bukkit.createProfile(uuid, name)`, `PlayerProfile#getTextures()` | Skins und Profile ohne Mojang-API-Gefummel. |

### 12. Schaden, Bann & Verbindung

| API | Einstieg | Vorzug |
| :--- | :--- | :--- |
| **DamageSource** | `event.getDamageSource()`, `getCausingEntity()`, `getDirectEntity()`, `getDamageType()` | Löst Verursacherketten (Schütze hinter dem Pfeil) selbst auf – ersetzt manuelles `Arrow#getShooter()`. |
| **CombatTracker** | `io.papermc.paper.world.damagesource.CombatTracker`, `CombatEntry`, `FallLocationType` | Vollständige Kampfhistorie inkl. Sturzherkunft – für Todesmeldungen und Kill-Zuordnung. |
| **BanListType** | `Bukkit.getBanList(BanListType.PROFILE / .IP)` | Typisiert; die String-Überladungen von `BanList` sind deprecated. |
| **PlayerLoginConnection** | `getAuthenticatedProfile()`, `getUnsafeProfile()`, `getClientAddress()` | Trennt bestätigte von behaupteter Identität – bei `online-mode=true` sicherheitsrelevant. |

---

## ⚠️ Verifizierte Verhaltensfallen

**Es gibt keine Ausnahmen vom Paper-Grundsatz.** Jede Zeile im Projekt benutzt die Paper-API.

Was hier steht, sind **keine** Abweichungen von den 20 Vorgaben, sondern Verhalten von Paper und
Vanilla, das die Regeln nicht abdecken können – jeder Punkt ist mit einem echten Bug im Projekt
bezahlt. Alle genannten Aufrufe (`setAware`, `setInvulnerable`, `setPhase`, `getScore`,
`createExplosion`) **sind** die aktuelle, nicht-deprecatete API; es gibt zu keinem davon eine
Paper-Alternative, die man stattdessen nehmen könnte. Wer einen dieser Punkte im Code umsetzt,
kommentiert ihn dort.

- **`setAI(false)` niemals auf `EnderDragon`.** Das NoAI-Flag wird zum Client synchronisiert; der
  Drache ist ein mehrteiliges Modell, dessen Segmente clientseitig in `aiStep()` nachgeführt werden.
  Mit NoAI bleibt das Modell optisch stehen, obwohl die Entity serverseitig korrekt wandert.
  Aggression stattdessen über `setAware(false)`, `setInvulnerable(true)` und Cancelling des Schadens.
- **`EnderDragon#getBossBar()` ist `null`** außerhalb einer End-Welt mit Drachenkampf. Immer prüfen.
- **`EnderDragon.Phase.HOVER`** verankert den Drachen an einem festen Schwebepunkt. Nach einem
  Teleport muss die Phase neu gesetzt werden, sonst fliegt er zurück.
- **Scoreboard-Zeilen brauchen den String-Entry.** `Objective` bietet zwar auch
  `getScore(OfflinePlayer)` und `getScoreFor(Entity)` – für freie Textzeilen taugt aber nur
  `getScore(String)`. Das ist die aktuelle API, kein Legacy-Rückfall: Der Entry-String ist ein
  unsichtbarer Schlüssel, die sichtbare Zeile entsteht über `Score#customName(Component)`.
- **Eigene Listener können eigene Features blockieren.** Beispiel: `CreatureSpawnEvent` global zu
  canceln verhindert auch plugin-eigene Spawns – `SpawnReason.CUSTOM` ausnehmen.
- **`world.createExplosion` trifft doppelt so weit wie die Sprengkraft.**
  `ServerExplosion#hurtEntities` sucht die Opfer im Umkreis `radius * 2.0`. Wer eigene
  Sprengungs-Buchführung schreibt, muss denselben Radius verwenden – sonst fehlt die äußere Hälfte
  der Druckwelle.

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
   `SoundCategory`, `.spigot()`, `CommandExecutor`, `TabCompleter`, `getNearbyEntities(`,
   `setItemMeta(`, `AsyncPlayerChatEvent`, `PlayerLoginEvent`, `BanList.Type`, `setMaxHealth(`,
   `GameRule.`, `new ItemStack(`, `setPlayerListName(`.

   > Die letzten sieben Muster fängt der Deprecation-Lint **nicht** zuverlässig ab –
   > `setItemMeta` und `new ItemStack` sind gar nicht deprecated, sondern nur die schlechtere
   > Wahl. Genau dafür ist diese Liste da.
4. Bei Verhaltensänderungen: `Server/logs/latest.log` nach dem Start auf `ERROR`/`Exception` prüfen.

**Bei Fehlverhalten im Spiel nicht raten.** Erst Belege sammeln (Log lesen, notfalls temporäre
Diagnose-Ausgaben einbauen), dann fixen, danach die Diagnose wieder entfernen. Zwei falsche
Vermutungen kosten mehr Zeit als eine Messung. Bei Vanilla-Mechanik ist der Bytecode die Quelle der
Wahrheit:

```bash
unzip -o -q Server/versions/26.2/paper-26.2.jar 'net/minecraft/world/level/ServerExplosion.class' -d /tmp/x
javap -p -c /tmp/x/net/minecraft/world/level/ServerExplosion.class | less
```

---

## 🛠️ Build- & Verpackungsprozess

Nach jeder Änderung wird der Code **direkt kompiliert (`javac`)**, als `.jar` verpackt und im
Server-Plugins-Ordner platziert:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Das Skript kompiliert mit `javac` (UTF-8) gegen `paper-api-26.2.build.87-stable.jar`, kopiert
`paper-plugin.yml`, `Standard.zip` und `DustPvP.zip`, baut `OneShotOneKill_26.2.jar` und kopiert
sie nach `Server/plugins/`. Alle Pfade sind relativ zu `$PSScriptRoot`; das Repository darf
verschoben oder umbenannt werden. Bricht `javac` oder `jar` ab, schlägt der Build fehl.

> Ein laufender Server lädt die JAR **nicht** neu – Änderungen sind erst nach einem Neustart aktiv.
