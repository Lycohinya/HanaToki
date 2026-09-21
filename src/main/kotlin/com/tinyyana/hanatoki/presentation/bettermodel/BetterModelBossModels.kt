package com.tinyyana.hanatoki.presentation.bettermodel

import com.tinyyana.hanatoki.folia.WorldOp
import com.tinyyana.hanatoki.presentation.BossModelHandle
import com.tinyyana.hanatoki.presentation.BossModelOwnerRegistry
import com.tinyyana.hanatoki.presentation.BossModels
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.BetterModelBukkit
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.bukkit.platform.BukkitLocation
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.event.PluginEndReloadEvent
import kr.toxicity.model.api.tracker.ModelScaler
import kr.toxicity.model.api.tracker.Tracker
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [BossModels] 的 BetterModel 3.5.0 實作。**全部** BetterModel import 都關在這個 package
 * 裡——`presentation` package 的其餘檔案(`BossModels`/`BossModelHandle`/
 * `BossModelOwnerRegistry`/`BossModelsFactory`)一律不 import 任何 BetterModel class,
 * 不會在 class-loading 時連到它。
 *
 * ## Folia 規則
 * - tracker **建立**(`ModelRenderer.create`)底層會生 `ItemDisplay`,是真的 Bukkit
 *   world/entity mutation。這裡不自己派工等待:呼叫端必須已經站在該實體/該座標所屬的
 *   region 執行緒上(同 [com.tinyyana.hanatoki.folia.InstanceDispatch.submit] 的假設)——
 *   目前唯一呼叫端是 admin debug 指令,天然在對的執行緒上;沒站對執行緒就記警告回 null,
 *   不假裝成功。
 * - tracker 建立之後的**變動**(播動畫/停動畫/換縮放/關閉)一律經 [WorldOp] 派工到
 *   該實體的 `EntityScheduler`(entity-bound)或該座標所屬的 `RegionScheduler`(static),
 *   不直接呼叫 `Bukkit.getRegionScheduler()`/`entity.getScheduler()`(ARCH §5.2 規則 8)。
 * - 讀 locator 座標([BossModelHandle.locator])不碰任何 Bukkit API,只讀
 *   `Tracker`/`RenderedBone` 自己用 lock 保護的內部狀態(`RenderedBone.worldPosition()`)——
 *   任何執行緒呼叫都安全,細節見 [Handle.locator] 的 KDoc。
 *
 * ## `/bettermodel reload` 行為
 * `EntityTrackerRegistry.reload()`(BetterModel 原始碼 `tracker/EntityTrackerRegistry.java:383-393`)
 * 會把每個 tracker 序列化成 `TrackerData`(只挑 `canBeSaved()` = true 的,GENERAL 型模型預設如此,
 * `data/renderer/ModelRenderer.java` 的 `Type.GENERAL(true)`),關閉舊 tracker 之後用同一份資料
 * 重建——也就是說**EntityTracker 在 reload 之後會自動恢復**,不需要這裡插手。這份原始碼裡完全
 * 沒有出現任何處理 `DummyTracker` reload 的路徑(它沒有掛在任何 `EntityTrackerRegistry` 底下),
 * 所以保守假設 reload 會把它關掉——[restoreStaticHandlesAfterReload] 只重建**還記得自己是
 * static 且 tracker 已關閉**的 handle,EntityTracker 的 handle 直接跳過。這件事沒有在真正的
 * BetterModel 執行環境上實測,見交付報告。
 */
class BetterModelBossModels(private val plugin: Plugin) : BossModels {

    override val available: Boolean = true

    private val registry = BossModelOwnerRegistry<Handle>()
    private val entityIndex = ConcurrentHashMap<UUID, String>()

    init {
        BetterModelBukkit.platform().eventBus().subscribe(plugin, PluginEndReloadEvent::class.java) {
            restoreStaticHandlesAfterReload()
        }
    }

    override fun bind(owner: String, entity: Entity, modelId: String): BossModelHandle? {
        val renderer = BetterModel.modelOrNull(modelId) ?: run {
            plugin.logger.warning("[HanaToki] BossModels.bind: 找不到 BetterModel 模型 $modelId")
            return null
        }
        if (!Bukkit.isOwnedByCurrentRegion(entity)) {
            plugin.logger.warning(
                "[HanaToki] BossModels.bind 必須在 entity=${entity.uniqueId} 所屬的 region 執行緒上呼叫(owner=$owner),略過",
            )
            return null
        }
        val tracker = renderer.create(BaseEntity.of(BukkitAdapter.adapt(entity)))
        val key = newKey(owner)
        val handle = Handle(modelId, key, tracker, entity, null)
        registry.put(owner, key, handle)
        entityIndex[entity.uniqueId] = key
        return handle
    }

    override fun spawnStatic(owner: String, location: Location, modelId: String): BossModelHandle? {
        val renderer = BetterModel.modelOrNull(modelId) ?: run {
            plugin.logger.warning("[HanaToki] BossModels.spawnStatic: 找不到 BetterModel 模型 $modelId")
            return null
        }
        if (!Bukkit.isOwnedByCurrentRegion(location)) {
            plugin.logger.warning(
                "[HanaToki] BossModels.spawnStatic 必須在 location=$location 所屬的 region 執行緒上呼叫(owner=$owner),略過",
            )
            return null
        }
        val tracker = renderer.create(BukkitAdapter.adapt(location))
        val key = newKey(owner)
        val handle = Handle(modelId, key, tracker, null, location.clone())
        registry.put(owner, key, handle)
        return handle
    }

    override fun closeOwner(owner: String) = registry.closeOwner(owner)

    override fun closeForEntity(entityId: UUID) {
        val key = entityIndex.remove(entityId) ?: return
        registry.remove(key)?.close()
    }

    override fun closeAll() {
        entityIndex.clear()
        registry.closeAll()
    }

    override fun modelIds(): Collection<String> = BetterModel.modelKeys()

    override fun animationNames(modelId: String): Collection<String> =
        BetterModel.modelOrNull(modelId)?.animations()?.keys ?: emptySet()

    private fun newKey(owner: String) = "$owner#${UUID.randomUUID()}"

    /** 只救 DummyTracker(見 class KDoc);EntityTracker 交給 BetterModel 自己的 reload 流程。 */
    private fun restoreStaticHandlesAfterReload() {
        registry.snapshot().forEach { (_, handle) -> handle.restoreIfStaticAndLost() }
    }

    private inner class Handle(
        override val modelId: String,
        private val key: String,
        @Volatile private var tracker: Tracker,
        private val boundEntity: Entity?,
        private val staticLocation: Location?,
    ) : BossModelHandle {

        @Volatile private var closed = false
        @Volatile private var baseAnimation: String? = null
        @Volatile private var currentScale: Float? = null

        override val isActive: Boolean get() = !closed && !tracker.isClosed

        override fun play(animation: String, loop: Boolean, onEnd: Runnable?): Boolean {
            if (!isActive) return false
            if (tracker.renderer().animation(animation).isEmpty) return false
            val modifier = AnimationModifier.builder()
                .type(if (loop) AnimationIterator.Type.LOOP else AnimationIterator.Type.PLAY_ONCE)
                .build()
            dispatch { t -> t.animate(animation, modifier, Runnable { onEnd?.run() }) }
            return true
        }

        override fun play(animation: String): Boolean = play(animation, false, null)

        override fun stop(animation: String) {
            if (!isActive) return
            dispatch { it.stopAnimation(animation) }
        }

        override fun setBase(animation: String) {
            baseAnimation = animation
            play(animation, true, null)
        }

        /**
         * 直接讀,不經 [dispatch]——`RenderedBone.worldPosition()` 只碰 `Tracker`/`RenderedBone`
         * 內部的 lock(`DuplexLock`),不碰任何 Bukkit world/entity API,任何執行緒呼叫都安全。
         * 換算方式跟 BetterModel 自己「影子跟隨模型」那段程式碼一樣(`EntityTracker.java` 建構子裡
         * shadow 的 tick handler,約行 110-113):
         * `shadow.syncPosition(location().add(wPos.x, wPos.y, wPos.z))`——這裡的
         * `tracker.location()` 是 [BukkitLocation],把它的 `source()` 取出來再加上 bone 的
         * offset 就是骨頭目前的世界座標。
         */
        override fun locator(name: String): Location? {
            if (!isActive) return null
            val t = tracker
            val bone = t.bone(name) ?: return null
            val offset = bone.worldPosition()
            val base = (t.location() as? BukkitLocation)?.source() ?: return null
            return base.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
        }

        override fun scale(value: Float) {
            currentScale = value
            if (!isActive) return
            dispatch { it.scaler(ModelScaler.value(value)) }
        }

        override fun close() {
            if (closed) return
            closed = true
            registry.remove(key)
            boundEntity?.let { entityIndex.remove(it.uniqueId) }
            val t = tracker
            if (t.isClosed) return
            if (boundEntity != null && !boundEntity.isValid) {
                // 實體已經不在了,沒有 region 可以派工——直接關(Tracker.close() 本身冪等)。
                t.close()
                return
            }
            dispatch { it.close() }
        }

        private fun dispatch(action: (Tracker) -> Unit) {
            val t = tracker
            when {
                boundEntity != null -> WorldOp.dispatch(plugin, boundEntity) { if (!t.isClosed) action(t) }
                staticLocation != null -> WorldOp.dispatchAt(plugin, staticLocation) { if (!t.isClosed) action(t) }
            }
        }

        /** DummyTracker 被 reload 收掉之後重建(見 class KDoc);EntityTracker 不需要,直接跳過。 */
        fun restoreIfStaticAndLost() {
            if (closed || boundEntity != null || staticLocation == null) return
            if (!tracker.isClosed) return
            if (!Bukkit.isOwnedByCurrentRegion(staticLocation)) {
                plugin.logger.warning(
                    "[HanaToki] boss model $modelId(key=$key)在 BetterModel reload 後遺失,且目前執行緒不在它所屬 region,略過重建",
                )
                return
            }
            val renderer = BetterModel.modelOrNull(modelId) ?: return
            tracker = renderer.create(BukkitAdapter.adapt(staticLocation))
            currentScale?.let { v -> tracker.scaler(ModelScaler.value(v)) }
            baseAnimation?.let { anim -> play(anim, true, null) }
        }
    }
}
