**繁體中文** | [English](README.en.md)

# HanaToki

給 Paper 插件用的 Folia-first 微型副本(instance/dungeon)引擎。它處理每一種「有邊界的
玩法空間」都會需要的通用機制——場地 slot 分配、進出/隊伍狀態、stage(狀態機)流程、世界
建立與回滾、敵人生成與追蹤、檢定判定、獎勵派送——讓遊戲插件只需要描述**自己的內容**:
stage 叫什麼名字、每個 stage 要發生什麼事、怎樣算「通關」。

它同樣適用於 90 秒的 Boss 決鬥,也適用於一個永久存在的共用競技場世界;底層是同一套引擎,
差別只在設定不同(見 `config/DungeonDefinition.kt` 的 `ExecutionMode`:`SESSION` 對
`PERSISTENT`)。

授權:TinyYana Universal Software License(TYUSL)1.0——見 `LICENSE`。

## 這是什麼 / 不是什麼

HanaToki **是**:

- 一個純靠 YAML + 一個小型 Kotlin 擴充點就能定義「副本空間」的引擎(副本、競技場、解謎房、
  Boss 房——任何玩家或隊伍會進入、做點什麼、然後離開的有邊界區域都算)。
- **完全不認識具體的遊戲內容。** 它不知道「金幣」「連段計量」「大劍」是什麼,只處理抽象
  概念:副本 id、檢定 id、outcome 字串、獎勵 key。實際的意義由你的插件賦予。
- Folia 原生。任何會動到世界、實體或玩家的狀態變更,都會派工給真正擁有那個物件的
  region/entity scheduler——引擎不假設有單一全域 tick 執行緒。

HanaToki **還不是**(尚未支援):

- GUI/選單系統、跨伺服器副本、matchmaking,或是中途加入已在進行的副本。
- 方塊保護插件。副本場地**沒有**內建的防破壞保護——如果你把它跑在共用/常駐世界裡
  (不是用完即丟的虛空世界),要自己搭配領地/保護插件或自訂 listener。

## 快速開始

### 1. 加進你的專案當編譯依賴

HanaToki **零依賴**(不 `depend`/`softdepend` 任何其他插件,編譯期也不依賴 Paper + Kotlin
以外的任何東西)。要在別的 Gradle 專案裡對它的型別編譯,把它當 included build 拉進來:

```kotlin
// 你的插件的 settings.gradle.kts
includeBuild("../HanaToki")
```

```kotlin
// 你的插件的 build.gradle.kts
dependencies {
    compileOnly("com.tinyyana:HanaToki")
}
```

執行期只要確保 `HanaToki.jar` 跟你的插件一起放在伺服器的 `plugins/` 資料夾裡,並在你的
`plugin.yml` 裡宣告 `depend: [ HanaToki ]` 就可以。

### 2. 用 YAML 描述一座副本

把 YAML 檔跟你的插件一起發佈(或執行期動態產生),再用 `HanaTokiCore.loadContentDefinitions(file)`
載入(見下面第 3 步)。最小範例:一座兩分鐘的單人戰鬥副本,帶一個可互動的拉桿:

```yaml
dungeons:
  goblin-den:
    display: "哥布林巢穴"
    world: goblin_den_instance      # 世界不存在時會自動建一個空的虛空世界
    slot-count: 8                   # 最多 8 局同時獨立進行,彼此互不干擾
    slot-spacing-blocks: 1024       # 每一局的間距,確保絕對不會疊在一起
    session-time-limit-seconds: 120 # 硬上限;超過就強制結束這一局
    solo-cap: 1
    party-cap: 4
    reconnect-grace-seconds: 60     # 斷線玩家有多久可以重連,逾時才會被真的踢出
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

- `world-create: true`(預設值)表示這個世界歸引擎管:世界不存在的話,HanaToki 會自動幫你
  建一個空的虛空世界——不需要手動設定 Multiverse 之類的工具。如果 `world` 指的是一個既有
  的共用世界或手工蓋好的場地,才需要設成 `false`。
- `interactions`/`encounters` 底下的每一組座標,都是**相對於這一局 slot 錨點的偏移量**,
  不是絕對座標——引擎會自己算出每一局實際落在哪裡。
- 完整欄位列表(執行模式、地形生成器掛載、世界邊界留白、標籤...)見
  `DungeonDefinition.kt`——每個欄位都有文件註解。

### 3. 在 `onEnable` 註冊你的內容

你的插件 `depend` 了 HanaToki,所以一定比引擎晚啟動:

```kotlin
class MyGamePlugin : JavaPlugin() {
    override fun onEnable() {
        val hanaToki = (server.pluginManager.getPlugin("HanaToki") as HanaTokiPlugin).core

        // 玩家看得到的文字(併進引擎的訊息表)
        hanaToki.texts.merge(mapOf(
            "goblin-den.entry" to "你踏進了哥布林的巢穴……",
            "goblin-den.victory" to "巢穴恢復了寂靜",
        ))

        // 你的副本定義,從你發佈的 YAML 資源載入
        hanaToki.loadContentDefinitions(File(dataFolder, "dungeons.yml"))

        // 你的玩法邏輯:每個 stage 實際要發生什麼事
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

`DungeonBehavior` 是你每個副本 id 要實作的唯一介面——完整 callback 列表(stage 進出/超時、
interaction、check outcome、encounter 清場/失敗、actor 死亡)見 `stage/DungeonBehavior.kt`。
每個 callback 傳進來的 `StageContext` 是你製造效果的**唯一工具箱**——生成道具展示、操控
actor 的動作與姿勢、發訊息、結算 stage、擲檢定。callback 裡永遠不要直接碰 Bukkit 的
世界/實體 API,一律透過 `ctx`,引擎才能確保它跑在正確的排程執行緒上。

### 4. 讓玩家進得去

玩家入口**沒有內建 UI**——這是刻意留給你決定的(選單、告示牌、NPC,隨便你的遊戲怎麼設計)。
在程式碼層面,其他插件透過 `DungeonAccess` 這個 Bukkit `ServicesManager` 服務來呼叫
HanaToki(所以不需要對 HanaToki 建編譯期依賴就能用):

```kotlin
val access = server.servicesManager.getRegistration(DungeonAccess::class.java)?.provider
access?.enterDungeon(player.uniqueId, "goblin-den")
```

引擎另外附了一個 `/hanatoki` 指令供測試與管理用(見下方)——**這不是設計給玩家的入口**。
它預設是 op-only(`hanatoki.enter`),就是為了不讓玩家繞過你自己在 `DungeonAccess` 上面
搭的那層入口(選單、NPC、任務觸發……不管是什麼)。

## 架構原則

- **引擎不知道你的遊戲在講什麼。** 它永遠不會看到具體的貨幣名稱、能力名稱、道具名稱——
  只有你的內容提供的、單純的字串:副本 id、檢定 id、outcome 字串、獎勵 key。
- **零依賴。** HanaToki 不 `includeBuild`、不 `compileOnly`、不 `depend`/`softdepend` 任何
  其他插件。別的插件想借它的型別編譯,自己在自己的 build 裡把 HanaToki 包進去
  (見上方「快速開始」)。
- **Folia 原生。** 每一項核心狀態變更,都會派工到真正擁有那個座標/實體的 region 或 entity
  scheduler——引擎不會假設有單一全域 tick 執行緒,也不會假設「副本有一個錨點」就代表那個
  錨點的執行緒擁有整個場地。

## 模組

| 模組 | 內容 |
|---|---|
| `instance/` | `SlotPool`(場地 slot 無鎖分配)、`Session`/`MemberState`(進出/計時/離線 grace 狀態機)、`SessionManager` |
| `stage/` | `StageGraph`/`InstanceState`(純邏輯狀態機)、`StageEngine`、`StageContext`(你製造效果的唯一介面)、`DungeonBehavior`(你的擴充點) |
| `encounter/` | `EncounterController`:生成、entity binding、死亡追蹤、清場 |
| `actor/` | `ActorSpec`/`ActorHandle`/`ActorController`:演出用 NPC(載體是原版 `Mannequin`) |
| `check/` | `CheckResolver`/`CheckDescriptor` port + `CheckAggregator`(單人判定或多數決) |
| `reward/` | `CompletionResult` + `RewardSink` port + `RewardDispatcher`(派送失敗會補跑) |
| `world/` | `DiffLog`(方塊變更的純排序/分組邏輯)、`WorldDiffRecorder`(實際 diff 記錄與回滾) |
| `folia/` | `WorldOp`(world/entity mutation 派工)、`PlayerOp`(玩家操作派工)、`InstanceDispatch`(把一局的邏輯序列化到單一執行緒) |
| `api/` | `DungeonAccess`(進出入口,給其他插件呼叫)、`PresenceBridge`(查詢玩家在不在副本裡)、`MusicCue`、`DungeonInfo` |
| `config/` | `DungeonDefinitionParser`/`DungeonRegistry`(YAML 副本定義解析) |
| `command/` | `/hanatoki`(別名 `hana`) |

## 引擎與內容的邊界

引擎自帶幾座 `test-*` 副本定義(`src/main/resources/dungeons.yml`),純粹是引擎自己能力的
回歸測試樣本——**不是**要拿來當正式內容,而且預設關閉(`enable-test-dungeons: false`,
每一座還額外標了 `test-only: true`)。開發時想碰它們的話,把 `enable-test-dungeons` 打開
就好。

真正的內容——你的遊戲實際要出的副本——完全住在**你自己的插件**裡:你自己的 YAML 檔、你
自己的 `DungeonBehavior` 實作、你自己的玩家文字。照上面「快速開始」第 3 步的方式在
`onEnable()` 裡載入與註冊。

## 跨插件呼叫的安全規則

`StageContext`、`DungeonBehavior`、`ActorHandle` 這些介面會被**跨插件**實作與呼叫——
而每個 Paper 插件都是透過 library loader 拿到**自己那一份** Kotlin stdlib,由不同的
classloader 載入。兩個插件對同一個 Kotlin 執行期型別拿到不同的 `Class` 物件,呼叫點會直接
丟出 `LinkageError`(不是編譯錯誤),所以任何跨這條邊界的型別都守著幾條規則:

- **簽章裡不准出現 Kotlin 專屬型別。** `kotlin.Pair`、`() -> Unit`(`Function0`)、
  `(T) -> Unit`(`Function1`)一律不行,實測會丟
  `LinkageError: loader constraint violation ... different Class objects for the type
  kotlin/jvm/functions/Function1`。回呼改用 `Runnable` / `java.util.function.Consumer`
  (Kotlin 呼叫端照樣可以寫 lambda,SAM 轉換會處理),具名參數改用 `Map<String, String>`
  (`kotlin.collections.Map` 在 bytecode 就是 `java.util.Map`,是安全的)。
- **不要用 Kotlin 預設參數值**——會編成 `xxx$default` 這種合成簽章,是同一類跨插件
  `NoSuchMethodError` 風險的來源。需要便利多載就明確寫多載。
- 帶 body 的介面方法(`DefaultImpls`)可以用,但增刪時兩邊要一起重編。

## 指令與權限

| 指令 | 說明 |
|---|---|
| `/hanatoki enter <dungeonId> [player2] [player3] ...` | 進入一個副本 slot,可選擇性帶其他在線玩家一起組隊 |
| `/hanatoki leave` | 離開目前所在的副本 |
| `/hanatoki admin list` | 列出目前的 slot/session 狀態 |
| `/hanatoki admin kick <player>` | 把玩家踢出目前的 session |
| `/hanatoki admin reset <slotId>` | 強制結算並回收指定 slot(含清場) |
| `/hanatoki admin debug` | 印出內部狀態供除錯 |

| 權限節點 | 預設 | 說明 |
|---|---|---|
| `hanatoki.enter` | op | 直接用指令進出副本。這是**開發與管理員測試工具**,不是玩家入口——正式上線的遊戲應該要給玩家一個真正的入口(選單、NPC、告示牌、任務觸發……),建立在另一個插件的 `DungeonAccess` API 之上,那條路不查這個權限。 |
| `hanatoki.admin` | op | 管理指令(`list`/`kick`/`reset`/`debug`) |

## 開發

```bash
./gradlew build   # 編譯 + 單元測試 + jar
./gradlew test    # 只跑單元測試
```

單元測試涵蓋不直接碰 Bukkit 型別的純邏輯(`SlotPool`、`Session`、`SessionManager`、
`InstanceState`、`DiffLog`、`CheckAggregator`、`DungeonDefinitionParser`);牽涉真實
`Location`/`BlockData`/實體/排程的部分(`WorldDiffRecorder`、`WorldOp`/`PlayerOp`、
`ActorController`、`DungeonRegistry`、指令)改靠實際 Folia 伺服器上的整合測試涵蓋。

## 貢獻

歡迎 issue 與 pull request。如果你要加新功能,PR 說明裡附一句「為什麼需要它」會很有幫助——
這個引擎刻意不會為了還沒出現的需求預先做能力,通常會請你先講清楚案例。
