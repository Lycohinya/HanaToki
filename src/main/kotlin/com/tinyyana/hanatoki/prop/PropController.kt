package com.tinyyana.hanatoki.prop

import com.tinyyana.hanatoki.folia.WorldOp
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * [PropHandle] 的引擎側實作。結構刻意跟 [com.tinyyana.hanatoki.actor.ActorController] 一模一樣
 * (整個 HanaToki 一份、按 `"<sessionId>#<propId>"` 分鍵、spawn 派到目標座標所屬 region、
 * 之後的操作走實體自己的 EntityScheduler)——同一類問題用同一種寫法,不另發明第二套。
 */
class PropController(private val plugin: Plugin) {

    private val props = ConcurrentHashMap<String, Entity>()

    private fun key(sessionId: UUID, propId: String) = "$sessionId#$propId"

    fun handleFor(sessionId: UUID): PropHandle = SessionPropHandle(sessionId)

    fun despawnAllForSession(sessionId: UUID): CompletableFuture<Void> {
        val prefix = "$sessionId#"
        val futures = props.keys.filter { it.startsWith(prefix) }.mapNotNull { k ->
            props.remove(k)?.let { entity -> WorldOp.dispatch(plugin, entity) { it.remove() } }
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    /** 擺設是不是副本生出來的(死亡掉落清除/debug 反查用,同 actor/encounter)。 */
    fun isTracked(entityId: UUID): Boolean = props.values.any { it.uniqueId == entityId }

    private inner class SessionPropHandle(private val sessionId: UUID) : PropHandle {

        override fun spawnItem(
            propId: String,
            location: Location,
            item: ItemStack,
            scale: Float,
            yawDegrees: Float,
            fixedBillboard: Boolean,
        ): CompletableFuture<Void> {
            val k = key(sessionId, propId)
            props.remove(k)?.let { old -> WorldOp.dispatch(plugin, old) { it.remove() } }
            return WorldOp.dispatchAt(plugin, location) { loc ->
                val world = loc.world ?: return@dispatchAt
                val display = world.spawn(loc, ItemDisplay::class.java) { d ->
                    d.isPersistent = false
                    // getter 是 @Nullable、setter 是 @NotNull,Kotlin 看不成一個 var
                    // (同 ActorController 對 EntityEquipment 護甲槽的處理),用 setter 呼叫。
                    d.setItemStack(item)
                    d.billboard = if (fixedBillboard) Display.Billboard.FIXED else Display.Billboard.CENTER
                    // GROUND 讓造型像「長在地上/放在地上」而不是握在手裡的角度。
                    d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
                    d.transformation = Transformation(
                        Vector3f(0f, 0f, 0f),
                        AxisAngle4f(Math.toRadians(yawDegrees.toDouble()).toFloat(), 0f, 1f, 0f),
                        Vector3f(scale, scale, scale),
                        AxisAngle4f(0f, 0f, 1f, 0f),
                    )
                    // 副本世界是固定天色的封閉場地,交給環境光算的話花會整片黑掉。
                    d.brightness = Display.Brightness(11, 15)
                    d.viewRange = 2.0f
                }
                props[k] = display
            }
        }

        override fun spawnBlock(
            propId: String,
            location: Location,
            blockData: String,
            teleportDurationTicks: Int,
        ): CompletableFuture<Void> {
            val data = runCatching { org.bukkit.Bukkit.createBlockData(blockData) }.getOrElse {
                plugin.logger.warning("[HanaToki] prop $propId 的 block-data「$blockData」解析失敗,不生成:${it.message}")
                return CompletableFuture.completedFuture(null)
            }
            val k = key(sessionId, propId)
            props.remove(k)?.let { old -> WorldOp.dispatch(plugin, old) { it.remove() } }
            return WorldOp.dispatchAt(plugin, location) { loc ->
                val world = loc.world ?: return@dispatchAt
                val display = world.spawn(loc, org.bukkit.entity.BlockDisplay::class.java) { d ->
                    d.isPersistent = false
                    d.block = data
                    d.setTeleportDuration(teleportDurationTicks.coerceIn(0, 59))
                }
                props[k] = display
            }
        }

        override fun moveTo(propId: String, location: Location): CompletableFuture<Void> {
            val entity = props[key(sessionId, propId)] ?: return CompletableFuture.completedFuture(null)
            // 位移一律走實體自己的 EntityScheduler(ARCH §5.1④);Folia 上 `teleport` 是無條件
            // throw,`teleportAsync` 是唯一路徑。
            return WorldOp.dispatch(plugin, entity) { if (it.isValid) it.teleportAsync(location) }
        }

        override fun despawn(propId: String): CompletableFuture<Void> {
            val entity = props.remove(key(sessionId, propId)) ?: return CompletableFuture.completedFuture(null)
            return WorldOp.dispatch(plugin, entity) { it.remove() }
        }

        override fun despawnAll(): CompletableFuture<Void> = despawnAllForSession(sessionId)

        override fun count(): Int = props.keys.count { it.startsWith("$sessionId#") }
    }
}
