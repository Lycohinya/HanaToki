package com.tinyyana.hanatoki.content

/**
 * 資料驅動副本內容的不可變規格(由七個 YAML 解析而來,見 docs/CONTENT-DATA.md)。
 * 全部是純 Kotlin 型別:解析與驗證可以脫離 Bukkit 單元測試;材質、實體型別、方塊字串一律留成字串,
 * 到註冊時(有 Bukkit registry 的執行期)才解析。
 */
data class ContentSpec(
    val dungeonId: String,
    /** cue/dialog 註冊的命名空間(LycoLib 端的 namespace)。 */
    val namespace: String,
    /** 依 stages.yml 宣告順序;第一個是起始 stage。 */
    val stages: List<StageSpec>,
    val scene: SceneSpec,
    val objects: Map<String, ObjectSpec>,
    val enemies: Map<String, EnemySpec>,
    val encounters: Map<String, EncounterSpec>,
    val tools: Map<String, ToolSpec>,
    val hints: Map<String, HintSpec>,
) {
    val startStage: String get() = stages.first().id
    fun stage(id: String): StageSpec? = stages.firstOrNull { it.id == id }
}

// ---- stages.yml ----

data class StageSpec(
    val id: String,
    val onStart: StageEffects,
    /** 全部成立才算完成(AND)。 */
    val complete: List<Condition>,
    val onComplete: StageEffects,
    /** null = 最後一段:完成後播 on-complete、打 `slice-end` log,不結算、不發獎。 */
    val next: String?,
)

/** stage 開始/完成時的演出:cue、音樂、對話、標題。 */
data class StageEffects(
    val cues: List<String> = emptyList(),
    /** [com.tinyyana.hanatoki.api.MusicCue] 的 cue id(既有 port)。 */
    val music: String? = null,
    val dialog: String? = null,
    val titleKey: String? = null,
    val subtitleKey: String? = null,
)

sealed interface Condition {
    data class EncounterCleared(val encounter: String) : Condition
    data class ObjectState(val objectId: String, val state: String) : Condition
    data class Reach(val marker: String, val radius: Double) : Condition
}

// ---- scene.yml ----

data class SceneSpec(val entry: String, val pieces: Map<String, PieceSpec>)

/** [origin] 相對於 slot anchor 的方塊偏移;[rotationDegrees] 為 0/90/180/270(順時針)。 */
data class PieceSpec(val id: String, val asset: String, val ox: Int, val oy: Int, val oz: Int, val rotationDegrees: Int)

// ---- objects.yml ----

/** 某個物件狀態被進入時要做的事:播 cue、替換方塊(座標相對 marker)。 */
data class StateEffect(val cues: List<String> = emptyList(), val blocks: List<BlockEdit> = emptyList())

data class BlockEdit(val dx: Int, val dy: Int, val dz: Int, val block: String)

sealed interface ObjectSpec {
    val id: String
    val markerId: String
    /** null = 整局都存在;非 null = 只在該 stage 進行中才能被互動。 */
    val stage: String?
    val states: Map<String, StateEffect>
}

data class RepeatCue(val cue: String, val everySeconds: Double)
data class Milestone(val at: Double, val effect: StateEffect)
data class LanternLit(
    val cues: List<String>,
    val lightRadius: Double,
    val lightLevel: Int,
    val lightSpacing: Int,
    val clearsEnemies: Boolean,
    /** 要打開的 gate 物件 id。 */
    val opens: String?,
)
data class ProgressBar(val textKey: String, val color: String)

data class LanternSpec(
    override val id: String,
    override val markerId: String,
    override val stage: String?,
    override val states: Map<String, StateEffect>,
    val burnSeconds: Double,
    val stayRadius: Double,
    /** 範圍內人數 → 燒燈速度倍率;人數超過最大 key 用最大 key 那筆;沒寫的人數 = 1.0。 */
    val speedByPlayers: Map<Int, Double>,
    val holdCue: String?,
    val repeatCue: RepeatCue?,
    val milestones: List<Milestone>,
    val pauseCues: List<String>,
    val resumeCues: List<String>,
    val onLit: LanternLit,
    val progressBar: ProgressBar?,
) : ObjectSpec

data class GateSpec(
    override val id: String,
    override val markerId: String,
    override val stage: String?,
    override val states: Map<String, StateEffect>,
    /** 每多少 tick 滑開一欄。 */
    val columnTicks: Int,
    /** left = 從 marker(左下角)那一端往另一端滑開;right = 反向。 */
    val slideFrom: String,
) : ObjectSpec

object ObjectStates {
    const val LANTERN_UNLIT = "unlit"
    const val LANTERN_BURNING = "burning"
    const val LANTERN_LIT = "lit"
    const val GATE_CLOSED = "closed"
    const val GATE_OPEN = "open"

    val LANTERN = listOf(LANTERN_UNLIT, LANTERN_BURNING, LANTERN_LIT)
    val GATE = listOf(GATE_CLOSED, GATE_OPEN)

    fun valid(spec: ObjectSpec): List<String> = when (spec) {
        is LanternSpec -> LANTERN
        is GateSpec -> GATE
    }
}

// ---- enemies.yml ----

data class EnemyAnimations(val idle: String, val stunned: String, val dissolve: String)

/** 各人數倍率;沒寫的欄位 = 1.0。 */
data class EnemyMultipliers(val health: Double = 1.0, val speed: Double = 1.0, val damage: Double = 1.0)

data class NeedsFlashSpec(
    val whiffCue: String?,
    val stunCue: String?,
    val dissolveCue: String?,
    val refillCue: String?,
    /** 打散到實體移除的延遲 tick(要蓋住模型 dissolve 動畫)。 */
    val dissolveTicks: Int,
    val whiffCooldownTicks: Int,
    val glowOnStun: Boolean,
)

data class EnemySpec(
    val id: String,
    /** [org.bukkit.entity.EntityType] 常數名;必須是 Mob。 */
    val baseEntity: String,
    /** BetterModel 模型 id;null = 不綁模型(載體保持可見)。 */
    val model: String?,
    val animations: EnemyAnimations,
    /** 規則標籤。已實作:needs-flash。保留(接受但不實作):frozen-when-watched、flees-when-powered。 */
    val rules: Set<String>,
    val health: Double,
    val speed: Double?,
    val damage: Double?,
    val scale: Double?,
    val byPlayers: Map<Int, EnemyMultipliers>,
    val spawnCues: List<String>,
    val needsFlash: NeedsFlashSpec?,
)

object EnemyRules {
    const val NEEDS_FLASH = "needs-flash"
    const val FROZEN_WHEN_WATCHED = "frozen-when-watched"
    const val FLEES_WHEN_POWERED = "flees-when-powered"

    val IMPLEMENTED = setOf(NEEDS_FLASH)

    /** 標籤擴充點:載入器接受(內容可以先寫上),但本版沒有行為。註冊時打 WARN。 */
    val RESERVED = setOf(FROZEN_WHEN_WATCHED, FLEES_WHEN_POWERED)
}

// ---- encounters.yml ----

sealed interface Trigger {
    /** 所屬 stage 開始時。 */
    data object StageStart : Trigger
    data class ObjectState(val objectId: String, val state: String) : Trigger
    data class EncounterCleared(val encounter: String) : Trigger
}

data class EncounterSpec(
    val id: String,
    val stage: String,
    val enemy: String,
    val markerGroup: String,
    val trigger: Trigger,
    val delayTicks: Int,
    /** 人數 → 隻數;n 人用 ≤ n 的最大 key。必須含 1。 */
    val counts: Map<Int, Int>,
    val intervalTicks: Int,
    val startCues: List<String>,
)

// ---- tool.yml ----

data class ToolSpec(
    val id: String,
    /** 材質名稱(字串,執行期才解析)。 */
    val material: String,
    val itemModel: String?,
    val nameKey: String,
    val loreKeys: List<String>,
    val slot: Int,
    val maxCharges: Int,
    val startCharges: Int,
    /** 錐形全角(度)。 */
    val coneAngle: Double,
    val range: Double,
    val stunSeconds: Double,
    val cooldownTicks: Int,
    /** 打散敵人時給擊殺者補幾格。 */
    val refillOnDissolve: Int,
    val flashCues: List<String>,
    val emptyCues: List<String>,
    /** 燈火變動時顯示在 ActionBar 的文案 key(參數 {charges}、{max});null = 只用物品耐久條。 */
    val hudTextKey: String?,
)

// ---- hints.yml ----

sealed interface HintTrigger {
    data object FirstWhiff : HintTrigger
    /** 距上次進展 N 秒。 */
    data class NoProgress(val seconds: Double) : HintTrigger
    data class NearMarker(val marker: String, val radius: Double) : HintTrigger
}

data class HintSpec(
    val id: String,
    val trigger: HintTrigger,
    /** null = 任何 stage。 */
    val stage: String?,
    val cues: List<String>,
    val once: Boolean,
    val repeatSeconds: Double,
)
