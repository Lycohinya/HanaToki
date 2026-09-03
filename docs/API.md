# HanaToki API 文件

這份文件描述 HanaToki 0.1.2 目前真的能跨插件使用的契約。它以 source 為準，刻意把「已接線」「只有型別」「內部實作剛好是 public」分開，避免看到一個 public class 就誤以為它是穩定 API。

安裝、YAML、指令與排錯請讀 [使用手冊](USAGE.md)。

## 快速導覽

| 你要查的事 | 章節 |
|---|---|
| 依賴、bootstrap 與 plugin lifecycle | [§2–3](#2-加入編譯依賴) |
| Bukkit service 與玩家進出 | [§4–6](#4-bukkit-servicesmanager-契約) |
| 定義載入、behavior、`StageContext` 與 state | [§7–10](#7-定義與-behavior-bootstrap) |
| Actor、Prop 與自訂世界生成器 | [§11–13](#11-actor-api) |
| Check、Reward、Music 與額度 service | [§14–18](#14-check-api) |
| 失敗語意與驗證清單 | [§19–20](#19-失敗與執行緒速查) |

## 1. API 範圍與相容性

### 支援的整合面

| 類別 | 主要型別 |
|---|---|
| Bootstrap | `HanaTokiPlugin.core`、`HanaTokiCore.loadContentDefinitions(File)`、`Texts` |
| 內容擴充 | `DungeonBehavior`、`DungeonBehaviorRegistry`、`StageContext`、`InstanceState` |
| 場景物件 | `ActorSpec`、`ActorHandle`、`PropHandle` |
| 世界生成 | `WorldGeneratorRegistry` |
| HanaToki 對外提供的 service | `DungeonAccess`、`PresenceBridge` |
| HanaToki 向外尋找的 service | `CheckResolver`、`MusicCue`、`RewardSink` |
| 內容層提供、其他插件消費的契約 | `RewardQuotaLookup`、`RewardQuotaAdmin` |

### 目前只有型別，別當成已完成流程

- `CheckDescriptor` 已宣告，但 HanaToki core 沒有查詢它。
- `DungeonInfo` 與 `HanaTokiCore.listDungeonInfo()` 目前只供 debug／未來 UI 資料使用，沒有註冊成 Bukkit service。
- `DungeonBehavior.onCheckOutcome`、`onEncounterCleared`、`onEncounterFailed` 已存在於介面，但核心目前沒有 dispatch 它們。

### 不保證的內部面

`HanaTokiCore`、`SessionManager`、`SlotPool`、`DungeonRegistry`、`StageEngine`、controller 與 `folia/` 類別有些是 Kotlin public，主要是同 repo 組裝與測試方便。除本文件明列的入口外，不視為穩定跨插件 API。

### 版本規則

HanaToki 尚未承諾 semantic-versioning 下的 binary compatibility。跨插件介面增刪方法時，請一起重編 HanaToki 與所有內容插件。

## 2. 加入編譯依賴

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

```yaml
# plugin.yml
depend: [HanaToki]
```

只要程式碼直接引用 `DungeonAccess::class.java`、`DungeonBehavior` 或其他 HanaToki 型別，就有編譯期依賴。若消費端不想直接依賴，只能自己做反射 adapter；HanaToki 目前沒有附這層 helper。

不要把 Kotlin stdlib shade 進內容插件後，假設跨 plugin classloader 的 Kotlin runtime 型別仍相同。公開簽章維持以下規則：

- callback 用 JDK `Runnable`／`Consumer`，不用 Kotlin function type；
- 跨界資料優先用 `UUID`、`String`、primitive、JDK／Bukkit 型別；
- 不用 Kotlin 預設參數產生的 `$default` bridge；
- 介面加減帶 body 的方法後，兩邊一起重編。

## 3. 內容插件生命週期

### `onEnable` 建議順序

1. 取得 `HanaTokiPlugin` 與 `core`。
2. 先註冊 `CheckResolver`、`RewardSink`、`MusicCue` 等內容會立刻用到的 service。
3. 有自訂地形時，先註冊 `WorldGeneratorRegistry`。
4. 用 `Texts.merge` 合併內容文案。
5. 呼叫 `loadContentDefinitions(File)`。
6. 用 `DungeonBehaviorRegistry.register` 註冊每座副本的 behavior。
7. 最後才開放玩家入口。

```kotlin
private lateinit var hanaToki: HanaTokiPlugin

override fun onEnable() {
    hanaToki = server.pluginManager.getPlugin("HanaToki") as? HanaTokiPlugin
        ?: error("HanaToki 未載入")

    server.servicesManager.register(
        RewardSink::class.java,
        MyRewardSink(),
        this,
        ServicePriority.Normal,
    )

    saveResource("dungeons.yml", false)
    hanaToki.core.texts.merge(loadMyMessages())
    hanaToki.core.loadContentDefinitions(File(dataFolder, "dungeons.yml"))
    DungeonBehaviorRegistry.register("goblin-den", GoblinDenBehavior())
}
```

`loadContentDefinitions` 在 global tick thread 上呼叫時會同步執行；從其他 region 呼叫時只會排進 global region scheduler，方法本身立即回傳 `Unit`，沒有 completion 或錯誤 channel。熱重載時不要在它回傳的瞬間就宣稱副本 ready；至少確認 slot 已 provision，或乾脆完整重啟。

### `onDisable` 清理

```kotlin
override fun onDisable() {
    hanaToki.core.texts.removeByPrefix("goblin-den.")
    WorldGeneratorRegistry.unregister("my-generator")
    server.servicesManager.unregisterAll(this)
}
```

`DungeonBehaviorRegistry` 目前沒有 unregister。單獨重載內容插件可能保留舊 behavior instance；可靠更新方式是讓 HanaToki 與內容插件一起重啟。

## 4. Bukkit ServicesManager 契約

| Service | Provider | Consumer | 缺席或失敗時 |
|---|---|---|---|
| `DungeonAccess` | HanaToki | 玩家入口／選單插件 | service 不存在時入口應顯示暫時不可用 |
| `PresenceBridge` | HanaToki | 音樂、UI、保護等唯讀消費端 | service 不存在時由消費端 fail closed |
| `CheckResolver` | 內容／integration | HanaToki | 回傳已完成的 `"unavailable"` |
| `MusicCue` | 內容／integration | HanaToki | 無聲 no-op，不記警告 |
| `RewardSink` | 內容／integration | HanaToki | 放進 JVM 記憶體 pending queue 並記 warning |
| `RewardQuotaLookup` | 內容／integration | 外部選單／管理工具 | provider 自訂；HanaToki core 不查它 |
| `RewardQuotaAdmin` | 內容／integration | 外部管理工具 | provider 自訂；HanaToki core 不查它 |

HanaToki 在 `onEnable` 註冊 `DungeonAccess` 與 `PresenceBridge`，在 `onDisable` 透過 `unregisterAll(this)` 收回。

## 5. `DungeonAccess`

Package：`com.tinyyana.hanatoki.api`

```kotlin
interface DungeonAccess {
    fun hasDungeon(dungeonId: String): Boolean
    fun enterDungeon(playerId: UUID, dungeonId: String): Boolean
    fun enterDungeonDuo(playerId: UUID, partnerId: UUID, dungeonId: String): Boolean
    fun leaveDungeon(playerId: UUID): Boolean

    // 2026-08-29 新增：等交易真的做完才 complete
    fun enterDungeonTracked(playerId: UUID, dungeonId: String): CompletableFuture<DungeonEntryOutcome>
    fun enterDungeonDuoTracked(
        playerId: UUID,
        partnerId: UUID,
        dungeonId: String,
    ): CompletableFuture<DungeonEntryOutcome>
}

interface DungeonEntryOutcome {
    fun status(): String          // 見 DungeonEntryStatus 的常數
    fun succeeded(): Boolean
    fun sessionId(): String?      // 成功時是這一局的 sessionId
    fun instanceId(): String?     // 有開局內背包時是這次交易的 instanceId
    fun failureReason(): String?  // 失敗原因（給 log／管理員看）
    fun rolledBack(): Boolean     // 失敗時已建立的狀態有沒有清乾淨
}

object DungeonEntryStatus {
    const val ENTERED = "ENTERED"            // 新開一局，人已落地
    const val JOINED = "JOINED"              // 加入一個正在跑的常駐副本，人已落地
    const val NO_DUNGEON = "NO_DUNGEON"
    const val NO_SLOT = "NO_SLOT"
    const val PLAYER_OFFLINE = "PLAYER_OFFLINE"
    const val TELEPORT_FAILED = "TELEPORT_FAILED"
    const val INVENTORY_FAILED = "INVENTORY_FAILED"
    const val SHUTTING_DOWN = "SHUTTING_DOWN"
}
```

### 取得 service

```kotlin
val access = server.servicesManager
    .getRegistration(DungeonAccess::class.java)
    ?.provider
```

### 方法語意

| 方法 | `true`／回傳值代表什麼 | 呼叫端仍要處理 |
|---|---|---|
| `hasDungeon(id)` | definition map 有這個 id | 不代表世界／slot provision 成功，也不代表目前有空位 |
| `enterDungeon(player, id)` | 玩家在線、definition 存在，且同步預檢有空位 | 傳送仍非同步；**不代表傳送成功**。要知道結果請改用 `enterDungeonTracked` |
| `enterDungeonTracked(player, id)` | future complete 時，`succeeded()` 為 true 代表**人已經真的站在場地上**、局內背包（若有開）也換好了 | future 在任意執行緒 complete；接著要對玩家做事請自己派回他的 scheduler |
| `enterDungeonDuo(a, b, id)` | 兩人在線、definition 存在，且 session manager 分到／加入 slot | 不檢查兩個 UUID 是否相同，也不執行 `party-cap` |
| `leaveDungeon(player)` | 找到玩家目前的 session，並開始離場 | persistent 返回點缺失時可能回 false；傳送仍非同步 |

入口插件在呼叫前至少檢查：

- 玩家是否已在任何 HanaToki session；
- 雙人 UUID 是否不同、夥伴是否同意／符合資格；
- 內容自己的費用、任務、冷卻與 party 規則；
- API 回 false 時要給玩家下一步，而不是安靜吃掉。

核心目前不會擋「已在 session 的玩家再次 enter」。這可能新開另一局並覆蓋 player-to-session 索引，呼叫端要先用 `PresenceBridge.isInside` 擋掉。

**2026-08-29 修正（原本的缺陷描述已不成立）**：`enter*` 不再丟掉 `teleportAsync` 的結果。傳送回 false 或 exceptional、進場途中登出、world/slot 失效、局內背包交易失敗時，核心會**完整回滾** session 成員資格、slot 佔用、stage 狀態與排程、bossbar、返回點登記與局內背包 journal，不會留下「`PresenceBridge` 說他在裡面、人卻站在原地」的狀態。布林版 `enterDungeon` 另外會主動對玩家發失敗訊息。

多人進場是**全有全無**：任何一位傳送失敗，整局回滾，兩個人都留在原地。

`enter*` 保存返回點時，**已改為在該玩家自己的 EntityScheduler 上讀取 `Player.location`**，因此可以從任意執行緒呼叫。

## 5.1 局內背包與 `InstanceItems`（2026-08-29）

副本定義寫了 `instance-inventory.enabled: true` 時，玩家進場（**傳送真的落地之後**）會把永久背包持久化保存、清空、換成定義的 `loadout`；離場、死亡、主動退出、admin reset、斷線 grace 逾時、關服、崩潰重啟都走同一條還原路徑。

不變式：**只要快照曾經成功落地，不論 JVM 在哪一步死掉，玩家最後都能回到一份合法的永久背包——恰好一份。** 狀態機、崩潰恢復規則與收斂順序見 `docs/hanatoki/HANATOKI_ARCHITECTURE.md` §5.6。

journal 檔案在 `plugins/HanaToki/instances/`，一個 instance 一個檔案。伺服器開著時用 `/hanatoki admin journal` 看，關著時用 `tools/hanatoki-journal.py show <dir>`。`/hanatoki admin restore <instanceId>` 可以人工推一筆卡住的交易。

局內物品的所有權標記由引擎提供，道具插件鑄造局內武器時蓋章即可，**不要另建一套平行的 scope 欄位**：

```kotlin
interface InstanceItems {
    fun mark(item: ItemStack, instanceId: String): ItemStack
    fun instanceIdOf(item: ItemStack): String?
    fun isInstanceScoped(item: ItemStack): Boolean
    fun activeInstanceIdOf(playerId: UUID): String?
    fun isLegalFor(playerId: UUID, item: ItemStack): Boolean
}

val items = server.servicesManager.getRegistration(InstanceItems::class.java)?.provider
```

沒有標記 = 永久物品（預設；既有物品完全不受影響）。標記過的物品**丟不掉、不能被非本局的人撿、不能進任何容器（含終界箱）、死亡時不會掉落、跨世界與登入時會被清掉**。這些攔截由引擎的 listener 負責，消費端不需要自己做。

`instanceId` 從 `DungeonEntryOutcome.instanceId()` 或 `activeInstanceIdOf(playerId)` 拿。

## 6. `PresenceBridge`

```kotlin
interface PresenceBridge {
    fun isInside(playerId: UUID): Boolean
    fun dungeonIdOf(playerId: UUID): String?
    fun worldNameOf(playerId: UUID): String?
}
```

- `isInside` 只看 session registry，不讀玩家所在世界。
- `dungeonIdOf` 找不到 session 時回 `null`。
- `worldNameOf` 回該 session 所屬副本定義的 `world`，沒有 session 時回 `null`。
- 這是唯讀查詢面，不應拿來改玩家狀態。

⚠ **`isInside` 為 true 不代表人已經到了。** session 在進場交易的第 2 步就建立，`teleportAsync`
是第 6 步（第 5 步還要等 `prepareStage` 蓋完場地，關卡型副本實測數秒）。要判斷「人真的站在副本裡」
的消費端必須拿 `worldNameOf` 跟玩家當下的世界名比對——只問 `isInside` 會在等待窗口裡誤判。
2026-09-03 玩家回報「深域進入前就放音樂、傳送過去反而被中斷」的根因就是這個。

## 7. 定義與 behavior bootstrap

### `HanaTokiPlugin.core`

`core` 在 HanaToki `onEnable` 完成初始化後可用，setter 是 private。內容插件用硬依賴確保自己較晚啟動。

本文件支援直接使用的 core 成員只有：

```kotlin
hanaToki.core.loadContentDefinitions(file)
hanaToki.core.texts
```

`listDungeonInfo()` 可用於 provisional UI/debug，但目前不是 service contract。

### `DungeonBehaviorRegistry`

```kotlin
DungeonBehaviorRegistry.register(dungeonId, behavior)
val behavior = DungeonBehaviorRegistry.get(dungeonId)
```

- 相同 id 會靜默覆蓋。
- 沒有 unregister。
- 底層是一般 mutable map；只在受控的 plugin enable 階段註冊，不要在多條 region thread 動態改。

## 8. `DungeonBehavior`

```kotlin
interface DungeonBehavior {
    fun onStageEnter(ctx: StageContext, stageId: String) {}
    fun onStageExit(ctx: StageContext, stageId: String) {}
    fun onStageTimeout(ctx: StageContext, stageId: String) { ctx.resolve("timeout") }
    fun onInteraction(ctx: StageContext, interactionId: String, playerId: UUID) {}
    fun onCheckOutcome(ctx: StageContext, checkId: String, outcome: String) {}
    fun onEncounterCleared(ctx: StageContext, encounterId: String) {}
    fun onActorDeath(ctx: StageContext, actorId: String) {}
    fun onEncounterFailed(ctx: StageContext, encounterId: String) {}
}
```

### 目前會觸發

| Callback | 時機 |
|---|---|
| `onStageEnter` | instance start 與每次 transition 進入 stage |
| `onStageExit` | `ctx.transition` 離開舊 stage 前 |
| `onStageTimeout` | stage 到期且 YAML 沒有 `timeout-transition`；預設 resolve `timeout` |
| `onInteraction` | 已登記的 right-click／physical interaction 被 session 成員觸發 |
| `onActorDeath` | actor 綁定的實體死亡 |

### 目前不會觸發

`onCheckOutcome`、`onEncounterCleared`、`onEncounterFailed` 沒有 runtime dispatch。檢定請直接處理 `ctx.requestCheck(...).thenAccept`，encounter 清場請使用 `ctx.spawnEncounter(id, Consumer<StageContext>)` 的 callback。

所有已觸發的 behavior callback 都在該 instance anchor 的序列執行區執行。callback 內不要直接用 Bukkit API 跨 region 讀寫玩家、世界或實體。

## 9. `StageContext`

### 身分與狀態

| 成員 | 語意 |
|---|---|
| `sessionId` | 目前 instance 的 UUID |
| `slotId` | 場地 slot id，例如 `goblin-den#0` |
| `dungeonId` | definition id |
| `anchor` | 這個 slot 的 Bukkit `Location` |
| `state` | 目前 `InstanceState`；只在 instance 序列執行區讀寫 |
| `activeMembers()` | 目前尚未 dropped 的玩家 UUID；包含離線 grace 中的成員，不等於目前在線列表 |

### 世界與座標

| 方法 | 語意 |
|---|---|
| `mutate(location, Consumer<Block>)` | 到該座標 region 執行變更，先記錄原 block data，回傳完成 future |
| `interactionLocation(id)` | 查已展開的絕對座標；未知 id 回 null |
| `encounterLocation(id)` | 查已展開的絕對座標；未知 id 回 null |
| `particles(location, particle, count, spreadX, spreadY, spreadZ, extra)` | 在座標 region 生成粒子 |
| `soundAll(location, sound, volume, pitch)` | 逐一對 active member 播放場地音效 |

### 玩家訊息與傷害

| 方法群組 | 語意 |
|---|---|
| `message(player, key[, params])` | 對單一玩家送 MiniMessage key |
| `messageAll(key[, params])` | 對所有 active member 送訊息 |
| `title(player, titleKey, subtitleKey)`／`titleAll` | 送 title；空 subtitle key 代表不顯示 |
| `actionBar(player, key)`／`actionBarAll` | 送 action bar |
| `damageMembersWithin(location, radius, amount)` | 在每位玩家自己的 scheduler 做距離判定並造成一般傷害 |
| `damageMembersWithin(..., damageTypeKey)` | 同上；無效 key 記 warning 後退回一般傷害 |
| `membersWithin(location, radius)` | 非同步回傳範圍內 active member |
| `nearestMemberDirection(location)` | 非同步回 `[dx, dz, distance]`；沒人時空陣列 |

`membersWithin`、`nearestMemberDirection` 與其他 future 完成時不保證在 instance thread。要改 state 或轉場：

```kotlin
ctx.membersWithin(center, 4.0).thenAccept { members ->
    ctx.submit {
        ctx.state.stats["hits"] = members.size.toLong()
        if (members.isNotEmpty()) ctx.transition("combat")
    }
}
```

### Stage 與外部服務

| 方法 | 語意 |
|---|---|
| `transition(stageId)` | 呼叫舊 stage exit、取消 stage tasks，再把 state 切到新 id 並呼叫 enter；呼叫端必須保證 id 存在 |
| `resolve(resultKey)` | session：結算並收場；persistent：只結算當前 stage 的一輪 |
| `requestCheck(playerId, checkId)` | 轉給 `CheckResolver`；缺席回 `"unavailable"` |
| `musicCue(cueId)` | 對每位 active member 呼叫 `MusicCue`；provider 缺席 no-op |
| `sessionRemainingSeconds()` | session 剩餘秒數；到期或找不到回 0 |
| `log(message)` | 以 HanaToki logger 記內容 log |

`transition` 本身不先驗證 stage id。傳入不存在的 id 時，舊 stage 已退出、task 已取消，而且 `currentStageId` 已經被改掉；通常要等後續 `state.stage()` 才拋錯。這不是可恢復的「轉場被拒絕」，內容程式與 YAML 都要在進入 runtime 前驗證 id。

### Encounter、actor、prop 與 boss bar

| 方法 | 語意 |
|---|---|
| `spawnEncounter(id, onCleared)` | 依 YAML 生成實體；最後一隻死亡時在 instance 序列執行區呼叫一次 callback |
| `despawnEncounter(id)` | 強制移除該 encounter 仍在追蹤的實體 |
| `actors()` | 取得目前 session 的 `ActorHandle` |
| `props()` | 取得目前 session 的 `PropHandle` |
| `bossBar(text, progress, color)` | 顯示／更新 boss bar，預設 `PROGRESS` overlay |
| `bossBar(text, progress, color, overlay)` | 指定 `PROGRESS` 或 `NOTCHED_6/10/12/20` |
| `hideBossBar()` | 隱藏並移除 session boss bar |

`spawnEncounter` 對未知 id、未知展開座標或 invalid `EntityType` 採 log／no-op，回傳的 future 仍會成功完成，`onCleared` 永遠不會執行。呼叫端不能用 future success 當成「已生成」證據；要驗證實體或設計自己的 timeout／失敗轉場，避免 typo 把 stage 永久卡住。

`bossBar.text` 是 MiniMessage 原文，不是 message key。progress 由實作 clamp 到 0–1；無效 color／overlay 會回退預設並記 log。

### 排程

| 方法 | 語意 |
|---|---|
| `submit(Runnable)` | 回到 instance anchor 的序列執行區 |
| `submitLater(delayTicks, Runnable)` | 至少 1 tick 後執行；stage exit／session end 自動取消 |
| `submitRepeating(initialDelay, period, Runnable)` | 兩個 tick 值至少 1；stage exit／session end 自動取消 |

## 10. `InstanceState`

| 成員 | 語意 |
|---|---|
| `currentStageId` | 目前 stage；外部唯讀 |
| `stage()` | 取得目前 `StageDefinition` |
| `stageElapsedMs(nowMs)` | 目前 stage 已經過的毫秒數 |
| `tryFireOnce(triggerId)` | 同 stage 第一回 true，之後 false；轉場會清空 |
| `tryFireRepeatable(id, cooldownMs, nowMs)` | 冷卻到期才 true 並更新時間 |
| `get/set/remove(key)` | 內容自訂的 in-memory bag |
| `stats` | `MutableMap<String, Long>`；resolve 時複製進 `CompletionResult` |
| `pendingCheckVotes` | 技術上 public，但核心尚未接線；不列入穩定內容 API |

`InstanceState` 沒有自行加鎖。只在 behavior callback 或 `ctx.submit` 裡改它。`bag` 只活到目前 instance 結束，不是玩家持久資料。

## 11. Actor API

### `ActorSpec`

`ActorSpec` 是無參數可變類別，使用 `apply {}` 設定：

```kotlin
val spec = ActorSpec().apply {
    entityType = "MANNEQUIN"
    displayName = "<red>守門人</red>"
    displayNameVisible = true
    immovable = true
    invulnerable = false
    maxHealth = 80.0
}

ctx.actors().spawn("keeper", location, spec)
```

| 欄位 | 預設 | 語意 |
|---|---:|---|
| `entityType` | `MANNEQUIN` | Bukkit `EntityType` 常數名 |
| `ai` | `false` | LivingEntity AI |
| `collidable` | `false` | 是否碰撞 |
| `glowing`／`invisible` | `false` | 發光與隱形 |
| `removeWhenFarAway` | `null` | null 不改實體預設 |
| `skinTexture`／`skinSignature` | `null` | Mannequin profile texture |
| `displayName`／`displayNameVisible` | `null`／`false` | MiniMessage 名牌 |
| 裝備欄 | `null` | main/off hand、helmet、chestplate、leggings、boots |
| `immovable`／`invulnerable` | `true`／`true` | 演出 actor 的安全預設 |
| `maxHealth` | `null` | 同時設定 max 與 current health |
| `gravity`／`silent` | `false`／`true` | 重力與實體原生音效 |

非 Mannequin entity 會略過 Mannequin-only 欄位。

### `ActorHandle`

| 方法群組 | 回傳／失敗語意 |
|---|---|
| `spawn(id, location, spec)` | 同 id 先移除舊 actor，再非同步生成 |
| `despawn(id)`／`despawnAll()` | 找不到 no-op |
| `entityIdOf(id)` | 不存在回 null |
| `isAlive(id)` | 同步查詢；只適合 actor 仍在安全 ownership 範圍的情況 |
| `healthFractionOf(id)` | 同步，0–1；不存在／不可受傷回 -1。會移動 actor 改用 async 版 |
| `healthFractionAsync(id)` | entity scheduler 查詢；不存在回 -1 |
| `damage(id, amount)` | 機制傷害；不存在 no-op |
| `locationOf(id)` | 非同步 clone；宣告非 null，但實作在不存在時以 null 完成 |
| `teleport`／`lookAt`／`faceNearestPlayer` | 經 entity scheduler 操作；找不到通常 no-op |
| `setMainHand`／`setEquipment` | 換裝；invalid slot 記 warning 並 no-op |
| `setDisplayName`／`setDescription` | 名牌與 Mannequin 描述 |
| `setInvulnerable` | 切換無敵 |
| `swingMainHand`／`playHurtAnimation` | 原版客戶端動畫 |
| `setPose`／`validPoseNames` | 設定／查詢核心接受的 Mannequin pose |
| `setRotation` | 直接設定 yaw／pitch |

`locationOf` 要以 nullable-in-practice 使用：

```kotlin
ctx.actors().locationOf("keeper").thenAccept { location ->
    if (location == null) return@thenAccept
    ctx.submit { ctx.state.set("keeper-x", location.x) }
}
```

## 12. Prop API

| 方法 | 語意 |
|---|---|
| `spawnItem(id, location, item, scale, yaw, fixedBillboard)` | 生成一般 ItemDisplay；同 id 先替換 |
| `spawnBlock(id, location, blockData, teleportTicks)` | 生成 BlockDisplay；invalid block data 記 warning 並不生成 |
| `spawnPart(id, location, item, teleportTicks)` | 生成固定 billboard、transform `NONE` 的骨架部件 |
| `pose(id, tx, ty, tz, pitch, yaw, roll, scale, interpolationTicks)` | Euler 角度姿勢與客戶端補間 |
| `poseQuaternion(id, tx, ty, tz, qx, qy, qz, qw, scale, interpolationTicks)` | 四元數姿勢 |
| `moveTo(id, location)` | 搬移顯示實體；找不到 no-op |
| `despawn(id)`／`despawnAll()` | 移除單一／全部 prop |
| `count()` | 目前 session 追蹤中的 prop 數 |

`spawnPart` 的 `teleportTicks` 與 pose interpolation 要配合內容更新頻率。孤兒掃描使用的 scoreboard tag 屬於引擎實作細節，不應由內容插件依賴。

## 13. World generator API

```kotlin
WorldGeneratorRegistry.register(
    "my-generator",
    Supplier { MyChunkGenerator() },
)

// 載入 world-generator: my-generator 的定義
hanaToki.core.loadContentDefinitions(file)

override fun onDisable() {
    WorldGeneratorRegistry.unregister("my-generator")
}
```

- `register` 相同 id 會覆蓋。
- 一定要在載入引用它的 definition 前註冊。
- `isRegistered(id)` 可做同步存在檢查；它只代表 registry 有 supplier，不代表 supplier 能成功產生 generator。
- `create(id)` 每次呼叫 supplier 取得新實例；supplier 丟例外時吞掉並回 null。
- 世界尚未載入、需要 HanaToki 建立時，generator id 無法 create 會拒絕建立，不退回 void，以免後續 chunk 變成虛空。
- 如果 Bukkit 已經載入同名世界，provisioner 會直接沿用，不再驗證 generator，也不重新套用 `world-auto-save`。內容插件必須把預載入順序與既有 world 設定納入驗收。

內容插件通常只需要 `register`／`unregister`；`isRegistered` 與 `create` 主要是 bootstrap introspection／引擎實作面，不承諾為穩定 SPI。

跨插件簽章請用 `java.util.function.Supplier<ChunkGenerator>`，不要改成 Kotlin `() -> ChunkGenerator`。

## 14. Check API

```kotlin
fun interface CheckResolver {
    fun resolve(playerId: UUID, checkId: String): CompletableFuture<String>
}
```

Provider 必須：

- 立即回傳 future，不阻塞 region thread；
- 用 outcome key 表達結果，例如 `success`、`fail`、`crit`；
- 可以在任何執行緒完成 future；consumer 收到後要 `ctx.submit` 才能改 state；
- 自己處理玩家不存在、check id 不存在與底層錯誤的 outcome／exception 策略。

HanaToki 沒找到 resolver 時回 `"unavailable"`。behavior 必須有 fail-safe 分支。

`CheckAggregation`／`CheckAggregator` 目前是引擎內的純邏輯 helper，不列入穩定跨插件 API。它也不會自動餵 `onCheckOutcome`。

## 15. Reward API

```kotlin
data class CompletionResult(
    val completionId: UUID,
    val playerId: UUID,
    val dungeonId: String,
    val resultKey: String,
    val durationMs: Long,
    val stats: Map<String, Long>,
)

fun interface RewardSink {
    fun onCompletion(result: CompletionResult)
}
```

每位 active member 各拿一筆 `CompletionResult`。Provider 應：

1. 立刻把 I/O 排到合適的非 region thread；`onCompletion` 是 fire-and-forget。
2. 以 `completionId` 做持久化冪等，避免同一筆補送時重複發獎。
3. 自己記錄成功／失敗與人工補償證據；HanaToki 沒有 ack。

`onCompletion` 若同步丟例外，dispatcher 目前不會捕捉，可能中斷後續玩家的發獎與 session 收斂。Provider 應在 service 邊界內接住例外、留下紀錄，再把可重試工作交給自己的持久化佇列。

沒有 `RewardSink` 時，結果進目前 JVM 的 in-memory queue。實際 source 只在之後又有一筆 `dispatch` 且當時已有 sink 時，先 drain 舊資料再送新資料；不會在 service registration 當下自動 drain，也不跨伺服器重啟。

## 16. Music API

```kotlin
fun interface MusicCue {
    fun cue(playerId: UUID, cueId: String)
}
```

`ctx.musicCue(id)` 會逐 active member 經玩家 EntityScheduler 呼叫 provider。這適合短 cue 或交給外部 music state machine 的事件；provider 缺席時 HanaToki 靜默略過。

不要用它重播已由另一套背景音樂 director 管理的同一首長音軌，否則兩份播放狀態彼此不知道對方存在。

## 17. Reward quota contracts

這兩支介面由 HanaToki 定義型別，但 core 不提供實作。內容插件可以註冊 provider，讓外部選單／管理工具用同一份額度規則。

```kotlin
interface RewardQuotaLookup {
    fun quotaOf(playerId: UUID, dungeonId: String): IntArray?
}

interface RewardQuotaAdmin {
    fun grantBonusQuota(playerId: UUID, dungeonId: String, amount: Int): Int?
}
```

### `quotaOf`

- `null`：沒有副本／沒有獎勵規則／provider 查不到。
- `[remaining, cap]`：目前剩餘份數與上限。
- `[-1, -1]`：不限量；消費端不要拿 -1 做一般算術。

### `grantBonusQuota`

- 回傳加值後總份數。
- `null` 表示沒有規則或加值失敗。
- contract 說明 bonus 不受一般 cap 封頂；實際資料一致性、權限與稽核由 provider／呼叫端負責。

## 18. `DungeonInfo` 與 `CheckDescriptor`

```kotlin
data class DungeonInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val expectedMinutes: String,
    val tags: List<String>,
    val freeSlots: Int,
    val totalSlots: Int,
    val activeSessionCount: Int,
) {
    val available: Boolean get() = freeSlots > 0
}
```

`available` 只看 free slot，不代表世界健康、玩家資格或內容 provider 都 ready。若未來拿它做 UI，仍要設計 unavailable／error 狀態。

```kotlin
fun interface CheckDescriptor {
    fun describe(checkId: String): String
}
```

`CheckDescriptor` 目前無 core consumer。可以由自家插件直接使用，但不要宣稱 HanaToki UI 會自動顯示它。

## 19. 失敗與執行緒速查

| 情境 | 現在的行為 | 安全做法 |
|---|---|---|
| future callback 想改 state | callback thread 未指定 | `ctx.submit { ... }` |
| 找不到 check provider | outcome `unavailable` | behavior 做 fail-safe |
| 找不到 music provider | no-op | 音樂不能是通關必要條件 |
| 找不到 reward provider | JVM 記憶體暫存 | provider 儘早註冊，integration 持久化 completion |
| invalid actor／prop 輸入 | 多半 log + no-op | 看結果與 log，不只看 future completed |
| definition 有 id 但 world 失敗 | `hasDungeon` 仍可能 true | 同時查 slot／啟動 log |
| 只重載內容插件 | behavior 沒有 unregister | HanaToki 與內容一起完整重啟 |
| public interface 改動 | 可能 `NoSuchMethodError`／`LinkageError` | 所有直接消費端一起重編、同批部署 |

## 20. 最小驗證清單

內容插件每次改 API 使用方式後，至少驗證：

1. HanaToki 與內容插件都能 `clean test build`。
2. 真 Folia／Lecithin 啟動無 `LinkageError`、`NoSuchMethodError` 或 ownership 例外。
3. definition、world、slot 數符合設定。
4. 玩家從正式入口進場，不靠 `/hanatoki enter` 繞過資格。
5. interaction、check、actor／encounter、resolve 各走到一次。
6. 獎勵 provider 收到唯一 completion，失敗時有可追的紀錄。
7. 離場、死亡、斷線重連、timeout、admin reset 都能收斂。
8. session 場地真的回滾，slot 在回滾後才重新可用。
9. 測試結束後停止伺服器並確認沒有背景 process／port 殘留。

## 21. 動態 Encounter 與 lifecycle hook（0.3.0，2026-08-30）

`ctx.spawnEncounter(encounterId)` 是**定義驅動**的（YAML 裡寫死一種怪、一個座標）。0.3.0 新增第二種形態：內容層在執行期決定生什麼、生在哪，引擎只負責所有權、Folia 派工、終態與 session 結束時的收斂。Roguelike 的 Director、Threat、怪物內容全部住在內容插件；HanaToki 連「怪物」這個詞都不認識。

Package：`com.tinyyana.hanatoki.encounter`

```kotlin
interface StageContext {
    fun dynamicEncounters(): DynamicEncounterHandle
}

class EntitySpawn(location: Location, entityTypeName: String, initializer: Consumer<Entity>?)

interface DynamicEncounterCallbacks {           // 全部在 anchor region 序列化執行
    fun onEntityKilled(runtimeId: String, entityId: UUID, location: Location) {}
    fun onEntityRemoved(runtimeId: String, entityId: UUID, reason: String) {}  // killed | despawned | lost
    fun onCleared(runtimeId: String) {}          // 整場恰好一次；被 despawn 的場沒有
}

interface DynamicEncounterHandle {
    fun spawn(templateId: String, spawns: List<EntitySpawn>, callbacks: DynamicEncounterCallbacks): CompletableFuture<DynamicSpawnResult>
    fun despawn(runtimeId: String): CompletableFuture<Void>
    fun despawnAll(): CompletableFuture<Void>
    fun mutate(entityId: UUID, action: Consumer<Entity>): CompletableFuture<Void>   // 該實體的 EntityScheduler
    fun dropItem(location: Location, stack: ItemStack): CompletableFuture<UUID?>    // 引擎追蹤、session 結束清掉
    fun entitiesOf(runtimeId: String): List<UUID>; fun remaining(runtimeId: String): Int
    fun templateOf(runtimeId: String): String?;   fun activeRuntimeIds(): List<String>
    fun activeEncounterCount(): Int; fun trackedEntityCount(): Int; fun trackedDropCount(): Int
    fun isTracked(entityId: UUID): Boolean
}
```

| 語意 | 保證 |
|---|---|
| definition id 與 runtime id 分離 | `templateId` 只是標籤；回傳的 `runtimeId` 是隨機 UUID，同 template 可並存 |
| 逐座標派工 | 每一筆 `EntitySpawn` 各自派到自己座標的 RegionScheduler；`initializer` 在那個 task 內執行 |
| partial failure rollback | 任何一筆失敗 → 已生出來的逐一經 EntityScheduler 移除、名額釋放、結果 `FAILED`；只有全部成功才登記 |
| 單一終態 | ACTIVE → CLEARED（最後一隻被殺死）或 ACTIVE → DESPAWNED；兩條路在鎖內做「還是 ACTIVE 才改」，`onCleared` 恰好一次 |
| 消失也算離場 | `EntityRemoveFromWorldEvent`（區塊卸載、別的插件 remove、掉出世界）走 `reason=lost`，場不會永遠清不掉；登記完成後對每一隻做 liveness probe 補上生成與登記之間的縫 |
| 有界 | 每個 instance 的 `dynamic-encounters: { max-active, max-entities, max-drops }`（定義檔，預設 8/48/64）；超過 → `REJECTED`，不會「生到上限為止」 |
| session 結束 | `StageEngine.endFor` 收掉所有場與追蹤中的掉落物 |

`DynamicSpawnResult.status` 是 `SPAWNED` / `REJECTED`（沒動世界：cap、空批次、不認得的 EntityType）/ `FAILED`（已回滾）。`/hanatoki admin debug` 多一行 `dynamic encounters=… entities=… drops=… refs=… callbacks=… gone[killed=… despawned=… lost=…]`，soak 結束後前五個全部要是 0；`lost` 高代表有實體沒經死亡就消失（區塊卸載、別的插件在清）。

### 新的 `DungeonBehavior` hook

```kotlin
fun onMemberReady(ctx: StageContext, playerId: UUID) {}   // 進場交易整個完成（局內背包已換好）；起始物品在這裡發
fun onSessionEnd(ctx: StageContext, reason: String) {}    // 任何原因結束；encounter/actor/prop/排程已收，場地還沒回滾
```

`onStageEnter` 跑在傳送與局內背包清空**之前**，在那裡往背包放東西會被清掉——這就是 `onMemberReady` 存在的理由。`reason` 是 `EndReason` 的常數名。兩者都是帶 body 的介面方法，增刪要兩邊一起重編。

## 22. 立體場地與可互動物件（0.4.x，2026-08-30）

Roguelike 的三層回環地圖逼出來的一批原語。全部是 `StageContext` / `PropHandle` 上的方法，
簽章一律 JDK/Bukkit 型別（跨插件規則見 `StageContext` 的 KDoc）。

### 持久幾何：`mutatePersistentBatch` / `readBlock`

```kotlin
fun mutatePersistentBatch(locations: List<Location>, action: Consumer<Block>): CompletableFuture<Void>
fun readBlock(location: Location, reader: Consumer<Block>): CompletableFuture<Void>
```

| 方法 | 什麼時候用 |
|---|---|
| `mutate` | 局內的暈現、可破壞物、事件節點的外觀變化——**該回滾的東西** |
| `mutatePersistentBatch` | topology 固定、不隨 seed 變、一個 slot 蓋一次就沿用的場地本體 |

`mutatePersistentBatch` **不進 diff log**：整局結束不回滾。兩件事因此變成呼叫端的責任——

1. **冪等**：同一個 slot 第二次開局時場地還在，內容層要自己知道「這一版已經蓋過了」。
   建議做法是埋一顆版本標記方塊，開局先 `readBlock` 問世界；**不要只靠自己記一張表**——
   世界資料夾被刪掉時表還在但場地不在，玩家會直接掉進虛空。
2. **世界要寫回磁碟**：定義檔的 `world-auto-save` 必須是 `true`，否則重開機之後場地不見。

派工依 **chunk** 分組（一個 chunk 完整屬於一個 Folia region，所以同 chunk 的方塊可以在一個 task
裡寫完）。逐格呼叫 `regionScheduler.execute` 上萬次會把 region 卡住——這是刻意做成批次的理由。

`readBlock` 存在的理由就是上面那個「問世界」：讀方塊一樣要在擁有那個座標的 region 執行緒上做，
所以它是非同步的。

### 玩家絕對座標：`memberPositions`

```kotlin
fun memberPositions(): CompletableFuture<Map<UUID, DoubleArray>>   // [x, y, z]
```

`nearestMemberDirection` 只給水平方向與距離，平面競技場夠用；**立體地圖要知道玩家在哪一層**
才能把怪生在他那一層的可走地面上，差一個 Y 就是整場怪生在地板底下或半空中。

安全性理由與 `membersWithin` / `nearestMemberDirection` 同一條：座標由**玩家自己的
EntityScheduler** 讀出來，不從 anchor region 跨 region 讀別人的座標。future 完成時不保證在哪條
執行緒，要碰 `state`/`transition`/`resolve` 之前一律先 `submit`。

### 可互動物件的標籤：`PropHandle.spawnText`

```kotlin
fun spawnText(propId: String, location: Location, miniMessage: String,
              billboardToPlayer: Boolean, backgroundArgb: Int): CompletableFuture<Void>
```

場地上可以互動的東西如果只是一顆方塊，玩家看不出它是什麼、也不知道要右鍵。內容是 MiniMessage
**原文**不是 message key（標籤通常要嵌現算出來的東西，例如剩餘次數）；同一個 `propId` 再呼叫一次
就是換字。跟其他 prop 一樣不進 diff log，session 結束時一起收。

### 具名參數的動作列

```kotlin
fun actionBar(playerId: UUID, key: String, params: Map<String, String>)
fun actionBarAll(key: String, params: Map<String, String>)
```

與 `message` 對稱。加這個多載的理由很單純：動作列要放的常常就是「你現在在哪個區域」這種帶變數的字。

## 23. 副本世界的地形保護（0.4.1，2026-08-30）

`DungeonWorldGuard`：**任何** HanaToki 副本世界裡，玩家不能放/挖方塊、不能倒桶、不能掛畫或放
盔甲座。放在引擎層而不是每座副本各寫一次——三種 instance 形態（回滾型、持久型、常駐型）壞掉的
方式不同，但都不該讓玩家動一根手指。內容層自己用 `ctx.mutate` 蓋場地走的是插件路徑，不受影響。

bypass 權限 `hanatoki.build` **預設 false，連 op 都沒有**：管理員平常在副本裡手滑放一顆方塊
就會留在那裡。要改場地必須明確開權限。

2026-08-30 正式服實測:在此之前引擎完全沒有這道保護,玩家在刀塚裡放的地獄石留了下來。

## 24. 故障窗驗收矩陣（2026-09-01，真 Lecithin 26.2 / Folia）

「安全 Run 容器」的不變式只有一句：**只要快照曾經成功落地，不論 JVM 在哪一行死掉，玩家最後都能
回到一份合法的永久背包——恰好一份。** 這一節記錄那句話實際被哪些注入驗過，以及每一條的注入手法，
免得下一輪又從零猜「要怎麼撞到那個視窗」。

三條共同判準，每一個窗都要同時成立：**永久物品未遺失、局內物品未複製、同一 Run 未重複結算。**

| 故障窗 | 注入手法 | 驗收腳本 |
|---|---|---|
| 進場交易中斷（傳送/背包切換拿不到線上 Player） | 送出 `enter` 後 150ms 內斷線 | `hanatoki-fault-windows-test.js teleport` |
| journal 寫不進去 | `instances/` 換成同名檔案 | `hanatoki-roguelike-entry-failure-test.js` |
| 換背包後崩潰（ACTIVE 落地即斷電） | `hanatoki-rig.ps1 kill` | `hanatoki-fault-restart.ps1 -Window crash` |
| restore 中途崩潰 | `hanatoki-journal.py setstate … RESTORING` 後 kill | `hanatoki-fault-restart.ps1 -Window restore` |
| 結算中途崩潰 | `completions.db` 那筆改回 `pending` 後 kill | `hanatoki-fault-restart.ps1 -Window settle` |
| 斷線超過 `reconnect-grace-seconds` | 斷線後等 grace + 20s | `hanatoki-fault-windows-test.js grace` |
| Run 進行中 plugin disable/re-enable | `plugman unload/load`（消費端先停、引擎後停） | `hanatoki-fault-windows-test.js plugman` |
| 死在副本裡且停在死亡畫面 | 不按重生 | `hanatoki-roguelike-death-respawn-test.js` |

### 寫這類驗收時會踩到的四個坑（都實際踩過）

1. **`bot.game.dimension` 不能拿來判「有沒有進到副本世界」。** mineflayer 讀的是**維度型別**，
   而副本世界是 overworld 型別的自訂世界——有沒有進場都回報 `overworld`。用它當判準會讓整條進場鏈
   看起來全壞掉。**判進場一律用座標位移**（slot 之間相隔一個 `slot-spacing-blocks`）。
2. **重啟後的「查不到重複結算」是假斷言。** `settlements` 是記憶體紀錄，重啟就空了，所以那條查詢
   在重啟後恆真。要證明沒有重複發放只能讀 `completions.db`：被注入的認領應該已被
   `sweepStalePending` 清掉，而且全表沒有殘留 `pending`。
3. **硬殺 JVM 之後不能要求背包指紋完全相等。** 玩家 `.dat` 沒有落盤，重啟會退回上一次自動存檔，
   背包可能比注入前**多**幾樣。要守的是「該有的一件都沒少」與「局內物品沒有殘留」，不是相等。
4. **難度會被別的測試留下來。** 走背包交易的那幾支刻意把 rig 設成 `peaceful` 且不還原；任何需要
   真的造成傷害的測試都必須自己宣告難度，否則接在它們後面跑就會看到「打不掉血」的連鎖假失敗。
