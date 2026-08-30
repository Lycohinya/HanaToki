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

    /**
     * 掃掉**登記表以外**的骨架部件(見 [PropHandle.PART_TAG])。
     *
     * 為什麼需要:`props` 是記憶體登記表,`/pmxt reload` 會把它清空**但實體還留在世界裡**,
     * 於是場上多出幾顆永遠不會被收走的浮空部件。LycoItems 的居合刀身踩過同一個洞。
     *
     * ⚠ **不能用 `world.entities`**——Folia 上那個呼叫會拋跨 region ownership 例外,有人在線時
     * 熱插拔當場炸。照 `IaiBladeAnimation.sweepOrphans` / `CompanionService.sweepOrphans` 的
     * 寫法:逐 chunk,而且只碰目前 region 擁有的 chunk。
     *
     * @return 這一輪掃掉幾顆
     */
    fun sweepOrphanParts(world: org.bukkit.World, centerChunkX: Int, centerChunkZ: Int, radiusChunks: Int): Int {
        val live = props.values.mapTo(HashSet()) { it.uniqueId }
        var removed = 0
        for (cx in (centerChunkX - radiusChunks)..(centerChunkX + radiusChunks)) {
            for (cz in (centerChunkZ - radiusChunks)..(centerChunkZ + radiusChunks)) {
                if (!world.isChunkLoaded(cx, cz)) continue
                val chunk = runCatching { world.getChunkAt(cx, cz) }.getOrNull() ?: continue
                // 別的 region 擁有的 chunk 一律跳過:讀它的實體清單就是跨 region 存取。
                if (!runCatching { org.bukkit.Bukkit.isOwnedByCurrentRegion(world, cx, cz) }.getOrDefault(false)) continue
                for (e in runCatching { chunk.entities }.getOrDefault(emptyArray())) {
                    if (!e.scoreboardTags.contains(PropHandle.PART_TAG)) continue
                    if (live.contains(e.uniqueId)) continue
                    e.remove()
                    removed += 1
                }
            }
        }
        return removed
    }

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

        override fun spawnText(
            propId: String,
            location: Location,
            miniMessage: String,
            billboardToPlayer: Boolean,
            backgroundArgb: Int,
        ): CompletableFuture<Void> {
            val component = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(miniMessage)
            val k = key(sessionId, propId)
            // 同一塊標籤換字是高頻的(節點冷卻倒數每秒重畫一次),先試著就地改。
            // remove + respawn 會讓玩家看到閃一下,而且每秒生一顆新實體。
            // ⚠ 這裡**不讀** existing.isValid:那是從呼叫端的執行緒讀一個掛在別處座標的實體
            //   (跨 region 讀,ARCH §5.1)。有效性交給 WorldOp.dispatch 的 retired callback 與
            //   task 內的判斷處理。
            val existing = props[k]
            if (existing is org.bukkit.entity.TextDisplay) {
                return WorldOp.dispatch(plugin, existing) { entity ->
                    val display = entity as? org.bukkit.entity.TextDisplay ?: return@dispatch
                    if (display.isValid) display.text(component)
                }
            }
            props.remove(k)?.let { old -> WorldOp.dispatch(plugin, old) { it.remove() } }
            return WorldOp.dispatchAt(plugin, location) { loc ->
                val world = loc.world ?: return@dispatchAt
                val display = world.spawn(loc, org.bukkit.entity.TextDisplay::class.java) { d ->
                    d.isPersistent = false
                    d.text(component)
                    d.billboard = if (billboardToPlayer) Display.Billboard.CENTER else Display.Billboard.FIXED
                    d.backgroundColor = org.bukkit.Color.fromARGB(backgroundArgb)
                    d.alignment = org.bukkit.entity.TextDisplay.TextAlignment.CENTER
                    d.isSeeThrough = false
                }
                props[k] = display
            }
        }

        override fun spawnPart(
            propId: String,
            location: Location,
            item: ItemStack,
            teleportDurationTicks: Int,
        ): CompletableFuture<Void> {
            val k = key(sessionId, propId)
            props.remove(k)?.let { old -> WorldOp.dispatch(plugin, old) { it.remove() } }
            return WorldOp.dispatchAt(plugin, location) { loc ->
                val world = loc.world ?: return@dispatchAt
                val display = world.spawn(loc, ItemDisplay::class.java) { d ->
                    d.isPersistent = false
                    d.setItemStack(item)
                    d.billboard = Display.Billboard.FIXED
                    // ⚠ NONE 而不是 GROUND:GROUND 會把模型自己的 display context 疊在
                    // 我們算的 transformation 上,骨架的關節座標會整組對不準。
                    d.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                    d.brightness = Display.Brightness(11, 15)
                    d.viewRange = 2.0f
                    d.setTeleportDuration(teleportDurationTicks.coerceIn(0, 59))
                    // 重開之後認得回來的唯一憑據,見 PropHandle.PART_TAG
                    d.addScoreboardTag(PropHandle.PART_TAG)
                }
                props[k] = display
            }
        }

        override fun pose(
            propId: String,
            tx: Float,
            ty: Float,
            tz: Float,
            pitchDegrees: Float,
            yawDegrees: Float,
            rollDegrees: Float,
            scale: Float,
            interpolationTicks: Int,
        ): CompletableFuture<Void> {
            val entity = props[key(sessionId, propId)] ?: return CompletableFuture.completedFuture(null)
            return WorldOp.dispatch(plugin, entity) { e ->
                val d = e as? Display ?: return@dispatch
                if (!d.isValid) return@dispatch
                // ⚠ 順序:先補間參數、再 transformation。反過來會沿用上一段的 duration
                // (LycoItems/IaiBladeAnimation.kt 踩過的坑,那裡也寫了同一條註解)。
                d.interpolationDelay = 0
                d.interpolationDuration = interpolationTicks.coerceAtLeast(0)
                // yaw → pitch → roll 的外旋順序,跟「先轉身、再抬手、最後翻腕」的直覺一致。
                val q = org.joml.Quaternionf()
                    .rotateY(Math.toRadians(yawDegrees.toDouble()).toFloat())
                    .rotateX(Math.toRadians(pitchDegrees.toDouble()).toFloat())
                    .rotateZ(Math.toRadians(rollDegrees.toDouble()).toFloat())
                d.transformation = Transformation(
                    Vector3f(tx, ty, tz),
                    q,
                    Vector3f(scale, scale, scale),
                    org.joml.Quaternionf(),
                )
            }
        }

        override fun poseQuaternion(
            propId: String,
            tx: Float,
            ty: Float,
            tz: Float,
            qx: Float,
            qy: Float,
            qz: Float,
            qw: Float,
            scale: Float,
            interpolationTicks: Int,
        ): CompletableFuture<Void> {
            val entity = props[key(sessionId, propId)] ?: return CompletableFuture.completedFuture(null)
            return WorldOp.dispatch(plugin, entity) { e ->
                val d = e as? Display ?: return@dispatch
                if (!d.isValid) return@dispatch
                d.interpolationDelay = 0
                d.interpolationDuration = interpolationTicks.coerceAtLeast(0)
                d.transformation = Transformation(
                    Vector3f(tx, ty, tz),
                    org.joml.Quaternionf(qx, qy, qz, qw),
                    Vector3f(scale, scale, scale),
                    org.joml.Quaternionf(),
                )
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
