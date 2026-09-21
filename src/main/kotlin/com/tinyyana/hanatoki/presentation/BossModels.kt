package com.tinyyana.hanatoki.presentation

import org.bukkit.entity.Entity
import java.util.UUID

/**
 * Boss 外觀呈現的服務面——engine-neutral,BetterModel 缺席時退回 [NoOpBossModels]
 * (見 plugin.yml 的 softdepend 說明,與 [BossModelsFactory] 的建構邏輯)。
 *
 * [owner] 是不透明的分組 key:整局結束/整位 owner 收斂時一次關掉它名下所有 handle——同
 * [com.tinyyana.hanatoki.actor.ActorController] 用 sessionId 分組的作法。admin debug 指令
 * 另外用 `"debug:<player uuid>"` 這種第二種 owner,兩者互不干擾。
 *
 * ⚠ 只有 [bind](EntityTracker)——2026-09-22 的兩機器人封包測試證實 `DummyTracker`(原本的
 * `spawnStatic`)不會自動追蹤任何觀察者,`.spawn(player)` 從來沒被呼叫過,結果是「指令回報
 * 成功,但沒有任何一個 client 收到過任何一個 item_display 封包」。BetterModel 內建指令用的
 * 是 EntityTracker(綁在一隻 Husk 上),那條路才真的會自動追蹤觀察者。詳情見
 * `presentation.bettermodel.BetterModelBossModels` 的 KDoc。
 */
interface BossModels {
    /** false = BetterModel 沒裝/沒啟用,[bind] 一律回 null。 */
    val available: Boolean

    /**
     * 把模型掛在一個活的 entity 上(EntityTracker),外觀會跟著那個實體移動、頭部跟隨視角,
     * 而且會自動追蹤靠近的觀察者(BetterModel 自己的行為,不需要 HanaToki 另外處理)。
     * 呼叫端必須已經站在 [entity] 所屬的 region 執行緒上(同
     * [com.tinyyana.hanatoki.folia.InstanceDispatch] 的假設)——不在正確執行緒上呼叫會
     * 記警告並回 null,不會自己派工等待。
     *
     * ⚠ [close][BossModelHandle.close] 會把 [entity] 一併移除(見 handle 實作 KDoc)——
     * [entity] 必須是專門為這個模型準備的載體,不能是別的系統也在管理生命週期的實體。
     */
    fun bind(owner: String, entity: Entity, modelId: String): BossModelHandle?

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
    override fun closeOwner(owner: String) {}
    override fun closeForEntity(entityId: UUID) {}
    override fun closeAll() {}
    override fun modelIds(): Collection<String> = emptyList()
    override fun animationNames(modelId: String): Collection<String> = emptyList()
}
