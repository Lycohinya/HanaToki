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
    /** rc.23:bosses.yml(沒有檔案 = 空)。 */
    val bosses: Map<String, BossSpec> = emptyMap(),
    /** rc.26:chat.yml(沒有檔案 = 空):cue id → 播這個 cue 時一併送到聊天室的台詞。 */
    val chat: Map<String, ChatLine> = emptyMap(),
) {
    val startStage: String get() = stages.first().id
    fun stage(id: String): StageSpec? = stages.firstOrNull { it.id == id }
}

// ---- chat.yml(rc.26)----

/**
 * 播 cue [cueId] 時，除了 cues.yml 的聲光，再把 [textKey](texts)送進聽眾的聊天室。[delaySeconds] 之後才送。
 * 用在戰鬥中的角色台詞:HUD 對話框會打斷操作，聊天室不會。
 */
data class ChatLine(val cueId: String, val textKey: String, val delaySeconds: Double)

// ---- stages.yml ----

data class StageSpec(
    val id: String,
    val onStart: StageEffects,
    /** 全部成立才算完成(AND)。 */
    val complete: List<Condition>,
    val onComplete: StageEffects,
    /** null = 最後一段:完成後播 on-complete、打 `slice-end` log,不結算、不發獎。 */
    val next: String?,
    /** 目前目標(ActionBar 一行字):依序第一個「until 還沒達成」的步驟;空 = 這個 stage 不顯示目標。 */
    val objective: List<ObjectiveStep> = emptyList(),
    /** 所有步驟的 until 都達成後顯示的文案 key;null = 不顯示。 */
    val objectiveDoneKey: String? = null,
    /** 目標定時重播的間隔秒數;0 = 只在進 stage、步驟換了、燈火變了時顯示。 */
    val objectiveEverySeconds: Double = 0.0,
    /** rc.22:這個 stage 完成時,把場上剩下的敵人全部打散(預設 false = 留著,行為同舊版)。 */
    val clearsEnemies: Boolean = false,
    /** rc.22:非 null = 這個 stage 完成後(先跑尾聲)呼叫 `resolve(result)` 結算;只能用在 `next` 為 null 的最後一段。 */
    val resolve: ResolveSpec? = null,
    /** rc.26:非 null = stage 開始後要等任一成員抵達這一區才算「抵達」,才播 [onArrive]、才觸發 `stage-arrive` 波次、才出場這個 stage 的 boss。 */
    val arrive: ArriveSpec? = null,
    /** rc.26:抵達時播的效果(只有 [arrive] 非 null 才能寫)。 */
    val onArrive: StageEffects = StageEffects(),
    /** rc.28:這個 stage 進行期間覆寫局內道具的數值(key = tool.yml 的 tool id);沒寫的道具與欄位沿用 tool.yml。 */
    val toolOverrides: Map<String, ToolOverride> = emptyMap(),
)

/**
 * rc.28 stage 層級的 tool 覆寫。[charges] = 這一段的格數上限(耐久條跟著換)，[passiveRegenSeconds]／[zoneRegenSeconds] = 被動／光源區回補間隔，
 * [refillOnDissolve] = 打散敵人補幾格；[fillOnStart] = 進這個 stage 時把在線成員補到新的上限。沒寫(null)的欄位沿用 tool.yml。
 */
data class ToolOverride(
    val charges: Int?,
    val passiveRegenSeconds: Double?,
    val zoneRegenSeconds: Double?,
    val refillOnDissolve: Int?,
    val fillOnStart: Boolean,
)

/**
 * rc.26 stage 抵達區:以 [marker](任何種類)為原點加世界方塊偏移 [offset] `[dx, dy, dz]`,半徑 [radius] 的球。
 * 任一成員進到球內就抵達(每個 stage 只觸發一次)。
 */
data class ArriveSpec(val marker: String, val radius: Double, val offset: List<Double>)

/**
 * 最後一段結算開關(rc.22)。stage 完成並播完 on-complete 之後,依序播 [epilogue] 的每一步,播完才 `resolve(result)`。
 * 沒有這個區塊的 stage 維持舊行為(最後一段只打 `slice end`,不結算)。
 */
data class ResolveSpec(
    /** 結算 key(CompletionResult.resultKey),LycoHanaToki 的獎勵規則用它比對。 */
    val result: String,
    val epilogue: List<EpilogueStep>,
    /** 對話步驟等全部成員選完的最長秒數;逾時就繼續(斷線或離場的人不會卡住結算)。 */
    val dialogTimeoutSeconds: Double,
)

/**
 * 尾聲的一步:[cue] 與 [dialog] 至少寫一個或只寫 [waitSeconds]。cue 沒有「播完」回呼,所以靠 [waitSeconds] 等;
 * 對話步驟等全部成員選完(最久 [ResolveSpec.dialogTimeoutSeconds]),之後再等 [waitSeconds]。
 */
data class EpilogueStep(
    val cue: String?,
    val dialog: String?,
    val waitSeconds: Double,
    /** cue 的原點 marker;null = 進場 marker。 */
    val marker: String?,
)

/**
 * 一步目標:[textKey] 是 texts key(參數 {charges} {max} {pips});[until] 達成就換下一步,null = 一直停在這步。
 * rc.24:[during] 非 null 時,這一步只在該條件成立期間顯示(不成立就跳過這一步,例如 powered 期間、boss 跪下期間)。
 */
data class ObjectiveStep(val textKey: String, val until: Condition?, val during: Condition? = null)

/** stage 開始/完成時的演出:cue、音樂、對話、標題。 */
data class StageEffects(
    val cues: List<String> = emptyList(),
    /** [com.tinyyana.hanatoki.api.MusicCue] 的 cue id(既有 port)。 */
    val music: String? = null,
    val dialog: String? = null,
    val titleKey: String? = null,
    val subtitleKey: String? = null,
    /** rc.28:只有 stage 的 `on-arrive` 可寫。[dialog] 延遲幾秒才開(讓敵人先現身、使魔再開口)。 */
    val dialogDelaySeconds: Double = 0.0,
    /** rc.26:只有 stage 的 `on-arrive` 可寫。依序一段一段播的對話序列(每段等全部成員讀完才播下一段),播完才算「對話結束」。 */
    val dialogs: List<String> = emptyList(),
    /** rc.26:[dialogs] 每一段等全部成員讀完的上限秒數(有人一直不按也不卡住)。 */
    val dialogTimeoutSeconds: Double = 120.0,
    /** rc.24:只有 stage 的 `on-complete` 可寫。stage 完成時要打開的 gate 物件 id。 */
    val opens: List<String> = emptyList(),
)

sealed interface Condition {
    data class EncounterCleared(val encounter: String) : Condition
    data class ObjectState(val objectId: String, val state: String) : Condition
    data class Reach(val marker: String, val radius: Double) : Condition
    /** rc.23:bosses.yml 的 boss 已被打倒(log `boss-defeated <bossId>`)。 */
    data class BossDefeated(val boss: String) : Condition

    /** rc.24(只能用在 objective 的 until／while):任一成員目前 powered(吃了 power-up)。 */
    data object Powered : Condition

    /** rc.24(只能用在 objective 的 until／while):boss 目前正在跪下(輸出窗口)。 */
    data class BossKneel(val boss: String) : Condition

    /** rc.25(只能用在 objective):這個 stage 開始後,任一成員吃過 power-up(累積旗標,powered 結束後仍成立)。 */
    data object PoweredUsed : Condition

    /** rc.25(只能用在 objective):這個 stage 開始後,[enemy](enemies.yml)的敵人被玩家打散或被 powered 的成員碰散過(累積旗標,清場不算)。 */
    data class EnemyDispersed(val enemy: String) : Condition

    /** rc.26(只能用在 objective 的 until／while):這個 stage 的 `arrive` 已經觸發(沒寫 arrive 的 stage 一開始就是 true)。 */
    data object Arrived : Condition

    /** rc.26(只能用在 objective 的 until／while):這個 stage 有任何燈籠正在燃燒(含暫停中、尚未點亮)。 */
    data object LanternBurning : Condition

    /** 狀態型條件:隨時間來去或隨 stage 累積,只能用在 objective,不能當 stage 的完成條件。 */
    val isTransient: Boolean get() = this is Powered || this is BossKneel || this is PoweredUsed || this is EnemyDispersed || this is Arrived || this is LanternBurning
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

/**
 * 未點亮燈籠的世界標示(只放給該玩家看的持續 cue,LycoLib 端實作成 loop 光效)。
 * 玩家距離 ≤ [radius] 播 [cue];≤ [readyRadius] 換成 [readyCue](「現在閃一下一定點得著」)。
 */
data class ObjectHint(
    val cue: String,
    val readyCue: String?,
    val radius: Double,
    val readyRadius: Double,
    /** 只有主手拿著這個道具時才顯示;null = 不限。 */
    val requiresTool: String?,
)

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
    /** > 0:閃光時持有者離燈籠 ≤ 這個距離就點燃,不必瞄準(仍可用錐形從更遠點燃);0 = 只認錐形。 */
    val igniteRadius: Double = 0.0,
    val hint: ObjectHint? = null,
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

/**
 * 南瓜燈等 power-up(rc.22,marker 必須是 trigger):成員靠近([radius] 內)就讓**全隊** powered [seconds] 秒。
 * 狀態 ready → taken:[consume] = true 時拿走後變 taken(`states.taken` 的 blocks 把南瓜燈換成空的外觀),
 * [respawnSeconds] > 0 才會重新變回 ready;[consume] = false 時一直是 ready,拿過之後要隔
 * max([respawnSeconds], [seconds]) 秒才能再拿(不會站著連續觸發)。
 */
data class PowerUpSpec(
    override val id: String,
    override val markerId: String,
    override val stage: String?,
    override val states: Map<String, StateEffect>,
    val radius: Double,
    val seconds: Double,
    val consume: Boolean,
    val respawnSeconds: Double,
    /** powered 開始時在 marker 上播;**持續型 cue 在 powered 結束時一併 cancel**(音樂疊層寫成 loop cue)。 */
    val startCues: List<String>,
    /** powered 全部結束時在 marker 上播。 */
    val endCues: List<String>,
    /** rc.25:南瓜燈「在架上」(ready 且所屬 stage 進行中)期間持續播的 loop cue,被拿走、換 stage 時取消,重生時再播。讓玩家從遠處(越過樹籬)看到。 */
    val beaconCue: String? = null,
    /** rc.27:在架上期間 marker 上方浮著的發光實體(item display),緩慢上下浮動、慢轉；被拿走就移除，重生再出現。null = 沒有實體。 */
    val prop: PowerUpProp? = null,
) : ObjectSpec

/**
 * power-up 的浮空實體:[item] 是 Bukkit Material 名稱(例如 JACK_O_LANTERN，執行期解析，解析失敗記警告不生成)，
 * 浮在 marker 上方 [height] 格，以 [scale] 倍原尺寸(1.0 = 一整格方塊)顯示；每 [bobSeconds] 秒一個上下週期(幅度 [bob] 格)，
 * 每半個週期慢轉 [spinDegrees] 度。
 */
data class PowerUpProp(val item: String, val scale: Double, val height: Double, val bob: Double, val bobSeconds: Double, val spinDegrees: Double)

object ObjectStates {
    const val POWER_UP_READY = "ready"
    const val POWER_UP_TAKEN = "taken"
    val POWER_UP = listOf(POWER_UP_READY, POWER_UP_TAKEN)

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
        is PowerUpSpec -> POWER_UP
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
    /** 活著(可被照)時跟著敵人的持續 cue;null = 沒有標示。 */
    val aliveCue: String? = null,
    /** 僵直中(現在可以打)跟著敵人的持續 cue,取代 [aliveCue];null = 僵直時不留標示。 */
    val stunnedCue: String? = null,
)

data class EnemySpec(
    val id: String,
    /** [org.bukkit.entity.EntityType] 常數名;必須是 Mob。 */
    val baseEntity: String,
    /** BetterModel 模型 id;null = 不綁模型(載體保持可見)。 */
    val model: String?,
    val animations: EnemyAnimations,
    /** 規則標籤。已實作:needs-flash、seeks-object、harasses-players、frozen-when-watched、chases-players、flees-when-powered。 */
    val rules: Set<String>,
    val health: Double,
    val speed: Double?,
    val damage: Double?,
    val scale: Double?,
    val byPlayers: Map<Int, EnemyMultipliers>,
    val spawnCues: List<String>,
    val needsFlash: NeedsFlashSpec?,
    /** 規則 seeks-object 的設定;沒有該標籤 = null。 */
    val seeksObject: SeeksObjectSpec? = null,
    /** 規則 harasses-players 的設定;沒有該標籤 = null。 */
    val harassesPlayers: HarassSpec? = null,
    /** 規則 frozen-when-watched 的設定;沒有該標籤 = null。 */
    val frozenWhenWatched: FrozenWhenWatchedSpec? = null,
    /** 規則 chases-players 的設定;沒有該標籤 = null。 */
    val chasesPlayers: ChasesPlayersSpec? = null,
    /** 規則 flees-when-powered 的設定;沒有該標籤 = null。 */
    val fleesWhenPowered: FleesWhenPoweredSpec? = null,
) {
    /** 碰到玩家的效果:harasses-players,或 chases-players 內嵌的 contact。 */
    val contact: HarassSpec? get() = harassesPlayers ?: chasesPlayers?.contact

    /** 一般傷害一律取消(牠只能被閃光打散,或在 powered 時被碰散):needs-flash、frozen-when-watched、chases-players、flees-when-powered。 */
    val immuneToDamage: Boolean
        get() = needsFlash != null || frozenWhenWatched != null || chasesPlayers != null || fleesWhenPowered != null

    /** 要進每輪有界掃描的敵人(seeks-object、接觸效果、人偶、追擊)。 */
    val scanned: Boolean
        get() = seeksObject != null || contact != null || frozenWhenWatched != null || chasesPlayers != null

    /** 掃描時需要成員快照(位置、朝向、血量)的敵人;只有 seeks-object 的不需要。 */
    val needsSnapshots: Boolean
        get() = contact != null || frozenWhenWatched != null || chasesPlayers != null

    /** 這個敵人的移動完全由框架下路徑指令控制(拿掉原版的鎖定目標與移動目標 AI)。 */
    val frameworkMoves: Boolean
        get() = seeksObject?.ignoresPlayers == true || frozenWhenWatched != null || chasesPlayers != null
}

/** frozen-when-watched 的姿勢:[id] 寫進 log,[animation] 是模型動畫名。 */
data class PoseSpec(val id: String, val animation: String)

/**
 * 規則 frozen-when-watched(人偶):任一成員的視錐([halfAngle] 半角、[maxDistance] 內)看得到、且(開啟 [occlusion] 時)
 * 中間沒有方塊擋住,就凍結(停 AI 與路徑)並換成隨機 [poses] 之一;沒人看時朝最近的成員走。
 * 視錐只算**成員快照的位置與朝向**;遮擋檢查(raytrace 方塊)只對已在視錐與距離內的候選做,且在敵人自己的 region 執行。
 */
data class FrozenWhenWatchedSpec(
    val halfAngle: Double,
    val maxDistance: Double,
    val occlusion: Boolean,
    /** 這個規則希望的掃描週期(tick);整局掃描週期取所有規則的最小值。 */
    val scanTicks: Int,
    /** 沒人看時 `Pathfinder.moveTo` 的速度倍率。 */
    val speed: Double,
    /** 敵人腳下往上多高算「身體中心」(視錐與遮擋瞄準點)。 */
    val bodyHeight: Double,
    val poses: List<PoseSpec>,
    /** 移動時的模型動畫;null = 沿用 animations.idle。 */
    val movingAnimation: String?,
    /** 從凍結轉為移動時,在敵人位置播(喀喀聲)。 */
    val moveCues: List<String>,
    /** 從移動轉為凍結時,在敵人位置播。 */
    val freezeCues: List<String>,
    /** 兩次 [moveCues] 之間至少隔幾 tick(視線在邊緣來回掃時不連續洗聲)。 */
    val cueCooldownTicks: Int,
    /** rc.28:true = 凍結(被看著)期間接觸玩家不推、不扣燈火(只有移動中的才有接觸效果)。預設 false = 同舊版。 */
    val harmlessWhileFrozen: Boolean = false,
)

enum class ChaseTarget(val id: String) {
    NEAREST("nearest"), FARTHEST("farthest"), LOWEST_HEALTH("lowest-health");

    companion object {
        fun of(id: String): ChaseTarget? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 規則 chases-players(大幽靈):追一位成員。[target] 決定追誰;只考慮 [sightRange] 內的成員,
 * 已鎖定的目標跑出 [sightRange] 但仍在 [giveUpRange] 內會繼續追,超過就放棄(停下來)。
 * [contact] 是接觸效果(跟 harasses-players 同一套型別:push、damage…),null = 只追不碰人。
 */
data class ChasesPlayersSpec(
    val speed: Double,
    val target: ChaseTarget,
    val sightRange: Double,
    val giveUpRange: Double,
    val repathTicks: Int,
    val contact: HarassSpec?,
    /** rc.26:出生後原地不動(不追也不碰人)幾秒才開始追;0 = 一出生就追(同舊版)。用來讓她先現身、說完開場再追。 */
    val startDelaySeconds: Double = 0.0,
)

/**
 * 規則 flees-when-powered:有成員 powered 時(power-up 物件觸發),這隻敵人暫停追擊、改成逃離最近的 powered 成員,
 * 換外觀([animation] 模型動畫與 [poweredCue] 持續 cue),被 powered 成員碰到([touchRadius])就被打散。
 * powered 結束後恢復原本的追擊。必須同時有 chases-players。
 */
data class FleesWhenPoweredSpec(
    val speed: Double,
    /** 逃跑目標點離自己多遠(朝遠離成員的方向,被牆擋住會依序試偏轉角度)。 */
    val fleeDistance: Double,
    val touchRadius: Double,
    val repathTicks: Int,
    /** powered 期間的模型 loop 動畫;null = 不換。 */
    val animation: String?,
    /** powered 期間跟著敵人的持續 cue(loop 光效);null = 不放。 */
    val poweredCue: String?,
    /** 被碰散時播。 */
    val dissolveCue: String?,
    val dissolveTicks: Int,
)

/**
 * 規則 seeks-object:這種敵人朝某個燈籠物件移動;到了 [contestRadius] 內就「啃」它(扣燃燒進度)。
 *
 * - 只有處於 [states] 的燈籠才算目標(例如 burning);其他狀態時這個規則不動作。
 * - 啃燈的計數只算**活著、沒僵直**的敵人(被閃光照住的不啃),最多 [maxDrainers] 隻同時有效;
 *   每隻每秒扣 [drainPerSecond] 秒的燃燒進度(單位 = burn-seconds 的秒,不受範圍內人數倍率影響)。
 * - [ignoresPlayers] = true 時拿掉原版的鎖定目標 AI,她不理玩家(站在燈旁也一樣被威脅燈);
 *   false = 仍保留原版 AI,只是每 [repathTicks] 補一次往燈的路。
 */
data class SeeksObjectSpec(
    val objectId: String,
    val states: Set<String>,
    val repathTicks: Int,
    /** 尋路速度倍率(`Pathfinder.moveTo` 的 speed)。 */
    val speed: Double,
    val contestRadius: Double,
    val drainPerSecond: Double,
    val maxDrainers: Int,
    val ignoresPlayers: Boolean,
    /** 有敵人在啃時,每 [contestCueEverySeconds] 秒在燈籠位置播一次。 */
    val contestCues: List<String>,
    val contestCueEverySeconds: Double,
)

/**
 * 規則 harasses-players:這種敵人碰到玩家([contactRadius] 內)就對他做 [effects],不致死。
 * 冷卻是**每位玩家各算**([cooldownTicks]):幾隻同時貼上來也不會連續連推。
 * 掃描週期 [scanTicks] 與 seeks-object 的 repath 共用同一輪(取所有敵人中最小者)。
 */
data class HarassSpec(
    val contactRadius: Double,
    val cooldownTicks: Int,
    val scanTicks: Int,
    val effects: List<HarassEffect>,
    /** 觸發時在敵人身上播(所有成員聽得到看得到)。 */
    val cues: List<String>,
    /** true = 這種敵人對玩家的原版近戰一律取消(傷害與擊退都不發生),只剩 [effects];避免推擊與近戰疊加。 */
    val suppressMelee: Boolean = false,
)

sealed interface HarassEffect {
    /** 水平往遠離敵人的方向推 [strength],垂直 [lift]。 */
    data class Push(val strength: Double, val lift: Double) : HarassEffect
    /** 扣(負)或補(正)某個局內道具的燈火,夾在 0..max。 */
    data class ToolCharges(val tool: String, val delta: Int) : HarassEffect
    /** 原版藥水效果([effect] 是 namespaced key,例如 minecraft:slowness)。 */
    data class Potion(val effect: String, val durationTicks: Int, val amplifier: Int) : HarassEffect
    /** 傷害 [amount];玩家血量 <= [minHealth] 時不扣(不致死)。 */
    data class Damage(val amount: Double, val minHealth: Double) : HarassEffect
}

object EnemyRules {
    const val NEEDS_FLASH = "needs-flash"
    const val SEEKS_OBJECT = "seeks-object"
    const val HARASSES_PLAYERS = "harasses-players"
    const val FROZEN_WHEN_WATCHED = "frozen-when-watched"
    const val CHASES_PLAYERS = "chases-players"
    const val FLEES_WHEN_POWERED = "flees-when-powered"

    val IMPLEMENTED = setOf(NEEDS_FLASH, SEEKS_OBJECT, HARASSES_PLAYERS, FROZEN_WHEN_WATCHED, CHASES_PLAYERS, FLEES_WHEN_POWERED)

    /** 標籤擴充點:載入器接受(內容可以先寫上),但本版沒有行為。註冊時打 WARN。rc.22 起全部已實作,目前為空。 */
    val RESERVED: Set<String> = emptySet()
}

// ---- encounters.yml ----

sealed interface Trigger {
    /** 所屬 stage 開始時。 */
    data object StageStart : Trigger
    data class ObjectState(val objectId: String, val state: String) : Trigger
    data class EncounterCleared(val encounter: String) : Trigger
    /** rc.26:所屬 stage 的 `arrive` 觸發時(stage 必須寫 arrive)。 */
    data object StageArrive : Trigger
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
    /** 第 i 隻出生在該組排序後第 `(markerOffset + i) % 組大小` 個 marker;讓同一組出生點的不同波次用不同位置。 */
    val markerOffset: Int = 0,
    /** rc.25:不用 mob_spawn 組,改在這個 marker(任何種類)出生;[markerGroup] 此時是空字串。 */
    val spawnMarker: String? = null,
    /** rc.25:出生點相對 [spawnMarker] 的偏移 `[dx, dy, dz]`(世界方塊);空 = 不偏移。 */
    val spawnOffset: List<Double> = emptyList(),
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
    /** 低於上限時每 N 秒 +1(不論在哪)。這是「燈火 0 也不會卡死」的保底;null = 沒有被動回補。 */
    val passiveRegenSeconds: Double? = null,
    val zoneRegen: ZoneRegen? = null,
    /** 這次閃光把燈火用到 0 時播給持有者的 cue。 */
    val drainedCues: List<String> = emptyList(),
    /** 自動回補(被動或光源區)+1 時播給持有者的 cue。 */
    val gainCues: List<String> = emptyList(),
    /** rc.28:true = 閃光碰到「正在燒」的燈籠(沒有點到未點燃的燈籠)不扣格，「繼續照住燈」不吃燈火。預設 false = 每次閃光都扣一格。 */
    val freeOnBurningLamp: Boolean = false,
)

/**
 * 光源區回補:持有者站在[處於 [states] 的燈籠]或[指定 marker]半徑 [radius] 內,
 * 回補改成每 [seconds] 秒 +1(取被動與光源區較快的一個,不相加)。
 */
data class ZoneRegen(val seconds: Double, val radius: Double, val states: Set<String>, val markers: List<String>)

// ---- hints.yml ----

sealed interface HintTrigger {
    data object FirstWhiff : HintTrigger
    /** 距上次進展 N 秒。 */
    data class NoProgress(val seconds: Double) : HintTrigger
    data class NearMarker(val marker: String, val radius: Double) : HintTrigger
    /** 某位成員的燈火是 0 時(用盡那一下或空揮)。個人提示,只播給當事人。 */
    data object ToolEmpty : HintTrigger
    /** 某位成員主手沒拿局內道具、卻在還沒僵直的敵人 [radius] 格內。個人提示,只播給當事人。 */
    data class UnequippedNearEnemy(val radius: Double) : HintTrigger
    /** 有敵人連續啃燈 [seconds] 秒(seeks-object 的啃燈沒有中斷過)。全隊提示。 */
    data class LanternContested(val seconds: Double) : HintTrigger
    /** rc.25:[enemy] 的敵人被玩家打散或被 powered 的成員碰散的那一刻。全隊提示。 */
    data class EnemyDispersed(val enemy: String) : HintTrigger
}

data class HintSpec(
    val id: String,
    val trigger: HintTrigger,
    /** null = 任何 stage。 */
    val stage: String?,
    val cues: List<String>,
    val once: Boolean,
    val repeatSeconds: Double,
    /** rc.26:true = 所屬 stage 的 `arrive` 觸發之前不播(所屬 stage 必須寫 arrive)。預設 false = 同舊版。 */
    val requiresArrival: Boolean = false,
)
