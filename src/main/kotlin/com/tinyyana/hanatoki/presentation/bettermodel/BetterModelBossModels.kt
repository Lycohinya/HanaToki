package com.tinyyana.hanatoki.presentation.bettermodel

import com.tinyyana.hanatoki.folia.WorldOp
import com.tinyyana.hanatoki.presentation.BossModelHandle
import com.tinyyana.hanatoki.presentation.BossModelOwnerRegistry
import com.tinyyana.hanatoki.presentation.BossModels
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.bukkit.platform.BukkitLocation
import kr.toxicity.model.api.entity.BaseEntity
import kr.toxicity.model.api.tracker.EntityTracker
import kr.toxicity.model.api.tracker.ModelScaler
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
 * ## 2026-09-22 修正:只留 EntityTracker,刪掉 DummyTracker(`spawnStatic`)
 * 兩機器人封包測試證實 `DummyTracker` 不會自動追蹤任何觀察者(它的 `spawn(PlatformPlayer)`
 * 只有呼叫端主動呼叫才會送封包,`data/renderer/ModelRenderer.java` 的 `create(PlatformLocation)`
 * 那條路徑完全沒有掛任何「有玩家靠近就自動 spawn」的邏輯)——`model spawn` 回報成功、
 * `model play` 回報成功,但沒有任何一個 client 收到任何一個 item_display 封包,locator
 * 也量不到移動(因為 tracker 的 tick 迴圈在 `playerCount() == 0` 時直接 `shutdown()`,見
 * `tracker/Tracker.java:180-183`)。內建 `/bettermodel spawn` 用的是綁在一隻 `Husk` 上的
 * `EntityTracker`,那條路徑會自動追蹤觀察者(`EntityTrackerRegistry` 的
 * `refreshSpawn()`/`spawnIfNotSpawned()`),兩個 bot 都確實收到了 65 個 item_display。
 * 真正的 Boss 一律綁在一個活的 base entity 上,所以現在 debug 指令也走同一條路
 * (見 [com.tinyyana.hanatoki.command.HanaTokiCommand] 的 `model spawn`)。
 *
 * ## Folia 規則
 * - tracker **建立**(`ModelRenderer.create`)底層會生 `ItemDisplay`/hitbox/nametag,是真的
 *   Bukkit world/entity mutation。這裡不自己派工等待:呼叫端必須已經站在該實體所屬的 region
 *   執行緒上(同 [com.tinyyana.hanatoki.folia.InstanceDispatch.submit] 的假設)——目前唯一
 *   呼叫端是 admin debug 指令,天然在對的執行緒上;沒站對執行緒就記警告回 null,不假裝成功。
 * - tracker 建立之後的**變動**(播動畫/停動畫/換縮放/關閉)一律經 [WorldOp] 派工到該實體的
 *   `EntityScheduler`,不直接呼叫 `Bukkit.getRegionScheduler()`/`entity.getScheduler()`
 *   (ARCH §5.2 規則 8)。
 * - 讀 locator 座標([Handle.locator])不碰任何 Bukkit API,只讀 `Tracker`/`RenderedBone`
 *   自己用 lock 保護的內部狀態(`RenderedBone.worldPosition()`)——任何執行緒呼叫都安全。
 *
 * ## `/bettermodel reload` 之後 tracker 物件本身會換掉
 * `EntityTrackerRegistry.reload()`(`tracker/EntityTrackerRegistry.java:383-393`)會把每個
 * tracker 關掉、序列化成 `TrackerData`,再用同一份資料建一顆**全新的** `EntityTracker` 物件
 * (`load()` → `model.create(entity, parsed.modifier(), parsed::applyAs)`,`java:427-430`)。
 * 所以這裡**不快取** `Tracker` 參考,[Handle] 每次動作都經 [resolveTracker] 重新從
 * `EntityTrackerRegistry` 查(key = model id,同 `RenderSource.java:154-155` 建立時用的
 * `pipeline.name()`)。縮放不用手動還原——`TrackerData.applyAs`(`tracker/TrackerData.java`)
 * 會呼叫 `tracker.scaler(scaler())`,而 `asTrackerData()` 存的正是 reload 前那顆 tracker 當時
 * 的 `scaler` 欄位,兩者串起來等於「reload 前設過的縮放,reload 後原樣照搬」。但**動畫不會**
 * ——`TrackerData` 完全沒有存任何動畫狀態欄位(見它的 record 定義),所以 [Handle] 一旦偵測到
 * 拿到的 tracker 換了一顆新物件,就會重播 [Handle.baseAnimation]。
 */
class BetterModelBossModels(private val plugin: Plugin) : BossModels {

    override val available: Boolean = true

    private val registry = BossModelOwnerRegistry<Handle>()
    private val entityIndex = ConcurrentHashMap<UUID, String>()

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
        // 回傳值本身不留著(見 class KDoc:reload 之後會換一顆新物件,Handle 一律用
        // resolveTracker() 重新查),這裡呼叫只是為了讓 BetterModel 真的建立並登記它。
        renderer.create(BaseEntity.of(BukkitAdapter.adapt(entity)))
        val key = newKey(owner)
        val handle = Handle(modelId, key, entity)
        registry.put(owner, key, handle)
        entityIndex[entity.uniqueId] = key
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

    private inner class Handle(
        override val modelId: String,
        private val key: String,
        private val boundEntity: Entity,
    ) : BossModelHandle {

        @Volatile private var closed = false
        @Volatile private var baseAnimation: String? = null

        /** 只用來偵測「reload 換了一顆新 tracker」,不是快取——見 class KDoc。 */
        @Volatile private var lastSeenTracker: EntityTracker? = resolveTracker()

        private fun resolveTracker(): EntityTracker? =
            BetterModel.registryOrNull(boundEntity.uniqueId)?.tracker(modelId)

        override val isActive: Boolean get() = !closed && resolveTracker()?.isClosed == false

        override fun play(animation: String, loop: Boolean, onEnd: Runnable?): Boolean {
            if (closed) return false
            val t = resolveTracker() ?: return false
            if (t.isClosed || t.renderer().animation(animation).isEmpty) return false
            dispatch { current -> animateOn(current, animation, loop, onEnd) }
            return true
        }

        override fun play(animation: String): Boolean = play(animation, false, null)

        override fun stop(animation: String) {
            if (closed) return
            dispatch { it.stopAnimation(animation) }
        }

        override fun setBase(animation: String) {
            baseAnimation = animation
            play(animation, true, null)
        }

        /**
         * 直接讀,不經派工——`RenderedBone.worldPosition()` 只碰 `Tracker`/`RenderedBone`
         * 內部的 lock(`DuplexLock`),不碰任何 Bukkit world/entity API,任何執行緒呼叫都安全。
         * 換算方式跟 BetterModel 自己「影子跟隨模型」那段程式碼一樣(`EntityTracker.java` 建構子
         * 裡 shadow 的 tick handler,約行 110-113):
         * `shadow.syncPosition(location().add(wPos.x, wPos.y, wPos.z))`——這裡的
         * `tracker.location()` 是 [BukkitLocation],把它的 `source()` 取出來再加上 bone 的
         * offset 就是骨頭目前的世界座標。reload 之後這裡會自然拿到新的 tracker(沒有快取)。
         */
        override fun locator(name: String): Location? {
            if (closed) return null
            val t = resolveTracker() ?: return null
            if (t.isClosed) return null
            val bone = t.bone(name) ?: return null
            val offset = bone.worldPosition()
            val base = (t.location() as? BukkitLocation)?.source() ?: return null
            return base.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
        }

        override fun scale(value: Float) {
            if (closed) return
            dispatch { it.scaler(ModelScaler.value(value)) }
        }

        /**
         * 關掉 tracker,並把 [boundEntity] 一併移除——這個 handle 綁定的載體是專門為它準備的
         * (debug 指令生的暫時 Husk,或未來真正 Boss 的專屬載體),不是別的系統在管的實體。
         * 兩件事都在 [boundEntity] 自己的 EntityScheduler 上做([WorldOp.dispatch])。
         *
         * ⚠ 先把自己從 [registry]/[entityIndex] 移除,才真的移除實體:`entity.remove()` 會同步
         * 觸發 `EntityRemoveFromWorldEvent`,[HanaTokiListener] 的對應 handler 會再呼叫一次
         * [BetterModelBossModels.closeForEntity]——這時 [entityIndex] 已經查不到這個 uuid,
         * 那次呼叫是 no-op,不會遞迴關第二次。
         */
        override fun close() {
            if (closed) return
            closed = true
            registry.remove(key)
            entityIndex.remove(boundEntity.uniqueId)
            WorldOp.dispatch(plugin, boundEntity) { e ->
                resolveTracker()?.let { if (!it.isClosed) it.close() }
                if (e.isValid) e.remove()
            }
        }

        private fun animateOn(t: EntityTracker, animation: String, loop: Boolean, onEnd: Runnable?) {
            val modifier = AnimationModifier.builder()
                .type(if (loop) AnimationIterator.Type.LOOP else AnimationIterator.Type.PLAY_ONCE)
                .build()
            t.animate(animation, modifier, Runnable { onEnd?.run() })
        }

        private fun dispatch(action: (EntityTracker) -> Unit) {
            WorldOp.dispatch(plugin, boundEntity) worldOp@{ _ ->
                val current = resolveTracker() ?: return@worldOp
                if (current.isClosed) return@worldOp
                if (lastSeenTracker !== current) {
                    // reload 換了一顆新的 EntityTracker——縮放已經由 TrackerData.applyAs 帶過來
                    // (見 class KDoc),但動畫沒有,這裡補播基底動畫。
                    lastSeenTracker = current
                    baseAnimation?.let { anim -> animateOn(current, anim, true, null) }
                }
                action(current)
            }
        }
    }
}
