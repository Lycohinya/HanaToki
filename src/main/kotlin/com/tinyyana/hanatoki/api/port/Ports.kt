package com.tinyyana.hanatoki.api.port

import org.bukkit.Location
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.IntConsumer

/*
 * 資料驅動副本內容(content/)對外的 port。HanaToki 只定義介面,實作者(LycoHanaToki → LycoLib)
 * 經 Bukkit ServicesManager 註冊 [CuePort]/[DialogPort];查不到實作者時 HanaToki 走 no-op 並打一行 INFO
 * (每個 port 每局一次),副本照常跑,不會壞掉。註冊慣例同 [com.tinyyana.hanatoki.api.MusicCue]。
 *
 * ⚠ 跨插件介面:只用 JDK/Bukkit 型別。**不用 Kotlin 函式型別**(`(Int) -> Unit` 會在不同 classloader 的
 * kotlin-stdlib 之間丟 `LinkageError`,見 `StageContext` KDoc),回呼用 [IntConsumer]/[Runnable]。
 * 不使用預設參數值、介面不帶 body。
 */

/** 光效/cue 需要改世界(例如光源方塊)時走這裡。HanaToki 以 `StageContext.mutate` 實作,局結束回滾。 */
fun interface StageWorldMutator {
    fun setBlock(location: Location, data: BlockData)
}

/** 一次 cue 播放的把手。持續型 cue(vfx、循環音)用 [cancel] 收掉;對已結束的 cue 呼叫是 no-op。 */
fun interface CueRun {
    fun cancel()
}

/** cue 播放。cue 的內容(音效、粒子、vfx、HUD 通知、音樂層)在 LycoLib 的 cues.yml/vfx.yml,HanaToki 只引用 ID。 */
interface CuePort {
    /**
     * @param namespace cue 檔註冊時的命名空間(HanaToki 取自 stages.yml 的 `namespace`)
     * @param origin cue 的原點(物件 marker、幽靈位置…)
     * @param audience 聽得到/看得到的玩家(副本成員);**實作端自行把對玩家的操作派到該玩家的 EntityScheduler**
     * @param follow 跟隨的實體(幽靈…),null = 固定在 [origin]
     * @param mutator 光效需要改世界時用;null = 這個 cue 不應改世界
     * @return 永遠非 null;不存在的 cue 回一個 no-op 的 [CueRun] 並自行記 log
     */
    fun play(
        namespace: String,
        cueId: String,
        origin: Location,
        audience: Collection<Player>,
        follow: Entity?,
        mutator: StageWorldMutator?,
    ): CueRun
}

/** 對話播放。 */
interface DialogPort {
    /**
     * @param onDone 1..N = 玩家選的選項;0 = 取消/斷線/無選項。可能在任意執行緒被呼叫。
     */
    fun open(player: Player, namespace: String, dialogId: String, onDone: IntConsumer)
}

/**
 * 探索收集物([com.tinyyana.hanatoki.content.CollectibleSpec])的**終身**收集紀錄查詢(rc.32，選填)。
 * 實作者(LycoHanaToki)把紀錄存在自己的資料庫；沒有實作者時引擎一律當「第一次找到」、進度只算本局。
 *
 * ⚠ 只用 JDK 型別(`Set<String>` 在 bytecode 是 `java.util.Set`)。實作會碰資料庫，所以回傳 future、由實作端排到非 tick 執行緒；
 * 引擎在局開始與成員加入時預取、快取在這一局裡，收下時只查快取(沒回來或失敗 = 當第一次)。
 */
interface CollectionPort {
    /** 這位玩家在 [dungeonId] 已終身收過的藏點 id(物件 id)。 */
    fun collectedSpots(playerId: UUID, dungeonId: String): CompletableFuture<Set<String>>
}
