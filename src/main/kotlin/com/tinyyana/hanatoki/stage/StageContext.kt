package com.tinyyana.hanatoki.stage

import org.bukkit.Location
import org.bukkit.block.Block
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * behavior callback 拿到的唯一操作面。所有方法內部自行決定要不要另外派工到③
 * (ARCH §5.1)——behavior 實作**不需要**知道 region/thread 細節,只呼叫這裡的方法。
 *
 * 這是一個 facade interface(不是 data class):實際實作在 [com.tinyyana.hanatoki.HanaTokiCore]
 * 內部(它握有 plugin/registry/dispatcher 這些依賴),這裡刻意只暴露 behavior 真正會用到的
 * 最小操作集——不預先加「以後可能用得到」的方法。
 */
interface StageContext {
    val sessionId: UUID
    val slotId: String
    val dungeonId: String
    val anchor: Location
    val state: InstanceState

    fun activeMembers(): List<UUID>

    /** ARCH §5.1③:針對世界座標的 mutation,自動記錄 diff(供整局結束時回滾)。 */
    fun mutate(location: Location, action: (Block) -> Unit): CompletableFuture<Void>

    /** interaction/encounter 定義的相對偏移已在載入時展開成絕對座標,這裡查表用。 */
    fun interactionLocation(interactionId: String): Location?
    fun encounterLocation(encounterId: String): Location?

    fun message(playerId: UUID, key: String, vararg params: Pair<String, String>)
    fun messageAll(key: String, vararg params: Pair<String, String>) = activeMembers().forEach { message(it, key, *params) }

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

    /** encounter/ 模組:依 [com.tinyyana.hanatoki.config.EncounterDef] 生怪,回傳綁定的 controller。 */
    fun spawnEncounter(encounterId: String, onEntityDeath: (StageContext) -> Unit): CompletableFuture<Unit>

    fun despawnEncounter(encounterId: String)

    fun log(message: String)

    /**
     * 任何非同步回呼(check 結果、CompletableFuture whenComplete...)要碰 [state]/[transition]/
     * [resolve] 之前,一律先用這個回到 anchor 所屬 region(ARCH §5.1②)——不假設回呼在哪個
     * 執行緒上執行。
     */
    fun submit(action: () -> Unit)
}
