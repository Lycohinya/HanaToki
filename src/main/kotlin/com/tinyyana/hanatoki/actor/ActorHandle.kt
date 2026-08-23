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

    /** 目前血量比例(0.0–1.0)。實體不存在或不是可受傷 actor 時回 -1。 */
    fun healthFractionOf(actorId: String): Double

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

    /** 設定無敵狀態——決鬥開場演出時擋傷害,演出結束再開放。 */
    fun setInvulnerable(actorId: String, invulnerable: Boolean): CompletableFuture<Void>
}
