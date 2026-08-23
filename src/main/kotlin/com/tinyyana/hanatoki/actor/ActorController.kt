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
import org.bukkit.entity.Mannequin
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
                val mannequin = world.spawn(loc, Mannequin::class.java) { m -> applySpec(m, spec) }
                actors[k] = mannequin
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

        override fun healthFractionOf(actorId: String): Double {
            val entity = actors[key(sessionId, actorId)] as? Mannequin ?: return -1.0
            if (!entity.isValid) return -1.0
            val max = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: return -1.0
            if (max <= 0.0) return -1.0
            return (entity.health / max).coerceIn(0.0, 1.0)
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
            withActor(actorId) { entity -> (entity as? Mannequin)?.equipment?.setItemInMainHand(item) }

        override fun setDisplayName(actorId: String, text: String?, visible: Boolean): CompletableFuture<Void> =
            withActor(actorId) { entity ->
                entity.customName(text?.let { MINI.deserialize(it) })
                entity.isCustomNameVisible = visible && text != null
            }

        override fun setInvulnerable(actorId: String, invulnerable: Boolean): CompletableFuture<Void> =
            withActor(actorId) { it.isInvulnerable = invulnerable }

        private fun withActor(actorId: String, action: (Entity) -> Unit): CompletableFuture<Void> {
            val entity = actors[key(sessionId, actorId)] ?: return CompletableFuture.completedFuture(null)
            return WorldOp.dispatch(plugin, entity, action)
        }
    }

    private fun applySpec(m: Mannequin, spec: ActorSpec) {
        m.isPersistent = false
        m.isSilent = spec.silent
        m.setGravity(spec.gravity)
        m.isInvulnerable = spec.invulnerable
        m.isImmovable = spec.immovable
        m.setAI(false)
        m.isCollidable = false
        spec.maxHealth?.let { hp ->
            m.getAttribute(Attribute.MAX_HEALTH)?.baseValue = hp
            m.health = hp
        }
        spec.displayName?.let { name ->
            m.customName(MINI.deserialize(name))
            m.isCustomNameVisible = spec.displayNameVisible
        }
        profileFor(spec)?.let { m.profile = it }
        val eq = m.equipment
        spec.mainHand?.let { eq.setItemInMainHand(it) }
        spec.offHand?.let { eq.setItemInOffHand(it) }
        // `EntityEquipment` 的護甲槽在 Bukkit 是 `setX(ItemStack)`(非 Kotlin property 語法可寫的
        // var——getter/setter 簽章不成對),一律用 setEquipment(slot, item) 這條統一入口。
        spec.helmet?.let { eq.setItem(EquipmentSlot.HEAD, it) }
        spec.chestplate?.let { eq.setItem(EquipmentSlot.CHEST, it) }
        spec.leggings?.let { eq.setItem(EquipmentSlot.LEGS, it) }
        spec.boots?.let { eq.setItem(EquipmentSlot.FEET, it) }
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

    private companion object {
        val MINI: MiniMessage = MiniMessage.miniMessage()
    }
}
