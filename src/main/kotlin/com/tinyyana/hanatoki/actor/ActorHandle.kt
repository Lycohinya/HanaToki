package com.tinyyana.hanatoki.actor

import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * ARCH §2「Actor」:一個 instance 底下的演出用 NPC 操作面。由 [com.tinyyana.hanatoki.stage.StageContext.actors]
 * 取得,actorId 在同一個 session 內唯一。
 *
 * **範圍刻意很小**——這批方法是被「和風決鬥」這個真實內容案例逼出來的最小集(生成/消失/
 * 固定站位的位移/看向/換裝/受傷綁定),不是預先設計的 NPC framework。沒有對話樹、沒有尋路、
 * 沒有動畫狀態機:需要它們的內容還不存在(ARCH §12「先有案例再抽象」)。
 *
 * 跨插件介面:不使用 Kotlin 預設參數、不放帶 body 的方法(理由見 `StageContext` KDoc)。
 */
interface ActorHandle {

    /**
     * 生成一位 actor。回傳的 future 在實體真的生出來(已在該座標所屬 region 的 task 內執行完)
     * 之後 complete。同一個 actorId 重複 spawn 會先把舊的移除。
     */
    fun spawn(actorId: String, location: Location, spec: ActorSpec): CompletableFuture<Void>

    /** 移除一位 actor(經該實體自己的 EntityScheduler)。找不到就是 no-op。 */
    fun despawn(actorId: String): CompletableFuture<Void>

    /** 這個 session 底下所有 actor 一次移除(session 結束/admin reset 的清場路徑)。 */
    fun despawnAll(): CompletableFuture<Void>

    /** actor 綁定的實體 UUID——內容層要把它當 Boss 血量條/傷害來源時查這個。null = 不存在。 */
    fun entityIdOf(actorId: String): UUID?

    fun isAlive(actorId: String): Boolean

    /**
     * 目前血量比例(0.0–1.0)。實體不存在或不是可受傷 actor 時回 -1。
     *
     * ⚠ **同步讀取,只有在 actor 被釘在 anchor 附近時才安全**(站樁演出 actor 的常態)。
     * 會移動的 actor(有 AI 的 Boss)請用 [healthFractionAsync]——從 anchor region 直接讀一個
     * 可能走到別的 region 的實體的血量,是 ARCH §5.1④ 一再修正過的同一類錯誤。
     */
    fun healthFractionOf(actorId: String): Double

    /**
     * 同上,但讀取在該實體自己的 EntityScheduler 裡做。實體不存在/已死時 future 以 -1 完成。
     *
     * ⚠ future 完成時不保證在哪條執行緒,要碰 `state`/`transition`/`resolve` 之前先
     * `StageContext.submit`(同 [locationOf])。
     */
    fun healthFractionAsync(actorId: String): CompletableFuture<Double>

    /**
     * 直接對 actor 造成傷害(不是玩家打的那一下,是關卡機制造成的——例如共鳴檢定破防成功
     * 對 Boss 的重創)。實體不存在就是 no-op。
     */
    fun damage(actorId: String, amount: Double): CompletableFuture<Void>

    /**
     * actor 目前的位置(**非同步**:讀取在該實體自己的 EntityScheduler 裡做,回傳一份 clone)。
     * 實體不存在/已死時 future 以 `null` 完成。
     *
     * 存在理由:會移動的 actor(有 AI 的 Boss)的招式落點、外觀掛件的跟隨、牽引回競技場的
     * 判定都需要知道牠現在在哪,而 behavior 在 anchor region 上**不准**直接讀別的實體的座標
     * (ARCH §5.1④,跟 `StageContext.nearestMemberDirection` 同一條理由)。
     *
     * ⚠ future 完成時不保證在哪條執行緒,要碰 `state`/`transition`/`resolve` 之前先
     * `StageContext.submit`。
     */
    fun locationOf(actorId: String): CompletableFuture<org.bukkit.Location>

    /** 瞬移到新位置(決鬥的後撤/突進——[ActorSpec.immovable] 的 actor 只能這樣位移)。 */
    fun teleport(actorId: String, location: Location): CompletableFuture<Void>

    /** 轉向看往某座標(固定站位 actor 的「轉身」)。 */
    fun lookAt(actorId: String, target: Location): CompletableFuture<Void>

    /**
     * 轉向看往 [radius] 內最近的玩家。查詢在 actor 自己的 EntityScheduler task 內做
     * (用實體自己 region 的 `getNearbyPlayers`),不是從別的 region 讀玩家座標。
     */
    fun faceNearestPlayer(actorId: String, radius: Double): CompletableFuture<Void>

    /** 換主手物品(例如收刀/拔刀的視覺差異)。傳 null 代表清空。 */
    fun setMainHand(actorId: String, item: org.bukkit.inventory.ItemStack?): CompletableFuture<Void>

    /** 設定名牌文字(MiniMessage 原文);傳 null 隱藏名牌。 */
    fun setDisplayName(actorId: String, text: String?, visible: Boolean): CompletableFuture<Void>

    /**
     * 設定 `Mannequin` 的**描述**(名牌下面那一行)。傳空字串 = 不顯示。
     *
     * ⚠ 存在理由不是「多一個功能」:`Mannequin` 有一個**預設描述**
     * (`Mannequin.defaultDescription()`),不動它的話玩家會在 Boss 名牌底下看到原版寫的那行字。
     * 2026-08-24 真人回饋直接點名這件事(「不用在名字下面放他是 NPC」)——演出用 actor
     * **一律要主動清掉它**,不能靠預設。
     */
    fun setDescription(actorId: String, text: String): CompletableFuture<Void>

    /** 設定無敵狀態——決鬥開場演出時擋傷害,演出結束再開放。 */
    fun setInvulnerable(actorId: String, invulnerable: Boolean): CompletableFuture<Void>

    // ---- 看得見的動作(Phase 5 由「少女的動作要肉眼可辨」這個真人回饋逼出來)----
    //
    // 這批全部是**原版客戶端本來就會播的動畫**,不是自製骨骼系統:換裝、揮擊、受擊、姿勢、
    // 朝向。內容層把它們排成序列就成了「拔刀 → 架勢 → 斬擊 → 後撤」。Paper 的 `Mannequin`
    // 繼承 `LivingEntity`(`swingMainHand`/`playHurtAnimation`)與 `Entity`(`setPose`/
    // `setRotation`),所以這些都不需要封包層介入。

    /** 揮主手——原版的揮擊動畫,是「她真的砍了一刀」最直接的視覺證據。 */
    fun swingMainHand(actorId: String): CompletableFuture<Void>

    /** 受擊抖動(紅光 + 後仰)。[yawDegrees] 是受擊方向,0 = 正面。 */
    fun playHurtAnimation(actorId: String, yawDegrees: Float): CompletableFuture<Void>

    /**
     * 設定身體姿勢。[poseName] 是 [org.bukkit.entity.Pose] 的常數名(例如 `"SNEAKING"`)。
     *
     * ⚠ **不是每個 Pose 都能套在 Mannequin 上**——伺服器端有一份白名單,而且那份白名單只有
     * runtime 查得到(`Mannequin.validPoses()` 是 InternalAPIBridge 橋接,API jar 裡看不到內容)。
     * 傳了不被接受的姿勢時實作會記警告並略過,不會丟例外;內容層要用哪些姿勢請先跑
     * `/hanatoki admin poses` 在實際核心上問一次。
     *
     * [fixed] = true 代表鎖住,伺服器的每 tick 姿勢計算不會把它蓋掉(演出用一律 true)。
     */
    fun setPose(actorId: String, poseName: String, fixed: Boolean): CompletableFuture<Void>

    /** 這台伺服器實際接受哪些 Mannequin 姿勢(runtime 查詢,見 [setPose])。 */
    fun validPoseNames(): List<String>

    /** 直接設定朝向角度(度)。「背對玩家」這種固定演出角度用它,不用 [lookAt] 反推座標。 */
    fun setRotation(actorId: String, yawDegrees: Float, pitchDegrees: Float): CompletableFuture<Void>

    /** 換任一裝備欄(頭/胸/腿/腳/副手)。[slotName] 是 [org.bukkit.inventory.EquipmentSlot] 的常數名。 */
    fun setEquipment(actorId: String, slotName: String, item: org.bukkit.inventory.ItemStack?): CompletableFuture<Void>
}
