package com.tinyyana.hanatoki.testcontent

import com.tinyyana.hanatoki.stage.DungeonBehavior
import com.tinyyana.hanatoki.stage.StageContext
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * **無時限 Run 容器的 architecture probe**(不是正式 Roguelike 內容——地圖、武器、Threat、
 * Loot Director 都不在這裡,那些屬 integration/內容插件,見 ARCH §11)。
 * 註冊給 `dungeons.yml` 的 `test-roguelike`。
 *
 * 它只證明四件事可以同時成立,而且是引擎層就成立的:
 *
 * 1. 一場 SESSION 型的 Run 可以**沒有時限**,不會被逾時收掉,HUD 顯示的是「已經跑了多久」
 *    而不是假倒數。
 * 2. 玩家進去看到的是**隔離的局內背包**,起始裝備由定義給,身上帶的生存裝備完全看不到。
 * 3. 站上撤離板 = 正常 Resolution(`resultKey=extracted`);死亡 = `resultKey=death`
 *    (定義的 `death-resolution: true`)。兩條路都會把永久背包還回去。
 * 4. 局內物品帶不出去(丟不掉、放不進容器、死了不掉落、跨世界會被清掉)。
 *
 * 場地只有一塊 9×9 地板加一片撤離板——虛空世界沒有地板,不蓋的話玩家一進場就往下掉。
 * 全部經 `ctx.mutate` 蓋,所以整局結束會逐格回滾成空氣。
 */
class RoguelikeShellBehavior : DungeonBehavior {

    override fun onStageEnter(ctx: StageContext, stageId: String) {
        if (stageId != "run") return
        buildFloor(ctx).whenComplete { _, _ ->
            ctx.submit {
                ctx.messageAll("roguelike.entry")
                startClock(ctx)
            }
        }
    }

    /** anchor 為中心的 9×9 地板(y-1)+ 撤離板。 */
    private fun buildFloor(ctx: StageContext): CompletableFuture<Void> {
        val floor = mutableListOf<CompletableFuture<Void>>()
        for (dx in -4..4) {
            for (dz in -4..4) {
                val loc = Location(
                    ctx.anchor.world,
                    (ctx.anchor.blockX + dx).toDouble(),
                    (ctx.anchor.blockY - 1).toDouble(),
                    (ctx.anchor.blockZ + dz).toDouble(),
                )
                floor += ctx.mutate(loc) { it.type = Material.DEEPSLATE_TILES }
            }
        }
        ctx.interactionLocation("extract")?.let { loc ->
            floor += ctx.mutate(loc) { it.type = Material.LIGHT_WEIGHTED_PRESSURE_PLATE }
            floor += ctx.mutate(loc.clone().add(0.0, -1.0, 0.0)) { it.type = Material.WAXED_OXIDIZED_CUT_COPPER }
        }
        return CompletableFuture.allOf(*floor.toTypedArray())
    }

    /**
     * Endless Run 的 HUD:顯示**已經跑了多久**,不是倒數。
     *
     * `ctx.sessionHasTimeLimit()` 為 false 時 `sessionRemainingSeconds()` 回傳 -1
     * (見 `StageContext` 的說明)——直接拿它去算進度條會得到負值,所以這裡先問有沒有時限
     * 再決定畫哪一種。進度條在無時限模式固定滿格:沒有「還剩幾成」這回事,硬要畫一條會動的
     * 就是在對玩家撒謊。
     */
    private fun startClock(ctx: StageContext) {
        ctx.submitRepeating(20L, 20L) {
            if (ctx.sessionHasTimeLimit()) {
                val remaining = ctx.sessionRemainingSeconds()
                ctx.bossBar(clock(remaining), remaining / 300.0, "WHITE")
            } else {
                ctx.bossBar(clock(ctx.sessionElapsedSeconds()), 1.0, "WHITE")
            }
        }
    }

    private fun clock(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return "<#b9c6cf>試作 Run</#b9c6cf> <#8d8579>|</#8d8579> <#b9c6cf>%d:%02d</#b9c6cf>".format(safe / 60, safe % 60)
    }

    override fun onInteraction(ctx: StageContext, interactionId: String, playerId: UUID) {
        if (interactionId != "extract") return
        ctx.state.stats["extracted"] = 1L
        ctx.messageAll("roguelike.extracted")
        ctx.resolve("extracted")
    }
}
