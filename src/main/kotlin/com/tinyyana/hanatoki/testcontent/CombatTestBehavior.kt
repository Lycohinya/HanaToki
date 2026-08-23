package com.tinyyana.hanatoki.testcontent

import com.tinyyana.hanatoki.stage.DungeonBehavior
import com.tinyyana.hanatoki.stage.StageContext

/**
 * 最小 encounter/ 驗證關(architecture probe,對照 ARCH §5「最小 Encounter/Combat 能力」)。
 * 註冊給 `dungeons.yml` 的 `test-combat`:進場即生怪,清光即通關,逾時走預設 timeout 分支。
 */
class CombatTestBehavior : DungeonBehavior {
    override fun onStageEnter(ctx: StageContext, stageId: String) {
        if (stageId != "entry") return
        ctx.messageAll("combat.entry")
        ctx.spawnEncounter("main-wave") { deathCtx ->
            deathCtx.messageAll("combat.cleared")
            deathCtx.resolve("cleared")
        }
    }

    override fun onStageTimeout(ctx: StageContext, stageId: String) {
        ctx.messageAll("combat.timeout")
        ctx.resolve("timeout")
    }
}
