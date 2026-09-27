package com.tinyyana.hanatoki.instance

import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 2026-09-27 刀塚事故的回歸測試:收斂任何一步失敗都不能讓 slot 永遠 occupied,
 * 也不能在有人還站在場地上時回滾/歸還;每個 slot 恰好歸還一次。
 *
 * [Fake] 模擬玩家與世界:傳送可能回 false、丟例外、撞上 retired;玩家可能死亡、斷線、重生。
 * 所有 future 都同步完成,時間由 [Fake.clock] 控制,整個測試是確定性的。
 */
class SlotCleanupCoordinatorTest {

    private enum class Teleport { OK, FALSE, THROW, FAILED_FUTURE, RETIRED }

    private class Player(var online: Boolean = true, var dead: Boolean = false, var insideSlot: String? = null, var retired: Boolean = false)

    private class Fake(slotCount: Int) : SlotCleanupCoordinator.Ports {
        var clock = 0L
        val pool = SlotPool<Int>().apply { repeat(slotCount) { register("tachizuka", "tachizuka#$it", it) } }
        val players = HashMap<UUID, Player>()
        val releases = HashMap<String, Int>()
        val rollbacks = HashMap<String, Int>()
        val evacuations = HashMap<UUID, Int>()
        val warnings = mutableListOf<String>()
        var teleport: (UUID, Int) -> Teleport = { _, _ -> Teleport.OK }
        var rollbackResult: (String) -> CompletableFuture<Void> = { CompletableFuture.completedFuture(null) }
        var evacuateOverride: ((UUID) -> CompletableFuture<Boolean>)? = null
        val restored = HashMap<UUID, Int>()

        override fun restore(playerId: UUID, reason: String): CompletableFuture<Boolean> {
            restored.merge(playerId, 1, Int::plus)
            val p = players[playerId]
            return CompletableFuture.completedFuture(p != null && p.online && !p.dead && !p.retired)
        }

        override fun status(playerId: UUID, cleanup: SlotCleanupCoordinator.CleanupView): CompletableFuture<MemberStatus> {
            val p = players[playerId] ?: return CompletableFuture.completedFuture(MemberStatus.OFFLINE)
            val s = when {
                !p.online -> MemberStatus.OFFLINE
                p.retired -> MemberStatus.UNKNOWN
                p.dead -> MemberStatus.DEAD
                p.insideSlot == cleanup.slotId -> MemberStatus.INSIDE
                else -> MemberStatus.OUTSIDE
            }
            return CompletableFuture.completedFuture(s)
        }

        override fun evacuate(playerId: UUID, cleanup: SlotCleanupCoordinator.CleanupView, attempt: Int): CompletableFuture<Boolean> {
            evacuations.merge(playerId, 1, Int::plus)
            evacuateOverride?.let { return it(playerId) }
            val p = players.getValue(playerId)
            return when (teleport(playerId, attempt)) {
                Teleport.OK -> {
                    p.insideSlot = null
                    CompletableFuture.completedFuture(true)
                }
                Teleport.FALSE -> CompletableFuture.completedFuture(false)
                Teleport.THROW -> throw IllegalStateException("Return teleport failed")
                Teleport.FAILED_FUTURE -> CompletableFuture.failedFuture(IllegalStateException("teleport exploded"))
                Teleport.RETIRED -> CompletableFuture.completedFuture(false)
            }
        }

        override fun rollback(cleanup: SlotCleanupCoordinator.CleanupView): CompletableFuture<Void> {
            rollbacks.merge(cleanup.slotId, 1, Int::plus)
            // 回滾當下場地上不能有人。
            val inside = players.values.filter { it.online && it.insideSlot == cleanup.slotId }
            check(inside.isEmpty()) { "rollback with ${inside.size} player(s) still inside ${cleanup.slotId}" }
            return rollbackResult(cleanup.slotId)
        }

        override fun release(cleanup: SlotCleanupCoordinator.CleanupView) {
            check(pool.isOccupied(cleanup.slotId)) { "double release of ${cleanup.slotId}" }
            releases.merge(cleanup.slotId, 1, Int::plus)
            pool.free(cleanup.slotId)
        }

        override fun info(message: String) {}

        override fun warn(message: String) {
            warnings += message
        }
    }

    private fun fake(slots: Int = 12) = Fake(slots)

    private fun coordinator(f: Fake) = SlotCleanupCoordinator(f) { f.clock }

    private fun view(slot: String, session: UUID = UUID.randomUUID(), holdsSlot: Boolean = true) =
        SlotCleanupCoordinator.CleanupView(session, slot, "tachizuka", "resolved", null, holdsSlot)

    /** 每一輪時間往前 [stepMs] 再 pump 一次(core tick 每秒一次,這裡快轉跳過退避)。 */
    private fun drive(f: Fake, c: SlotCleanupCoordinator, rounds: Int, stepMs: Long = 2_000L, each: (Int) -> Unit = {}) {
        repeat(rounds) { round ->
            each(round)
            f.clock += stepMs
            c.pumpAll()
        }
    }

    @Test
    fun `normal end restores, evacuates, rolls back and releases exactly once`() {
        val f = fake()
        val c = coordinator(f)
        val slot = f.pool.allocate("tachizuka")!!.slotId
        val a = UUID.randomUUID()
        f.players[a] = Player(insideSlot = slot)
        val done = c.begin(view(slot), listOf(a), CompletableFuture.completedFuture(null))
        assertTrue(done.isDone)
        assertEquals(1, f.releases[slot])
        assertEquals(1, f.rollbacks[slot])
        assertFalse(f.pool.isOccupied(slot))
        assertNotNull(f.restored[a])
        assertFalse(c.hasPendingFor(slot))
    }

    @Test
    fun `death waits for respawn before evacuating and never releases early`() {
        val f = fake()
        val c = coordinator(f)
        val slot = f.pool.allocate("tachizuka")!!.slotId
        val a = UUID.randomUUID()
        f.players[a] = Player(dead = true, insideSlot = slot)
        val done = c.begin(view(slot), listOf(a), CompletableFuture.completedFuture(null))
        drive(f, c, 5)
        assertFalse(done.isDone)
        assertEquals(null, f.evacuations[a]) // 對屍體不傳送
        assertTrue(f.pool.isOccupied(slot))
        // 重生:onRespawn 把重生點改到返回點,人就不在場上了。
        f.players.getValue(a).apply { dead = false; insideSlot = null }
        drive(f, c, 1)
        assertTrue(done.isDone)
        assertEquals(1, f.releases[slot])
    }

    @Test
    fun `death then disconnect releases once the player is offline`() {
        val f = fake()
        val c = coordinator(f)
        val slot = f.pool.allocate("tachizuka")!!.slotId
        val a = UUID.randomUUID()
        f.players[a] = Player(dead = true, insideSlot = slot)
        val done = c.begin(view(slot), listOf(a), CompletableFuture.completedFuture(null))
        drive(f, c, 3)
        assertFalse(done.isDone)
        f.players.getValue(a).online = false
        drive(f, c, 1)
        assertTrue(done.isDone)
        assertEquals(1, f.releases[slot])
    }

    @Test
    fun `teleport false, throw, failed future and retired keep recovery state and retry`() {
        for (mode in listOf(Teleport.FALSE, Teleport.THROW, Teleport.FAILED_FUTURE, Teleport.RETIRED)) {
            val f = fake()
            val c = coordinator(f)
            val slot = f.pool.allocate("tachizuka")!!.slotId
            val a = UUID.randomUUID()
            f.players[a] = Player(insideSlot = slot)
            var failuresLeft = 3
            f.teleport = { _, _ -> if (failuresLeft-- > 0) mode else Teleport.OK }
            val done = c.begin(view(slot), listOf(a), CompletableFuture.completedFuture(null))
            assertFalse(done.isDone, "mode=$mode")
            assertTrue(f.pool.isOccupied(slot), "mode=$mode")
            assertTrue(c.hasPendingFor(slot), "mode=$mode must stay reachable")
            drive(f, c, 20, stepMs = 30_000L)
            assertTrue(done.isDone, "mode=$mode")
            assertEquals(1, f.releases[slot], "mode=$mode")
            assertEquals(4, f.evacuations[a], "mode=$mode")
        }
    }

    @Test
    fun `player that can never leave keeps the slot occupied and stays visible`() {
        val f = fake()
        val c = coordinator(f)
        val slot = f.pool.allocate("tachizuka")!!.slotId
        val a = UUID.randomUUID()
        f.players[a] = Player(insideSlot = slot)
        f.teleport = { _, _ -> Teleport.FALSE }
        val done = c.begin(view(slot), listOf(a), CompletableFuture.completedFuture(null))
        drive(f, c, 50, stepMs = 30_000L)
        assertFalse(done.isDone)
        assertEquals(null, f.rollbacks[slot])
        assertTrue(f.pool.isOccupied(slot))
        assertTrue(c.describe().single().contains(slot))
        assertTrue(f.warnings.any { it.contains("還有 1 位沒離場") })
        // admin reset 只能推一把,不能在人還在場上時硬放。
        assertTrue(c.pumpSlot(slot))
        assertTrue(f.pool.isOccupied(slot))
        // drain 等得到這一筆(由呼叫端決定等多久)。
        assertEquals(1, c.pendingFor(setOf("tachizuka")).size)
        // 人終於送得出去:下一輪就收乾淨。
        f.teleport = { _, _ -> Teleport.OK }
        c.pumpSlot(slot)
        assertTrue(done.isDone)
        assertEquals(1, f.releases[slot])
    }

    @Test
    fun `restore is confirmed before evacuating a living player`() {
        val f = fake()
        val slot = f.pool.allocate("tachizuka")!!.slotId
        val a = UUID.randomUUID()
        f.players[a] = Player(insideSlot = slot)
        val order = mutableListOf<String>()
        val base = f
        val c2 = SlotCleanupCoordinator(object : SlotCleanupCoordinator.Ports by base {
            override fun restore(playerId: UUID, reason: String): CompletableFuture<Boolean> {
                order += "restore"
                return base.restore(playerId, reason)
            }

            override fun evacuate(playerId: UUID, cleanup: SlotCleanupCoordinator.CleanupView, attempt: Int): CompletableFuture<Boolean> {
                order += "evacuate"
                return base.evacuate(playerId, cleanup, attempt)
            }
        }) { f.clock }
        c2.begin(view(slot), listOf(a), CompletableFuture.completedFuture(null))
        assertEquals(listOf("restore", "evacuate"), order.take(2))
        assertEquals(1, f.releases[slot])
    }

    @Test
    fun `duplicate begin and repeated pumps release exactly once`() {
        val f = fake()
        val c = coordinator(f)
        val slot = f.pool.allocate("tachizuka")!!.slotId
        val session = UUID.randomUUID()
        val a = UUID.randomUUID()
        f.players[a] = Player(insideSlot = slot)
        f.teleport = { _, attempt -> if (attempt < 2) Teleport.FALSE else Teleport.OK }
        val v = view(slot, session)
        val first = c.begin(v, listOf(a), CompletableFuture.completedFuture(null))
        val second = c.begin(v, listOf(a), CompletableFuture.completedFuture(null))
        assertTrue(first === second)
        drive(f, c, 5)
        c.pumpAll(); c.pumpSlot(slot); c.pumpAll()
        assertEquals(1, f.releases[slot])
        assertEquals(1, f.rollbacks[slot])
    }

    @Test
    fun `pump is not re-entrant while an evacuation is in flight`() {
        val f = fake()
        val c = coordinator(f)
        val slot = f.pool.allocate("tachizuka")!!.slotId
        val a = UUID.randomUUID()
        f.players[a] = Player(insideSlot = slot)
        val pending = CompletableFuture<Boolean>()
        f.evacuateOverride = { pending }
        val done = c.begin(view(slot), listOf(a), CompletableFuture.completedFuture(null))
        drive(f, c, 10, stepMs = 60_000L)
        assertEquals(1, f.evacuations[a])
        f.players.getValue(a).insideSlot = null
        pending.complete(true)
        assertTrue(done.isDone)
        assertEquals(1, f.releases[slot])
    }

    @Test
    fun `stage end failure or hang does not block the cleanup forever`() {
        val f = fake()
        val c = coordinator(f)
        val failedSlot = f.pool.allocate("tachizuka")!!.slotId
        val failed = c.begin(view(failedSlot), emptyList(), CompletableFuture.failedFuture(IllegalStateException("boom")))
        assertTrue(failed.isDone)
        assertEquals(1, f.releases[failedSlot])

        val hungSlot = f.pool.allocate("tachizuka")!!.slotId
        val releasedBefore = f.releases[hungSlot] ?: 0 // 可能拿到剛剛歸還的同一個 slot
        val hung = c.begin(view(hungSlot), emptyList(), CompletableFuture())
        drive(f, c, 10, stepMs = 1_000L)
        assertFalse(hung.isDone)
        drive(f, c, 25, stepMs = 1_000L)
        assertTrue(hung.isDone)
        assertEquals(releasedBefore + 1, f.releases[hungSlot])
    }

    @Test
    fun `rollback failure is retried and never releases while people are inside`() {
        val f = fake()
        val c = coordinator(f)
        val slot = f.pool.allocate("tachizuka")!!.slotId
        var failures = 1
        f.rollbackResult = {
            if (failures-- > 0) CompletableFuture.failedFuture(IllegalStateException("dispatch lost")) else CompletableFuture.completedFuture(null)
        }
        val done = c.begin(view(slot), emptyList(), CompletableFuture.completedFuture(null))
        assertFalse(done.isDone)
        assertTrue(f.pool.isOccupied(slot))
        drive(f, c, 1)
        assertTrue(done.isDone)
        assertEquals(2, f.rollbacks[slot])
        assertEquals(1, f.releases[slot])
    }

    @Test
    fun `entry rollback of a persistent join finishes without touching the slot`() {
        val f = fake()
        val c = coordinator(f)
        val slot = f.pool.allocate("tachizuka")!!.slotId
        val a = UUID.randomUUID()
        f.players[a] = Player(insideSlot = null)
        val done = c.begin(view(slot, holdsSlot = false), listOf(a), CompletableFuture.completedFuture(null))
        assertTrue(done.isDone)
        assertEquals(null, f.releases[slot])
        assertEquals(null, f.rollbacks[slot])
        assertTrue(f.pool.isOccupied(slot))
    }

    @Test
    fun `more than twelve runs in a row never leak a slot`() {
        val f = fake(slots = 12)
        val c = coordinator(f)
        val scenarios = listOf(
            "normal", "death-respawn", "death-disconnect", "teleport-false", "teleport-throw",
            "retired", "fallback-only", "offline", "already-outside", "rollback-fail", "stage-fail", "two-players-mixed",
        )
        var runs = 0
        // 每一批同時打 6 局(一半 slot),收斂各自卡在不同的失敗上,打 8 批 = 48 局。
        repeat(8) { batch ->
            val active = mutableListOf<Triple<String, CompletableFuture<Void>, () -> Unit>>()
            repeat(6) { i ->
                val scenario = scenarios[(batch * 6 + i) % scenarios.size]
                val slot = f.pool.allocate("tachizuka")?.slotId
                assertNotNull(slot, "run #$runs ($scenario) found no free slot: a previous cleanup leaked")
                runs++
                val members = List(if (scenario == "two-players-mixed") 2 else 1) { UUID.randomUUID() }
                members.forEach { f.players[it] = Player(insideSlot = slot) }
                val m = members.first()
                var afterwards: () -> Unit = {}
                var stageEnd: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
                when (scenario) {
                    "death-respawn" -> {
                        f.players.getValue(m).dead = true
                        afterwards = { f.players.getValue(m).apply { dead = false; insideSlot = null } }
                    }
                    "death-disconnect" -> {
                        f.players.getValue(m).dead = true
                        afterwards = { f.players.getValue(m).online = false }
                    }
                    "retired" -> {
                        f.players.getValue(m).retired = true
                        afterwards = { f.players.getValue(m).retired = false }
                    }
                    "offline" -> f.players.getValue(m).online = false
                    "already-outside" -> f.players.getValue(m).insideSlot = null
                    "stage-fail" -> stageEnd = CompletableFuture.failedFuture(IllegalStateException("stage"))
                    "two-players-mixed" -> {
                        f.players.getValue(members[1]).dead = true
                        afterwards = { f.players.getValue(members[1]).online = false }
                    }
                }
                active += Triple(slot!!, c.begin(view(slot), members, stageEnd), afterwards)
            }
            // 傳送行為:依場景各自失敗幾次;回滾偶爾失敗一次。
            val flaky = HashMap<UUID, Int>()
            f.teleport = { player, attempt ->
                val n = flaky.merge(player, 1, Int::plus)!!
                when {
                    n <= 2 && player.hashCode() % 3 == 0 -> Teleport.FALSE
                    n <= 2 && player.hashCode() % 3 == 1 -> Teleport.THROW
                    attempt < SlotCleanupCoordinator.FALLBACK_DESTINATION_FROM_ATTEMPT && player.hashCode() % 5 == 0 -> Teleport.RETIRED
                    else -> Teleport.OK
                }
            }
            var rollbackFailOnce = true
            f.rollbackResult = {
                if (rollbackFailOnce) {
                    rollbackFailOnce = false
                    CompletableFuture.failedFuture(IllegalStateException("rollback"))
                } else {
                    CompletableFuture.completedFuture(null)
                }
            }
            drive(f, c, 3)
            // 途中:死亡的人重生/斷線、retired 的實體恢復。
            active.forEach { it.third() }
            drive(f, c, 20, stepMs = 30_000L)
            active.forEach { (slot, done, _) ->
                assertTrue(done.isDone, "batch $batch slot $slot never finished: ${c.describe()}")
            }
        }
        assertTrue(runs > 12)
        // 每個 slot 被歸還的次數 == 被使用的次數;沒有任何 slot 還佔著。
        assertEquals(runs, f.releases.values.sum())
        assertEquals(12, f.pool.freeCount("tachizuka"))
        assertTrue(c.describe().isEmpty())
    }

    @Test
    fun `backoff grows and caps`() {
        assertEquals(2_000L, SlotCleanupCoordinator.backoffMs(1))
        assertEquals(4_000L, SlotCleanupCoordinator.backoffMs(2))
        assertEquals(16_000L, SlotCleanupCoordinator.backoffMs(4))
        assertEquals(30_000L, SlotCleanupCoordinator.backoffMs(9))
    }
}
