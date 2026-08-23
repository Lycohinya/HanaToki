# HanaToki

Folia-first 微型副本(instance/dungeon)引擎。從 Lycohinya 的 `LycohinyaCore` 抽離出來,
目標是承載 1–3 分鐘的短局副本到常駐世界型的長壽副本,同一套 lifecycle,玩法類型不偏食
(PvE、Boss 決鬥、解謎、場景事件、非戰鬥通關條件皆可)。

授權:TinyYana Universal Software License (TYUSL) 1.0,見 `LICENSE`。

## 目前狀態

Phase 0–4 已完成:引擎骨架、Stage/Trigger/Interaction 狀態機、check/reward/music port、
最小 encounter、`actor/`(演出用 NPC),並且**已經有一座真正面向玩家的副本跑在上面**
(內容不在這個 repo——見下方「引擎與內容的邊界」)。

**還沒有**:GUI/選單、跨伺服器副本、matchmaking、中途加入、方塊保護
(場地目前沒有防破壞,共用世界部署時要靠外部保護插件或專屬副本世界)。

## 架構原則

- **引擎不知道具體遊戲內容是什麼**:不認識「花蜜」「共鳴」「太刀」這類專屬詞彙,
  只認 dungeon id、check id、outcome 字串、reward key 這類抽象概念。
- **零 Lyco 依賴**:不 `includeBuild`、不 `compileOnly`、不 `depend`/`softdepend` 任何
  `Lyco*` 插件或 `LycohinyaCore`。其他插件要用 HanaToki 的型別編譯,自行在自己的
  `settings.gradle.kts` 裡 `includeBuild("../HanaToki")`。
- **Folia 原生**:所有核心狀態變更走 region-scheduler/entity-scheduler 派工,不假設
  「instance 有 anchor 就等於 anchor 擁有整個場地」——世界/實體/玩家操作一律按實際
  location/entity 擁有權另外派工。

## 模組

| 模組 | 內容 |
|---|---|
| `instance/` | `SlotPool`(場地 slot 無鎖分配)、`Session`/`MemberState`(進出/計時/離線 grace 狀態機)、`SessionManager` |
| `stage/` | `StageGraph`/`InstanceState`(純邏輯狀態機)、`StageEngine`、`StageContext`(內容唯一的操作面)、`DungeonBehavior`(內容擴充點) |
| `encounter/` | `EncounterController`:spawn、entity binding、death tracking、清場 |
| `actor/` | `ActorSpec`/`ActorHandle`/`ActorController`:演出用 NPC(載體是原版 `Mannequin`) |
| `check/` | `CheckResolver`/`CheckDescriptor` port + `CheckAggregator`(INDIVIDUAL/MAJORITY) |
| `reward/` | `CompletionResult` + `RewardSink` port + `RewardDispatcher`(未派送補跑佇列) |
| `world/` | `DiffLog`(方塊變更的純排序/分組邏輯)、`WorldDiffRecorder`(實際 diff 記錄與回滾) |
| `folia/` | `WorldOp`(world/entity mutation 派工)、`PlayerOp`(玩家操作派工)、`InstanceDispatch`(instance 邏輯序列化) |
| `api/` | `PresenceBridge`(HanaToki 是 provider)、`MusicCue`、`DungeonInfo` |
| `config/` | `DungeonDefinitionParser`/`DungeonRegistry`(YAML 副本定義解析) |
| `command/` | `/hanatoki`(`hana` 別名) |

## 引擎與內容的邊界

引擎自帶的 `testcontent/`(三燈引路 `test-puzzle`、最小戰鬥 `test-combat`)是
**architecture probe**,不是正式內容——它們存在的目的是讓引擎的能力有一份可以直接跑的
回歸樣本。正式副本內容(世界觀、文案、數值、演出)屬於 integration 插件,做法是:

```kotlin
// 內容插件的 onEnable(它 depend HanaToki,所以一定比引擎晚啟動)
val core = (Bukkit.getPluginManager().getPlugin("HanaToki") as HanaTokiPlugin).core
core.texts.merge(myTexts)                       // 內容文字併進引擎的訊息表
core.loadContentDefinitions(myDungeonsYamlFile) // 副本定義跟內容住同一個 repo
DungeonBehaviorRegistry.register("my-dungeon", MyBehavior())
```

### ⚠ 跨插件呼叫的硬規則

`StageContext` / `DungeonBehavior` / `ActorHandle` 這些介面會被**別的插件**實作與呼叫,
而每個插件從 Paper library loader 拿到的是**自己那份 kotlin-stdlib**。因此:

- 簽章裡**不准出現 Kotlin 專屬型別**:`kotlin.Pair`、`() -> Unit`(`Function0`)、
  `(T) -> Unit`(`Function1`)一律不行,實測會丟
  `LinkageError: loader constraint violation ... different Class objects for the type
  kotlin/jvm/functions/Function1`。回呼改用 `Runnable` / `java.util.function.Consumer`
  (Kotlin 呼叫端照樣可以寫 lambda,SAM 轉換會處理),具名參數改用 `Map<String, String>`
  (`kotlin.collections.Map` 在 bytecode 就是 `java.util.Map`,是安全的)。
- **不用 Kotlin 預設參數值**——會編成 `xxx$default` 合成簽章,是同一類跨插件
  `NoSuchMethodError` 的來源。需要便利多載就明確寫多載。
- 帶 body 的介面方法(`DefaultImpls`)可以用,但增刪時兩邊要一起重編。

## 指令與權限

| 指令 | 說明 |
|---|---|
| `/hanatoki enter <dungeonId> [player2] ...` | 進入一個副本 slot(可帶隊友一起) |
| `/hanatoki leave` | 離開目前所在的副本 |
| `/hanatoki admin list` | 列出目前的 slot/session 狀態 |
| `/hanatoki admin kick <player>` | 把玩家踢出目前的 session |
| `/hanatoki admin reset <slotId>` | 強制結算並回收指定 slot(含清場) |
| `/hanatoki admin debug` | 印出內部狀態供除錯 |

| 權限節點 | 預設 | 說明 |
|---|---|---|
| `hanatoki.enter` | 所有玩家 | 進出副本 slot |
| `hanatoki.admin` | op | 管理指令 |

## 開發

```bash
./gradlew build   # 編譯 + 單元測試 + jar
./gradlew test    # 只跑單元測試
```

單元測試涵蓋不依賴 Bukkit 型別的純邏輯(`SlotPool`/`Session`/`SessionManager`/`InstanceState`/
`DiffLog`/`CheckAggregator`/`DungeonDefinitionParser`);牽涉真實 `Location`/`BlockData`/實體/
排程的部分(`WorldDiffRecorder`、`WorldOp`/`PlayerOp`、`ActorController`、`DungeonRegistry`、
指令)靠實際 Folia 伺服器上的 harness 測試涵蓋。
