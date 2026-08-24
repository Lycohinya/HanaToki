[繁體中文](README.md) · **English**

# HanaToki

HanaToki is a small dungeon engine for Paper and Folia plugins.

It handles the awkward parts of an encounter lifecycle: allocating an arena, tracking the party, advancing stages, spawning and cleaning up entities, restoring block changes recorded through `StageContext.mutate`, and returning players when the run ends. The content plugin still decides what happens inside the dungeon and what counts as a clear.

In short: **YAML describes the space, Kotlin defines the game, and HanaToki owns the lifecycle.**

## Is it a fit?

| What you need | HanaToki |
|---|---|
| Short dungeon runs, boss rooms, or persistent arenas on one Paper／Folia server | Yes |
| YAML coordinates plus Kotlin behavior and presentation | Yes |
| A complete no-code dungeon plugin | No |
| Built-in GUI, matchmaking, mid-run joining for session instances, cross-server routing, or block protection | Not currently included |

The engine does not own your economy, quests, items, menus, or player database. Content-specific rules remain in a separate plugin and connect through the API.

## Documentation

| Goal | Read |
|---|---|
| Install, configure, operate, and troubleshoot the plugin | [Usage guide (Traditional Chinese)](docs/USAGE.md) |
| Build a content plugin and look up API contracts | [API reference (Traditional Chinese)](docs/API.md) |
| Inspect a working content implementation | The adjacent `LycoHanaToki` repository |

The README is the entry point. Detailed schemas, method tables, failure semantics, and current limitations live in the linked documents so this page remains readable.

## Five-minute integration

The current build targets Java 25 and Paper API 26.2. Treat [`gradle.properties`](gradle.properties) and [`plugin.yml`](src/main/resources/plugin.yml) as the version sources of truth.

### 1. Add the composite build

```kotlin
// settings.gradle.kts
includeBuild("../HanaToki")
```

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("com.tinyyana:HanaToki:0.1.2")
}
```

### 2. Declare startup order

```yaml
# plugin.yml
depend: [HanaToki]
```

### 3. Load definitions and register behavior

```kotlin
override fun onEnable() {
    saveResource("dungeons.yml", false)

    val hanaToki = server.pluginManager.getPlugin("HanaToki") as? HanaTokiPlugin
        ?: error("HanaToki is not enabled")

    hanaToki.core.texts.merge(
        mapOf("goblin-den.enter" to "<gray>Footsteps echo through the cave...</gray>"),
    )
    hanaToki.core.loadContentDefinitions(File(dataFolder, "dungeons.yml"))
    DungeonBehaviorRegistry.register("goblin-den", GoblinDenBehavior())
}
```

```kotlin
class GoblinDenBehavior : DungeonBehavior {
    override fun onStageEnter(ctx: StageContext, stageId: String) {
        if (stageId == "combat") {
            ctx.spawnEncounter("goblins") { resumed ->
                resumed.resolve("cleared")
            }
        }
    }
}
```

### 4. Enter through your own player-facing flow

HanaToki does not ship a player menu. A menu, NPC, quest, or sign can retrieve `DungeonAccess` from Bukkit's `ServicesManager`:

```kotlin
val access = server.servicesManager
    .getRegistration(DungeonAccess::class.java)
    ?.provider

val accepted = access?.enterDungeon(player.uniqueId, "goblin-den") == true
```

`true` only means the request was accepted. Teleportation is asynchronous on Folia, and the entry plugin must still enforce its own eligibility, party, cost, and cooldown rules.

## Responsibility map

```text
Content plugin
  ├─ dungeons.yml: world, slots, stages, interactions, encounters
  ├─ DungeonBehavior: rules and presentation
  └─ ServicesManager: checks, rewards, music, and other integrations
                │
                ▼
HanaToki
  session → stage → actor / prop / encounter → resolution → cleanup
                │
                ▼
Paper／Folia region, entity, and global schedulers
```

`DungeonBehavior` callbacks must use `StageContext` for player, world, and entity work. A future may complete on any thread; call `ctx.submit { ... }` before mutating instance state, transitioning, or resolving.

## Current boundaries

- Built-in `test-*` dungeons are disabled by default and are development probes, not shipped game content.
- `solo-cap` and `party-cap` are parsed but not enforced by the core yet. Entry plugins must validate party rules.
- There is no configuration reload command. A full restart is the supported way to apply YAML or content updates.
- Reloading only a content plugin can leave the previous behavior registration behind because the registry has no unregister operation.
- Missing `RewardSink` deliveries are queued only in this JVM's memory; the queue does not survive a restart.
- Session instances do not support joining an already-running session. Persistent instances deliberately share one long-lived instance and can add members as players enter its world.
- Block rollback covers only writes made through `ctx.mutate`. Player edits and direct Bukkit block writes need separate protection or rollback logic.

See the [usage guide](docs/USAGE.md#9-現有限制與操作邊界) for operational consequences and recovery paths.

## Development

Windows:

```powershell
.\gradlew.bat clean test build
```

Linux／macOS:

```bash
./gradlew clean test build
```

Artifacts are written to `build/libs/`. Unit tests cover pure logic; world, entity, teleport, and scheduler paths still require integration testing on a real Folia／Lecithin server.

## License

[TinyYana Universal Software License 1.0](LICENSE)
