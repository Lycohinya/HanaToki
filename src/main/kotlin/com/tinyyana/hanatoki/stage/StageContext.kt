package com.tinyyana.hanatoki.stage

import com.tinyyana.hanatoki.actor.ActorHandle
import com.tinyyana.hanatoki.prop.PropHandle
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * behavior callback 拿到的唯一操作面。所有方法內部自行決定要不要另外派工到③
 * (ARCH §5.1)——behavior 實作**不需要**知道 region/thread 細節,只呼叫這裡的方法。
 *
 * 這是一個 facade interface(不是 data class):實際實作在 `StageEngine` 內部(它握有
 * plugin/registry/dispatcher 這些依賴),這裡刻意只暴露 behavior 真正會用到的最小操作集
 * ——不預先加「以後可能用得到」的方法。
 *
 * ## ⚠ 這是一個真正的跨插件介面:簽章裡不准出現任何 Kotlin 專屬型別
 *
 * 正式副本內容的 [DungeonBehavior] 實作住在 integration 插件(LycoHanaToki),與 HanaToki
 * **分屬不同 classloader,而且各自從 Paper library loader 載入自己那份 kotlin-stdlib**。
 * 2026-08-23 L3 實測(不是理論顧慮):`loadAdditional(File, SlotPool, (String) -> World?)` 一被
 * 跨插件呼叫就丟
 * `LinkageError: loader constraint violation ... different Class objects for the type
 * kotlin/jvm/functions/Function1`。同一條規則對 `Function0`(`() -> Unit`)與 `kotlin.Pair`
 * (`vararg params: Pair<String, String>`)一樣成立。
 *
 * 所以這個介面一律用 JDK 型別:
 * - 回呼用 [Runnable] / [java.util.function.Consumer],**不用** `() -> Unit` / `(T) -> Unit`
 *   (Kotlin 呼叫端照樣可以寫 lambda,SAM 轉換會處理)。
 * - 具名參數用 `Map<String, String>`(`kotlin.collections.Map` 在 bytecode 就是
 *   `java.util.Map`,是安全的 mapped type),**不用** `vararg Pair`。
 * - 不使用 Kotlin 預設參數值(會編成 `xxx$default` 合成簽章,同一類跨插件 `NoSuchMethodError`
 *   ——見 `LycoLib/.../api/items/LycoItemBridge.kt` 的同一條註記)。需要便利多載就明確寫多載。
 *
 * (帶 body 的介面方法是另一回事——它走 `DefaultImpls`,簽章只含 HanaToki/JDK 型別,全 repo
 * 已有跨插件先例 [`LycoItemBridge.giveItemSkin`]。[DungeonBehavior] 就靠它讓內容只覆寫需要的
 * callback。只是增刪這類方法時兩邊要一起重編,不能只換單邊 jar。)
 */
interface StageContext {
    val sessionId: UUID
    val slotId: String
    val dungeonId: String
    val anchor: Location
    val state: InstanceState

    fun activeMembers(): List<UUID>

    /** ARCH §5.1③:針對世界座標的 mutation,自動記錄 diff(供整局結束時回滾)。 */
    fun mutate(location: Location, action: Consumer<Block>): CompletableFuture<Void>

    /** interaction/encounter 定義的相對偏移已在載入時展開成絕對座標,這裡查表用。 */
    fun interactionLocation(interactionId: String): Location?
    fun encounterLocation(encounterId: String): Location?

    // ---- 對玩家做的事(ARCH §5.2 規則 2:一律經該玩家的 EntityScheduler 派工)----
    //
    // behavior 不需要知道派工這件事,但**不得**繞過這些方法自己 `Bukkit.getPlayer()` 直接
    // 操作——那會從 anchor region 直接碰可能不在該 region 的玩家(ARCH §5.1 修正過一次的同類
    // 錯誤:anchor 的所有權不涵蓋場地內其他實體)。實作面見 `folia/PlayerOp`。

    fun message(playerId: UUID, key: String)
    fun message(playerId: UUID, key: String, params: Map<String, String>)
    fun messageAll(key: String)
    fun messageAll(key: String, params: Map<String, String>)

    /** 演出 cue:大字幕。[subtitleKey] 傳空字串代表不顯示副標。 */
    fun title(playerId: UUID, titleKey: String, subtitleKey: String)
    fun titleAll(titleKey: String, subtitleKey: String)

    /** 演出 cue:動作列——適合戰鬥中的招式預告(不佔聊天欄、重複出現不吵)。 */
    fun actionBar(playerId: UUID, key: String)
    fun actionBarAll(key: String)

    /**
     * 演出 cue:在場地某座標播放音效給**每一位在場成員**(逐人派工到各自的 EntityScheduler,
     * 不是 `world.playSound` 的跨 region 廣播)。
     */
    fun soundAll(location: Location, sound: Sound, volume: Float, pitch: Float)

    /**
     * 演出 cue:在場地某座標放粒子(派工到該座標所屬 region,ARCH §5.1③)。粒子是世界狀態,
     * 附近所有人都看得到,不需要逐人派工。
     */
    fun particles(
        location: Location,
        particle: Particle,
        count: Int,
        spreadX: Double,
        spreadY: Double,
        spreadZ: Double,
        extra: Double,
    )

    /**
     * 對 [location] 半徑 [radius] 內的**在場成員**造成 [amount] 點傷害(招式命中判定)。
     *
     * 距離判定刻意在每位玩家自己的 EntityScheduler task 內做——讀的是他自己 region 的座標,
     * 比較對象是傳進來的不可變 [Location]。從 anchor region 直接讀別人的 location 才是跨 region
     * 讀(ARCH §5.1 一再修正過的同一類錯誤)。
     */
    fun damageMembersWithin(location: Location, radius: Double, amount: Double)

    /**
     * 同上,但指定傷害類型([org.bukkit.damage.DamageType] 的 namespaced key,例如
     * `"minecraft:magic"`)。
     *
     * ## 為什麼需要這個
     *
     * 「一招打幾點」對不同裝備的玩家意義天差地遠:同一個 raw damage,裸裝玩家扣滿,
     * 保護 IV 獄髓全套的玩家幾乎不掉血。只用一種傷害管道的話,數值調到能威脅頂裝玩家時,
     * 中裝玩家會被秒殺;調到中裝玩家能玩時,頂裝玩家站著不動也打不死——2026-08-23 真人
     * 驗收回報「幾乎沒有生存壓力」就是後者。內容層可以把一招拆成「吃護甲的一份 + 不吃
     * 護甲的一份」來壓縮這個落差。
     *
     * ⚠ **哪些傷害類型實際上會繞過護甲/附魔,Paper 的 JavaDoc 完全沒有寫**(2026-08-24 查證
     * `jd.papermc.io/paper/26.2/org/bukkit/damage/DamageType.html`,只有 `getDamageScaling`/
     * `getDamageEffect`/`getExhaustion` 三個屬性,沒有減傷語意)。而且正式服跑的是 Lecithin
     * (Folia 分支),不保證跟 Paper 一致。**內容層要用哪個類型、數值定多少,一律以 L4 實測
     * 為準**,不要照抄任何公式推導。
     *
     * key 解析失敗時退回沒有類型的 [damageMembersWithin](記警告),不讓一個打錯的字串把招式吃掉。
     */
    fun damageMembersWithin(location: Location, radius: Double, amount: Double, damageTypeKey: String)

    /**
     * ARCH §2「Trigger:進入區域」的原語:回傳 [location] 半徑 [radius] 內的在場成員。
     *
     * 與 [damageMembersWithin] 同一條安全性理由——距離判定在每位玩家自己的 EntityScheduler
     * task 內做,不從 anchor region 讀別人的座標。因此結果是非同步的:future 完成時**不保證在
     * 哪條執行緒**,要碰 [state]/[transition]/[resolve] 之前一律先 [submit]。
     */
    fun membersWithin(location: Location, radius: Double): CompletableFuture<List<UUID>>

    /**
     * 從 [location] 指向**最近的在場成員**的水平方向,長度正規化成 1(回傳 `[dx, dz, distance]`)。
     * 場上沒有人時回傳空陣列。
     *
     * ## 為什麼需要這個
     *
     * 招式判定不從 anchor region 讀玩家座標是硬規則(跨 region 讀,ARCH §5.1 修正過好幾次的
     * 同一類錯誤)。但「攻擊完全不朝向玩家」在真人手上讀起來不是「這招好背」,而是「這招壞掉了」
     * ——2026-08-24 回饋:「鎖敵好像有點怪,BOSS 居合居然不會朝著我」。
     *
     * 折衷:方向由**玩家自己的 EntityScheduler** 算出來(讀的是他自己 region 的座標),
     * 內容層拿到方向之後可以自己**吸附到有限個固定方位**——這樣既真的朝著玩家,線路又仍然是
     * 可以背起來的那幾條,兩邊都不放棄。
     *
     * 與 [membersWithin] 同一條非同步警語:future 完成時不保證在哪條執行緒,要碰
     * [state]/[transition]/[resolve] 之前一律先 [submit]。
     */
    fun nearestMemberDirection(location: Location): CompletableFuture<DoubleArray>

    /** ARCH §5.1②:切換 stage(內部會呼叫舊 stage 的 onExit、新 stage 的 onEnter)。 */
    fun transition(stageId: String)

    /** ARCH §2「Resolution」:結束這個 instance,產生 CompletionResult 交給 reward/ 派送。 */
    fun resolve(resultKey: String)

    /**
     * ARCH §9:發一次共鳴檢定請求。缺席(沒有 CheckResolver 註冊)時直接回傳 outcome
     * `"unavailable"`——behavior 的 `when` 分支沒對到已知 outcome 就會落進 else,
     * 這就是「fail-safe 分支」的落地方式(ARCH §9 最後一段)。
     */
    fun requestCheck(playerId: UUID, checkId: String): CompletableFuture<String>

    /** ARCH §6:integration 的 MusicCue port,缺席時無聲降級。 */
    fun musicCue(cueId: String)

    /** encounter/ 模組:依 [com.tinyyana.hanatoki.config.EncounterDef] 生怪。 */
    fun spawnEncounter(encounterId: String, onEntityDeath: Consumer<StageContext>): CompletableFuture<Void>

    fun despawnEncounter(encounterId: String)

    /** actor/ 模組:取得這個 instance 的演出用 NPC 操作面(ARCH §2「Actor」)。 */
    fun actors(): ActorHandle

    /** prop/ 模組:場地擺設(自訂造型的顯示實體,見 [PropHandle] 的 KDoc 為何不用方塊)。 */
    fun props(): PropHandle

    /**
     * 畫面最上方那條 boss bar。整場都看得到,適合放「這一局還剩多久」與「Boss 剩多少血」
     * ——這兩件事用動作列會被招式預告一直蓋掉,用聊天欄會洗版(2026-08-24 真人回饋:
     * 「限時 300 秒,玩家沒看到計時的地方」)。
     *
     * @param text MiniMessage 原文(**不是** message key——bar 上要放的通常是即時算出來的
     *   剩餘時間,不是固定字串)
     * @param progress 0.0–1.0
     * @param colorName `net.kyori.adventure.bossbar.BossBar.Color` 的常數名,例如 `"RED"`
     */
    fun bossBar(text: String, progress: Double, colorName: String)

    /**
     * 同上,但指定 bar 的樣式。[overlayName] 是
     * `net.kyori.adventure.bossbar.BossBar.Overlay` 的常數名——`"PROGRESS"`(實心,預設)或
     * `"NOTCHED_6/10/12/20"`(分段)。蒼櫻現況的 Boss 條是分成十段的,遷移要對得上。
     */
    fun bossBar(text: String, progress: Double, colorName: String, overlayName: String)

    fun hideBossBar()

    /**
     * 這一局的 session 時限還剩幾秒。
     *
     * - 有時限:剩餘秒數,0 = 已到期。
     * - **無時限(Endless Run / 常駐副本):回傳 -1**,不是 0、也不是一個超大值。
     *   0 會讓顯示層畫成「時間到」,超大值則是假倒數(以前常駐副本塞 `Long.MAX_VALUE`,
     *   這裡會回傳 2.9 億年)。先問 [sessionHasTimeLimit] 再決定要顯示倒數還是計時。
     * - 查不到 session:0。
     */
    fun sessionRemainingSeconds(): Long

    /**
     * 這一局有沒有時限。false = Endless Run,顯示層應該改用 [sessionElapsedSeconds] 顯示
     * 「已經跑了多久」而不是倒數(2026-08-29 新增)。
     */
    fun sessionHasTimeLimit(): Boolean

    /** 這一局已經跑了幾秒。Endless Run 的 HUD 要的是這個;查不到 session 回 0。 */
    fun sessionElapsedSeconds(): Long

    fun log(message: String)

    /**
     * 任何非同步回呼(check 結果、CompletableFuture whenComplete...)要碰 [state]/[transition]/
     * [resolve] 之前,一律先用這個回到 anchor 所屬 region(ARCH §5.1②)——不假設回呼在哪個
     * 執行緒上執行。
     */
    fun submit(action: Runnable)

    /**
     * 延後 [delayTicks] tick 之後回到 anchor region 執行(演出節拍、招式前搖/後搖用)。
     * 引擎會在 stage 離開與 session 結束時**自動取消**由這個方法排出的所有任務,behavior 不需要
     * 自己記帳——不取消的下場是場地已經回滾、session 已結束之後,招式排程還在對著空場地放粒子
     * 與判傷(ARCH §5.2 規則 6 的收斂順序)。
     */
    fun submitLater(delayTicks: Long, action: Runnable)

    /** 同上但重複執行,直到 stage 離開/session 結束被自動取消。 */
    fun submitRepeating(initialDelayTicks: Long, periodTicks: Long, action: Runnable)
}
