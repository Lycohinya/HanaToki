package com.tinyyana.hanatoki.stage

import com.tinyyana.hanatoki.instance.EndReason
import com.tinyyana.hanatoki.instance.EnterResult
import com.tinyyana.hanatoki.instance.SessionManager
import com.tinyyana.hanatoki.instance.SlotPool
import com.tinyyana.hanatoki.world.WorldGeneratorRegistry
import org.bukkit.generator.ChunkGenerator
import java.util.UUID
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContentOwnershipTest {
    @Test fun `stale behavior owner cannot remove replacement`() {
        val id = "ownership-test"
        val old = object : DungeonBehavior {}
        val replacement = object : DungeonBehavior {}
        DungeonBehaviorRegistry.register(id, old)
        DungeonBehaviorRegistry.register(id, replacement)
        assertFalse(DungeonBehaviorRegistry.unregister(id, old))
        assertSame(replacement, DungeonBehaviorRegistry.get(id))
        assertTrue(DungeonBehaviorRegistry.unregister(id, replacement))
        assertNull(DungeonBehaviorRegistry.get(id))
    }

    @Test fun `stale generator handle cannot remove replacement`() {
        val id = "ownership-generator"
        val old = WorldGeneratorRegistry.register(id, Supplier { object : ChunkGenerator() {} })
        val replacement = WorldGeneratorRegistry.register(id, Supplier { object : ChunkGenerator() {} })
        old.close()
        assertTrue(WorldGeneratorRegistry.isRegistered(id))
        replacement.close()
        assertFalse(WorldGeneratorRegistry.isRegistered(id))
    }

    @Test fun `target drain and slot unregister preserve unrelated persistent session`() {
        val pool = SlotPool<String>()
        pool.register("target", "target#0", "a")
        pool.register("other", "other#0", "b")
        val sessions = SessionManager(pool)
        val targetPlayer = UUID.randomUUID()
        val otherPlayer = UUID.randomUUID()
        val target = sessions.enter("target", listOf(targetPlayer), 0, null, 1000) as EnterResult.Entered<String>
        val other = sessions.enter("other", listOf(otherPlayer), 0, null, 1000, true) as EnterResult.Entered<String>
        sessions.endSession(target.session.sessionId, EndReason.ABANDONED)
        assertTrue(pool.isOccupied("target#0"))
        sessions.releaseSlotAfterRollback("target#0")
        pool.unregisterDungeon("target")
        assertNull(sessions.sessionOf(targetPlayer))
        assertSame(other.session, sessions.sessionOf(otherPlayer))
        assertTrue(pool.isOccupied("other#0"))
        assertTrue(pool.slotIds("target").isEmpty())
    }
}
