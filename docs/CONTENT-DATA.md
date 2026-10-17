# 資料驅動副本內容（1.0.0-rc.18）

內容插件只給 **一個資料夾 + texts**，行為由框架內建的 `DataDrivenBehavior` 依資料執行，內容端不寫 Kotlin。另做一座副本只換設定檔與資產。判準：加第 N+1 盞燈籠、第 N+1 種幽靈、第 N+1 段提示，只改一筆 YAML。

- 數值、清單、狀態對應、回饋 cue、提示時間、波次人數都放資料；程式只實作「規則長什麼樣」。
- 載入器在註冊時驗證，**任何錯誤整份拒絕**（future 以 `ContentDataException` 失敗，不半載入）。錯誤格式 `檔名 路徑: 訊息`，路徑含 ID 與欄位，例如 `objects.yml objects.lantern_a.burn-seconds: 必須 > 0.0（目前 -1.0）`；一次列出全部錯誤。
- 新能力預設零影響：沒有呼叫 `registerDataContent` 的副本（刀塚、蒼櫻、潘朵拉…）完全不經過這套程式。
- cues／vfx／dialog **不在 HanaToki 載入**，檔案只用 ID 引用，播放走 `CuePort`／`DialogPort`（見下）。

## 註冊用法

```kotlin
// 內容插件 onEnable（depend: HanaToki）。dataDir 內放 dungeons.yml、七個 YAML、map-assets/
val engine = (server.pluginManager.getPlugin("HanaToki") as HanaTokiPlugin).core
engine.registerDataContent(this, File(dataFolder, "content"), texts)   // texts: Map<String, String>
    .whenComplete { handle, error -> /* handle.closeAsync() 在 onDisable，同 registerContent */ }
```

`registerDataContent(owner: Plugin, dataDir: File, textEntries: Map<String, String>): CompletableFuture<ContentRegistration>`（`com.tinyyana.hanatoki.HanaTokiCore`）。檔案 IO 與驗證在呼叫端執行緒（插件 onEnable）做完，通過後交給既有的 `registerContent`（drain、熱插拔語意相同）。

資料夾：

```
content/
  dungeons.yml       引擎副本定義（正好一座；id = stages.yml 的 dungeon）
  stages.yml  scene.yml                      必要
  objects.yml  enemies.yml  encounters.yml  tool.yml  hints.yml   可省略（= 空）
  map-assets/<namespace>/structure/<path>.nbt   資料夾沒有的資產退回內容插件 jar 內同路徑的 map-assets/
```

`dungeons.yml` 必須：`stages.start/list` 與 stages.yml 一致（引擎沒有 stage 圖就不執行任何 stage）；`spawn-offset-x/y/z` 的整數部分等於 `player_spawn id=<scene.entry>` 相對 slot anchor 的位置（引擎用它傳送，對不上註冊時拒絕，不會讓玩家掉進虛空）；有 `tool.yml` 就必須 `instance-inventory: { enabled: true, loadout: [] }`（局內道具要蓋 instance 章）。

## ID 規則

所有 YAML key 與跨檔引用的 ID 只能用 `[a-z0-9_-]`，**不可含 `.`**（Bukkit 會把它拆成路徑），載入器直接讀 SnakeYAML 原始 key 擋下。marker 的 `id=` 同規則，且整張圖必須唯一。跨檔引用一律用 ID，不寫座標。未知欄位名（拼錯）一律報錯。重複的 YAML key 報錯。

## stages.yml

```yaml
dungeon: mist-patrol          # 必填，須等於 dungeons.yml 的 id
namespace: mistpage           # 必填，cue／dialog 註冊的命名空間（傳給 CuePort／DialogPort）
stages:                       # 宣告順序；第一個 = 起始 stage
  entry_court:
    on-start:                 # 全部可省略
      cues: [intro_cue]       # cue ID，原點 = entry marker
      music: mist.music.explore   # 既有 MusicCue port 的 id（不是 cue ID，可含點）
      dialog: mist_opening    # dialog ID，對每位成員開（DialogPort）
      title: key.title        # texts key（可含點），subtitle 同；參數 {stage_index} {stage_total}
      subtitle: key.subtitle
    complete:                 # 全部成立才完成（AND）
      - { type: encounter-cleared, encounter: intro }
      - { type: object-state, object: lantern_a, state: lit }
      - { type: reach, marker: candle_path, radius: 3 }   # 任一成員在 trigger marker 半徑內
    on-complete: { cues: [], title: ..., subtitle: ... }
    next: lantern_court       # 省略 = 最後一段：完成後播 on-complete、打 `slice end` log，不結算、不發獎
```

stage 鏈不可繞圈，每個 stage 都要沿 `next` 走得到；有 `next` 的 stage 必須寫 `complete`。

## scene.yml

```yaml
entry: entry                  # player_spawn marker 的 id；進場落點
pieces:                       # 一塊 piece 一筆；key = piece ID
  entry_court:   { asset: "mistpage:entry_court",   origin: [0, 0, 0] }     # origin 相對 slot anchor
  lantern_court: { asset: "mistpage:lantern_court", origin: [48, 0, 0], rotation: 90 }   # 0/90/180/270 順時針
```

每局 `ctx.maps().place(layout)` 放整張圖、局結束由引擎回收（`prepareStage` 失敗 = 這局進不了，進場整筆回滾）。marker 以 `properties["id"]`（`MapMarker.id`）查。**DATA 結構方塊放置後是空氣**（`StructureTemplate.parse` 把 data 模式 structure block 換成 air，`MapAssetLayerTest` 有斷言）。註冊時載入所有 piece 並核對：marker ID 唯一、entry 存在、物件／波次／提示引用的 marker 都在。

## objects.yml

共通：`type`（`lantern`｜`gate`）、`marker`（marker ID）、`stage`（可省略；填了只在該 stage 進行中可互動）、`states`。`states.<狀態>` = `{ cues: [...], blocks: [{ offset: [dx,dy,dz], block: "minecraft:..." }] }`，blocks 座標相對 marker，進入該狀態時經 `ctx.mutate` 替換（局結束回滾）。`unlit`／`closed` 的 blocks 在場景放好後套用。

**lantern**（marker 必須是 `trigger`）：狀態 `unlit → burning → lit`，單向、不倒退、只完成一次。

| 欄位 | 語意 |
|---|---|
| `burn-seconds`、`stay-radius` | 必填。燒滿所需秒數；只有半徑內有人才累積，無人時暫停 |
| `speed-by-players` | `{人數: 倍率}`，n 人用 ≤ n 的最大 key，沒寫 = 1.0 |
| `hold-cue` | burning 期間持續的 cue（CueRun 保留，暫停／完成時 cancel，恢復時重播） |
| `repeat-cue` | `{ cue, every-seconds }` 燒燈中重播的短音 |
| `milestones` | `[{ at: 0.33, cues, blocks }]` 進度到幾分之幾時 |
| `pause-cues`／`resume-cues` | 無人暫停／有人回來 |
| `on-lit` | 必填。`cues`、`light-radius`（0 = 不放光）、`light-level`（1–15，預設 12）、`light-spacing`（預設 4，光源方塊格距）、`clears-enemies`（光圈內敵人打散、不補燈；這個 stage 還沒開始的波次取消）、`opens`（gate 物件 ID） |
| `progress-bar` | `{ text: texts key（含 {percent}）, color }`，燒燈時的 boss bar |

**gate**（marker 必須是 `door`，要有水平 `facing` 與 `w`／`h`）：狀態 `closed → open`。`facing` = 門面朝的方向；站在門前（facing 那一側）看過去，marker 在**左下角**，寬往右延伸（右手邊 = facing 逆時針轉 90 度）。`column-ticks`（預設 4）每隔幾 tick 把一欄（h 格高）換成空氣做滑開；`slide-from`：`left`（從 marker 端）｜`right`。

## enemies.yml

```yaml
enemies:
  small_ghost:
    base-entity: ZOMBIE       # EntityType 常數名，必須是 Mob（會追人）；載體隱形，傷害由牠的近戰屬性造成
    model: mist_ghost         # BetterModel 模型 ID；省略 = 不綁模型；綁定失敗時載體保持可見並 WARN
    animations: { idle: idle, stunned: stunned, dissolve: dissolve }   # 預設就是這三個名字
    rules: [needs-flash]      # 規則標籤
    health: 6
    speed: 0.16               # 移動速度屬性基礎值（選填）
    damage: 1.0               # 近戰傷害（選填）
    scale: 0.7                # 縮放（選填）
    by-players: { 2: { health: 1.3, speed: 1.0, damage: 1.0 } }   # 各人數倍率，沒寫 = 1.0
    spawn-cues: [ghost_appear]
    needs-flash:              # rules 含 needs-flash 時必填
      whiff-cue: whiff        # 未僵直被打：刀穿過
      stun-cue: ghost_stun
      dissolve-cue: ghost_dissolve
      refill-cue: page_refill # 擊殺者補燈火時只播給擊殺者
      dissolve-ticks: 12      # 打散到實體移除（要蓋住模型 dissolve 動畫）
      whiff-cooldown-ticks: 6
      glow-on-stun: true
```

規則標籤：**已實作** `needs-flash`；**保留**（接受、不實作、註冊時 WARN）`frozen-when-watched`、`flees-when-powered`；其他標籤報錯。needs-flash 行為：所有傷害一律取消（牠不會被打死，只會被打散；虛空與 `/kill` 放行）。未僵直時玩家攻擊 → 取消、播 whiff cue、觸發 `first-whiff` 提示。被閃光命中 → 僵直 `stun-seconds`（tool.yml）：停 AI、發光、模型播 `stunned`；僵直中受擊 → 打散（`dissolve` 動畫、cue、延遲移除），擊殺者補燈火（tool.yml `refill-on-dissolve`）。載體是原版怪物（有 `model` 時隱形），自動免疫日曬與火。

## encounters.yml

```yaml
encounters:
  wave1:
    stage: lantern_court               # 所屬 stage（只有該 stage 進行中才會被觸發）
    enemy: small_ghost
    marker-group: wave1                # mob_spawn marker 的 group=；依 marker id 排序輪流使用
    trigger: { type: object-state, object: lantern_a, state: burning }   # 或 stage-start、encounter-cleared
    delay-ticks: 0
    counts: { 1: 3, 2: 4, 4: 5 }       # 人數 → 隻數（必含 1；n 人用 ≤ n 的最大 key）
    interval-ticks: 20                 # 每隻之間
    start-cues: [wave_start]
```

全部生出且全數消失（打散或死亡）→ 波次清空，打 `wave-clear` log，並可成為其他波次的 `encounter-cleared` 觸發與 stage 完成條件。

## tool.yml

```yaml
tools:
  night_lantern:
    type: flash                # 目前只有 flash
    material: LANTERN          # 物品材質（執行期解析）
    item-model: "lycohinya:night_lantern"   # 選填
    name: tool.night-lantern.name           # texts key
    lore: [tool.night-lantern.lore]
    slot: 0                    # 熱鍵列 0–8
    charges: 3                 # 格數上限
    start-charges: 3
    cone-angle: 50             # 錐形全角（度）
    range: 8
    stun-seconds: 3
    cooldown-ticks: 14
    refill-on-dissolve: 1      # 打散敵人給擊殺者補幾格
    cues: { flash: flash, empty: flash_empty }   # 純量或清單
    hud-text: tool.night-lantern.hud    # 選填；ActionBar 提示 {charges} {max}
```

進場（`onMemberReady`）發給每位成員，經 `InstanceItems.mark` 蓋 instance 章（局內物，離場帶不出去）；不能放置；PDC `hanatoki:tool_id` 辨識。燈火數量顯示在物品**耐久條**（最大耐久 = 格數），另可用 hud-text。只認主手事件（主副手事件去重）；右鍵一律拒絕原版互動。閃光：事件當下取持有者眼睛位置與視線快照 → 一次判定錐形內的 needs-flash 敵人（實體位置經 `ActorHandle.locationOf` 取，不跨 region 讀）與 `unlit` 燈籠（→ burning，點燃目標取 marker 上方 0.5 與 2.5 格兩點）。沒燈火 → 播 `empty` cue 不扣；有冷卻。

## hints.yml

```yaml
hints:
  hint_flash_first: { trigger: { type: first-whiff }, cues: [hint_flash_first] }
  hint_idle:        { trigger: { type: no-progress, seconds: 20 }, cues: [hint_idle] }
  hint_point:       { trigger: { type: near-marker, marker: lantern_a, radius: 12 }, stage: lantern_court, cues: [hint_lantern] }
```

`once`（預設 true）；`once: false` 時以 `repeat-seconds`（預設 30）限頻。`stage` 可省略。「進展」= stage 開始、敵人打散、物件換狀態、柵門開、閃光命中、到達 marker。

## Port（`com.tinyyana.hanatoki.api.port`）

HanaToki 只定義介面，實作者（LycoHanaToki → LycoLib）經 Bukkit ServicesManager 註冊；**每次使用都查**（熱插拔後不抓過期實作）。沒有實作者 → no-op，每局每個 port 打一行 INFO（`port-missing`），副本照常跑。簽章只用 JDK／Bukkit 型別，回呼用 `IntConsumer`／`Runnable`（Kotlin 函式型別跨 classloader 會 `LinkageError`）。

```kotlin
fun interface StageWorldMutator { fun setBlock(location: Location, data: BlockData) }  // HanaToki 實作：ctx.mutate，局結束回滾；局結束後 no-op
fun interface CueRun { fun cancel() }
interface CuePort {
    fun play(namespace: String, cueId: String, origin: Location, audience: Collection<Player>,
             follow: Entity?, mutator: StageWorldMutator?): CueRun
}
interface DialogPort {
    fun open(player: Player, namespace: String, dialogId: String, onDone: IntConsumer)   // 1..N 選項；0 = 取消／斷線／無選項；可能在任意執行緒回呼
}
```

HanaToki 追蹤每局播出的所有 `CueRun`（不設上限），局結束全部 `cancel()`；燈籠的持續 cue 另外持有，暫停／完成時 cancel。cue 播放失敗只記 WARN，不影響狀態機。`audience` 是副本成員的 `Player` 參考，HanaToki 不碰它們；實作端自行派到各玩家的 EntityScheduler。

## 證據 log

每次狀態轉移一行 INFO：`[HanaToki/<dungeon>] <事件> <id…> slot=<slotId>`。事件：`scene-placed`、`stage-start <stage>`、`stage-complete <stage>`、`slice end <stage>`、`object <id> <from>-><to>`（含 `burning paused/resumed`、`milestone`）、`gate-open <id>`、`light`、`wave-start <id> count=N players=N`、`wave-clear <id>`、`wave-cancel <id>`、`enemy-spawn`、`enemy-stun`、`enemy-recover`、`enemy-dissolve <actor> by=<玩家|lantern>`、`enemy-gone`、`enemies-cleared`、`whiff`、`flash`、`flash-hit`、`flash-empty`、`refill`、`tool-give`、`hint <id>`、`reach <marker>`、`dialog <id> player= choice=`、`port-missing`、`session-end`。

## 執行緒

世界與實體操作一律經 `StageContext`／`ActorHandle`（各自派工到擁有者 region／EntityScheduler），callback 內不直接 setBlock／spawnEntity。外部事件（右鍵、受擊）在事發執行緒做**同步判定**（取消與否，只讀 volatile 的敵人狀態），其餘先 `ctx.submit` 回到 session 的 anchor region 才動狀態。計時用 `SessionRun` 自己的 `TimerQueue`（由每 2 tick 的週期任務推進）：`StageContext.transition()` 會取消 `submitLater`／`submitRepeating` 全部排程，柵門滑開、打散延遲移除不能被轉場砍掉；週期任務本身在每個 stage 進場時重新掛上。

## 範圍與已知限制

- 第一段切片只做敵人規則 `needs-flash`、物件 `lantern`／`gate`、局內道具 `flash`。人偶、Boss、南瓜燈、結算、常駐場景都不在內。最後一段完成只播 cue 與打 `slice end`，不 `resolve`、不發獎；stage 逾時不結算。
- 閃光不做視線遮擋判定（隔著牆的幽靈會被照到）。
- 場景每局重放（`maps().place`）；常駐模式未做。
- 光源方塊每格一次 `ctx.mutate`（上限約 2500 格由載入器檢查），只放在空氣格。
- 重連（寬限期內）落點 = 進場落點（anchor + `spawn-offset`），不是 anchor 本身；資料驅動副本的 anchor 在場景地表之下。
