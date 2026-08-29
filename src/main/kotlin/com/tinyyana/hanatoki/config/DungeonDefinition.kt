package com.tinyyana.hanatoki.config

import com.tinyyana.hanatoki.stage.StageDefinition
import com.tinyyana.hanatoki.stage.StageGraph

/** ARCH §2「Interaction」的物理綁定方式:v1 只做這兩種,夠三燈引路/開門用。 */
enum class InteractionKind { RIGHT_CLICK, PHYSICAL }

/**
 * 一座副本的 **instance 生命週期形態**(MIGRATION_PLAN §5.0)。
 *
 * 這是刀塚(短局)與蒼櫻(常駐)兩個真實案例逼出來的唯一分岔軸,**只影響「instance 什麼時候
 * 算結束」**,不影響 stage 執行、ctx、actor/prop/encounter/check/reward 任何一段。
 *
 * - [SESSION](預設,= Phase 1–4 的既有行為):一次開局 = 一組固定成員 + 一個 slot + 時限;
 *   逾時/全員退出/Resolution 都會結束它,結束時送人回家、回滾場地、歸還 slot。
 * - [PERSISTENT]:同一個 dungeonId 只有一個永不結束的 instance。不因時間或無人而結束,
 *   `resolve()` 只結算「這一輪」然後由 behavior 自己轉場;成員資格跟著「人在不在那個世界」走。
 */
enum class ExecutionMode { SESSION, PERSISTENT }

/** 相對 slot anchor 的整數偏移座標(不是絕對座標——絕對座標由 [DungeonRegistry] 依 slot 展開)。 */
data class InteractionDef(val id: String, val dx: Int, val dy: Int, val dz: Int, val kind: InteractionKind)

/** ARCH §5「Encounter」的最小 spawn 描述:一種敵人、固定數量、固定偏移中心 + 散開半徑。 */
data class EncounterDef(val id: String, val entityType: String, val count: Int, val dx: Int, val dy: Int, val dz: Int, val radius: Double)

/**
 * ARCH §2「DungeonDefinition」——Phase 2 新增 stage 圖 / interaction / encounter / UI 資料欄位
 * (§7「UI 只提供資料能力」)。`stageGraph == null` = Phase 1 舊行為(空副本,進去、計時、超時送回,
 * 沒有 stage/interaction 系統介入),保持向後相容(現有 `test-empty` 定義不用改)。
 */
data class DungeonDefinition(
    val id: String,
    val display: String,
    val worldName: String,
    /**
     * **[worldName] 是不是 HanaToki 專屬的副本世界。**
     *
     * true(預設)= 這個世界屬於引擎:不存在就用 [com.tinyyana.hanatoki.world.VoidChunkGenerator]
     * 自動建一個,而且引擎會把它登記進「副本世界」名單——玩家離開/死亡/重連時會被送回進場前的
     * 位置,不會被留在裡面。
     *
     * false = 這個世界是共用的既有世界(生存主世界、或管理員/Multiverse 準備好的常駐場地)。
     * 引擎只查、不建,也**不會**把它當副本世界處理。指向 `world` 的定義一定要設 false,否則
     * 玩家的生存主世界會被當成「該把人送出去的地方」。
     */
    val worldCreate: Boolean = true,
    /** instance 生命週期形態(見 [ExecutionMode])。預設 [ExecutionMode.SESSION] = 既有行為。 */
    val mode: ExecutionMode = ExecutionMode.SESSION,
    /**
     * 這個世界要用哪個**已註冊**的地形生成器([com.tinyyana.hanatoki.world.WorldGeneratorRegistry])。
     *
     * null(預設)= 引擎自帶的 [com.tinyyana.hanatoki.world.VoidChunkGenerator],而且會套用整套
     * void 世界安全網(邊界自動撐開、出生點屏障平台、固定天色、關 random tick——那些設施存在的
     * 理由就是「虛空 + 逐格回滾」)。
     *
     * 填了 id = 常駐地形世界:引擎只負責把世界用那個生成器建起來/載入,並關掉自然生怪,
     * **不套用上面那套安全網**(套上去會把玩家關進世界邊界、蓋掉生成器自己決定的出生點)。
     * id 沒有註冊時整座副本會被跳過並記 severe log——**刻意不退回 void 生成器**:對一個既有的
     * 地形世界用 void 生成器載入,新區塊會變成虛空,那是不可逆的資料損壞。
     */
    val worldGeneratorId: String? = null,
    /**
     * 自動建立的副本世界要不要寫回磁碟。預設 false——session 型副本的場地 100% 由 behavior
     * 程式化蓋出來、整局結束逐格回滾成空氣,磁碟上沒有任何值得保留的狀態,關掉之後世界資料夾
     * 大小不隨遊玩次數增長。常駐型副本(場地要跨重開機保留)設 true。
     */
    val worldAutoSave: Boolean = false,
    /** 世界邊界在最遠的 slot 之外再留幾格。虛空世界最怕有人一路往外走把空 chunk 全生出來。 */
    val worldBorderMargin: Int = 256,
    val slotCount: Int,
    val slotSpacingBlocks: Int,
    /**
     * slot anchor 的基準座標偏移(沿 X/Z 軸)。多座副本共用同一個 world 時(v1 test 副本的
     * 常態,見 `dungeons.yml`),各自的 slot 都是從 `(0,64,0)` 沿 X 軸展開——不給偏移的話
     * 兩座副本的 slot#0 會落在同一個座標,直接互相污染。預設 0 保留 Phase 1 `test-empty`
     * 的既有行為(當時只有一座副本,不需要偏移)。
     */
    val anchorOffsetX: Int = 0,
    val anchorOffsetZ: Int = 0,
    /**
     * slot anchor 的 Y。預設 64(Phase 1 起的既有值,void 世界的場地高度)。
     * 常駐世界的場地高度由地形生成器決定,要在這裡對齊(蒼櫻的競技場地板是 `base-y + 1`)。
     */
    val anchorY: Int = 64,
    /**
     * 進場落點相對 anchor 的偏移與朝向。預設 (0,0,0)/yaw 0 = 直接落在 anchor 上(既有行為)。
     *
     * 存在理由:常駐副本的 anchor 是**競技場正中央**(Boss 站的地方),把人傳到那裡等於一進場
     * 就貼臉。蒼櫻現況是從競技場南緣往內三格、面向中央進場(`BlendedChunkGenerator
     * .getFixedSpawnLocation`),這三個欄位就是把那個落點表達出來。
     */
    val spawnOffsetX: Double = 0.0,
    val spawnOffsetY: Double = 0.0,
    val spawnOffsetZ: Double = 0.0,
    val spawnYaw: Float = 0f,
    /**
     * 這一局的時限(秒)。**null = 沒有時限**(Endless Run)。
     *
     * YAML 寫 `session-time-limit-seconds: unlimited`(或 `none`/`-1`)就是 null。
     * 刻意不接受「寫一個很大的秒數當無限」——那個值最後會變成 HUD 上的假倒數,
     * 而且到期時會真的把玩家的 Run 收掉(見 [com.tinyyana.hanatoki.instance.Session.timeLimitMs])。
     *
     * ⚠ [ExecutionMode.PERSISTENT] 本來就不看這個值(常駐 instance 恆不逾時)。
     */
    val sessionTimeLimitSeconds: Long?,
    val soloCap: Int,
    val partyCap: Int,
    val reconnectGraceSeconds: Long,
    val tags: List<String> = emptyList(),
    val expectedMinutes: String = "",
    val description: String = "",
    val stageGraph: StageGraph? = null,
    val interactions: Map<String, InteractionDef> = emptyMap(),
    val encounters: Map<String, EncounterDef> = emptyMap(),
    /**
     * **這是引擎自帶的 architecture probe,不是正式內容。**
     *
     * 標了這個的定義預設**不會被註冊**(世界不建、slot 不登記、玩家看不到也進不去),
     * 要在 `dungeons.yml` 的頂層設 `enable-test-dungeons: true` 才會載入。
     *
     * 存在理由(2026-08-24 上線前查到):`test-empty`/`test-puzzle`/`test-combat` 原本是
     * 無條件註冊的,而 `/hanatoki enter` 又是 `default: true`——引擎一裝上正式服,**全體玩家
     * 都可以進 probe 副本**,而 integration 那側還替其中兩座配了通關花蜜,等於一條刷花蜜的路。
     * 這不是引擎的錯誤設定,是「開發期預設」跟「上線」之間少了一道閘門。
     */
    val testOnly: Boolean = false,
    /**
     * SESSION 形態下,玩家死亡要不要走 Resolution(`resultKey=death`)而不是直接退出這一局。
     *
     * false(預設)= 既有行為:死亡 = 退出 Session,不結算、不發獎(刀塚等既有副本靠這個)。
     * true = Roguelike 語意:死亡也是一種 Run 結果,帶 duration/stats 走一次 Resolution 之後
     * 才收斂;`InstanceState.claimResolution` + `SessionManager.endSession` 的兩道原子認領保證
     * 「死亡」與「同一刻的逾時/手動 resolve」只會結算其中一次。
     *
     * ⚠ 對 [ExecutionMode.PERSISTENT] 無效——常駐副本的死亡本來就不退出成員集合(決策 D),
     * 把它改成會結算等於「在蒼櫻死一次就發一次獎」。
     */
    val deathResolution: Boolean = false,
    /** 局內背包隔離設定;null(預設)= 這座副本不做背包隔離,既有副本行為完全不變。 */
    val instanceInventory: InstanceInventoryDef? = null,
)

/**
 * 局內背包隔離(Roguelike 的「安全 Run 容器」)。
 *
 * 開啟後,玩家進場**傳送真的落地之後**才會把永久背包快照持久化、清空、換成 [loadout];
 * 離場(通關/死亡/主動退出/admin reset/斷線逾時/關服/崩潰重啟)一律走同一條 restore。
 * 詳見 `com.tinyyana.hanatoki.inventory.InstanceInventoryService`。
 */
data class InstanceInventoryDef(
    /** 進場時發給玩家的局內起始物品。空 = 進場後就是一個空背包(Roguelike 從零開始)。 */
    val loadout: List<LoadoutEntry> = emptyList(),
)

/**
 * 一格局內起始物品。**只有 material/amount/slot/顯示名**——這是引擎層的最小表達能力,
 * 真正的武器/能力核由內容插件用 `InstanceItems` 自己鑄造後放進來(ARCH §11:正式內容不進引擎)。
 *
 * @param slot 放進哪一格(0-8 是快捷列)。null = 找空位放。
 */
data class LoadoutEntry(
    val material: String,
    val amount: Int = 1,
    val slot: Int? = null,
    val displayName: String? = null,
)

/**
 * 純解析邏輯:吃一個已經攤平的 `Map<String, Any?>`,不碰 Bukkit `ConfigurationSection`,
 * 讓 YAML 欄位規則可以直接單元測試(見 DungeonDefinitionParserTest)。
 * Bukkit 端的 loader 用 `ConfigurationSection.getValues(false)`(遞迴把巢狀 section 轉成
 * `Map<String, Any?>`)產生這裡吃的輸入,不是逐 key 手動攤平——巢狀的 `stages`/`interactions`/
 * `encounters` 才能保持純 Map,這裡才能繼續脫離 Bukkit 單元測試。
 */
object DungeonDefinitionParser {
    class DefinitionError(message: String) : IllegalArgumentException(message)

    fun parse(id: String, raw: Map<String, Any?>): DungeonDefinition {
        fun str(key: String, default: String? = null): String =
            (raw[key] as? String) ?: default ?: throw DefinitionError("dungeons.$id.$key 缺少必要欄位")

        fun int(key: String, default: Int): Int =
            (raw[key] as? Number)?.toInt() ?: default

        fun long(key: String, default: Long): Long =
            (raw[key] as? Number)?.toLong() ?: default

        fun dbl(key: String, default: Double): Double =
            (raw[key] as? Number)?.toDouble() ?: default

        val mode = when (val modeStr = (raw["mode"] as? String)?.lowercase() ?: "session") {
            "session" -> ExecutionMode.SESSION
            "persistent" -> ExecutionMode.PERSISTENT
            else -> throw DefinitionError("dungeons.$id.mode=$modeStr 不是已知的執行形態(session/persistent)")
        }

        val slotCount = int("slot-count", 1)
        if (slotCount < 1) throw DefinitionError("dungeons.$id.slot-count 必須 >= 1")
        // 常駐副本只會有一個永不結束的 instance,多出來的 slot 永遠不會被分配到——與其讓它
        // 靜靜地佔著座標,不如在載入時就講清楚設定寫錯了。
        if (mode == ExecutionMode.PERSISTENT && slotCount != 1) {
            throw DefinitionError("dungeons.$id 是 persistent 形態,slot-count 必須是 1(目前 $slotCount)")
        }

        val timeLimit = parseTimeLimit(id, raw["session-time-limit-seconds"])

        @Suppress("UNCHECKED_CAST")
        val tags = (raw["tags"] as? List<*>)?.map { it.toString() } ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val stagesRaw = raw["stages"] as? Map<String, Any?>
        val stageGraph = stagesRaw?.let { parseStageGraph(id, it) }

        @Suppress("UNCHECKED_CAST")
        val interactionsRaw = raw["interactions"] as? Map<String, Any?> ?: emptyMap()
        val interactions = interactionsRaw.entries.associate { (interId, v) ->
            @Suppress("UNCHECKED_CAST")
            val m = v as? Map<String, Any?> ?: throw DefinitionError("dungeons.$id.interactions.$interId 格式錯誤")
            interId to parseInteraction(id, interId, m)
        }

        @Suppress("UNCHECKED_CAST")
        val encountersRaw = raw["encounters"] as? Map<String, Any?> ?: emptyMap()
        val encounters = encountersRaw.entries.associate { (encId, v) ->
            @Suppress("UNCHECKED_CAST")
            val m = v as? Map<String, Any?> ?: throw DefinitionError("dungeons.$id.encounters.$encId 格式錯誤")
            encId to parseEncounter(id, encId, m)
        }

        return DungeonDefinition(
            id = id,
            display = str("display", id),
            worldName = str("world"),
            worldCreate = (raw["world-create"] as? Boolean) ?: true,
            mode = mode,
            worldGeneratorId = (raw["world-generator"] as? String)?.takeIf { it.isNotBlank() },
            worldAutoSave = (raw["world-auto-save"] as? Boolean) ?: false,
            worldBorderMargin = int("world-border-margin", 256),
            slotCount = slotCount,
            slotSpacingBlocks = int("slot-spacing-blocks", 1024),
            anchorOffsetX = int("anchor-offset-x", 0),
            anchorOffsetZ = int("anchor-offset-z", 0),
            anchorY = int("anchor-y", 64),
            spawnOffsetX = dbl("spawn-offset-x", 0.0),
            spawnOffsetY = dbl("spawn-offset-y", 0.0),
            spawnOffsetZ = dbl("spawn-offset-z", 0.0),
            spawnYaw = dbl("spawn-yaw", 0.0).toFloat(),
            sessionTimeLimitSeconds = timeLimit,
            soloCap = int("solo-cap", 1),
            partyCap = int("party-cap", 4),
            reconnectGraceSeconds = long("reconnect-grace-seconds", 180),
            tags = tags,
            expectedMinutes = (raw["expected-minutes"] as? String) ?: "",
            description = (raw["description"] as? String) ?: "",
            stageGraph = stageGraph,
            interactions = interactions,
            encounters = encounters,
            testOnly = (raw["test-only"] as? Boolean) ?: false,
            deathResolution = (raw["death-resolution"] as? Boolean) ?: false,
            instanceInventory = parseInstanceInventory(id, raw["instance-inventory"]),
        )
    }

    /**
     * `session-time-limit-seconds`:正整數 = 秒數;`unlimited`/`none`/`infinite`/`-1` = 沒有時限。
     *
     * 0 或其他負數**不當成無限**而是報錯——「寫 0 就是不限時」是每個人都會猜錯一次的隱含約定,
     * 而猜錯的後果是一座本來該限時的副本悄悄變成永不結束。要無限就把 `unlimited` 寫出來。
     */
    private fun parseTimeLimit(id: String, raw: Any?): Long? {
        if (raw == null) return 180L
        if (raw is String) {
            return when (raw.trim().lowercase()) {
                "unlimited", "none", "infinite", "endless", "-1" -> null
                else -> throw DefinitionError(
                    "dungeons.$id.session-time-limit-seconds=$raw 不是秒數也不是 unlimited",
                )
            }
        }
        val seconds = (raw as? Number)?.toLong()
            ?: throw DefinitionError("dungeons.$id.session-time-limit-seconds 必須是秒數或 unlimited")
        if (seconds == -1L) return null
        if (seconds <= 0) {
            throw DefinitionError(
                "dungeons.$id.session-time-limit-seconds 必須 > 0;要無時限請明確寫 unlimited(或 -1)",
            )
        }
        return seconds
    }

    private fun parseInstanceInventory(id: String, raw: Any?): InstanceInventoryDef? {
        @Suppress("UNCHECKED_CAST")
        val m = raw as? Map<String, Any?> ?: return null
        if ((m["enabled"] as? Boolean) == false) return null
        val loadoutRaw = m["loadout"] as? List<*> ?: emptyList<Any?>()
        val loadout = loadoutRaw.mapIndexed { index, entry ->
            @Suppress("UNCHECKED_CAST")
            val e = entry as? Map<String, Any?>
                ?: throw DefinitionError("dungeons.$id.instance-inventory.loadout[$index] 格式錯誤")
            val material = (e["material"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw DefinitionError("dungeons.$id.instance-inventory.loadout[$index].material 缺少必要欄位")
            val amount = (e["amount"] as? Number)?.toInt() ?: 1
            if (amount < 1) throw DefinitionError("dungeons.$id.instance-inventory.loadout[$index].amount 必須 >= 1")
            val slot = (e["slot"] as? Number)?.toInt()
            if (slot != null && slot !in 0..40) {
                throw DefinitionError("dungeons.$id.instance-inventory.loadout[$index].slot 必須在 0..40")
            }
            LoadoutEntry(material, amount, slot, (e["name"] as? String)?.takeIf { it.isNotBlank() })
        }
        return InstanceInventoryDef(loadout)
    }

    private fun parseStageGraph(dungeonId: String, raw: Map<String, Any?>): StageGraph {
        val start = raw["start"] as? String
            ?: throw DefinitionError("dungeons.$dungeonId.stages.start 缺少必要欄位")
        @Suppress("UNCHECKED_CAST")
        val listRaw = raw["list"] as? Map<String, Any?>
            ?: throw DefinitionError("dungeons.$dungeonId.stages.list 缺少必要欄位")
        val stages = listRaw.entries.associate { (stageId, v) ->
            @Suppress("UNCHECKED_CAST")
            val m = (v as? Map<String, Any?>) ?: emptyMap()
            stageId to StageDefinition(
                id = stageId,
                timeoutSeconds = (m["timeout-seconds"] as? Number)?.toLong(),
                timeoutTransition = m["timeout-transition"] as? String,
            )
        }
        if (start !in stages) throw DefinitionError("dungeons.$dungeonId.stages.start=$start 不在 list 裡")
        return StageGraph(start, stages)
    }

    private fun parseInteraction(dungeonId: String, interactionId: String, m: Map<String, Any?>): InteractionDef {
        fun i(key: String): Int = (m[key] as? Number)?.toInt() ?: 0
        val kindStr = (m["kind"] as? String) ?: "right-click"
        val kind = when (kindStr) {
            "right-click" -> InteractionKind.RIGHT_CLICK
            "physical" -> InteractionKind.PHYSICAL
            else -> throw DefinitionError("dungeons.$dungeonId.interactions.$interactionId.kind=$kindStr 不是已知類型")
        }
        return InteractionDef(interactionId, i("x"), i("y"), i("z"), kind)
    }

    private fun parseEncounter(dungeonId: String, encounterId: String, m: Map<String, Any?>): EncounterDef {
        fun i(key: String): Int = (m[key] as? Number)?.toInt() ?: 0
        val entity = (m["entity"] as? String)
            ?: throw DefinitionError("dungeons.$dungeonId.encounters.$encounterId.entity 缺少必要欄位")
        val count = (m["count"] as? Number)?.toInt() ?: 1
        val radius = (m["radius"] as? Number)?.toDouble() ?: 2.0
        return EncounterDef(encounterId, entity, count, i("x"), i("y"), i("z"), radius)
    }
}
