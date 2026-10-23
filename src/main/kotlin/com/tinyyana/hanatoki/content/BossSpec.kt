package com.tinyyana.hanatoki.content

/*
 * bosses.yml(rc.23)的不可變規格。純 Kotlin、不碰 Bukkit,解析與驗證可單元測試。
 * 一個 boss = 一座場地上的「必打」敵人:血量用命中數表示,階段、招式時間軸、光環護盾與寶石(場邊燈)、跪下輸出窗口、
 * 倒下與全滅重來全部是資料。框架不認得任何特定角色,其他副本寫一個區塊就能用。
 */

/** boss 人數倍率:血量(命中數)與招式傷害。沒寫的人數 = 1.0。 */
data class BossMultipliers(val health: Double = 1.0, val damage: Double = 1.0)

/** boss 模型動畫名(BetterModel);預設值就是共用契約的動畫名。 */
data class BossAnimations(
    val idle: String = "idle",
    val glide: String = "glide",
    val hit: String = "hit",
    val shieldSpark: String = "shield_spark",
    val kneel: String = "kneel",
    val rise: String = "rise",
    val phaseUp: String = "phase_up",
    val snuff: String = "snuff",
    val defeat: String = "defeat",
    val sitEnd: String = "sit_end",
)

enum class GlideOrder(val id: String) {
    CYCLE("cycle"), RANDOM("random");

    companion object {
        fun of(id: String): GlideOrder? = entries.firstOrNull { it.id == id }
    }
}

/** 在 [BossSpec.points] 之間緩慢滑行:[speed] 格/秒,到點停 [dwellSeconds] 秒再走下一個點。 */
data class BossGlideSpec(val speed: Double, val dwellSeconds: Double, val order: GlideOrder)

/**
 * 一個階段:血量比例 <= [at] 就進入(第一階段 at = 1.0,進場即進入)。換階段的演出放 [onEnter]
 * (cues、音樂、title)。招式要幾階段才開放寫在招式自己的 `phase`。
 */
data class BossPhaseSpec(val number: Int, val at: Double, val onEnter: StageEffects)

/** 光環護盾的演出:亮著時近戰打不動(取消傷害)。[haloBone] 是模型骨骼(選填),護盾碎時隱藏、恢復時顯示。 */
data class BossShieldSpec(
    val upCues: List<String>,
    val breakCues: List<String>,
    /** 護盾擋下近戰時在命中點播(濺金色火花、鏗一聲)。 */
    val sparkCues: List<String>,
    val sparkCooldownTicks: Int,
    val haloBone: String?,
    /** rc.26:光環亮著期間常駐的光效 cue(跟著 boss 載體，loop)；光環碎時取消，恢復時重播。null = 沒有。 */
    val auraCue: String? = null,
)

/**
 * 一顆寶石 = 一盞場邊燈([lamp],lantern 物件)。寶石數量由「目前亮著的場邊燈」推出:燈被熄掉寶石就恢復,
 * 四盞同時亮著光環才碎。[bone] 是模型骨骼(選填,碎時隱藏);[beamCues] 在亮起的燈上播(光束)。
 */
data class BossGemSpec(
    val lamp: String,
    val bone: String?,
    val beamCues: List<String>,
    val breakCues: List<String>,
    val restoreCues: List<String>,
)

enum class LampReset(val id: String) {
    ON_RISE("on-rise"), NEVER("never");

    companion object {
        fun of(id: String): LampReset? = entries.firstOrNull { it.id == id }
    }
}

/** 跪下輸出窗口:[seconds] 秒內任何近戰都有效;起身後依 [lampsReset] 重置場邊燈、光環恢復。 */
data class BossKneelSpec(
    val seconds: Double,
    val cues: List<String>,
    val riseCues: List<String>,
    val riseSeconds: Double,
    val lampsReset: LampReset,
    /** rc.30:跪下輸出的連擊；null = 每下一樣重(同舊版)。 */
    val combo: KneelCombo? = null,
)

/**
 * 跪下輸出窗口的連擊(rc.30)。窗口內任何人的有效近戰命中，距上一下 <= [windowSeconds] 秒就連擊 +1，否則從 1 重算；
 * 傷害倍率 = min([cap], 1 + [step] × (連擊數 − 1))。每下命中播 [hitCues]，並給攻擊者 ActionBar [textKey](參數 `{combo}` `{mult}`)。
 * 窗口最後 [countdownSeconds] 秒每秒播一次 [countdownCues]，並對全隊 ActionBar [countdownTextKey](參數 `{seconds}` 剩幾秒)。
 */
data class KneelCombo(
    val step: Double,
    val cap: Double,
    val windowSeconds: Double,
    val hitCues: List<String>,
    val textKey: String?,
    val countdownSeconds: Double,
    val countdownCues: List<String>,
    val countdownTextKey: String?,
)

/** 吃到這些 power-up 物件可無視光環打她;[fromPhase] 之前它們是「拿走」狀態(看不到也拿不到)。 */
data class BossPumpkinSpec(val objects: List<String>, val fromPhase: Int)

enum class DownedMove(val id: String) {
    NEAREST_REVIVE("nearest-revive-point"), STAY("stay");

    companion object {
        fun of(id: String): DownedMove? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 倒下狀態:玩家受到致死傷害時不死亡,套用這個身體狀態([gameMode] 是 Bukkit GameMode 常數名)。
 * 不能攻擊、不能點燈、不吃傷害;她下一次跪下時全部復活。外觀與提示都是資料。
 */
data class DownedSpec(
    val gameMode: String,
    val invisible: Boolean,
    val glowing: Boolean,
    val invulnerable: Boolean,
    /** 移動速度屬性的乘數修正(MULTIPLY_SCALAR_1):-0.8 = 只剩兩成速度;0 = 不改。transient,斷線與重啟都不會留下。 */
    val speedModifier: Double,
    val revivePoints: List<String>,
    val move: DownedMove,
    /** 復活時設定的血量(點數)。 */
    val reviveHealth: Double,
    val cues: List<String>,
    val markCue: String?,
    val reviveCues: List<String>,
    val titleKey: String?,
    val subtitleKey: String?,
    val hudTextKey: String?,
    val hudEverySeconds: Double,
    /** rc.24:自己倒下時只給他的提示 cue(例如 HUD bark)。 */
    val selfHintCues: List<String> = emptyList(),
    /** rc.24:有人倒下時只給其他在線、沒倒下的成員的提示 cue。 */
    val allyHintCues: List<String> = emptyList(),
    /** rc.24:有人倒下時給其他在線、沒倒下的成員的一次性 ActionBar 提示(texts key)。 */
    val allyHudTextKey: String? = null,
)

enum class WipeHealth(val id: String) {
    FULL("full"), KEEP("keep");

    companion object {
        fun of(id: String): WipeHealth? = entries.firstOrNull { it.id == id }
    }
}

enum class WipePhase(val id: String) {
    FIRST("first"), KEEP("keep");

    companion object {
        fun of(id: String): WipePhase? = entries.firstOrNull { it.id == id }
    }
}

/** 全滅重來:所有人回到 [checkpoint] marker,boss 依 [health]／[phase] 重置,場邊燈重置;前面各段點亮的燈與已開的門不動。 */
data class BossWipeSpec(
    val checkpoint: String,
    val health: WipeHealth,
    val phase: WipePhase,
    val restartDelaySeconds: Double,
    val cues: List<String>,
    val titleKey: String?,
    val subtitleKey: String?,
)

data class BossDefeatSpec(val cues: List<String>, val seconds: Double)

/**
 * 補血(rc.25,選填,預設全關):[onStart] = 開打時(含全滅重來後重新開打)、[onKneel] = 光環碎、她跪下時,
 * 把所有在線成員的血量補滿。讓單人在合理躲避下打得完,不會因為進場時血量不滿就被固定比例傷害連續打倒。
 */
data class BossHealSpec(val onStart: Boolean, val onKneel: Boolean, val refillTools: Boolean = false)

/**
 * 出場演出(rc.24,選填):boss 先在 [marker] 現身(播 [cues] 與一次性 [animation]),停 [holdSeconds] 秒後
 * 用 [glideSeconds] 秒滑(0 = 直接傳送)到 [glideTo](預設 `points` 第一個)落地,再等 start-delay 到才開打。
 * 全滅重來不重播出場演出。
 */
data class BossIntroSpec(
    val marker: String,
    val cues: List<String>,
    val animation: String?,
    /** 出場動畫播多久後回到 idle。 */
    val animationSeconds: Double,
    val holdSeconds: Double,
    val glideSeconds: Double,
    val glideTo: String?,
    /** rc.26:true = 出場後停在 [marker],等 stage `on-arrive.dialogs` 播完才開始計 [holdSeconds] 與 start-delay(沒有對話序列時同舊版)。 */
    val awaitArriveDialogs: Boolean = false,
)

/** boss 血量條(StageContext.bossBar):[textKey] 是 texts key,參數 {percent} {phase}。 */
data class BossBarSpec(val textKey: String, val color: String, val overlay: String)

/** 地面判定區的原點:boss 腳下(預設),或指定 marker。 */
data class MoveOrigin(val marker: String?)

sealed interface MoveShape {
    /** 掃地:金圈 [MoveSpec.warnSeconds] 後從原點往外推 [expandSeconds] 秒到 [maxRadius];跳起來(不在地上)的人躲過。 */
    data class Ring(val maxRadius: Double, val bandWidth: Double, val expandSeconds: Double, val dodgeCues: List<String>) : MoveShape

    /** 落雷:每位目標腳下一個圈(預警開始時定位,不跟隨);圈內的人在 [MoveSpec.warnSeconds] 後受傷。[maxTargets] = 0 表示所有人。 */
    data class Circle(val radius: Double, val maxTargets: Int) : MoveShape

    /** 雙杖十字:兩條垂直的線(X),線寬 [halfWidth]*2、各向兩端延伸 [length];站進線之間的空格。[angleOffset] 度。 */
    data class Cross(val length: Double, val halfWidth: Double, val angleOffset: Double) : MoveShape

    /** 光環迴旋:半徑 [innerRadius]..[radius] 的光束環,留一個 [gapDegrees] 度的缺口,每秒轉 [degreesPerSecond] 度,持續 [durationSeconds] 秒。 */
    data class Spin(
        val radius: Double,
        val innerRadius: Double,
        val gapDegrees: Double,
        val degreesPerSecond: Double,
        val durationSeconds: Double,
        val hitIntervalTicks: Int,
        /** rc.24:光束取樣點播的 cue(沿 [beams] 條光束線,短促、非 loop)。空 = 不畫光束。 */
        val beamCues: List<String> = emptyList(),
        /** rc.24:缺口兩側邊緣取樣點播的 cue。空 = 不畫缺口邊緣。預警期間就開始播(缺口停在起始方位)。 */
        val gapCues: List<String> = emptyList(),
        /** rc.24:取樣週期(tick,4..40);預警與迴旋期間每隔這麼久取樣一輪。 */
        val sampleTicks: Int = 4,
        /** rc.24:缺口外的實心弧上畫幾條光束線(只在有 [beamCues] 時用)。 */
        val beams: Int = 3,
        /** rc.24:每條線(光束線或缺口邊緣)上最多幾個取樣點,從內圈到外圈等距。 */
        val pointsPerLine: Int = 6,
    ) : MoveShape {
        /** 每一輪取樣最多播出幾個 cue(載入時限制 <= [MAX_SAMPLE_CUES])。 */
        val cuesPerSample: Int
            get() = (if (gapCues.isEmpty()) 0 else 2 * gapCues.size * pointsPerLine) +
                (if (beamCues.isEmpty()) 0 else beams * beamCues.size * pointsPerLine)

        val hasVisual: Boolean get() = gapCues.isNotEmpty() || beamCues.isNotEmpty()

        companion object {
            const val MAX_SAMPLE_CUES = 80
        }
    }

    /** 喚敵:在 [markers] 生出 [enemy](enemies.yml 的敵人);[countByPlayers] 決定用幾個 marker(沒寫 = 全部)。同種敵人場上最多 [maxAlive] 隻。 */
    data class Summon(val enemy: String, val markers: List<String>, val countByPlayers: Map<Int, Int>, val maxAlive: Int) : MoveShape

    /** 熄燈:指向一盞亮著的場邊燈,[MoveSpec.warnSeconds] 秒內有人對它閃光就保住,否則燈熄(寶石恢復)。 */
    data class Snuff(
        val flashCues: List<String>,
        val savedCues: List<String>,
        val snuffCues: List<String>,
        /** rc.30:非 null = 也可以點名「燒到這個比例以上」的燈(還沒亮的)，吹熄 = 重置燒燈進度。 */
        val burningMinFraction: Double? = null,
        /** rc.30:燒著的燈被抽中的權重倍數(亮著的燈權重 1)。 */
        val burningWeight: Int = 1,
        /** rc.30:> 0 = 預警期間她滑向那盞燈，停在離燈這麼遠的地方(格)。 */
        val glideStopDistance: Double = 0.0,
        /** rc.30:滑向燈的速度(格/秒)。 */
        val glideSpeed: Double = 6.0,
    ) : MoveShape
}

/** 一招。[fromPhase] = 第幾階段起開放;[weight]／[cooldownSeconds] 決定抽中機率與同一招的最短間隔。 */
data class MoveSpec(
    val id: String,
    val weight: Int,
    val cooldownSeconds: Double,
    val fromPhase: Int,
    /** 預警秒數:演出開始到判定的時間。 */
    val warnSeconds: Double,
    /** 判定後多久才能接下一招。 */
    val recoverySeconds: Double,
    val animation: String?,
    val warnCues: List<String>,
    val hitCues: List<String>,
    /** 命中時扣玩家最大血量的比例(0..1),不看護甲。 */
    val damagePercent: Double,
    val push: HarassEffect.Push?,
    val origin: MoveOrigin,
    val shape: MoveShape,
    /** rc.30:ring／circle 的判定範圍碰到場邊燈([lampRadius] 格內)時，把那盞燈燒進度推進這個比例(1.0 = 直接點亮)；0 = 不影響燈。 */
    val lampBoost: Double = 0.0,
    val lampRadius: Double = 1.5,
    val lampBoostCues: List<String> = emptyList(),
    /** rc.30:circle 選目標時，正在照燈的人被選中的機率偏向(0..1)；0 = 同舊版隨機。 */
    val lightingBias: Double = 0.0,
)

/**
 * boss 載體怎麼移動(rc.30)。[TELEPORT] = 每步 teleportAsync(舊行為)；[VELOCITY] = 開著 AI(拿掉所有目標)、每步設速度讓原版移動推著走，
 * 超過 [BossSpec.moveMode] 一步的距離上限才用傳送。
 *
 * 為什麼:Folia／Lecithin 的 teleportAsync 在目標周圍 8 個 chunk 不全屬於目前 region 時(玩家離她遠、她在載入範圍邊緣)走「移除、複製、重新加入」，
 * BetterModel 聽 EntityAddToWorld／EntityRemoveFromWorld 就把整個模型(載體 zombie 加 44 個 item_display)對所有觀看者重送一次——
 * 滑行每 2 tick 一步 = 玩家看到模型一直消失(bot 封包紀錄 170 秒重送 800 次，最長間隔 1.41 秒)。速度驅動的移動不經過傳送，沒有這個問題。
 */
enum class BossMoveMode(val id: String) {
    TELEPORT("teleport"), VELOCITY("velocity");

    companion object {
        fun of(id: String): BossMoveMode? = entries.firstOrNull { it.id == id }
    }
}

data class BossSpec(
    val id: String,
    /** boss 所在的 stage:這個 stage 開始時 boss 出場。 */
    val stage: String,
    val baseEntity: String,
    val model: String?,
    val scale: Double?,
    val animations: BossAnimations,
    /** 基準血量,單位是「命中數」(1 人)。 */
    val health: Double,
    val byPlayers: Map<Int, BossMultipliers>,
    /** 一下有效近戰扣幾點;[poweredHitWeight] 是 powered(南瓜燈)成員穿過光環那一下。 */
    val hitWeight: Double,
    val poweredHitWeight: Double,
    val hitCooldownTicks: Int,
    /**
     * 攻擊蓄力比例低於這個值不算有效近戰(預設 0 = 不限)。注意:Paper 在傷害事件**之前**就重置蓄力計時
     * (Lecithin `Player.java:1117` onAttack、`:1146` hurtOrSimulate),事件裡讀到的蓄力幾乎是 0,所以預設不能 > 0;
     * 限頻靠 [hitCooldownTicks]。
     */
    val minAttackStrength: Double,
    val startDelaySeconds: Double,
    val spawnCues: List<String>,
    val points: List<String>,
    val glide: BossGlideSpec,
    /** 一招結束後到下一招之間的閒置秒數。 */
    val gapSeconds: Double,
    /** 換階段的演出時間(閒置時換階段才暫停出招)。 */
    val phaseUpSeconds: Double,
    val phases: List<BossPhaseSpec>,
    val moves: Map<String, MoveSpec>,
    val shield: BossShieldSpec,
    val gems: List<BossGemSpec>,
    val kneel: BossKneelSpec,
    val pumpkins: BossPumpkinSpec?,
    val downed: DownedSpec,
    val wipe: BossWipeSpec,
    val defeat: BossDefeatSpec,
    val bar: BossBarSpec?,
    /** rc.24:玩家有效近戰命中時(護盾擋下的不算)在命中點播。 */
    val hitCues: List<String> = emptyList(),
    /** rc.24:出場演出;null = 直接在 points 第一個點出場(同舊版)。 */
    val intro: BossIntroSpec? = null,
    /** rc.25:補血時機;預設不補。 */
    val heal: BossHealSpec = BossHealSpec(false, false),
    /** rc.30:載體的移動方式；預設 [BossMoveMode.TELEPORT] 同舊版。 */
    val moveMode: BossMoveMode = BossMoveMode.TELEPORT,
) {
    val lampIds: List<String> get() = gems.map { it.lamp }
}
