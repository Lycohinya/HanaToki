# HanaToki

Folia-first 微型副本(instance/dungeon)引擎。從 Lycohinya 的 `LycohinyaCore` 抽離出來,
目標是承載 1–3 分鐘的短局副本到常駐世界型的長壽副本,同一套 lifecycle,玩法類型不偏食
(PvE、解謎、場景事件、非戰鬥通關條件皆可)。

授權:TinyYana Universal Software License (TYUSL) 1.0,見 `LICENSE`。

## 目前狀態:Phase 1(骨架)

依 [Lycohinya 私有規劃 repo](https://github.com/Lycohinya/Lycohinya) 裡的
`docs/hanatoki/MIGRATION_PLAN.md` 分期執行,目前完成 Phase 0(技術查證 spike)與 Phase 1
(引擎骨架:slot 分配、Session 進出/計時/離線重連、方塊 diff 回滾、Folia 派工原語、
PresenceBridge)。**還沒有** Stage/Trigger/Interaction、Encounter、GUI、獎勵/檢定串接——
那些是後續 Phase 的範圍。

## 架構原則

- **引擎不知道具體遊戲內容是什麼**:不認識「花蜜」「共鳴」「太刀」這類 Lycohinya 專屬詞彙,
  只認 dungeon id、check id、outcome 字串、reward key 這類抽象概念。
- **零 Lyco 依賴**:不 `includeBuild`、不 `compileOnly`、不 `depend`/`softdepend` 任何
  `Lyco*` 插件或 `LycohinyaCore`。其他插件要用 HanaToki 的型別編譯,自行在自己的
  `settings.gradle.kts` 裡 `includeBuild("../HanaToki")`。
- **Folia 原生**:所有核心狀態變更走 region-scheduler/entity-scheduler 派工,不假設
  「instance 有 anchor 就等於 anchor 擁有整個場地」——世界/實體操作一律按實際 location/
  entity 擁有權另外派工(見架構設計文件的 Folia concurrency model 一節)。

## 模組

| 模組 | 內容 |
|---|---|
| `instance/` | `SlotPool`(場地 slot 無鎖分配)、`Session`/`MemberState`(進出/計時/離線 grace 狀態機)、`SessionManager` |
| `world/` | `DiffLog`(方塊變更記錄的純排序/分組邏輯)、`WorldDiffRecorder`(實際方塊 diff 記錄與回滾) |
| `folia/` | `WorldOp.dispatch`(world/entity mutation 派工原語)、`InstanceDispatch.submit`(instance 邏輯狀態序列化原語) |
| `api/` | `PresenceBridge`(對外 port,HanaToki 是 provider,primitive-only 簽章) |
| `config/` | `DungeonDefinitionParser`/`DungeonRegistry`(YAML 副本定義解析) |
| `command/` | `/hanatoki`(`hana` 別名) |

## 指令與權限

| 指令 | 說明 |
|---|---|
| `/hanatoki enter <dungeonId>` | 進入一個副本 slot |
| `/hanatoki leave` | 離開目前所在的副本 |
| `/hanatoki admin list` | 列出目前的 slot/session 狀態 |
| `/hanatoki admin kick <player>` | 把玩家踢出目前的 session |
| `/hanatoki admin reset <slotId>` | 強制結算並回收指定 slot |
| `/hanatoki admin debug` | 印出內部狀態供除錯 |

| 權限節點 | 預設 | 說明 |
|---|---|---|
| `hanatoki.enter` | 所有玩家 | 進出副本 slot |
| `hanatoki.admin` | op | 管理指令(list/kick/reset/debug) |

## 開發

```bash
./gradlew build   # 編譯 + 單元測試 + jar
./gradlew test    # 只跑單元測試
```

單元測試涵蓋不依賴 Bukkit 型別的純邏輯(`SlotPool`/`Session`/`SessionManager`/`DiffLog`/
`DungeonDefinitionParser`);牽涉真實 `Location`/`BlockData`/排程的部分(`WorldDiffRecorder`、
`WorldOp`、`DungeonRegistry`、指令)靠實際伺服器上的手動/harness 測試涵蓋——見上游規劃 repo
的 `tools/hanatoki-spike/` 與 `docs/hanatoki/MIGRATION_PLAN.md` 的 Phase 1 完成紀錄。
