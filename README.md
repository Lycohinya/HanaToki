**繁體中文** · [English](README.en.md)

# HanaToki

HanaToki 是給 Paper／Folia 插件使用的微型副本引擎。

它負責一趟副本從進場到收尾的麻煩事：分配場地、記住隊伍、推進關卡、生成與清理實體、還原透過 `StageContext.mutate` 記錄的方塊，最後把玩家送回原本的位置。副本裡要演什麼、怎樣算通關，留在內容插件裡寫。

簡單講：**YAML 描述場地，Kotlin 決定玩法，HanaToki 顧生命週期。**

## 先看它適不適合你

| 你的需求 | HanaToki 的答案 |
|---|---|
| 在同一台 Paper／Folia 伺服器開短局副本、Boss 房或常駐競技場 | 適合 |
| 用 YAML 放座標，再用 Kotlin 寫關卡判定與演出 | 適合 |
| 只裝一顆插件、完全不寫程式就做出完整副本 | 不適合 |
| 內建 GUI、配對、短局 session 的中途加入、跨伺服器傳送或方塊保護 | 目前沒有 |

引擎本身不認識花蜜、裝備、任務或任何特定玩法。這些東西由內容插件透過 API 接進來，所以 HanaToki 不會偷偷接管別人的經濟、選單或玩家資料。

## 文件從這裡走

| 你現在想做什麼 | 請讀 |
|---|---|
| 安裝、設定、開測試副本、查指令或排錯 | [使用手冊](docs/USAGE.md) |
| 寫內容插件、接服務、查 callback 與執行緒契約 | [API 文件](docs/API.md) |

第一次接觸建議先讀使用手冊。API 文件是查契約用的，不需要從第一行背到最後一行。

Lycohinya 正式使用的內容層 `LycoHanaToki` 是閉源專案，不提供公開閱讀連結；這個 repo 只維護通用引擎、probe 與整合契約。

## 五分鐘接進內容插件

目前的編譯目標是 Java 25、Paper API 26.2；版本以 [`gradle.properties`](gradle.properties) 與 [`plugin.yml`](src/main/resources/plugin.yml) 為準。

### 1. 加入 composite build

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

### 2. 宣告啟動順序

```yaml
# plugin.yml
depend: [HanaToki]
```

### 3. 載入定義並註冊玩法

```kotlin
override fun onEnable() {
    saveResource("dungeons.yml", false)

    val hanaToki = server.pluginManager.getPlugin("HanaToki") as? HanaTokiPlugin
        ?: error("HanaToki 未載入")

    hanaToki.core.texts.merge(
        mapOf("goblin-den.enter" to "<gray>洞裡傳來腳步聲……</gray>"),
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

### 4. 讓玩家從你的入口進場

HanaToki 不附玩家選單。選單、NPC 或任務觸發器應該呼叫 Bukkit `ServicesManager` 裡的 `DungeonAccess`：

```kotlin
val access = server.servicesManager
    .getRegistration(DungeonAccess::class.java)
    ?.provider

val accepted = access?.enterDungeon(player.uniqueId, "goblin-den") == true
```

`accepted == true` 只代表請求已受理；Folia 上的傳送仍是非同步。完整的失敗語意、雙人入口與離場規則在 [API 文件的 `DungeonAccess` 章節](docs/API.md#5-dungeonaccess) 裡。

## 它怎麼分工

```text
內容插件
  ├─ dungeons.yml：世界、slot、stage、互動點、encounter
  ├─ DungeonBehavior：關卡判定與演出
  └─ ServicesManager：檢定、獎勵、音樂等外部能力
                │
                ▼
HanaToki
  session → stage → actor / prop / encounter → resolution → cleanup
                │
                ▼
Paper／Folia 的 region、entity 與 global scheduler
```

`DungeonBehavior` callback 只透過 `StageContext` 操作玩家、世界與實體。非同步 future 完成後若要改 stage state，先用 `ctx.submit { ... }` 回到該 instance 的序列執行緒。這條不是建議，是 Folia 的安全邊界。

## 現況與限制

- 內建 `test-*` 副本預設關閉，不會建立世界或註冊 slot；只在本地驗收時開 `enable-test-dungeons: true`。
- `solo-cap` 與 `party-cap` 目前會被解析，但核心尚未用它們驗證隊伍人數。公開入口只有單人與雙人 API；內容插件仍要在自己的入口先做資格檢查。
- 沒有設定熱重載指令。改完 YAML 請重啟伺服器；只重載單一內容插件也可能留下舊的 behavior 註冊。
- `RewardSink` 缺席時，待發資料只暫存在目前 JVM 的記憶體裡，不是跨重啟的持久佇列。
- 正式內容不放在這個 repo。HanaToki 只保留預設關閉的 architecture probe。
- 方塊回滾只涵蓋 `ctx.mutate` 寫入。玩家自行破壞／放置，或內容插件直接呼叫 Bukkit 改方塊，都不會被 diff recorder 自動還原；需要另外的保護或回滾邏輯。

更完整的操作風險與復原方式見 [使用手冊的現有限制](docs/USAGE.md#9-現有限制與操作邊界)。

## 開發

Windows：

```powershell
.\gradlew.bat clean test build
```

Linux／macOS：

```bash
./gradlew clean test build
```

產物在 `build/libs/`。單元測試涵蓋純邏輯；世界、實體、傳送與 scheduler 路徑仍要在真 Folia／Lecithin 環境做整合驗證。

## 授權

[TinyYana Universal Software License 1.0](LICENSE)
