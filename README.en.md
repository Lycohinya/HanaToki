[繁體中文](README.md) | **English**

# HanaToki

A Folia-first micro-dungeon / instance engine for Paper plugins. It handles the generic
lifecycle every instanced encounter needs — slot allocation, session/party state, a stage
(state machine) graph, world provisioning and rollback, spawning and tracking mobs, roll
checks, and rewards — so a game plugin only has to describe *its own* content: what the
stages are called, what happens in each one, and what a "win" looks like.

It works equally well for a 90-second boss duel and for a permanent shared arena world; both
are the same engine underneath, just configured differently via `ExecutionMode`
(`SESSION` vs `PERSISTENT` — see `config/DungeonDefinition.kt`).

License: TinyYana Universal Software License (TYUSL) 1.0 — see `LICENSE`.

## What it is / isn't

HanaToki **is**:

- An engine for defining "instanced spaces" (dungeon, arena, puzzle room, boss room — any
  bounded area a player or party enters, does something in, and leaves) purely from YAML +
  a small Kotlin extension point.
- **Content-agnostic.** It has no idea what a "coin", a "combo meter", or a "greatsword" is.
  It only ever deals in abstract concepts: a dungeon ID, a check ID, an outcome string, a
  reward key. Your plugin supplies the actual meaning.
- Folia-native. Every state change that touches the world, an entity, or a player is
  dispatched to whichever region/entity scheduler actually owns that object — nothing assumes
  a single global tick thread.

HanaToki **is not** (yet):

- A GUI/menu system, a matchmaker, or a way to join an in-progress instance mid-run.
- A block-protection plugin. An instance's arena has no built-in protection against players
  breaking blocks — if you're running it inside a shared/persistent world (not a disposable
  void world), pair it with a claim/protection plugin or your own listener.

## Quick start

### 1. Add it as a build dependency

HanaToki has **zero dependency** on any other plugin (no `depend`/`softdepend`, no compile-time
dependency on anything but Paper + Kotlin). To compile against its types from another Gradle
project, pull it in as an included build:

```kotlin
// settings.gradle.kts of your plugin
includeBuild("../HanaToki")
```

```kotlin
// build.gradle.kts of your plugin
dependencies {
    compileOnly("com.tinyyana:HanaToki")
}
```

At runtime, just make sure `HanaToki.jar` is in the server's `plugins/` folder alongside
yours, and declare it as a `depend` in your `plugin.yml`.

### 2. Describe a dungeon in YAML

Ship a YAML file with your plugin (or generate one at runtime) and load it with
`HanaTokiCore.loadContentDefinitions(file)` — see step 3. Minimal example, a two-minute solo
combat encounter with one interactive lever:

```yaml
dungeons:
  goblin-den:
    display: "Goblin Den"
    world: goblin_den_instance      # created automatically as an empty "void" world
    slot-count: 8                   # up to 8 concurrent, fully independent copies
    slot-spacing-blocks: 1024       # how far apart each copy is placed, so they never overlap
    session-time-limit-seconds: 120 # hard cap; instance is force-ended if this is exceeded
    solo-cap: 1
    party-cap: 4
    reconnect-grace-seconds: 60     # a disconnected player has this long to rejoin before being dropped
    stages:
      start: entry
      list:
        entry: {}
        combat:
          timeout-seconds: 90
        victory: {}
    interactions:
      lever: { x: 3, y: 0, z: -2, kind: right-click }
    encounters:
      goblins: { entity: ZOMBIE, count: 5, x: 0, y: 0, z: 4, radius: 3 }
```

- `world-create: true` (the default) tells the engine this world belongs to it: if it doesn't
  exist yet, HanaToki builds an empty void world for it automatically — no manual Multiverse
  setup needed. Set it `false` if `world` is an existing shared or hand-built world instead.
- Every field in `interactions`/`encounters` is a coordinate **offset from the slot's anchor
  point**, not an absolute coordinate — the engine works out the real position per slot.
- See `DungeonDefinition.kt` for the full field list (execution mode, world generator hookup,
  border margin, tags, etc.) — every field has a doc comment.

### 3. Register your content in `onEnable`

Your plugin `depend`s on HanaToki, so it always starts after it:

```kotlin
class MyGamePlugin : JavaPlugin() {
    override fun onEnable() {
        val hanaToki = (server.pluginManager.getPlugin("HanaToki") as HanaTokiPlugin).core

        // Player-facing text for your stages/outcomes (merged into the engine's message table)
        hanaToki.texts.merge(mapOf(
            "goblin-den.entry" to "You step into the goblin den...",
            "goblin-den.victory" to "The den falls silent.",
        ))

        // Your dungeon definitions, loaded from the YAML you ship as a resource
        hanaToki.loadContentDefinitions(File(dataFolder, "dungeons.yml"))

        // Your gameplay logic: what actually happens at each stage
        DungeonBehaviorRegistry.register("goblin-den", GoblinDenBehavior())
    }
}
```

```kotlin
class GoblinDenBehavior : DungeonBehavior {
    override fun onStageEnter(ctx: StageContext, stageId: String) {
        if (stageId == "combat") ctx.encounters.spawn("goblins")
    }

    override fun onEncounterCleared(ctx: StageContext, encounterId: String) {
        if (encounterId == "goblins") ctx.resolve("victory")
    }
}
```

`DungeonBehavior` is the one interface you implement per dungeon ID — see
`stage/DungeonBehavior.kt` for the full callback list (stage enter/exit/timeout, interactions,
check outcomes, encounter cleared/failed, actor death). `StageContext` (passed into every
callback) is your entire toolbox for producing effects — spawning props, moving/posing actors,
sending messages, resolving the stage, rolling checks. You never touch Bukkit's world/entity
APIs directly from a callback; everything goes through `ctx` so the engine can keep it on the
right scheduler thread.

### 4. Let players in

Player entry has no built-in UI — that's deliberately left to you (a menu, a sign, an NPC,
whatever fits your game). Programmatically, other plugins reach HanaToki through the
`DungeonAccess` service (registered via Bukkit's `ServicesManager`, so you don't need a
compile-time dependency on HanaToki to use it):

```kotlin
val access = server.servicesManager.getRegistration(DungeonAccess::class.java)?.provider
access?.enterDungeon(player.uniqueId, "goblin-den")
```

There's also a built-in `/hanatoki` command for testing and administration (see below) — it is
**not** meant to be the player-facing entry point for a shipped game. It defaults to
op-only (`hanatoki.enter`) precisely so it can't be used to bypass whatever entry flow (menu,
NPC, quest trigger...) your own plugin builds on top of `DungeonAccess`.

## Architecture principles

- **The engine doesn't know what your game is about.** It never sees domain vocabulary like
  currency names, ability names, or item names — only dungeon IDs, check IDs, outcome
  strings, and reward keys, all plain strings supplied by your content.
- **Zero dependencies.** HanaToki doesn't `includeBuild`, `compileOnly`, or `depend`/
  `softdepend` on any other plugin. If another plugin wants to compile against its types, it
  includes HanaToki as a build (see Quick Start above).
- **Folia-native.** Every core state change is dispatched through the region/entity
  scheduler that actually owns the affected location or entity — the engine never assumes a
  single global tick thread, or that "an instance has an anchor point" means that anchor's
  thread owns the whole arena.

## Modules

| Module | Contents |
|---|---|
| `instance/` | `SlotPool` (lock-free arena slot allocation), `Session`/`MemberState` (join/leave/timing/offline-grace state machine), `SessionManager` |
| `stage/` | `StageGraph`/`InstanceState` (pure-logic state machine), `StageEngine`, `StageContext` (your only interface to produce effects), `DungeonBehavior` (your extension point) |
| `encounter/` | `EncounterController`: spawning, entity binding, death tracking, cleanup |
| `actor/` | `ActorSpec`/`ActorHandle`/`ActorController`: scripted NPCs (backed by vanilla `Mannequin`) |
| `check/` | `CheckResolver`/`CheckDescriptor` port + `CheckAggregator` (individual or majority-vote outcomes) |
| `reward/` | `CompletionResult` + `RewardSink` port + `RewardDispatcher` (retries delivery that failed) |
| `world/` | `DiffLog` (pure sort/group logic for block changes), `WorldDiffRecorder` (actual diff recording and rollback) |
| `folia/` | `WorldOp` (world/entity mutation dispatch), `PlayerOp` (player-action dispatch), `InstanceDispatch` (serializes an instance's own logic onto one thread) |
| `api/` | `DungeonAccess` (enter/leave, consumed by other plugins), `PresenceBridge` (is-player-inside queries), `MusicCue`, `DungeonInfo` |
| `config/` | `DungeonDefinitionParser`/`DungeonRegistry` (YAML dungeon-definition parsing) |
| `command/` | `/hanatoki` (alias `hana`) |

## The engine/content boundary

The engine ships with a handful of `test-*` dungeon definitions
(`src/main/resources/dungeons.yml`) that exist purely as regression fixtures for the engine's
own capabilities — they are **not** meant to be real content, and are disabled by default
(`enable-test-dungeons: false`, and each definition is additionally flagged
`test-only: true`). Turn `enable-test-dungeons` on locally if you want to poke at them while
developing against the engine.

Real content — the actual dungeons your game ships — lives entirely in *your* plugin: your
own YAML file(s), your own `DungeonBehavior` implementation(s), your own player-facing text.
Load and register them from your `onEnable()` as shown in Quick Start step 3.

## Cross-plugin call safety

`StageContext`, `DungeonBehavior`, and `ActorHandle` are interfaces implemented and called
across a plugin boundary — and every Paper plugin gets its **own copy** of the Kotlin
stdlib via the library loader, loaded by a different classloader. Two plugins holding
different `Class` objects for the same Kotlin runtime type causes a hard `LinkageError` at
the call site, not a compile error, so this repo holds itself to a few rules on any type
that crosses that boundary:

- **No Kotlin-only types in a public signature.** `kotlin.Pair`, `() -> Unit` (`Function0`),
  `(T) -> Unit` (`Function1`), etc. all fail with
  `LinkageError: loader constraint violation ... different Class objects for the type
  kotlin/jvm/functions/Function1`. Use `Runnable` / `java.util.function.Consumer` for
  callbacks instead (a Kotlin caller can still pass a lambda — SAM conversion handles it),
  and named-parameter-style calls should use `Map<String, String>` instead of a data class
  (`kotlin.collections.Map` compiles down to `java.util.Map`, which is safe).
- **No Kotlin default parameter values** on a cross-plugin-visible method — they compile to a
  synthetic `xxx$default` overload, which is the same class of `NoSuchMethodError` risk.
  Write explicit overloads instead if you want the convenience.
- Interface methods with a body (`DefaultImpls`) are fine to use, but both sides need to be
  recompiled together whenever one is added or removed.

## Commands and permissions

| Command | What it does |
|---|---|
| `/hanatoki enter <dungeonId> [player2] [player3] ...` | Enter a dungeon slot, optionally bringing named online players along as party members |
| `/hanatoki leave` | Leave whatever instance you're currently in |
| `/hanatoki admin list` | List current slot/session state |
| `/hanatoki admin kick <player>` | Remove a player from their current session |
| `/hanatoki admin reset <slotId>` | Force-resolve and reclaim a slot (including cleanup) |
| `/hanatoki admin debug` | Dump internal state for debugging |

| Permission | Default | What it gates |
|---|---|---|
| `hanatoki.enter` | op | Direct command-line entry/exit. This is a **development and admin testing tool**, not the player-facing entry point — a shipped game should give players a real entry flow (menu, NPC, sign, quest trigger...) built on the `DungeonAccess` API from another plugin, which does not check this permission. |
| `hanatoki.admin` | op | Admin subcommands (`list`/`kick`/`reset`/`debug`) |

## Development

```bash
./gradlew build   # compile + unit tests + jar
./gradlew test    # unit tests only
```

Unit tests cover the pure logic that doesn't touch Bukkit types directly (`SlotPool`,
`Session`, `SessionManager`, `InstanceState`, `DiffLog`, `CheckAggregator`,
`DungeonDefinitionParser`). The parts that involve real `Location`/`BlockData`/entities/
scheduling (`WorldDiffRecorder`, `WorldOp`/`PlayerOp`, `ActorController`, `DungeonRegistry`,
the command) are covered by integration tests run against a live Folia server instead.

## Contributing

Issues and pull requests are welcome. If you're adding a feature, a short note on the
motivating use case in the PR description is appreciated — this engine deliberately avoids
building capabilities speculatively ahead of an actual need.
