package com.tinyyana.hanatoki.actor

import com.tinyyana.hanatoki.folia.WorldOp
import io.papermc.paper.datacomponent.item.ResolvableProfile
import io.papermc.paper.entity.LookAnchor
import net.kyori.adventure.text.minimessage.MiniMessage
import com.destroystokyo.paper.profile.ProfileProperty
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Pose
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * ARCH §2「Actor」的引擎側實作(整個 HanaToki 一份,按 `"<sessionId>#<actorId>"` 分鍵,
 * 比照 [com.tinyyana.hanatoki.encounter.EncounterController] 的作法)。
 *
 * Folia 規則(ARCH §5.1③/④):
 * - spawn 派工到**目標座標**所屬 region([WorldOp.dispatchAt])——不是 anchor 的 region。
 * - 生成之後的一切操作(換裝/看向/瞬移/移除)一律走**該實體自己的 EntityScheduler**
 *   ([WorldOp.dispatch] 的 entity 多載),實體 retired 時視為立即完成。
 * - 登記表本身是 [ConcurrentHashMap],任何執行緒可安全查表(無副作用)。
 */
class ActorController(private val plugin: Plugin) {

    /** key = "<sessionId>#<actorId>" -> 實體。實體可能已死/已移除,查用時一律再驗 [Entity.isValid]。 */
    private val actors = ConcurrentHashMap<String, Entity>()

    private fun key(sessionId: UUID, actorId: String) = "$sessionId#$actorId"

    fun handleFor(sessionId: UUID): ActorHandle = SessionActorHandle(sessionId)

    /** session 結束時的清場路徑(admin reset/timeout/resolve 共用)。 */
    fun despawnAllForSession(sessionId: UUID): CompletableFuture<Void> {
        val prefix = "$sessionId#"
        val futures = actors.keys.filter { it.startsWith(prefix) }.mapNotNull { k ->
            actors.remove(k)?.let { entity -> WorldOp.dispatch(plugin, entity) { it.remove() } }
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    /** 實體是否是某個 session 的 actor(EntityDeathEvent/EntityDamageEvent handler 反查用)。 */
    fun actorIdOf(entityId: UUID): Pair<UUID, String>? {
        for ((k, entity) in actors) {
            if (entity.uniqueId != entityId) continue
            val sep = k.indexOf('#')
            if (sep <= 0) continue
            val sessionId = runCatching { UUID.fromString(k.substring(0, sep)) }.getOrNull() ?: continue
            return sessionId to k.substring(sep + 1)
        }
        return null
    }

    private inner class SessionActorHandle(private val sessionId: UUID) : ActorHandle {

        override fun spawn(actorId: String, location: Location, spec: ActorSpec): CompletableFuture<Void> {
            val k = key(sessionId, actorId)
            // 同 id 重複 spawn:先移除舊的(不等它完成——移除與生成在不同 region 各自獨立)。
            actors.remove(k)?.let { old -> WorldOp.dispatch(plugin, old) { it.remove() } }
            return WorldOp.dispatchAt(plugin, location) { loc ->
                val world = loc.world ?: return@dispatchAt
                val type = runCatching { EntityType.valueOf(spec.entityType) }.getOrElse {
                    plugin.logger.warning("[HanaToki] actor $actorId 的 entity-type=${spec.entityType} 不是已知的 EntityType,略過生成")
                    return@dispatchAt
                }
                @Suppress("UNCHECKED_CAST")
                val entityClass = (type.entityClass ?: run {
                    plugin.logger.warning("[HanaToki] actor $actorId 的 entity-type=${spec.entityType} 不能被生成,略過")
                    return@dispatchAt
                }) as Class<Entity>
                val entity = world.spawn(loc, entityClass) { e: Entity -> applySpec(e, spec) }
                actors[k] = entity
            }
        }

        override fun despawn(actorId: String): CompletableFuture<Void> {
            val entity = actors.remove(key(sessionId, actorId)) ?: return CompletableFuture.completedFuture(null)
            return WorldOp.dispatch(plugin, entity) { it.remove() }
        }

        override fun despawnAll(): CompletableFuture<Void> = despawnAllForSession(sessionId)

        override fun entityIdOf(actorId: String): UUID? = actors[key(sessionId, actorId)]?.uniqueId

        override fun isAlive(actorId: String): Boolean {
            val entity = actors[key(sessionId, actorId)] ?: return false
            // isValid 對「已移除/已死」都回 false,是唯一不需要跨 region 讀狀態的安全查詢。
            return entity.isValid
        }

        override fun healthFractionOf(actorId: String): Double =
            fractionOf(actors[key(sessionId, actorId)])

        override fun healthFractionAsync(actorId: String): CompletableFuture<Double> {
            val entity = actors[key(sessionId, actorId)] ?: return CompletableFuture.completedFuture(-1.0)
            val out = CompletableFuture<Double>()
            WorldOp.dispatch(plugin, entity) { out.complete(fractionOf(it)) }
                .whenComplete { _, _ -> out.complete(-1.0) } // 實體已 retired:上面那行沒跑到,補完成
            return out
        }

        private fun fractionOf(entity: org.bukkit.entity.Entity?): Double {
            val living = entity as? LivingEntity ?: return -1.0
            if (!living.isValid) return -1.0
            val max = living.getAttribute(Attribute.MAX_HEALTH)?.value ?: return -1.0
            if (max <= 0.0) return -1.0
            return (living.health / max).coerceIn(0.0, 1.0)
        }

        override fun teleport(actorId: String, location: Location): CompletableFuture<Void> =
            withActor(actorId) { it.teleportAsync(location) }

        override fun lookAt(actorId: String, target: Location): CompletableFuture<Void> =
            withActor(actorId) { it.lookAt(target.x, target.y, target.z, LookAnchor.EYES) }

        override fun faceNearestPlayer(actorId: String, radius: Double): CompletableFuture<Void> =
            withActor(actorId) { entity ->
                // 在 actor 自己的 EntityScheduler task 內查——用的是實體自己 region 的資料,
                // 不是從別的 region 讀玩家座標(ARCH §5.2 規則 2 的同一條理由)。
                val here = entity.location
                val nearest = here.world?.getNearbyPlayers(here, radius)
                    ?.minByOrNull { it.location.distanceSquared(here) }
                    ?: return@withActor
                entity.lookAt(nearest.location.x, nearest.location.y, nearest.location.z, LookAnchor.EYES)
            }

        override fun setMainHand(actorId: String, item: ItemStack?): CompletableFuture<Void> =
            withActor(actorId) { entity -> (entity as? LivingEntity)?.equipment?.setItemInMainHand(item) }

        override fun setDisplayName(actorId: String, text: String?, visible: Boolean): CompletableFuture<Void> =
            withActor(actorId) { entity ->
                entity.customName(text?.let { MINI.deserialize(it) })
                entity.isCustomNameVisible = visible && text != null
            }

        override fun setInvulnerable(actorId: String, invulnerable: Boolean): CompletableFuture<Void> =
            withActor(actorId) { it.isInvulnerable = invulnerable }

        override fun setDescription(actorId: String, text: String): CompletableFuture<Void> =
            withActor(actorId) { entity ->
                (entity as? Mannequin)?.description =
                    if (text.isBlank()) net.kyori.adventure.text.Component.empty() else MINI.deserialize(text)
            }

        override fun swingMainHand(actorId: String): CompletableFuture<Void> =
            withActor(actorId) { (it as? LivingEntity)?.swingMainHand() }

        override fun playHurtAnimation(actorId: String, yawDegrees: Float): CompletableFuture<Void> =
            withActor(actorId) { (it as? LivingEntity)?.playHurtAnimation(yawDegrees) }

        override fun setPose(actorId: String, poseName: String, fixed: Boolean): CompletableFuture<Void> =
            withActor(actorId) { entity ->
                val pose = poseByName(poseName) ?: return@withActor
                // 伺服器對 Mannequin 只接受一部分姿勢,而且清單只有 runtime 拿得到。套用不被
                // 接受的姿勢在某些核心上會丟 IllegalArgumentException,先過濾再套。
                if (entity is Mannequin && pose !in validPoses()) {
                    plugin.logger.warning("[HanaToki] 這台伺服器不接受 Mannequin 的姿勢 $poseName,略過(可用:${validPoseNames()})")
                    return@withActor
                }
                entity.setPose(pose, fixed)
            }

        override fun validPoseNames(): List<String> = validPoses().map { it.name }

        override fun setRotation(actorId: String, yawDegrees: Float, pitchDegrees: Float): CompletableFuture<Void> =
            withActor(actorId) { it.setRotation(yawDegrees, pitchDegrees) }

        override fun setEquipment(actorId: String, slotName: String, item: ItemStack?): CompletableFuture<Void> =
            withActor(actorId) { entity ->
                val slot = runCatching { EquipmentSlot.valueOf(slotName) }.getOrNull() ?: run {
                    plugin.logger.warning("[HanaToki] $slotName 不是已知的 EquipmentSlot,略過")
                    return@withActor
                }
                (entity as? LivingEntity)?.equipment?.setItem(slot, item)
            }

        override fun damage(actorId: String, amount: Double): CompletableFuture<Void> =
            withActor(actorId) { (it as? LivingEntity)?.damage(amount) }

        override fun locationOf(actorId: String): CompletableFuture<Location> {
            val entity = actors[key(sessionId, actorId)] ?: return CompletableFuture.completedFuture(null)
            val out = CompletableFuture<Location>()
            // 座標一律在**實體自己的 EntityScheduler** 裡讀(ARCH §5.1④)。回傳的是一份 clone,
            // 呼叫端拿到之後在任何執行緒上做距離計算都安全(Location 是純資料)。
            WorldOp.dispatch(plugin, entity) { out.complete(if (it.isValid) it.location.clone() else null) }
                .whenComplete { _, _ -> out.complete(null) } // 實體已 retired:上面那行沒跑到,補完成
            return out
        }

        private fun withActor(actorId: String, action: (Entity) -> Unit): CompletableFuture<Void> {
            val entity = actors[key(sessionId, actorId)] ?: return CompletableFuture.completedFuture(null)
            return WorldOp.dispatch(plugin, entity, action)
        }
    }

    /**
     * 套用 [ActorSpec]。分成三段:所有實體共通的、[LivingEntity] 才有的、[Mannequin] 專屬的
     * ——後兩段對不是那個型別的載體自動略過(見 [ActorSpec.entityType] 的 KDoc)。
     */
    private fun applySpec(entity: Entity, spec: ActorSpec) {
        entity.isPersistent = false
        entity.isSilent = spec.silent
        entity.setGravity(spec.gravity)
        entity.isInvulnerable = spec.invulnerable
        entity.isGlowing = spec.glowing
        (entity as? LivingEntity)?.isInvisible = spec.invisible
        spec.displayName?.let { name ->
            entity.customName(MINI.deserialize(name))
            entity.isCustomNameVisible = spec.displayNameVisible
        }

        val living = entity as? LivingEntity
        living?.setAI(spec.ai)
        living?.isCollidable = spec.collidable
        spec.removeWhenFarAway?.let { living?.setRemoveWhenFarAway(it) }
        spec.maxHealth?.let { hp ->
            living?.getAttribute(Attribute.MAX_HEALTH)?.baseValue = hp
            living?.health = hp
        }
        val eq = living?.equipment
        if (eq != null) {
            spec.mainHand?.let { eq.setItemInMainHand(it) }
            spec.offHand?.let { eq.setItemInOffHand(it) }
            // `EntityEquipment` 的護甲槽在 Bukkit 是 `setX(ItemStack)`(非 Kotlin property 語法可寫的
            // var——getter/setter 簽章不成對),一律用 setEquipment(slot, item) 這條統一入口。
            spec.helmet?.let { eq.setItem(EquipmentSlot.HEAD, it) }
            spec.chestplate?.let { eq.setItem(EquipmentSlot.CHEST, it) }
            spec.leggings?.let { eq.setItem(EquipmentSlot.LEGS, it) }
            spec.boots?.let { eq.setItem(EquipmentSlot.FEET, it) }
        }

        val mannequin = entity as? Mannequin ?: return
        mannequin.isImmovable = spec.immovable
        // 清掉 Mannequin 的預設描述(名牌下面那行)。內容層要顯示什麼再自己 setDescription;
        // 預設值是原版寫的,對玩家沒有意義(2026-08-24 真人回饋)。
        mannequin.description = net.kyori.adventure.text.Component.empty()
        profileFor(spec)?.let { mannequin.profile = it }
    }

    /**
     * null = 不動 profile(用 `Mannequin` 的預設外觀)。帶了 [ActorSpec.skinTexture] 就組一個
     * 離線 profile 塞 `textures` property——**是否需要 Mojang 簽章才會正確顯示屬於未實測事實**,
     * 由內容層 config 決定要不要帶,引擎這端不假設,失敗時記警告並退回預設外觀。
     */
    private fun profileFor(spec: ActorSpec): ResolvableProfile? {
        val texture = spec.skinTexture?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val profile = Bukkit.createProfile(UUID.randomUUID(), "hanatoki_actor")
            profile.setProperty(
                spec.skinSignature?.takeIf { it.isNotBlank() }
                    ?.let { ProfileProperty("textures", texture, it) }
                    ?: ProfileProperty("textures", texture),
            )
            ResolvableProfile.resolvableProfile(profile)
        }.onFailure {
            plugin.logger.warning("[HanaToki] actor 皮膚 profile 建立失敗,退回預設外觀:${it.message}")
        }.getOrNull()
    }

    /**
     * 這台核心接受哪些 Mannequin 姿勢。`Mannequin.validPoses()` 是 runtime 橋接
     * (`InternalAPIBridge`),API jar 裡查不到內容——**只能在伺服器上實際問**,所以查一次
     * 快取起來,並且對「這顆核心沒有實作」的情況降級成空集合(那時 setPose 全部略過,
     * 演出退回換裝/揮擊/朝向,關卡照樣能玩)。
     */
    private val cachedValidPoses: Set<Pose> by lazy {
        runCatching { Mannequin.validPoses() }
            .onFailure { plugin.logger.warning("[HanaToki] 取不到 Mannequin.validPoses(),actor 姿勢功能停用:${it.message}") }
            .getOrDefault(emptySet())
    }

    internal fun validPoses(): Set<Pose> = cachedValidPoses

    private fun poseByName(name: String): Pose? =
        runCatching { Pose.valueOf(name) }.getOrElse {
            plugin.logger.warning("[HanaToki] $name 不是已知的 Pose")
            null
        }

    private companion object {
        val MINI: MiniMessage = MiniMessage.miniMessage()
    }
}
