# HanaToki 使用手冊

這份手冊給安裝與維護 HanaToki 的服主、管理員，以及準備接入內容插件的開發者。玩家不會直接操作 HanaToki；正式伺服器應該由內容插件提供選單、NPC、任務或其他入口。

如果你要查 Kotlin 型別與方法，直接去 [API 文件](API.md)。

## 快速導覽

| 你現在要做什麼 | 章節 |
|---|---|
| 安裝、首次啟動與開 probe | [§1–4](#1-安裝前先確認環境) |
| 寫 dungeon YAML 與訊息 | [§5–6](#5-副本-yaml) |
| 查指令與權限 | [§7](#7-指令與權限) |
| 排錯、更新與回滾 | [§8–10](#8-日常檢查與故障排除) |

## 1. 安裝前先確認環境

| 項目 | 目前要求 |
|---|---|
| Minecraft／Paper API | 26.2 |
| Java | 25 |
| 排程模型 | Paper 或 Folia-family；正式整合測試應在真 Folia／Lecithin 核心上跑 |
| 其他插件 | 引擎本體沒有 `depend`／`softdepend` |
| Kotlin runtime | 由 `plugin.yml` 的 Paper library loader 載入 `kotlin-stdlib-jdk8` |

第一次啟動時，伺服器需要能取得 `plugin.yml` 宣告的 Kotlin library。若主機封鎖外部下載，插件會在進入 HanaToki 自己的啟用流程前就失敗；先看伺服器啟動 log，不要一直重裝 jar 猜原因。

## 2. 安裝與首次驗證

### 使用已建置的 jar

1. 關閉伺服器。
2. 把 HanaToki jar 放進 `plugins/`。
3. 若還有內容插件，把它的 jar 一起放進 `plugins/`；內容插件的 `plugin.yml` 必須宣告 `depend: [HanaToki]`。
4. 啟動伺服器。
5. 確認 log 出現 `[HanaToki] 已啟用`，且沒有副本定義解析失敗、世界建立失敗或缺少 library 的錯誤。
6. 用管理員帳號執行 `/hanatoki admin debug`，確認副本定義、slot 數與副本世界符合預期。

HanaToki 沒有設定熱重載指令。安裝、換 jar 或改 YAML 後，以完整重啟作為可靠的生效方式。

### 從 source 建置

Windows：

```powershell
.\gradlew.bat clean test build
```

Linux／macOS：

```bash
./gradlew clean test build
```

正式 jar 在 `build/libs/`，沒有 classifier；`*-thin.jar` 是未經 Shadow task 處理的另一份產物，不是這份手冊預設的部署檔。

## 3. 啟動後會出現哪些東西

| 路徑或狀態 | 用途 |
|---|---|
| `plugins/HanaToki/dungeons.yml` | 引擎自帶的 probe 副本；預設全部關閉 |
| `plugins/HanaToki/messages.yml` | 引擎共用訊息與 probe 文案，格式是 MiniMessage |
| 副本世界資料夾 | `world-create: true` 且世界不存在時建立 |
| 記憶體中的 session／slot／diff | 隨伺服器執行；不是資料庫 |

HanaToki 本身不建立玩家資料庫。獎勵、額度、經濟與 completion 去重如果需要持久化，都是提供 `RewardSink` 等服務的內容插件責任。

`world-auto-save: false` 只代表 Bukkit 不持續自動儲存該世界，不代表磁碟上絕對不會出現世界資料夾。備份與刪除世界前仍要把它當成實際世界資料處理。

## 4. 內建測試副本

預設設定是：

```yaml
enable-test-dungeons: false
```

這時 `test-empty`、`test-puzzle`、`test-combat` 會整座跳過：不建立世界、不註冊 slot，也不能透過指令進場。

只在隔離的開發或驗收環境開啟：

```yaml
enable-test-dungeons: true
```

改完後重啟，再依序檢查：

1. `/hanatoki admin debug` 能看到三座 `test-*` 定義。
2. `/hanatoki enter test-empty` 能進場。
3. `/hanatoki leave` 能回到進場前的位置。
4. 測完改回 `false` 並再次重啟。

不要在正式服把 probe 當活動內容。它們是架構驗收樣本，文案、獎勵與入口都沒有正式內容的保護層。

## 5. 副本 YAML

一座短局副本的最小可用範例：

```yaml
dungeons:
  goblin-den:
    display: "哥布林巢穴"
    description: "清掉洞裡的敵人，再從原路離開"
    world: goblin_den_instance
    mode: session
    slot-count: 4
    slot-spacing-blocks: 1024
    session-time-limit-seconds: 120
    reconnect-grace-seconds: 60
    stages:
      start: combat
      list:
        combat:
          timeout-seconds: 90
    encounters:
      goblins:
        entity: ZOMBIE
        count: 5
        x: 0
        y: 0
        z: 4
        radius: 3.0
```

### 5.1 頂層欄位

| 欄位 | 預設 | 實際作用 |
|---|---:|---|
| `enable-test-dungeons` | `false` | 是否載入同一份檔案中標記 `test-only: true` 的定義 |
| `dungeons` | 必填 | 以 dungeon id 為 key 的定義區塊；缺少時整份檔案略過並記 warning |

內容插件可以載入自己的另一份 YAML。每一份檔案各自判斷 `enable-test-dungeons`，不會沿用 HanaToki 主設定的值。

### 5.2 一座 dungeon 的欄位

| 欄位 | 預設 | 實際作用 |
|---|---:|---|
| `display` | dungeon id | 顯示名稱 |
| `description` | 空字串 | 給外部 UI 使用的簡介 |
| `world` | 必填 | 副本使用的世界名稱 |
| `world-create` | `true` | 世界尚未載入時是否由 HanaToki 建立；`false` 時只接受已載入的世界 |
| `mode` | `session` | `session` 或 `persistent` |
| `world-generator` | 無 | 世界尚未載入、且要由 HanaToki 建立時使用的 generator id；找不到時拒絕建立，不退回 void |
| `world-auto-save` | `false` | 只在 HanaToki 建立／載入原本未載入的世界時設定 Bukkit auto-save |
| `world-border-margin` | `256` | void 世界最遠 slot 外再保留的邊界距離 |
| `slot-count` | `1` | 同時可分配的場地數；必須至少 1，`persistent` 必須等於 1 |
| `slot-spacing-blocks` | `1024` | 同一 dungeon 的 slot 沿 X 軸分隔距離 |
| `anchor-offset-x` | `0` | 整組 slot 的 X 基準偏移 |
| `anchor-offset-z` | `0` | 整組 slot 的 Z 基準偏移 |
| `anchor-y` | `64` | slot anchor 高度 |
| `spawn-offset-x/y/z` | `0` | 第一次進場點相對 anchor 的偏移，可用小數；斷線 grace 內重連目前回 raw anchor，不套這組偏移 |
| `spawn-yaw` | `0` | 玩家進場朝向，單位是度 |
| `session-time-limit-seconds` | `180` | session 硬時限；必須大於 0。persistent 仍要求可解析，但不會因它逾時 |
| `reconnect-grace-seconds` | `180` | session 玩家離線後保留成員資格的時間 |
| `solo-cap` | `1` | 目前只會被解析，核心尚未拿它驗證入口人數 |
| `party-cap` | `4` | 目前只會被解析，核心尚未拿它驗證入口人數 |
| `tags` | `[]` | `DungeonInfo` 的分類資料；核心不解讀內容 |
| `expected-minutes` | 空字串 | `DungeonInfo` 的預估時間文字；核心不做數值運算 |
| `test-only` | `false` | 只有同檔案 `enable-test-dungeons: true` 時才載入 |
| `stages` | 無 | stage graph；省略時只有 session 計時與進出，沒有 behavior stage callback |
| `interactions` | `{}` | 具名互動點，座標都相對 anchor |
| `encounters` | `{}` | 具名敵人波次，座標都相對 anchor |

Parser 會擋缺少 `world`、有 `stages` 區塊時缺少 `stages.start/list`、stage 起點不存在、缺少 encounter `entity`、未知 mode／interaction kind，以及不合法的 slot count／session time limit。`stages` 整段本身仍可省略。這些是主要硬錯誤，不代表它會替你驗證所有數值與 runtime 關係；部署前仍要做 source review 與真伺服器驗收。

如果 Bukkit 已經載入同名世界，world provisioner 會直接沿用並回傳，不會重新套用 `world-auto-save`，也不會檢查 `world-generator` 能不能建立。管理員要先確認那個已載入世界的 generator 與儲存設定真的符合預期，不能只看 YAML。

`slot-spacing-blocks` 只是 anchor 間距。HanaToki 不知道 behavior 會改多大範圍，也不會替你檢查兩座場地是否重疊；場地半徑與 spacing 要由內容作者自己驗算。

`world-create: false` 若用在 `session`，該世界不會進入「結束時必須把玩家送走」的副本世界名單。這種配置不適合期待自動返回點的短局；內容插件必須自行設計離場，或改用引擎管理的專屬世界。

### 5.3 `session` 與 `persistent`

| 行為 | `session` | `persistent` |
|---|---|---|
| instance 數量 | 每次進場分配空 slot | 同一 dungeon id 共用唯一 instance |
| 成員 | 開局時的玩家；離線有 grace | 跟著玩家是否在該世界內同步 |
| `resolve()` | 發送完成結果、結束 session、送人回去、回滾 `ctx.mutate` 記錄的 diff、釋放 slot | 只結算這一輪；behavior 自己決定下一個 stage |
| 無人／時限 | 全員退出或逾時會收場 | 不因無人或時限收場 |
| 世界定位 | 常見是可回滾的 void slot | 常見是有地形、可長期存在的共用世界 |

`persistent` 不是「session 但時間設很長」。它的玩家歸屬、結算與清場語意都不同。

### 5.4 stage graph

```yaml
stages:
  start: dormant
  list:
    dormant: {}
    combat:
      timeout-seconds: 90
      timeout-transition: dormant
```

| 欄位 | 說明 |
|---|---|
| `start` | 必須指向 `list` 裡存在的 stage id |
| `list.<id>.timeout-seconds` | 這個 stage 的時限；省略代表不做 stage-level timeout |
| `list.<id>.timeout-transition` | 逾時後直接轉場；省略時呼叫 `DungeonBehavior.onStageTimeout`，其預設行為是 `resolve("timeout")` |

轉場時，上一個 stage 透過 `ctx.submitLater`／`ctx.submitRepeating` 建立的排程會被取消。不要把跨 stage 一定要存活的工作藏在這些 task 裡。

Parser 目前不會先驗證 `timeout-transition` 的目標是否存在。拼錯 id 時，runtime 會先退出舊 stage、取消它的排程並把 current stage 改成錯誤 id，之後才在讀取 stage 時失敗；它不會安全地留在原 stage。請在內容測試裡實際走過每條逾時路徑。

### 5.5 interactions

```yaml
interactions:
  lever:
    x: 3
    y: 0
    z: -2
    kind: right-click
```

`x/y/z` 都是相對 slot anchor 的整數偏移，省略時為 0。`kind` 目前只有：

- `right-click`：玩家右鍵該方塊時觸發。
- `physical`：玩家踩壓力板等 `PHYSICAL` action 時觸發。

同一個 session 內，事件會被轉成 `DungeonBehavior.onInteraction(ctx, interactionId, playerId)`。

### 5.6 encounters

```yaml
encounters:
  goblins:
    entity: ZOMBIE
    count: 5
    x: 0
    y: 0
    z: 4
    radius: 3.0
```

| 欄位 | 預設 | 說明 |
|---|---:|---|
| `entity` | 必填 | Bukkit `EntityType` 常數名；解析失敗時不生成 |
| `count` | `1` | 生成數量 |
| `x/y/z` | `0` | 相對 anchor 的中心偏移 |
| `radius` | `2.0` | 每隻實體在中心周圍的散開半徑 |

內容程式用 `ctx.spawnEncounter(id, callback)` 啟動。這個 callback 才是目前實際會在清場後被呼叫的路徑；`DungeonBehavior.onEncounterCleared`／`onEncounterFailed` 雖然已宣告，目前核心沒有呼叫它們。

未知 encounter id、查不到展開後的座標，或 `entity` 不是有效的 `EntityType` 時，`spawnEncounter` 只記錄／略過，回傳的 future 仍會成功完成，clear callback 不會被呼叫。`.thenAccept` 成功不代表怪有生出來；內容測試要同時驗證 entity／callback，否則 stage 可能永遠卡住。

HanaToki 追蹤的 encounter、actor 與 prop 實體死亡時，原版掉落物與經驗會被清空。正式獎勵請走 `RewardSink`，不要依賴怪物原版掉落。

## 6. 訊息檔

`messages.yml` 使用 MiniMessage。HanaToki 自己使用 `session.*`；正式內容應該把文案放在內容插件，再透過 `hanaToki.core.texts.merge(...)` 合併。

```yaml
goblin-den:
  enter: "<gray>洞裡傳來腳步聲……</gray>"
  clear: "<green>這裡安靜下來了</green>"
```

```kotlin
hanaToki.core.texts.merge(
    mapOf(
        "goblin-den.enter" to "<gray>洞裡傳來腳步聲……</gray>",
        "goblin-den.clear" to "<green>這裡安靜下來了</green>",
    ),
)
```

查不到 key 時，玩家會直接看到 key 本身。這是刻意的：漏文案要在測試時露出來，不要靜默變成空白。

內容插件停用時應呼叫 `removeByPrefix("goblin-den.")` 清掉自己的 overlay。`merge` 遇到相同 key 會覆蓋舊值。

## 7. 指令與權限

`/hana` 是 `/hanatoki` 的 alias。

### 一般驗收指令

| 指令 | 權限 | 結果 |
|---|---|---|
| `/hanatoki enter <dungeonId> [player2] ...` | `hanatoki.enter` | 開新 session，並帶上找得到的在線玩家；找不到的名字會略過 |
| `/hanatoki leave` | 無額外檢查 | 離開目前 session；不在副本時仍會顯示「已離開」 |

`hanatoki.enter` 預設只給 op。這條指令是開發／驗收工具，不應當成正式玩家入口。它不會執行內容插件自己的任務、費用、冷卻或隊伍資格檢查。

### 管理指令

以下都需要 `hanatoki.admin`，預設只給 op。

| 指令 | 用途 |
|---|---|
| `/hanatoki admin list` | 列出 session id、dungeon id、slot 與尚未 dropped 的成員數；包含離線 grace 中的玩家 |
| `/hanatoki admin kick <player>` | 把在線玩家移出 session |
| `/hanatoki admin reset <slotId>` | 強制結束 session、清場並回收指定 slot |
| `/hanatoki admin debug` | 顯示定義、空 slot、session 數與 session 型副本世界 |
| `/hanatoki admin poses` | 查目前核心接受的 Mannequin pose 名稱 |
| `/hanatoki admin difftest <slotId> [count]` | 測試方塊 diff 記錄；預設 500，僅限隔離環境 |
| `/hanatoki admin diffrollback <slotId>` | 回滾 difftest 留下的變更；僅限隔離環境 |

`reset`、`difftest` 與 `diffrollback` 會修改 runtime 世界狀態。先確認 slot id 與測試環境，不要在正式玩家正在使用的場地上試。

## 8. 日常檢查與故障排除

### 副本沒有出現在 `debug`

依序查：

1. 定義是不是 `test-only: true`，但同一份檔案沒有開 `enable-test-dungeons`。
2. YAML 是否真的有 `dungeons:` 根節點。
3. 啟動 log 是否出現 `副本定義 <id> 解析失敗`。
4. `world-create: false` 指向的世界是否已經先載入。
5. `world-generator` 是否在載入定義前完成註冊。

### `hasDungeon(id)` 是 true，但還是進不去

`hasDungeon` 只確認 definition map 裡有這個 id，不代表世界與 slot 已成功 provision。查啟動 log 是否有「世界不存在也無法建立」「生成器尚未註冊」或 `slot 未登記`，再看 `/hanatoki admin debug` 的 free slot。

### 進場 API 回 true，但玩家沒被傳送

`DungeonAccess` 的 true 只代表請求已受理。核心沒有把 `teleportAsync` 的最終結果回傳給呼叫端，傳送失敗時也不會自動把玩家從新 session 移除；他可能留在原地，卻仍被 `PresenceBridge`、callback 與結算視為成員。

玩家入口應在短暫逾時後，從該玩家的 EntityScheduler 檢查他是否真的到達預期副本世界／位置（預期世界名問 `PresenceBridge.worldNameOf`）。若仍未到達但 `PresenceBridge.isInside` 是 true，呼叫 `leaveDungeon` 收回 session membership，再顯示可重試的失敗訊息。接著查目的世界與 Folia log，不要只重送另一筆 enter。

### 進場前就被當成「人已經在副本裡」

`PresenceBridge.isInside` 從 session 建立那一刻就是 true，而傳送是進場交易的第 6 步（第 5 步還要等 `prepareStage` 蓋完場地）。那幾秒裡玩家人還在原本的世界。任何「人在副本裡才該發生」的效果（背景音樂、HUD、環境效果）要拿 `worldNameOf` 跟玩家當下的世界名比對，不能只問 `isInside`。

### 改了 YAML 但行為沒變

HanaToki 沒有 `/reload`。完整重啟伺服器後再驗。只重載內容插件時，舊 `DungeonBehavior` 可能仍留在 registry；不要把單插件 reload 當可靠更新流程。

### 通關後沒有獎勵

1. 查 log 是否有 `沒有註冊 RewardSink`。
2. 確認內容插件先註冊 `RewardSink`，再開放玩家進場。
3. `RewardSink.onCompletion` 必須自行做非同步 I/O，並用 `completionId` 做持久化去重。
4. 缺少 sink 時的 pending queue 只活在記憶體，而且 provider 回來後要等下一次 `dispatch` 才會順帶 drain；重啟會失去這批 pending。
5. `RewardSink.onCompletion` 如果同步丟出例外，HanaToki 目前不會捕捉；它可能中斷後續成員發獎與本次收場。Provider 應在邊界內自行捕捉、記錄並把 I/O 移出 region thread。

### 玩家離場後場地沒有還原

先不要手動刪世界。用 `/hanatoki admin list` 與 `debug` 確認 session／slot，再查 log 的回滾錯誤。session 型副本會等玩家傳送完成後分 region 回滾，最後才釋放 slot；中途硬殺 JVM 可能來不及走這條清場路徑。

## 9. 現有限制與操作邊界

- 沒有 GUI、matchmaking、中途加入中的 session、跨伺服器路由或方塊保護。
- `solo-cap`／`party-cap` 尚未被核心執行；入口插件要自行擋人數、重複進場與同一 UUID 的雙人邀請。
- `DungeonAccess.enter*` 會直接讀玩家位置保存返回點。請從發起玩家的 EntityScheduler／玩家事件執行緒呼叫；雙人跨 region 入口目前沒有「任意執行緒都安全」的保證。
- `DungeonBehaviorRegistry` 會靜默覆蓋同 id，沒有 unregister，也不是 thread-safe registry。只在受控的 enable 階段註冊；更新內容時重啟 HanaToki 與內容插件。
- `onCheckOutcome`、`onEncounterCleared`、`onEncounterFailed` 目前是尚未接線的 callback。不要把它們寫進玩法後期待會觸發。
- `RewardSink` 是 fire-and-forget。HanaToki 不知道內容插件是否真的完成資料庫寫入。
- return point、session、diff log 與 pending reward 都只在記憶體。伺服器重啟後，返回點只能退回玩家重生點或第一個非副本世界出生點。
- diff recorder 只看得到 `StageContext.mutate`。玩家破壞／放置與直接 Bukkit block mutation 不會自動回滾，必須另外阻擋或記錄。
- grace 內重連會把玩家傳到 raw slot anchor，不是 `spawn-offset-*` 算出的首次進場點。anchor 若被場景佔用，內容設計必須先留安全空間。
- `ActorHandle.locationOf` 的型別目前宣告為非 null future，但 actor 不存在時實作會用 `null` 完成；呼叫端要以 nullable-in-practice 處理。
- actor／prop 某些無效輸入只會記 log 並 no-op，future 不一定 exceptional completion。整合測試要看結果與 log，不能只看 future 完成。
- `world-create: true`、生成器與 world auto-save 都會影響世界資料。正式服改動前先備份並在同核心的測試環境驗證。
- 已經被其他插件或伺服器設定預先載入的世界會繞過 HanaToki 的 generator／auto-save 套用；啟動順序本身是設定的一部分。

## 10. 更新與回滾

HanaToki 的跨插件介面包含 `DungeonBehavior`、`StageContext`、actor／prop handle 與多個 ServicesManager service。介面方法一旦增刪，內容插件要一起重新編譯；不要只換單邊 jar 猜二進位相容性。

建議更新順序：

1. 備份舊 HanaToki jar、內容插件 jar，以及受影響的副本世界。
2. 用新版本重新建置 HanaToki 與所有直接編譯依賴它的內容插件。
3. 在隔離的同版本 Folia／Lecithin 測試服跑進場、互動、結算、離場、回滾與重連。
4. 關閉正式服，整批替換相依 jar，再啟動。
5. 檢查啟動 log、`/hanatoki admin debug`，最後走一次玩家入口。

需要回滾時，關服後把整組相依 jar 一起換回原版本；若新版已建立或改動世界資料，不要直接刪除，先用備份與實際世界狀態判斷。
