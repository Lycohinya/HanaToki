# 資料驅動副本內容（1.0.0-rc.28）

內容插件只給 **一個資料夾 + texts**，行為由框架內建的 `DataDrivenBehavior` 依資料執行，內容端不寫 Kotlin。另做一座副本只換設定檔與資產。判準：加第 N+1 盞燈籠、第 N+1 種幽靈、第 N+1 段提示，只改一筆 YAML。

- 數值、清單、狀態對應、回饋 cue、提示時間、波次人數都放資料；程式只實作「規則長什麼樣」。
- 載入器在註冊時驗證，**任何錯誤整份拒絕**（future 以 `ContentDataException` 失敗，不半載入）。錯誤格式 `檔名 路徑: 訊息`，路徑含 ID 與欄位，例如 `objects.yml objects.lantern_a.burn-seconds: 必須 > 0.0（目前 -1.0）`；一次列出全部錯誤。
- 新能力預設零影響：沒有呼叫 `registerDataContent` 的副本（刀塚、蒼櫻、潘朵拉…）完全不經過這套程式。
- cues／vfx／dialog **不在 HanaToki 載入**，檔案只用 ID 引用，播放走 `CuePort`／`DialogPort`（見下）。

## 註冊用法

```kotlin
// 內容插件 onEnable（depend: HanaToki）。dataDir 內放 dungeons.yml、八個 YAML（bosses.yml 可省略）、map-assets/
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
  objects.yml  enemies.yml  encounters.yml  tool.yml  hints.yml  bosses.yml  chat.yml（rc.26）  可省略（= 空）
  map-assets/<namespace>/structure/<path>.nbt   資料夾沒有的資產退回內容插件 jar 內同路徑的 map-assets/
```

`instance-inventory.loadout[]` 可寫 `lore: ["<MiniMessage>", …]`（rc.20，每行一筆，預設正體不斜體），讓局內起始物品（例如短刀）自己說明用法。

`dungeons.yml` 必須：`stages.start/list` 與 stages.yml 一致（引擎沒有 stage 圖就不執行任何 stage）；`spawn-offset-x/y/z` 的整數部分等於 `player_spawn id=<scene.entry>` 相對 slot anchor 的位置（引擎用它傳送，對不上註冊時拒絕，不會讓玩家掉進虛空）；可選 `world-time`（0..23999；18000 = 午夜，rc.19）：副本世界固定在這個一天內時刻，沒寫就維持原狀；`world-create: false` 或有 `world-generator` 時報錯。有 `tool.yml` 就必須 `instance-inventory: { enabled: true, loadout: [] }`（局內道具要蓋 instance 章）。

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
      - { type: boss-defeated, boss: nun }                # rc.23:bosses.yml 的 boss 已被打倒（log `boss-defeated nun`）
    on-complete: { cues: [], title: ..., subtitle: ..., opens: [gate_front] }   # opens(rc.24):這個 stage 完成時打開的 gate 物件
    next: lantern_court       # 省略 = 最後一段：完成後播 on-complete、打 `slice end` log，不結算、不發獎（除非寫了 resolve，見下）
    clears-enemies: false     # 選填（rc.22，預設 false）：完成時場上剩下的敵人全部打散（不補燈火）
```

stage 鏈不可繞圈，每個 stage 都要沿 `next` 走得到；有 `next` 的 stage 必須寫 `complete`。

**`on-complete.opens`(rc.24，選填，預設空)**:gate 物件 ID 清單(單一 ID 可寫純量)。stage 完成、播完 `on-complete` 的 cue／title／對話後、轉場之前，依序開這些門(同柵門被燈籠 `on-lit.opens` 開啟的動畫與 log `gate-open`；已開的門不重複開)。計時用 session 的 TimerQueue，滑門不會被轉場砍掉。**只有 `on-complete` 可寫**(`on-start`、boss 階段寫了報「未知的欄位」)；載入時核對每個 ID 都是 objects.yml 的 gate。用法:完成條件是 `reach`(走到出口)而不必再放一盞「開門用的燈」。

### 抵達區 arrive／on-arrive（rc.26，選填，預設關）

stage 一開始（上一段完成、門開）玩家常常還在上一區，這時就播開場對話、生敵人、讓 boss 出場，玩家根本還不知道那一區存在。`arrive` 讓這些事等「任一成員真的走到那一區」才發生。

```yaml
  doll_corridor:
    arrive: { marker: gate_corridor, radius: 3, offset: [6, 0, 1] }   # marker 加世界方塊偏移為球心，半徑 radius（0..64）；marker 不限 trigger 種類
    on-start: { cues: [doll_music] }                                  # 照舊：stage 一開始就播
    on-arrive:                                                        # 抵達時播（與 on-start 同欄位，另可寫 dialogs）
      cues: [familiar_chime]
      dialog: corridor_intro                                          # 對每位成員開，不等讀完（同 on-start）
      dialogs: [roof_01, roof_02]                                     # 選填：依序一段一段播，每段等全部成員讀完（離場的人不算）才播下一段，全播完才算「對話結束」
      dialog-timeout-seconds: 120                                     # 每段等全部成員的上限（1..600，預設 120），逾時就放行
      dialog-delay-seconds: 0                                         # rc.28：`dialog`（單段）延遲幾秒才開（0..30，預設 0），讓敵人先現身
    objective:
      - { text: goal.go, until: { type: arrived } }                   # 抵達前顯示「去那裡」
      - { text: goal.flash, until: { type: object-state, object: lantern_b, state: burning } }
```

- 偵測：每 10 tick 一次，任一在線成員在球內就抵達（每個 stage 一次，換 stage 重設）。記 log `stage-arrive <stage>`，並重置無進展計時。球要放在「不可能繞過」的位置（走廊口、門後），否則有人從旁邊繞過去就永遠不會觸發。
- 抵達當下依序：播 `on-arrive`（cues／music／dialog／title）→ 開 `dialogs` 序列（log `arrive-dialogs-start`／`arrive-dialogs-done`／`arrive-dialog-timeout`）→ 這個 stage 的 boss 出場 → 檢查等抵達的波次。
- **波次觸發 `{ type: stage-arrive }`**：所屬 stage 抵達時才開始計 `delay-ticks`。所屬 stage 沒寫 arrive 載入時擋下（請用 `stage-start`）。
- **objective 條件 `{ type: arrived }`**（只能寫在 objective 的 until／while）：stage 有 arrive 且已抵達。沒寫 arrive 的 stage 用它載入時擋下。
- **hint `requires-arrival: true`**：那個 stage 抵達前不播（`no-progress` 提示常用，免得玩家還在上一區就被講規則）。需要 `stage` 且該 stage 有 arrive。
- **boss**：stage 有 arrive 時，boss 不在 stage 開始時出場，改成抵達時才出場（出場演出、start-delay 都從抵達算）。boss `intro.await-arrive-dialogs: true`（需要 stage 的 `on-arrive.dialogs`）：出場後停在 intro marker 不下來，等對話序列播完（或逾時）才開始計 `hold-seconds` 與 `start-delay-seconds`（log `boss-intro-release`）。hold + glide 仍不可超過 start-delay。
- 沒寫 `arrive` 的 stage 行為與 rc.25 完全相同；`on-arrive` 沒有 `arrive` 載入時擋下；`on-arrive` 不能寫 `opens`。

### stage 層級的道具覆寫 tool-overrides（rc.28，選填）

`tool.yml` 的道具數值是全域的；某一段想要不同的回補速度或格數（例如 Boss 戰要常常閃光），在那個 stage 寫 `tool-overrides`，只影響這一段：

```yaml
  front_court:
    tool-overrides:
      night_lantern:                  # tool.yml 的 tool id，必須存在
        charges: 8                    # 格數上限（耐久條上限跟著換）
        passive-regen-seconds: 2.5    # 被動回補間隔
        zone-regen-seconds: 1.5       # 光源區回補間隔（道具必須有 zone-regen）
        refill-on-dissolve: 2         # 打散敵人補幾格
        fill-on-start: true           # 進這個 stage 時把在線成員補到新的上限（預設 false，維持目前格數）
```

沒寫的欄位沿用 tool.yml，至少要覆寫一個欄位。進 stage 時重算生效數值並重開回補計時，log `tool-override stage= tool= max= passive= zone= refill=`。tool.yml 另有 `free-on-burning-lamp: true`（rc.28）：閃光碰到「正在燒」的燈籠、又沒點到未點燃的燈籠時不扣格（log `flash-keep`），敵人照常判定。boss `heal` 另有 `tools: true`：heal 觸發時（on-start／on-kneel／全滅重來）把在線成員的道具補滿（log `boss-refill-tools`）。

### 最後一段結算 resolve（rc.22，選填，預設關）

最後一段（沒有 `next`）完成後，先依序播尾聲，播完才 `resolve(result)` 走 `HanaTokiCore.resolveSession`，讓 LycoHanaToki 的獎勵規則（以 `result` 為 key）觸發。沒有這個區塊的 stage 行為和舊版完全一樣（只打 `slice end`）。

```yaml
  front_court:
    complete: [ { type: reach, marker: front_door_exit, radius: 3 } ]   # 有 resolve 就必須寫 complete
    on-complete: { cues: [boss_down] }
    resolve:                       # 或 `resolve: true`（result 預設 cleared、沒有尾聲）
      result: cleared              # 預設 cleared；CompletionResult.resultKey
      dialog-timeout-seconds: 120  # 預設 120（1..600）：對話步驟等全部成員選完的上限，逾時就繼續
      epilogue:                    # 選填，依序；一步 = cue 或 dialog 擇一，或只有 wait-seconds
        - { cue: epilogue_lights, wait-seconds: 2 }      # cue 沒有「播完」回呼，靠 wait-seconds 等（0..300，預設 0）
        - { dialog: epilogue_talk }                      # 對每位成員開，等全部選完（離場的人不算），再等 wait-seconds
        - { cue: epilogue_end, marker: entry, wait-seconds: 3 }   # marker：cue 原點（預設進場 marker）
```

只能用在 `next` 為空的 stage；順序是 `on-complete`、`clears-enemies`、尾聲、resolve（同一段只結算一次，session 隨後結束）。stage 逾時仍不結算。尾聲播放中成員離場不會卡住：對話名單移除離場者，逾時照放行。

### 目前目標 objective（rc.20，選填）

玩家要知道「現在該做什麼」。每個 stage 可以宣告一行 ActionBar 目標文字（走 `ActionBars`／LycoLib HudBridge 的 `hanatoki.stage` 組，不碰 BetterHud 字型）：

```yaml
  lantern_court:
    objective-every-seconds: 1        # 選填 0.5–600；重畫間隔。0／省略 = 只在進 stage、步驟換了、燈火變了時畫
    objective:                        # 單一 texts key，或依序的步驟清單
      - { text: mist-patrol.goal.lantern-flash, until: { type: object-state, object: lantern_a, state: burning } }
      - { text: mist-patrol.goal.lantern-hold,  until: { type: object-state, object: lantern_a, state: lit } }
    objective-done: mist-patrol.goal.done   # 選填；所有 until 都達成後顯示
```

- 顯示「第一個 until 還沒達成」的步驟；`until` 沿用 `complete` 的條件（`encounter-cleared`／`object-state`／`reach`）。沒寫 `until` 的步驟永遠停在那一步，只能放最後一個。`reach` 只有寫在**同一個 stage 的 `complete`** 裡的 marker 才偵測得到，其他的載入時拒絕。
- **累積旗標條件（rc.25）**，同樣只能寫在 objective 的 `until`／`while`：`{ type: powered-used }`（這個 stage 開始後任一成員吃過 power-up，powered 結束後仍成立；需要至少一個 power-up 物件）、`{ type: enemy-dispersed, enemy: <enemies.yml id> }`（這個 stage 開始後該種敵人被玩家打散，或被 powered 的成員碰散過；光圈／stage 清場不算）。換 stage 兩個旗標都清掉。三步目標範例（南瓜迷宮）：

```yaml
    objective:
      - { text: goal.find, until: { type: powered-used } }                                              # 還沒吃到南瓜燈:去找
      - { text: goal.powered, while: { type: powered }, until: { type: enemy-dispersed, enemy: big_ghost } }  # powered 中、她還沒被碰散:追她
      - { text: goal.exit }                                                                               # 碰散她後,或 powered 結束她沒被碰散:去出口
```
- **`while`(rc.24，選填)**:步驟加 `while: {條件}`，只在條件成立期間才輪得到這一步，不成立就跳過、回來又顯示(跟 `until` 並用時兩者都要符合:`while` 成立且 `until` 還沒達成)。有 `while` 的步驟不受「沒寫 until 只能放最後」限制。`while`／`until` 另可用兩個**狀態型條件**(會來去，只能寫在 objective，不能當 stage 的 `complete`，載入時拒絕):`{ type: powered }`(任一成員目前 powered，需要至少一個 power-up 物件)、`{ type: boss-kneel, boss: <id> }`(該 boss 目前正在跪下，boss 必須在這個 stage)。重畫由 `objective-every-seconds` 與「顯示的步驟換了」觸發，所以進出 powered／跪下的瞬間就會換字。

```yaml
    objective:
      - { text: goal.strike, while: { type: boss-kneel, boss: nun } }    # 她跪下期間:上前揮刀
      - { text: goal.lamps, until: { type: boss-defeated, boss: nun } }  # 其餘時間:點亮場邊燈
```
- **rc.26 條件**（同樣只能寫在 objective）：`{ type: arrived }`（見上節）、`{ type: lantern-burning }`（這個 stage 有任何燈籠正在燃燒，含暫停；stage 要有 lantern 物件）。文字參數另有 `{burn}`：這個 stage 進度最高的燃燒中燈籠的五格圓點（`●●●○○`），沒有燈在燒是空字串；搭配 `while: lantern-burning` 的步驟，玩家一照燈就看到進度。
- **方位佔位符 `{nearest:power-up}`（rc.27）**：這位成員面向看，最近一顆 ready 的 power-up（所屬 stage 進行中）在哪個方位、多遠，例如「左前方 12 格」，每次重畫目標列更新（成員位置取自每 10 tick 的成員快照，文字含 `{nearest:` 時自動啟用）。方位八向（前／右前／右／右後／後／左後／左／左前，依玩家 yaw），方位字與格式取自內容 texts：`<dungeonId>.direction.<front|front-right|right|back-right|back|back-left|left|front-left>` 與 `<dungeonId>.direction.format`（含 `{dir}` `{distance}`）；缺字時只顯示距離數字，沒有成員快照或沒有 ready 的 power-up 是空字串。
- 文字是 texts key（同 `title`），參數 `{charges}` `{max}` `{pips}`（`●●○○○` 燈火條）取 tool.yml 第一個道具、每位玩家各自的值。燈火條要一直看得見，就用 `objective-every-seconds: 1`，不再另用 tool 的 `hud-text`（同一個 ActionBar 組，後送的蓋掉先送的）。
- 沒寫 `objective` 的 stage（以及整個舊副本）完全不經過這段。

## scene.yml

```yaml
entry: entry                  # player_spawn marker 的 id；進場落點
pieces:                       # 一塊 piece 一筆；key = piece ID
  entry_court:   { asset: "mistpage:entry_court",   origin: [0, 0, 0] }     # origin 相對 slot anchor
  lantern_court: { asset: "mistpage:lantern_court", origin: [48, 0, 0], rotation: 90 }   # 0/90/180/270 順時針
```

每局 `ctx.maps().place(layout)` 放整張圖、局結束由引擎回收（`prepareStage` 失敗 = 這局進不了，進場整筆回滾）。marker 以 `properties["id"]`（`MapMarker.id`）查。**DATA 結構方塊放置後是空氣**（`StructureTemplate.parse` 把 data 模式 structure block 換成 air，`MapAssetLayerTest` 有斷言）。註冊時載入所有 piece 並核對：marker ID 唯一、entry 存在、物件／波次／提示引用的 marker 都在。

## objects.yml

共通：`type`（`lantern`｜`gate`｜`power-up`）、`marker`（marker ID）、`stage`（可省略；填了只在該 stage 進行中可互動）、`states`。`states.<狀態>` = `{ cues: [...], blocks: [{ offset: [dx,dy,dz], block: "minecraft:..." }] }`，blocks 座標相對 marker，進入該狀態時經 `ctx.mutate` 替換（局結束回滾）。`unlit`／`closed` 的 blocks 在場景放好後套用。

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
| `ignite-radius` | 選填（rc.20，預設 0 = 只認錐形）。閃光時持有者離燈籠 ≤ 這個距離就點燃，**不必瞄準**；更遠的仍可用錐形（`range`）點燃。建議 ≤ `stay-radius`，點燃的位置本來就在守燈範圍內 |
| `hint` | 選填（rc.20）。未點亮燈籠的世界標示：`{ cue, ready-cue, radius, ready-radius, requires-tool }`。玩家距離 ≤ `radius` 播 `cue`、≤ `ready-radius` 換成 `ready-cue`（「現在閃一下一定點得著」）；`requires-tool` 填 tool ID = 只有主手拿著那個道具才顯示。**只放給該玩家自己**（audience = 他一人），cue 要寫成 loop 光效（LycoLib `vfx` 的 `loop: true`）；點燃、換 stage、離開範圍、離場都會取消。位置與主手格取自成員快照（每 10 tick 一次，不跨 region 讀玩家座標），邊界留 1.5 格遲滯 |

**gate**（marker 必須是 `door`，要有水平 `facing` 與 `w`／`h`）：狀態 `closed → open`。`facing` = 門面朝的方向；站在門前（facing 那一側）看過去，marker 在**左下角**，寬往右延伸（右手邊 = facing 逆時針轉 90 度）。`column-ticks`（預設 4）每隔幾 tick 把一欄（h 格高）換成空氣做滑開；`slide-from`：`left`（從 marker 端）｜`right`。

**power-up 的 `beacon-cue`（rc.25，選填）**：南瓜燈「在架上」（ready、所屬 stage 進行中）期間持續播的 **loop cue**（cue 引用 loop vfx），被拿走、換 stage、`pumpkins` 下架、局結束時取消，重生／上架時再播。讓玩家從遠處（越過樹籬）看到：用高過牆的細光柱＋頂端柔光球，亮度克制。同時最多 = 在架上的南瓜燈數 × 該 vfx 的 display 數，要算進 vfx 的 `max-total-displays`。

**power-up 的 `prop`（rc.27，選填）**：南瓜燈在架上期間，marker 上方浮著一個發光的實體（item display，由 `PropHandle.spawnPart` 生成，亮度固定偏亮），被拿走就移除、重生再出現（和 beacon-cue 同一套「在架上」判定）。

```yaml
    prop: { item: JACK_O_LANTERN, scale: 0.9, height: 1.1, bob: 0.3, bob-seconds: 3, spin-degrees: 90 }
    # item 必填，Material 名稱（執行期解析，解析失敗記警告、不生成）；scale 預設 1.0（1 = 一整格方塊）；height 預設 1.0（離 marker 腳下的高度）；
    # bob 預設 0.3 格、bob-seconds 預設 3（上下一個週期）、spin-degrees 預設 90（每半個週期慢轉幾度）
```

伺服器只在每半個週期送一次姿勢，客戶端自己補間成緩慢浮動。要讓周圍真的變亮，在 `states.ready.blocks` 放 `minecraft:light[level=15]`、`states.taken.blocks` 放空氣即可（資料驅動，框架不另放光源）。

**power-up**（rc.22，南瓜燈；marker 必須是 `trigger`）：成員走進 `radius` 內，**全隊** powered `seconds` 秒（見 flees-when-powered）。狀態 `ready ⇄ taken`：`states.<狀態>` 同其他物件（cues＋blocks，進入該狀態時播／換方塊；`ready` 的 blocks 在場景放好後套用）。

```yaml
objects:
  pumpkin_1:
    type: power-up
    marker: pumpkin_1
    stage: pumpkin_maze          # 選填；只在該 stage 進行中可拿
    radius: 1.5                  # 預設 1.5（0.5..8）：離 marker 多近算碰到
    seconds: 8                   # 必填（1..120）：全隊 powered 秒數（迷宮 8、Boss P3 6）
    consume: true                # 預設 true：拿走變 taken；false = 一直是 ready
    respawn-seconds: 30          # 預設 0（0..3600）：consume 時 > 0 才會重生；不消耗時是再次觸發前的冷卻（至少等 powered 結束）
    start-cues: [powered_music]  # powered 開始時在 marker 播；持續型 cue（音樂疊層）在 powered 結束時一併 cancel
    end-cues: [powered_end]      # powered 自然到期時在 marker 播
    states:
      taken: { cues: [pumpkin_pop], blocks: [ { offset: [0, 0, 0], block: "minecraft:air" } ] }
      ready: { blocks: [ { offset: [0, 0, 0], block: "minecraft:jack_o_lantern" } ] }
```

powered 是每位成員各自的到期時間（再吃一次把結束時間刷新成較晚的那個，不疊加）；成員離場即移除；**換 stage 一律結束 powered**（收持續 cue、敵人恢復追擊外觀）。偵測每 4 tick 一次（`membersWithin`，不跨 region 讀玩家座標）。stage 完成條件與目標的 `object-state` 可以引用 power-up 的 `ready`／`taken`。

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
      alive-cue: ghost_aura   # 選填（rc.20）：活著（可被照）時跟著敵人實體的持續 cue（loop 光效，follow: entity）
      stunned-cue: ghost_marked   # 選填：僵直中（現在可以打）換成這個，取代 alive-cue；打散或消失時拿掉
```

規則標籤（rc.22 起全部已實作，沒有保留標籤）：`needs-flash`、`seeks-object`、`harasses-players`、`frozen-when-watched`、`chases-players`、`flees-when-powered`；其他標籤報錯。needs-flash 行為：所有傷害一律取消（牠不會被打死，只會被打散；虛空與 `/kill` 放行）。未僵直時玩家攻擊 → 取消、播 whiff cue、觸發 `first-whiff` 提示。被閃光命中 → 僵直 `stun-seconds`（tool.yml）：停 AI、發光、模型播 `stunned`；僵直中受擊 → 打散（`dissolve` 動畫、cue、延遲移除），擊殺者補燈火（tool.yml `refill-on-dissolve`）。載體是原版怪物（有 `model` 時隱形），自動免疫日曬與火。

### 敵人規則 seeks-object／harasses-players（rc.21，選填）

兩者都是**加在 needs-flash 敵人上的行為**，沒有標籤的敵人（其他副本）完全不經過；規則標籤要有對應區塊，區塊也要有標籤，否則整份拒絕。分工建議用**兩種敵人定義**各自一個波次（「啃燈的」只朝燈、「搗蛋的」追玩家），不是同一隻身上疊兩種。

```yaml
    rules: [needs-flash, seeks-object]
    seeks-object:
      object: lantern_a            # 必填，必須是 lantern 物件
      states: [burning]            # 預設 [burning]；燈籠不在這些狀態時規則不動作
      repath-ticks: 10             # 4..100；多久補一次往燈的路，也是這一輪掃描的週期
      speed: 1.0                   # Pathfinder.moveTo 的速度倍率 0.1..3
      contest-radius: 2.5          # 離燈籠（marker，高度差 <= 3）多近算在啃
      drain-per-second: 0.45       # 每隻每秒扣幾個 burn-second 的進度（不受範圍內人數倍率影響）
      max-drainers: 3              # 同一種敵人同時有效的上限
      ignores-players: true        # true = 拿掉原版鎖定目標與移動目標 AI，不理玩家（玩家站燈旁也一樣啃燈）
      contest-cues: [lantern_gnawed]       # 有人在啃時每 contest-cue-every-seconds 在燈籠上播
      contest-cue-every-seconds: 1.0
    rules: [needs-flash, harasses-players]
    harasses-players:
      contact-radius: 1.8          # 碰到玩家的水平距離（高度差 <= 2）
      cooldown-ticks: 50           # 每位玩家各算，不分是哪隻敵人碰的
      scan-ticks: 10               # 4..100；掃描週期取所有規則敵人中最小的
      cues: [ghost_gust]           # 觸發時在敵人身上播
      suppress-melee: true         # 預設 false；true = 這種敵人的原版近戰（傷害與擊退）一律取消，只剩 effects，避免推擊與近戰疊加
      effects:                     # 至少一個，依序套用
        - { type: push, strength: 0.45, lift: 0.2 }          # 水平遠離敵人
        - { type: tool-charges, tool: night_lantern, delta: -1 }   # 扣（負）或補（正）燈火，夾在 0..max
        - { type: potion, effect: "minecraft:slowness", duration-ticks: 40, amplifier: 0 }
        - { type: damage, amount: 1.0, min-health: 6.0 }     # 玩家血量 <= min-health 時不扣（不致死）
```

啃燈語意（`LanternMachine.drain`）：只在 `burning` 生效（暫停中也扣，離開燈籠不能躲掉）；進度可退、**夾在 0**、不退回 `unlit`、不觸發完成，所以只有「站著撐住」而沒有打散她們就完成不了，但沒有失敗或倒數；燈亮後不再扣；milestone 只觸發一次（退回再升不重播）。**僵直中的敵人不啃、不碰人**——閃光把她們照住就是解法。啃燈速率每輪掃描發布，超過 60 tick 沒更新視為 0。

掃描：每 `scan-ticks` 一輪（單輪最多 24 隻，超過時跨輪輪替（round-robin，依 actorId 排序接著上一輪掃，每隻都會輪到；boss 本身不佔名額）、在 session 的 anchor region 執行緒用 `locationOf` 與 `memberSnapshots` 的結果一次處理完），全程走既有的 EntityScheduler／PlayerOp，沒有新增跨插件介面方法；來不及回來的一輪不會疊加，也不會覆蓋較新的結果。

### 敵人規則 frozen-when-watched／chases-players／flees-when-powered（rc.22，選填）

同樣「標籤與區塊成對」，三個區塊都可以寫空的 `{}` 用預設值。**移動規則**（`seeks-object`、`frozen-when-watched`、`chases-players`）每個敵人只能掛一個，這三種敵人的原版鎖定目標與移動目標 AI 在生成時就拿掉，只聽框架下的路徑指令（`harasses-players` 的接觸效果不受影響）。`flees-when-powered` 必須和 `chases-players` 一起用，且場上至少要有一個 `power-up` 物件。有 `needs-flash`、`frozen-when-watched`、`chases-players`、`flees-when-powered` 任一個的敵人**不吃一般傷害**（取消；只有 needs-flash 才會觸發打空／僵直／打散）。

**frozen-when-watched（人偶）**：任一成員的視錐內、距離內、（開啟遮擋時）視線沒被方塊擋住 = 被看著 → 停 AI 與路徑、換成隨機一個姿勢；沒人看 → 開 AI、朝最近的成員走。可以和 needs-flash 同掛：被閃光照到進僵直（`stunned` 動畫與 cue），僵直中不判視錐，打散後 `doll-dispersed`；僵直結束若仍被看著會留在凍結，不會動起來。

```yaml
    rules: [needs-flash, frozen-when-watched]
    frozen-when-watched:
      half-angle: 55           # 預設 55（5..90）：視錐半角（度），夾角用成員快照的 yaw／pitch
      max-distance: 24         # 預設 24（2..64）：超過這個距離不算被看到
      occlusion: true          # 預設 true：視錐內的候選再用 raytrace 方塊確認沒被擋住
      scan-ticks: 4            # 預設 4（2..100）：掃描週期；整局取所有規則的最小值
      speed: 1.0               # 預設 1.0（0.1..3）：沒人看時 Pathfinder.moveTo 的速度倍率
      body-height: 1.0         # 預設 1.0（0..4）：腳下往上多高算身體中心（視錐與遮擋的瞄準點）
      moving-animation: walk   # 選填；移動時的模型動畫，沒寫 = animations.idle
      poses: [tilt_head, reach, hug_knees, look_back]   # 預設就是這四種；清單（id = 模型動畫名）或 { id: 動畫名 } 區塊
      move-cues: [doll_click]  # 選填：從凍結轉為移動時播在人偶身上（follow 實體，聽得出方位）
      freeze-cues: [doll_stop] # 選填：從移動轉為凍結時播
      cue-cooldown-ticks: 6    # 預設 6（0..400）：move-cues 兩次之間的最短間隔，視線在邊緣來回掃時不洗聲
      harmless-while-frozen: false   # rc.28，預設 false：true = 被看著凍結期間接觸玩家不推、不扣燈火（只有移動中的才有接觸效果）
```

**rc.28 出生即凍結**：這種敵人一生出來就先是凍結的姿勢（原地不動、`setAI(false)`），第一輪掃描才決定放不放開——不再先走兩步才凍住。第一次判定若被看著，照常補記 `doll-frozen`／`doll-pose`。

掃描：視錐與距離在 anchor 執行緒用成員快照（`x,y,z,yaw,pitch,…,sneaking`；眼睛高度站 1.62、蹲 1.27）算；只有已在視錐內的候選才派 `rayTraceBlocks` 到**該敵人自己的 region**（`Bukkit.isOwnedByCurrentRegion` 確認射線經過的區塊都歸本 region，否則當作被看到並 WARN 一次），AI 開關與路徑指令在同一個回呼裡套用。單輪最多 24 隻（超過時輪替，見上）。姿勢只在「移動 → 凍結」那一刻換一次，不連續兩次同一個。

**chases-players（大幽靈）**：追一位成員；碰到的效果寫在內嵌的 `contact`（欄位與型別同 `harasses-players`，不能和 `harasses-players` 同時寫；冷卻獨立一份）。

```yaml
    rules: [chases-players, flees-when-powered]
    chases-players:
      speed: 1.1               # 預設 1.0（0.1..3）：Pathfinder.moveTo 速度倍率
      target: nearest          # 預設 nearest；nearest｜farthest｜lowest-health（只考慮 sight-range 內的成員）
      sight-range: 32          # 預設 32（2..128）：索敵距離
      give-up-range: 48        # 預設 sight-range 的 1.5 倍（>= sight-range）：已鎖定的目標跑出索敵距離但仍在這個距離內會繼續追，超過就停下
      start-delay-seconds: 0   # rc.26，預設 0（0..60）：出生後原地不動（不追也不碰人）幾秒才開始追；用來讓她先現身、說完開場再追
      repath-ticks: 10         # 預設 10（4..100）：多久重挑目標、補一次路
      contact:                 # 選填：接觸效果，同 harasses-players（contact-radius、cooldown-ticks、scan-ticks、effects、cues、suppress-melee）
        contact-radius: 1.6
        cooldown-ticks: 40
        suppress-melee: true
        effects:
          - { type: push, strength: 0.6, lift: 0.3 }
          - { type: damage, amount: 2.0, min-health: 6 }
```

**flees-when-powered**：有成員 powered 時（power-up 物件觸發），暫停追擊與接觸效果，改逃離最近的 powered 成員（先直接遠離，路徑失敗依序偏轉 ±45／±90／±135 度），換外觀；被 powered 成員碰到（`touch-radius`，判定先於任何接觸效果）就被打散。powered 結束或換 stage 恢復追擊。

```yaml
    flees-when-powered:
      speed: 1.3               # 預設 1.3（0.1..3）：逃跑速度倍率
      flee-distance: 12        # 預設 12（3..48）：逃跑目標點離自己多遠
      touch-radius: 1.8        # 預設 1.8（0.5..8）：powered 成員碰到多近就被打散
      repath-ticks: 8          # 預設 8（4..100）
      animation: scared        # 選填：powered 期間的模型 loop 動畫（結束恢復 idle）
      powered-cue: ghost_scared   # 選填：powered 期間跟著敵人的持續 cue（loop 光效）
      dissolve-cue: ghost_pop  # 選填：被碰散時播
      dissolve-ticks: 12       # 預設 12（1..400）：打散到實體移除的延遲
```

被碰散不補燈火、記 `powered-reversal`；同時有 needs-flash 的敵人仍可被閃光打散（走原本的路）。

清理：凍結旗標、追擊目標、逃跑外觀與 powered 在 stage 轉換、局結束時清掉；成員離場移除他的 powered 與追擊目標；僵直結束統一走 `syncBody`（AI 只在 ALIVE 且沒凍結時開）。

## bosses.yml(rc.23)

一個 boss = 一座場地上的「必打」敵人。框架不認得任何特定角色:血量用**命中數**表示,階段、招式時間軸、光環護盾與寶石(場邊燈)、跪下輸出窗口、倒下與全滅重來全部是資料。其他副本寫一個區塊就能用;沒有這個檔案的副本完全不經過這套程式。

- 一個 stage 只能有一個 boss;boss 在它的 `stage` 開始時出場,離開該 stage(轉場、局結束)時整個收掉。
- 載體是原版 Mob(`base-entity`,預設 ZOMBIE,AI 關閉,隱形並綁 BetterModel 模型)。**對載體的任何傷害一律取消**,玩家的一下有效近戰(`ENTITY_ATTACK`,不含橫掃;攻擊者沒倒下)才換算成命中數,所以不看武器與護甲。
- 護盾(光環)亮著時近戰打不動:取消傷害、在命中點濺 `shield.spark-cues`(有冷卻)。跪下時任何人有效;**powered**(吃南瓜燈,見 power-up)的成員可無視光環,那一下用 `powered-hit-weight`。
- Paper 在傷害事件**之前**就重置玩家的蓄力計時(Lecithin `Player.java:1117` `onAttack`,`:1146` 才 `hurtOrSimulate`),事件裡讀到的蓄力幾乎是 0,所以 `min-attack-strength` 預設 0(不限);限頻靠 `hit-cooldown-ticks`(每位玩家各算)。

| 欄位 | 語意 |
|---|---|
| `stage` | 必填。boss 所在的 stage(stages.yml);該 stage 常用 `complete: [{ type: boss-defeated, boss: <id> }]` |
| `base-entity`／`model`／`scale`／`animations` | 載體型別(預設 ZOMBIE)、BetterModel 模型 id、縮放(同時放大 hitbox)、動畫名。動畫預設值就是共用契約名:`idle glide hit shield-spark kneel rise phase-up snuff defeat sit-end`(底線版本 `shield_spark` 等) |
| `health` | 必填。1 人的總命中數;`by-players: { 2: { health: 1.6, damage: 1.0 } }` 乘上去(血量)與乘在招式傷害上。n 人用 ≤ n 的最大 key,沒寫 = 1.0 |
| `hit-weight`／`powered-hit-weight`／`hit-cooldown-ticks`／`min-attack-strength` | 一下有效近戰扣幾點(預設 1)、powered 穿過護盾那一下(預設同 hit-weight)、每位玩家兩下之間至少隔幾 tick(預設 12)、蓄力下限(預設 0,見上) |
| `start-delay-seconds`／`spawn-cues` | 出場後多久開打(預設 3)、出場 cue |
| `points`／`glide` | 滑行定點 marker(必填,至少一個);`glide: { speed: 1.2, dwell-seconds: 3, order: cycle｜random }` 格/秒。不追人、不近戰,招式施放時停下 |
| `gap-seconds`／`phase-up-seconds` | 一招結束後到下一招的閒置秒數(預設 2.5)、閒置時換階段的演出秒數(預設 2) |
| `phases` | `[{ at: 1.0, cues, music, title, subtitle }, { at: 0.55, … }]`:血量比例 ≤ at 進入;第一個必須 1.0,之後嚴格遞減,最多 8 個;不寫 = 單一階段。換階段播 `phase-up` 動畫(閒置時),`boss-phase <n>` |
| `moves` | 招式表,見下 |
| `shield` | `{ up-cues, break-cues, spark-cues, spark-cooldown-ticks (10), halo-bone, aura-cue }`。`halo-bone`:模型骨骼,護盾碎時隱藏、起身時顯示。`aura-cue`(rc.26):光環亮著期間常駐的 loop 光效 cue,跟著 boss 載體,護盾碎時取消、起身重播 |
| `gems` | 必填。`[{ lamp: <lantern 物件>, bone, beam-cues, break-cues, restore-cues }]`。**寶石 = 目前亮著的場邊燈**:每點亮一盞,在燈上播 `beam-cues`、在 boss 身上播 `break-cues`、隱藏 `bone`;燈被熄掉(熄燈招式、起身重置)寶石恢復;全部同時亮著 → 護盾碎 |
| `kneel` | `{ seconds: 10, cues, rise-cues, rise-seconds: 1.5, lamps-reset: on-rise｜never }`。跪下輸出窗口;起身後護盾恢復、(on-rise)所有場邊燈重置為暗。跪下期間打到階段門檻,階段**延到起身後**才換 |
| `pumpkins` | `{ objects: [power-up 物件], from-phase: 3 }`。階段到之前這些南瓜燈是「拿走」狀態(看不到、拿不到),到了才上架;全滅回第一階段時再次下架 |
| `downed` | 倒下狀態,見下 |
| `wipe` | 必填。全滅重來,見下 |
| `defeat` | `{ cues, seconds: 3 }`。打倒後播 `defeat` 動畫與 cue,`seconds` 後接 `sit-end` 迴圈 |
| `bar` | `{ text: texts key（{percent} {phase}）, color, overlay }`。boss 血量條(StageContext.bossBar,和燈籠的 `progress-bar` 共用同一條,所以場邊燈不可有 progress-bar) |
| `heal` | rc.25,選填,預設全關。`{ on-start: true, on-kneel: true }`:`on-start` = 開打時（含全滅重來後重新開打）、`on-kneel` = 光環碎她跪下時,把**在線成員**血量補滿（log `boss-heal reason=start｜restart｜kneel players=<n>`，派工到各玩家自己的執行緒）。招式傷害是最大血量的固定比例、不看護甲，進場時血量不滿就會被連續兩招打倒;補血讓單人在合理躲避下打得完 |
| `hit-cues` | rc.24,選填。玩家一下**有效**近戰命中時在命中點播(被光環擋下、玩家冷卻內、出場等待中的不算;擋下走 `shield.spark-cues`)。頻率受 `hit-cooldown-ticks` 限制 |
| `intro` | rc.24,選填，見下「出場演出」。沒寫 = 直接在 `points` 第一個點出場(同舊版)。rc.26 `await-arrive-dialogs: true`:停在屋頂等 stage `on-arrive.dialogs` 播完才計停留與開打倒數 |

### 出場演出 intro(rc.24)

```yaml
    start-delay-seconds: 30          # 出場後多久開打;hold-seconds + glide-seconds 不可超過它
    intro:
      marker: nun_roof_main          # 必填:出場(生成)位置,場景 marker
      cues: [nun_intro_cue]          # 出場時在 marker 播
      animation: phase_up            # 選填:出場時播一次的模型動畫(載體剛生成,模型綁好後約 1 秒才播);省略 = 不播
      animation-seconds: 3           # 預設 3(0.2..60):動畫播多久後回 idle
      hold-seconds: 26               # 預設 2(0..120):在 marker 停留多久才下來
      then-glide-to: nun_point_a     # 選填:落地的 marker;預設 points 第一個
      glide-seconds: 2.5             # 預設 2(0..30):下來花多久;0 = 直接傳送
```

出場時先 log `boss-intro <bossId> marker=<id>`，再 `boss-start`(格式不變);停留結束後用 `ActorHandle.teleport` 每 2 tick 一小步直線滑到落點(`glide` 動畫)，落地記 `boss-intro-done <bossId> point=<id>`，之後仍等到 `start-delay-seconds` 才開打(開打時若還沒落地會先補落地，不會在半空出招)。出場等待期間照舊:近戰只濺火花、不扣血、沒有招式。全滅重來**不重播**出場演出，直接回 `points` 第一個點。marker 與 `then-glide-to` 載入時核對場景。

### 招式 moves

共通欄位:`shape`(必填)、`weight`(預設 1)、`cooldown-seconds`(同一招的最短間隔,預設 0)、`phase`(第幾階段起開放,預設 1;至少要有一招 phase 1)、`warn-seconds`(預警,預設 2)、`recovery-seconds`(判定後到下一招,預設 1)、`animation`(施放時播的模型動畫,單次)、`warn-cues`(預警開始時在原點播)、`hit-cues`(判定時播)、`origin-marker`(判定區原點;預設 boss 腳下)、`damage-percent`(命中扣玩家**最大血量**的比例,必填,0..1)、`push: { strength, lift }`。`summon`／`snuff` 沒有傷害,不可寫 `damage-percent`／`push`。傷害是**固定比例**:直接改血量,不看護甲、附魔與抗性,再乘人數倍率 `by-players.damage`;致死不會死亡,進入倒下。框架只負責排程、判定區域與 log,地面預警的視覺走 `warn-cues`(LycoLib cue／VFX)。

| shape | 欄位 | 判定 |
|---|---|---|
| `ring` 掃地 | `max-radius`(必填)、`band-width`(2)、`expand-seconds`(0.8)、`dodge-cues` | 預警後環形波從原點往外推;波前掃過時**在地上**的人受傷,跳起來(快照 onGround = 0)的躲過並收到 `dodge-cues`(只給他)。取樣每 2 tick,用「掃過的區間」判定,取樣再疏也不漏 |
| `circle` 落雷 | `radius`(必填)、`max-targets`(0 = 所有人) | 每位目標腳下一個圈(預警開始時定位,不跟隨),時間到圈內的人受傷(每人最多一次) |
| `cross` 雙杖十字 | `length`(14)、`half-width`(1.2)、`angle-offset`(45) | 通過原點的兩條垂直線(X),線上的人受傷,楔形空格安全 |
| `spin` 光環迴旋 | `radius`(必填)、`inner-radius`(2)、`gap-degrees`(80)、`degrees-per-second`(45)、`duration-seconds`(8)、`hit-interval-ticks`(20);rc.24 視覺取樣:`gap-cues`、`beam-cues`、`sample-ticks`(4)、`beams`(3)、`points-per-line`(6) | 光束環留一個缺口,缺口從預警開始時離 boss 最近的人的方位起以固定角速度旋轉;缺口外(inner-radius..radius)的人每 `hit-interval-ticks` 受傷一次。視覺取樣見下 |
| `summon` 喚敵 | `enemy`(enemies.yml,必填)、`markers`(必填)、`count-by-players`、`max-alive`(4) | 預警後在 markers 生出敵人(用幾個由人數決定,起點輪替);同種敵人場上已達 `max-alive` 就不抽這一招。喚鬼用 needs-flash 敵人,喚人偶用 frozen-when-watched 敵人 |
| `snuff` 熄燈 | `flash-cues`(第一個是閃爍的持續 cue,其餘一次性)、`saved-cues`、`snuff-cues` | 點名一盞**亮著**的場邊燈,`warn-seconds` 內有人對它閃光(同點燃的錐形／ignite-radius 判定)就保住;沒保住燈熄(重置為暗)、寶石恢復、播 `snuff-cues` 與 `snuff` 動畫。沒有亮著的場邊燈時抽不到 |

**光環迴旋的缺口取樣(rc.24)**:cue 原點沒有朝向、keyframe 不能旋轉，旋轉的缺口畫不出來，所以框架依目前旋轉角**算出取樣點**，對每點播資料指定的短 cue(由內容的 vfx 決定長什麼樣:display、粒子都可以)。

- 取樣從**預警開始**就跑(缺口停在起始方位不動)，迴旋開始後跟著轉，直到 `duration-seconds`；每 `sample-ticks` tick(4..40，預設 4)一輪。
- `gap-cues`:缺口兩側邊緣(缺口中心 ± `gap-degrees`/2)兩條射線；`beam-cues`:缺口外實心弧內均分的 `beams`(1..8，預設 3)條光束線，第 i 條在實心弧起點後 (i + 0.5) / beams 處。兩者可只寫其一，都不寫 = 不取樣(舊行為)。
- 每條線上 `points-per-line`(1..12，預設 6)個點，從 `inner-radius` 到 `radius` 等距(取格子中心，不貼邊)，y = 判定區原點。
- **數量上限**:每一輪播出的 cue 數 = 2 × `gap-cues` 數 × 點數 + `beams` × `beam-cues` 數 × 點數，**不可超過 80**(載入時拒絕)。例如 gap 1 個 cue、beam 1 個 cue、3 條、每線 5 點 = 10 + 15 = 25 / 輪。
- 取樣 cue 必須是**短促、非 loop** 的(建議 ≤ 0.3 秒、1 個 display)；框架只保留最近 128 個 CueRun(局結束一併取消)，不會隨迴旋次數無上限累積。vfx 的 `max-total-displays` 要把「每輪 cue 數 × 存活時間 / 取樣週期」算進去。
- 招式被作廢(跪下、全滅、打倒、局結束)時取樣立刻停止。

**同一位玩家同時最多一個地面預警**:招式一次只進行一招;每個地面預警(`ring`／`cross`／`spin` 對所有在線成員,`circle` 對被選中的目標)登記到預警帳本,已有預警的目標被跳過(`circle`)或整招延後(其餘),都沒有目標就放棄這一招。log 每位目標一行 `boss-telegraph <moveId> target=<player>`;這一招結束(`boss-move-end`)、被取消(`boss-move-cancel`)或放棄(`boss-move-abort`)時才釋放。

### 場邊燈與護盾

場邊燈是 objects.yml 的 `lantern` 物件(`stage` 省略或等於 boss 的 stage),用既有的燃燒機制:對它閃光燒 `burn-seconds` 秒點亮(要有人在 `stay-radius` 內)。給 boss 用的燈不可有 `progress-bar`、`on-lit.light-radius`(必須 0,放出的光源方塊不會收回)、`on-lit.opens`、`on-lit.clears-enemies`,也不能被兩個 boss 共用(rc.26 起可以寫 `milestones`:重置為未點亮時進度與已觸發的 milestone 一起歸零,下次點燈會重播,可用來做燒燈三段發光)。這些燈**可以被重置為未點亮**(熄燈招式、起身、全滅重來):狀態機回 `unlit`、進度歸零、重新套用 `states.unlit` 的方塊、收掉持續 cue,之後可以再點亮(再記一次 `lantern-lit`)。一般燈籠的「不倒退」不變式不受影響,只有 boss 引用的燈會被重置。

### 倒下 downed

玩家受到**致死傷害**(任何來源:招式、人偶、幽靈、摔落…;虛空、`/kill`、世界邊界放行)時不死亡,進入倒下:套用下列身體狀態、(依 `move-to`)移到最近的復活點、不能攻擊、不能點燈與閃光、撿不起南瓜燈、不算守燈人數、不被敵人當目標。她下一次**跪下**時所有倒下的人復活(血量設成 `revive-health`),身體狀態整組還原。

| 欄位 | 預設 |
|---|---|
| `gamemode` | `ADVENTURE`(SURVIVAL／ADVENTURE／SPECTATOR) |
| `invisible`／`glowing`／`invulnerable` | false／false／true |
| `speed-modifier` | -0.8:移動速度屬性乘數修正(`MULTIPLY_SCALAR_1`,transient,斷線與重啟不會留下) |
| `revive-points`／`move-to` | 復活點 marker 清單;`nearest-revive-point`(預設)或 `stay`(原地保護) |
| `revive-health` | 10(點) |
| `cues`／`mark-cue`／`revive-cues` | 倒下時播(在玩家位置)、倒下期間跟著玩家的持續 cue、復活時播 |
| `title`／`subtitle`／`hud-text`／`hud-every-seconds` | 倒下時給他的標題、持續的 ActionBar 提示(預設每 1 秒) |
| `self-hint-cues`／`ally-hint-cues`／`ally-hud-text` | rc.24。倒下提示:`self-hint-cues` 只播給**倒下的人自己**(例如 HUD bark);`ally-hint-cues` 只播給**其他在線、沒倒下**的成員；`ally-hud-text`(texts key)是給這些隊友的一次性 ActionBar(**有 `objective` 的 stage 會每秒重畫目標列，同一組 ActionBar 後送的蓋先送的，所以一次性的 `ally-hud-text` 一秒內就被蓋掉；這種 stage 請改用 `ally-hint-cues` 的 HUD bark**)。這次倒下造成全滅時不提示。`cues` 仍是對全隊播(不分對象)。HUD bark 同組(如 `mist.bark`)後到取代先到:提示 cue 要在 cue 裡用 `delay-seconds` 錯開 `cues` 裡的 bark |

**還原不變式**(`BossRunTest`):倒下時**斷線**(在玩家自己的執行緒上、玩家資料存檔前同步還原)、**離場**、**局結束**、**stage 轉換**、**全滅重來**、**跪下復活**、**打倒**,都把 gamemode、隱形、發光、無敵、移動速度整組還原成倒下前的值,只還原一次;還原一律走該玩家自己的 EntityScheduler,不依賴成員名單(離場與局結束時名單已不含他)。倒下前的狀態另存在玩家 PDC(`hanatoki:downed_body`),關服或當機時回呼來不及跑,下次上線自動還原(移動速度修正是 transient,本來就不會留下)。**斷線重連(寬限期內)**:邏輯上仍倒下——回來後重新套用倒下狀態並回復活點;若這段時間她已經跪下過(把他算作復活),回來的人是活的,直接回 `wipe.checkpoint`。重連的既有傳送會把資料驅動副本的玩家送回入口,boss 戰中會在 10 tick 後再送一次。

**全滅**:在線的成員至少一位而且全部倒下(離線的成員不算存活,也不擋全滅)。

### 全滅重來 wipe

```yaml
    wipe:
      checkpoint: front_door     # 必填,marker:所有人回到這裡
      health: full               # full（預設,血量回滿）｜keep
      phase: first               # first（預設,回第一階段）｜keep
      restart-delay-seconds: 5   # 預設 5:這段時間後重新開打
      cues: []                   # 選填;title／subtitle 選填（texts key）
```

全滅時:作廢進行中的招式與預警、打散召喚的敵人、結束 powered、場邊燈全部重置、護盾恢復、身體狀態還原(再傳送)、boss 回起點並依策略重置血量與階段。**前面各段點亮的燈與已開的門不熄、不回滾**(只有 boss 引用的場邊燈會重置)。

### 範例(熄燈修女,節錄)

```yaml
bosses:
  nun:
    stage: front_court
    model: lamp_nun
    scale: 2.0
    health: 40
    by-players: { 2: { health: 1.6 }, 3: { health: 2.2, damage: 0.8 } }
    points: [nun_point_a, nun_point_b, nun_point_c]
    phases: [ { at: 1.0, music: boss.p1 }, { at: 0.55, cues: [phase_2] }, { at: 0.25, cues: [phase_3] } ]
    moves:
      sweep: { shape: ring, weight: 3, max-radius: 9, damage-percent: 0.25, animation: tele_sweep, warn-cues: [sweep_warn] }
      strike: { shape: circle, weight: 3, radius: 2.5, damage-percent: 0.3, animation: tele_strike }
      summon: { shape: summon, weight: 2, enemy: mist, markers: [mist_1, mist_2, mist_3], max-alive: 3, animation: tele_summon }
      snuff: { shape: snuff, weight: 2, warn-seconds: 3, animation: tele_snuff, snuff-cues: [nun_hum] }
      cross: { shape: cross, phase: 2, damage-percent: 0.3, animation: tele_cross }
      spin: { shape: spin, phase: 3, radius: 11, damage-percent: 0.2, animation: tele_spin }
    gems: [ { lamp: boss_lamp_1, bone: gem_1 }, { lamp: boss_lamp_2, bone: gem_2 }, { lamp: boss_lamp_3, bone: gem_3 }, { lamp: boss_lamp_4, bone: gem_4 } ]
    shield: { halo-bone: halo, spark-cues: [shield_spark] }
    pumpkins: { objects: [pumpkin_boss_1, pumpkin_boss_2], from-phase: 3 }
    downed: { revive-points: [revive_1, revive_2, revive_3], hud-text: boss.downed.hud }
    wipe: { checkpoint: front_door }
```

### 執行緒與已知限制

- 所有 boss 狀態只在 session 的 anchor region 執行緒讀寫;事件執行緒只讀 volatile／並行狀態(致死傷害攔截、攻擊取消),狀態變更一律 `ctx.submit`。世界與實體操作走 `StageContext`／`ActorHandle`／`ctx.mutate`,計時只用 session 的 `TimerQueue`(每個計時帶 epoch,跪下、全滅、打倒、局結束時舊計時自己失效)。
- 骨骼顯示／隱藏(`halo-bone`、`gems[].bone`)走 `BossModelBones`(選配介面,BetterModel 實作用 `TrackerUpdateAction.togglePart`);**只有編譯驗證,實機行為待測試服**,骨骼名不存在時略過不丟例外。
- 地面預警的取樣用成員快照(環形波每 2 tick、迴旋每 4 tick 一次);boss 的滑行用 `ActorHandle.teleport` 每 2 tick 一小步。`hit` 與 `shield_spark` 只在閒置時播(不蓋掉施放動畫)。
- 熄燈的「保住」判定只認錐形與 `ignite-radius`,不做視線遮擋(同閃光的既有限制)。

## encounters.yml

```yaml
encounters:
  wave1:
    stage: lantern_court               # 所屬 stage（只有該 stage 進行中才會被觸發）
    enemy: small_ghost
    marker-group: wave1                # mob_spawn marker 的 group=；依 marker id 排序輪流使用
    trigger: { type: object-state, object: lantern_a, state: burning }   # 或 stage-start、stage-arrive（rc.26）、encounter-cleared
    delay-ticks: 0
    counts: { 1: 3, 2: 4, 4: 5 }       # 人數 → 隻數（必含 1；n 人用 ≤ n 的最大 key）
    interval-ticks: 20                 # 每隻之間
    start-cues: [wave_start]
    marker-offset: 0                   # 選填（rc.21，0..64）：第 i 隻用該組排序後第 (offset + i) % 組大小 個 marker，讓同一組出生點的不同波次出生在不同位置
```

**`spawn-marker`／`spawn-offset`（rc.25，選填）**：不想用 mob_spawn 組、或組裡的出生點不在玩家看得到的位置時，改成在任一種 marker（trigger、door…）旁出生：`spawn-marker: maze_entry`、`spawn-offset: [0, 0, 6]`（世界方塊 `[dx, dy, dz]`，每個 |值| <= 64，只能和 spawn-marker 並用）。和 `marker-group` 擇一；寫了 spawn-marker 就不用也不能寫 marker-group，所有隻都出生在這一點（`marker-offset` 無作用）。載入時核對 marker 存在（場景核對）。波次的 `start-cues` 在這一點播。用法：大幽靈一進迷宮就出現在入口走廊，不是看不到的迷宮中央。

全部生出且全數消失（打散或死亡）→ 波次清空，打 `wave-clear` log，並可成為其他波次的 `encounter-cleared` 觸發與 stage 完成條件。

## chat.yml（rc.26，選填）

cues.yml 的 `hud` 吐槽是會蓋掉其他 ActionBar 的通知，戰鬥中的角色台詞（修女換階段、光環碎、跪下，幽靈姐姐被碰散…）改送聊天室比較不干擾操作。`chat.yml` 讓「播某個 cue」時一併送一則聊天室訊息（走 `StageContext.message`，文字是 texts key，MiniMessage），cue 本身只留聲光：

```yaml
chat:
  boss_phase2_a:                          # key = cues.yml 的 cue id（任何路徑播這個 cue 都會送）
    text: mist-patrol.chat.nun-phase2-a   # 必填，texts key
    delay-seconds: 0                      # 選填（0..60，預設 0）：播 cue 後多久才送
```

送給這次播放的聽眾（個人 cue 只送那位；一般 cue 送全體成員）。沒有 chat.yml 或 cue 沒列在裡面 = 行為同舊版。HanaToki 不驗證 cue id 存在（cues.yml 在 LycoLib 端），內容端的契約測試核對。

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
    cues: { flash: flash, empty: flash_empty, drained: lamp_drained, gain: lamp_gain }   # 純量或清單；drained／gain 選填
    hud-text: tool.night-lantern.hud    # 選填；ActionBar 提示 {charges} {max}
    passive-regen-seconds: 8            # 選填（rc.20）；低於上限時每 N 秒 +1（1–600）。這是「燈火 0 不會卡死」的保底
    zone-regen: { seconds: 2, radius: 7, states: [burning, lit], markers: [] }   # 選填；光源區回補
```

### 燈火自動回補（rc.20）

只靠「打散敵人補燈火」有死鎖：燈火 0、場上沒有可打的僵直敵人 → 照不到 → 打不散 → 不補。（2026-10-18 霧頁巡夜實測就是點燈用掉最後一格、5 隻幽靈衝出來。）

- `passive-regen-seconds`：低於上限時每 N 秒 +1，不論在哪、不依賴敵人，燈火 0 最久 N 秒（加一個 0.5 秒探測間隔）回到可用。`ChargeMeter` 純邏輯；已滿進度歸零，回補後不超過 `charges`。
- `zone-regen`：持有者站在**處於 `states` 的燈籠**（`burning`／`lit`，預設兩者）或**指定 `markers`** 半徑 `radius` 內，改成每 `seconds` 秒 +1（取被動與光源區較快的一個，不相加）。契約的「安全區每秒補燈」就是這個。站在燃燒中的燈籠旁補燈，同時也把人留在 `stay-radius` 內讓燈繼續燒。
- `drained` cue：這一下把燈火用到 0 時只播給持有者（悶聲）。`gain` cue：自動回補 +1 時只播給持有者（origin = 眼睛位置與朝向，光效可 `orient: aim`）。擊殺補燈火仍播敵人的 `refill-cue`。
- 都沒寫 = 行為與 rc.19 相同。沒有 `passive-regen-seconds` 的道具載入時打一行 WARN（有卡死風險），不擋註冊。

進場（`onMemberReady`）發給每位成員，經 `InstanceItems.mark` 蓋 instance 章（局內物，離場帶不出去）；不能放置；PDC `hanatoki:tool_id` 辨識。燈火數量顯示在物品**耐久條**（最大耐久 = 格數；原版只在「用掉至少一格」時才畫條，滿格看不到），要一直看得見就用 stage 的 `objective`（`{pips}`）或 `hud-text`。只認主手事件（主副手事件去重）；右鍵一律拒絕原版互動。閃光：事件當下取持有者眼睛位置與視線快照 → 一次判定錐形內的 needs-flash 敵人（實體位置經 `ActorHandle.locationOf` 取，不跨 region 讀）與 `unlit` 燈籠（→ burning，點燃目標取 marker 上方 0.5 與 2.5 格兩點）。沒燈火 → 播 `empty` cue 不扣；有冷卻。

## hints.yml

```yaml
hints:
  hint_flash_first: { trigger: { type: first-whiff }, cues: [hint_flash_first] }
  hint_idle:        { trigger: { type: no-progress, seconds: 20 }, cues: [hint_idle] }
  hint_point:       { trigger: { type: near-marker, marker: lantern_a, radius: 12 }, stage: lantern_court, cues: [hint_lantern] }
```

`once`（預設 true）；`once: false` 時以 `repeat-seconds`（預設 30）限頻。`stage` 可省略。「進展」= stage 開始、敵人打散、物件換狀態、柵門開、閃光命中、到達 marker。**`once` 是整局一次**（換 stage 不重置）：卡關提示要持續提醒就寫 `once: false`。

個人提示（rc.20，**只播給當事人**，once／repeat 也各算各的；沒有任何 tool 時載入拒絕）：

```yaml
  hint_empty:   { trigger: { type: tool-empty }, once: false, repeat-seconds: 25, cues: [hint_empty] }
  hint_unarmed: { trigger: { type: unequipped-near-enemy, radius: 8 }, once: false, repeat-seconds: 20, cues: [hint_unarmed] }
```

- `tool-empty`：某位成員閃光把燈火用到 0，或在 0 燈火時再按右鍵。
- `unequipped-near-enemy`：某位成員主手格不是任何 tool 的 `slot`，且 `radius` 格內有還沒僵直的 needs-flash 敵人（成員快照的 `heldSlot` 加敵人位置，每 10 tick 一次）。

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

### 提示觸發 enemy-dispersed（rc.25）

`hints.yml` 的 `trigger: { type: enemy-dispersed, enemy: <enemies.yml id> }`：該種敵人被玩家打散、或被 powered 的成員碰散的那一刻觸發（全隊提示；光圈／stage 清場不觸發）。`once: false` 加 `repeat-seconds` 讓每次碰散都提醒。

### 目標列清理（rc.25）

目標列走 ActionBar 的 `hanatoki.stage` 組，LycoLib 會讓最後一則停留到 duration 結束（約 1.5 秒），離場後看起來像「目標列沒消失」。HanaToki 現在記下收過目標列的成員（`ObjectiveHudTracker`），**局結束、成員離場、換到沒有 objective 的 stage** 時對他們送一則空白動作列，同組取代掉最後一則（走玩家自己的執行緒，不依賴成員名單）。2026-10-23 的 bot 紀錄裡「28 秒仍在刷新」是那一局根本還沒結束（bot 人在副本世界），不是殘留；r2e 的「剩 579ms」才是這個停留。

### 提示觸發 lantern-contested（rc.21）

`hints.yml` 的 `trigger: { type: lantern-contested, seconds: 8 }`：有 seeks-object 的敵人**連續**啃燈 `seconds` 秒（中間降到 0 隻就重算）時觸發，全隊提示；至少要有一種敵人有 seeks-object，否則整份拒絕。想讓站著看她們啃燈的人得到「照住她們再打散」的提醒，用它，不用 no-progress（燈沒點燃前也會算進去）。

## 證據 log

每次狀態轉移一行 INFO：`[HanaToki/<dungeon>] <事件> <id…> slot=<slotId>`。事件：`scene-placed`、`stage-start <stage>`、`stage-complete <stage>`、`slice end <stage>`（沒有 resolve 的最後一段）、`object <id> <from>-><to>`（含 `burning paused/resumed`、`milestone`）、`gate-open <id>`、`light`、`wave-start <id> count=N players=N offset=N`、`wave-clear <id>`、`wave-cancel <id>`、`enemy-spawn`、`enemy-seek <actor> enemy= target= path=ok|none`（每隻第一次下往燈的路徑指令）、`enemy-contest <燈籠> ghosts=N progress=0.00`（有隻數變化時與每秒一次；降到 0 再打一行）、`enemy-touch <actor> player= effect=push+charges-1…`、`enemy-stun`、`enemy-recover`、`enemy-dissolve <actor> by=<玩家|lantern>`、`enemy-gone`、`enemies-cleared`、`whiff`、`flash`、`flash-hit`、`flash-empty`、`refill`、`tool-give`、`regen player= tool= charges= zone=`、`tool-drained`、`flash-miss player= range= cone= <燈籠>:dist=,angle=`（閃光沒點到未點亮燈籠時的距離與視線偏角）、`hint <id> [player=]`、`reach <marker>`、`dialog <id> player= choice=`、`port-missing`、`session-end <reason> cancelledCues=done`。

rc.22 新增（紙燈別院全程契約的 tag，bot 認這些字串；`<enemyId>` 是 actor id，例如 `doll_wave-3`）：

| 事件 | 時機 |
|---|---|
| `lantern-lit <objectId>` | 燈籠燒滿點亮（與舊的 `object <id> burning->lit` 並存） |
| `doll-frozen <enemyId> watcher=<player>` | 人偶從移動轉為凍結（每次轉換一行）；另有 `doll-pose <enemyId> pose=<id>` 記這次挑的姿勢 |
| `doll-dispersed <enemyId> by=<player>` | frozen-when-watched 的敵人被玩家打散（光圈清場不記；仍有 `enemy-dissolve … by=lantern`） |
| `power-up-taken <objectId> player=<player>` | 有人碰到 power-up；`power-up <id> taken->ready` 是重生 |
| `powered-start players=<n> seconds=<s>` | 全隊 powered（每次吃都記；再吃是刷新） |
| `powered-end reason=<expired｜stage｜members-left>` | powered 全部結束 |
| `powered-reversal <enemyId> by=<player>` | powered 成員碰散一隻會逃的敵人（隨後還有 `enemy-dissolve`） |
| `enemy-chase <actor> target=<player｜none>` | chases-players 換目標（只在變更時記） |
| `epilogue-start stage=<id> steps=<n>`／`epilogue-done stage=<id>`／`epilogue-dialog-timeout …` | 尾聲開始／播完／對話等逾時 |
| `resolve stage=<id> result=<key>` | 尾聲後呼叫 resolve（隨後引擎結束 session，`session-end` 的 reason 是引擎的結束原因） |
| `session-end <reason> cancelledCues=done` | 局結束；`prepare-failed` 是場景放置失敗 |

另有 `enemies-cleared stage-complete count=N`（stage 的 `clears-enemies`）。

rc.23 新增（Boss；`<player>` 是玩家名，`<lamp>` 是場邊燈物件 id）：

| 事件 | 時機 |
|---|---|
| `boss-intro <bossId> marker=<id>` | rc.24。有 `intro` 時先記這行(boss 在 intro marker 現身)，隨後才是 `boss-start` |
| `boss-intro-done <bossId> point=<id>` | rc.24。出場演出落地(滑行結束或補落地) |
| `boss-start <bossId> hits=<n> players=<n>` | boss 出場（總命中數已含人數倍率） |
| `boss-phase <n>` | 進入第 n 階段（開打時 1；全滅回第一階段重來時再記一次 1） |
| `boss-telegraph <moveId> target=<player>` | 地面預警，每位目標一行；同一位玩家在 `boss-move-end`／`-cancel`／`-abort` 之前不會再出現第二個 |
| `boss-hit <moveId> player=<player>` / `boss-dodge <moveId> player=<player>` | 招式命中／跳過環形波 |
| `boss-summon <moveId> enemy=<id> count=<n>` | 喚敵 |
| `boss-snuff-warn <lamp>` / `boss-snuff-saved <lamp> by=<player>` / `boss-snuff <lamp>` | 熄燈：點名閃爍／被保住／燈熄 |
| `boss-move-end <moveId>` / `boss-move-cancel <moveId> reason=…` / `boss-move-abort <moveId> reason=…` | 一招結束／被作廢（跪下、全滅、打倒、局結束）／放棄（預警被占用、沒有目標） |
| `boss-shield-broken` | 四盞場邊燈同時亮、光環碎 |
| `boss-kneel seconds=<s>` | 她跪下（輸出窗口） |
| `member-downed <player>` | 玩家進入倒下 |
| `member-revived <player> reason=<kneel｜defeat>` | 倒下的人復活（她跪下時／她被打倒時） |
| `member-restored <player> reason=<wipe｜left｜stage｜session-end…>` | 倒下的人的身體狀態被還原（不是復活：全滅回檢查點、離場、轉場、局結束） |
| `boss-wipe-restart checkpoint=<marker>` | 全滅：所有人回檢查點、boss 重置 |
| `boss-defeated <bossId>` | boss 被打倒（隨後 stage 完成條件 `boss-defeated` 成立，接尾聲與 resolve） |
| `boss-stop <bossId> reason=<stage｜session-end…>` | boss 被收掉 |
| `object <lamp> reset->unlit` / `power-up <id> available｜hidden` | 場邊燈被重置／boss 的南瓜燈上架或下架 |

`lantern-lit <lamp>` 沿用（場邊燈每次重新點亮都會再記一次）。

## Debug 指令

`/hanatoki admin entities [slotId|all]`（權限 `hanatoki.admin`）：每一局列出 actor／prop／BetterModel tracker 數，以及資料驅動內容的現況（stage、敵人各狀態隻數、凍結／追擊／逃跑隻數、powered 成員與剩餘秒數、power-up 狀態、尾聲進度、CueRun 與計時數）。`all`（預設）最後比對「session 已結束卻還登記著的 actor／prop／tracker」，供斷線、離場、局結束後清理驗證（剛結束、場地還在收斂的一局會短暫出現，持續出現才是外洩）。內容現況在 session 的 anchor region 執行緒上組好才輸出。rc.23 起有 boss 的局另列一行 boss 現況（狀態、階段、命中數、護盾、寶石、倒下人數、進行中的預警與召喚數、進行中的招式）與 `倒下狀態玩家數=<n>`（沒有 boss 的局固定 0）。

## 執行緒

世界與實體操作一律經 `StageContext`／`ActorHandle`（各自派工到擁有者 region／EntityScheduler），callback 內不直接 setBlock／spawnEntity。外部事件（右鍵、受擊）在事發執行緒做**同步判定**（取消與否，只讀 volatile 的敵人狀態），其餘先 `ctx.submit` 回到 session 的 anchor region 才動狀態。計時用 `SessionRun` 自己的 `TimerQueue`（由每 2 tick 的週期任務推進）：`StageContext.transition()` 會取消 `submitLater`／`submitRepeating` 全部排程，柵門滑開、打散延遲移除不能被轉場砍掉；週期任務本身在每個 stage 進場時重新掛上。

## 範圍與已知限制

- 敵人規則 `needs-flash`、`seeks-object`、`harasses-players`、`frozen-when-watched`、`chases-players`、`flees-when-powered`，物件 `lantern`／`gate`／`power-up`，局內道具 `flash`，boss（`bosses.yml`：階段、招式時間軸、光環護盾與場邊燈、跪下窗口、倒下與復活、全滅重來）。常駐場景還不在內。最後一段完成預設只播 cue 與打 `slice end`，不 `resolve`、不發獎；要結算須在該 stage 寫 `resolve`（見上）；stage 逾時仍不結算。
- 人偶的遮擋檢查在人偶自己的 region 上做；射線若跨出該 region 就當作被看到（偏向玩家，WARN 一次）。遮擋以方塊碰撞外形判定，玻璃之類會擋視線。
- rc.23 的 boss 時間軸、判定區、護盾、倒下與全滅流程用假的 host 做了完整流程單元測試（`BossRunTest`），但實體生成、模型動畫與骨骼切換、玩家身體狀態的 Bukkit 實作、致死傷害攔截與斷線／登入事件的實機行為待測試服驗證。
- rc.24 的 boss 出場演出、被擊中 cue、倒下提示與迴旋取樣用假 host 做了完整流程單元測試(`BossRunTest`、`Rc24DataTest`、`BossLogicTest`)；stage `opens`、目標列狀態條件(powered／boss-kneel)的**執行期**(`SessionRun` 開門、`conditionMet`)只有編譯驗證加解析／驗證／`pickObjective` 單元測試，實機行為待測試服；出場滑行的模型動畫與迴旋取樣 cue 的顯示數量／效能同樣待測試服實測。
- rc.22 的 raycast、AI 開關與 region 行為只有單元測試覆蓋純邏輯（視錐、狀態機、解析、驗證）；實機行為待測試服驗證。
- 閃光不做視線遮擋判定（隔著牆的幽靈會被照到）。
- 場景每局重放（`maps().place`）；常駐模式未做。
- 光源方塊每格一次 `ctx.mutate`（上限約 2500 格由載入器檢查），只放在空氣格。
- 重連（寬限期內）落點 = 進場落點（anchor + `spawn-offset`），不是 anchor 本身；資料驅動副本的 anchor 在場景地表之下。
