package com.tinyyana.hanatoki.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 假 handle,不碰 Bukkit/BetterModel——只數 close() 被呼叫幾次,順便驗證冪等。 */
private class FakeHandle : AutoCloseable {
    var closeCount = 0
        private set

    override fun close() {
        closeCount++
    }
}

class BossModelOwnerRegistryTest {
    @Test
    fun `closeOwner 只關那個 owner 名下的 handle`() {
        val registry = BossModelOwnerRegistry<FakeHandle>()
        val a = FakeHandle()
        val b = FakeHandle()
        registry.put("session-1", "session-1#a", a)
        registry.put("session-2", "session-2#b", b)

        registry.closeOwner("session-1")

        assertEquals(1, a.closeCount)
        assertEquals(0, b.closeCount)
        assertEquals(1, registry.size())
    }

    @Test
    fun `closeOwner 對不存在的 owner 是 no-op`() {
        val registry = BossModelOwnerRegistry<FakeHandle>()
        registry.closeOwner("nothing-here")
        assertEquals(0, registry.size())
    }

    @Test
    fun `closeOwner 重複呼叫不會重複關同一個 handle`() {
        val registry = BossModelOwnerRegistry<FakeHandle>()
        val handle = FakeHandle()
        registry.put("debug:p1", "debug:p1#x", handle)

        registry.closeOwner("debug:p1")
        registry.closeOwner("debug:p1")

        assertEquals(1, handle.closeCount)
    }

    @Test
    fun `remove 不會呼叫 close,交給呼叫端自己決定`() {
        val registry = BossModelOwnerRegistry<FakeHandle>()
        val handle = FakeHandle()
        registry.put("owner", "owner#x", handle)

        val removed = registry.remove("owner#x")

        assertEquals(handle, removed)
        assertEquals(0, handle.closeCount)
        assertNull(registry.remove("owner#x")) // 第二次已經拿不到了
    }

    @Test
    fun `closeAll 關掉所有 owner 名下的所有 handle`() {
        val registry = BossModelOwnerRegistry<FakeHandle>()
        val a = FakeHandle()
        val b = FakeHandle()
        registry.put("session-1", "session-1#a", a)
        registry.put("session-2", "session-2#b", b)

        registry.closeAll()

        assertEquals(1, a.closeCount)
        assertEquals(1, b.closeCount)
        assertEquals(0, registry.size())
    }

    @Test
    fun `同一個 key 重複 put 會先關掉舊的那個`() {
        val registry = BossModelOwnerRegistry<FakeHandle>()
        val old = FakeHandle()
        val new = FakeHandle()
        registry.put("owner", "owner#x", old)
        registry.put("owner", "owner#x", new)

        assertEquals(1, old.closeCount)
        assertEquals(0, new.closeCount)
        assertEquals(1, registry.size())
    }

    @Test
    fun `hasOwner 反映目前登記狀態`() {
        val registry = BossModelOwnerRegistry<FakeHandle>()
        assertFalse(registry.hasOwner("owner"))
        registry.put("owner", "owner#x", FakeHandle())
        assertTrue(registry.hasOwner("owner"))
        registry.closeOwner("owner")
        assertFalse(registry.hasOwner("owner"))
    }
}
