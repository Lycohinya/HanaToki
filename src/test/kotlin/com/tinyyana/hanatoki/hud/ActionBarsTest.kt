package com.tinyyana.hanatoki.hud

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ActionBarsTest {
    private val bridge = object : ActionBarSink {
        override fun show(player: Player, text: Component) = Unit
    }

    @Test
    fun `lycolib absent sends directly and never builds the bridge sink`() {
        var built = 0
        val chosen = ActionBars.select(false, { built++; bridge }, { })
        assertSame(DirectActionBarSink, chosen)
        assertEquals(0, built)
    }

    @Test
    fun `lycolib present goes through the bridge sink`() {
        assertSame(bridge, ActionBars.select(true, { bridge }, { }))
    }

    @Test
    fun `bridge sink that fails to build falls back to direct and reports`() {
        var reported: Throwable? = null
        val chosen = ActionBars.select(true, { throw NoClassDefFoundError("com/tinyyana/lycoLib/api/hud/HudBridge") }, { reported = it })
        assertSame(DirectActionBarSink, chosen)
        assertEquals(true, reported is NoClassDefFoundError)
    }
}
