package com.tinyyana.hanatoki.presentation

import org.bukkit.Location
import org.bukkit.entity.Entity
import java.util.UUID

/**
 * Boss 外觀呈現的服務面——engine-neutral,BetterModel 缺席時退回 [NoOpBossModels]
 * (見 plugin.yml 的 softdepend 說明,與 [BossModelsFactory] 的建構邏輯)。
 *
 * [owner] 是不透明的分組 key:整局結束/整位 owner 收斂時一次關掉它名下所有 handle——同
 * [com.tinyyana.hanatoki.actor.ActorController] 用 sessionId 分組的作法。admin debug 指令
 * 另外用 `"debug:<player uuid>"` 這種第二種 owner,兩者互不干擾。
 */
interface BossModels {
    /** false = BetterModel 沒裝/沒啟用,[bind]/[spawnStatic] 一律回 null。 */
    val available: Boolean

    /**
     * 把模型掛在一個活的 entity 上(EntityTracker),外觀會跟著那個實體移動、頭部跟隨視角。
     * 呼叫端必須已經站在 [entity] 所屬的 region 執行緒上(同
     * [com.tinyyana.hanatoki.folia.InstanceDispatch] 的假設)——不在正確執行緒上呼叫會
     * 記警告並回 null,不會自己派工等待。
     */
    fun bind(owner: String, entity: Entity, modelId: String): BossModelHandle?

    /**
     * 在固定座標生一個不跟任何實體綁定的模型(DummyTracker),給場景擺件/debug 用。
     * 執行緒要求同 [bind]。
     */
    fun spawnStatic(owner: String, location: Location, modelId: String): BossModelHandle?

    /** 關掉這個 owner 名下所有 handle。找不到就是 no-op。 */
    fun closeOwner(owner: String)

    /** 某個 base entity 死亡/被移除時呼叫——關掉綁在它身上的 handle(找不到就是 no-op)。 */
    fun closeForEntity(entityId: UUID)

    /** onDisable/PlugMan 熱插拔收尾用:全部關掉。 */
    fun closeAll()

    /** 這台伺服器目前載入的 BetterModel 模型 id(`model list`/tab-complete 用)。 */
    fun modelIds(): Collection<String>

    /** 某個模型 id 有哪些動畫名字。找不到這個模型就回空集合。 */
    fun animationNames(modelId: String): Collection<String>
}

/** BetterModel 沒裝/沒啟用時的退回實作——全部方法安全地什麼都不做。 */
object NoOpBossModels : BossModels {
    override val available: Boolean = false
    override fun bind(owner: String, entity: Entity, modelId: String): BossModelHandle? = null
    override fun spawnStatic(owner: String, location: Location, modelId: String): BossModelHandle? = null
    override fun closeOwner(owner: String) {}
    override fun closeForEntity(entityId: UUID) {}
    override fun closeAll() {}
    override fun modelIds(): Collection<String> = emptyList()
    override fun animationNames(modelId: String): Collection<String> = emptyList()
}
