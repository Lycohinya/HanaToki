package com.tinyyana.hanatoki.encounter

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * 動態 encounter 的跨插件契約(ARCH §5「Encounter」的第二種形態,2026-08-30)。
 *
 * 跟 `ctx.spawnEncounter(encounterId)` 那條**定義驅動**的路不同:這裡的每一場 encounter 由
 * 內容層在執行期決定「生什麼、生在哪、生幾隻、每隻怎麼初始化」,引擎只負責四件事——
 * **所有權登記、Folia 派工、死亡/消失/清場的單一終態、session 結束時的收斂**。
 * Roguelike 的 Director、Threat、怪物角色全部住在內容插件,引擎連「怪物」這個詞都不認識。
 *
 * ## 身分:definition id 與 runtime id 分離
 *
 * 內容層給的 [EntitySpawn] 批次帶一個 `templateId`(它自己的定義 id,例如 `pack.husk`),
 * 引擎回一個 **runtime id**(隨機 UUID 字串)。同一個 template 可以同時並存好幾場,
 * 各自獨立清場、獨立回呼;`templateId` 只是查詢與 debug 用的標籤。
 *
 * ## ⚠ 跨插件簽章:只用 JDK/Bukkit 型別
 *
 * 同 [com.tinyyana.hanatoki.stage.StageContext] 的紅線:回呼用 [Consumer]/介面、資料用
 * `List`/`UUID`/`String`/primitive/Bukkit 型別,不用 Kotlin function type、`Pair`、預設參數。
 */
class EntitySpawn(
    /** 生成座標(可以跟 anchor 不同 region——每一筆各自派到自己座標的 RegionScheduler)。 */
    val location: Location,
    /** `org.bukkit.entity.EntityType` 的常數名(`"HUSK"`)。不認得的名字整批 spawn 失敗並回滾。 */
    val entityTypeName: String,
    /**
     * 生出來的那一刻、**在該座標的 region task 內**對實體做的初始化(屬性、裝備、名字…)。
     * null = 不做。丟例外視為這一隻生成失敗 → 整批回滾。
     */
    val initializer: Consumer<Entity>?,
)

/**
 * 一場動態 encounter 的生命週期回呼。**全部在 anchor 所屬 region 序列化執行**,可以直接碰
 * `ctx.state`/`transition`/`resolve`,不需要再 `submit`。
 *
 * 每一隻實體恰好收到一次 [onEntityRemoved];[onEntityKilled] 是它的子集(死亡那種離場)。
 * [onCleared] 整場最多一次,而且只在「全部被殺死」時才會有——被 `despawn` 收掉的場不算清場。
 */
interface DynamicEncounterCallbacks {
    /** 這隻死了(有 EntityDeathEvent)。[location] 是死亡當下的座標,掉落/演出用。 */
    fun onEntityKilled(runtimeId: String, entityId: UUID, location: Location) {}

    /**
     * 這隻不再屬於這場 encounter。[reason] 是 [REASON_KILLED]/[REASON_DESPAWNED]/[REASON_LOST]
     * 三者之一——內容層靠這個釋放自己對這隻實體的登記(怪物狀態表、技能冷卻…)。
     */
    fun onEntityRemoved(runtimeId: String, entityId: UUID, reason: String) {}

    /** 最後一隻被殺死。整場恰好一次;被 despawn 的場不會有。 */
    fun onCleared(runtimeId: String) {}

    companion object {
        const val REASON_KILLED = "killed"
        const val REASON_DESPAWNED = "despawned"

        /** 沒有死亡事件就消失了(區塊卸載、別的插件 remove、掉出世界…)。 */
        const val REASON_LOST = "lost"
    }
}

/** [DynamicEncounterHandle.spawn] 的結果。 */
class DynamicSpawnResult(
    /** [STATUS_SPAWNED] / [STATUS_REJECTED] / [STATUS_FAILED]。 */
    val status: String,
    /** 只有 [STATUS_SPAWNED] 才有。 */
    val runtimeId: String?,
    val entityIds: List<UUID>,
    /** 失敗/拒絕的原因(給 log 用,不是玩家文字)。 */
    val reason: String?,
) {
    val spawned: Boolean get() = status == STATUS_SPAWNED

    companion object {
        const val STATUS_SPAWNED = "SPAWNED"

        /** 沒有動任何世界狀態就被擋下:超過 cap、空批次、session 已結束。 */
        const val STATUS_REJECTED = "REJECTED"

        /** 生到一半失敗,**已生出來的全部回收**。 */
        const val STATUS_FAILED = "FAILED"
    }
}

/**
 * 一個 instance 的動態 encounter 操作面(`ctx.dynamicEncounters()`)。
 *
 * 所有回傳 future 的方法都可能在**任意執行緒** complete;要碰 instance 狀態一律先 `ctx.submit`。
 * 查詢類方法([entitiesOf]/[remaining]/[activeEncounterCount]…)是無鎖查表,任何執行緒可呼叫。
 */
interface DynamicEncounterHandle {
    /**
     * 生一場。每一筆 [EntitySpawn] 各自派到自己座標的 RegionScheduler;全部成功才登記並回
     * [DynamicSpawnResult.STATUS_SPAWNED],任何一筆失敗就把已生出來的收掉並回 `FAILED`。
     * 超過這座副本定義的 cap([com.tinyyana.hanatoki.config.DynamicEncounterLimits])回 `REJECTED`。
     */
    fun spawn(templateId: String, spawns: List<EntitySpawn>, callbacks: DynamicEncounterCallbacks): CompletableFuture<DynamicSpawnResult>

    /** 強制收掉一場(剩餘實體逐一經 EntityScheduler 移除)。不會觸發 onCleared。 */
    fun despawn(runtimeId: String): CompletableFuture<Void>

    /** 收掉這個 instance 的所有動態 encounter 與追蹤中的掉落物。 */
    fun despawnAll(): CompletableFuture<Void>

    /**
     * 對某隻追蹤中的實體做事(屬性變更、藥水效果、傳送…),派到**該實體自己的 EntityScheduler**。
     * 實體已不在追蹤表/已 retired → 視為立即完成,action 不會跑。
     */
    fun mutate(entityId: UUID, action: Consumer<Entity>): CompletableFuture<Void>

    fun entitiesOf(runtimeId: String): List<UUID>
    fun remaining(runtimeId: String): Int
    fun templateOf(runtimeId: String): String?
    fun activeRuntimeIds(): List<String>
    fun activeEncounterCount(): Int

    /** 這個 instance 目前追蹤中的實體總數(不含掉落物)。 */
    fun trackedEntityCount(): Int

    /** 這個 instance 目前追蹤中的掉落物數。 */
    fun trackedDropCount(): Int

    fun isTracked(entityId: UUID): Boolean

    /**
     * 在 [location] 生一個**受引擎追蹤**的掉落物:session 結束時一併移除,不會留在場地上給下一局撿。
     * 超過 `max-drops` 回 null(什麼都沒生)——內容層自己決定要不要改成直接進背包。
     * 物品本身要不要標成局內物品是內容層的事([com.tinyyana.hanatoki.api.InstanceItems.mark])。
     */
    fun dropItem(location: Location, stack: ItemStack): CompletableFuture<UUID?>
}
