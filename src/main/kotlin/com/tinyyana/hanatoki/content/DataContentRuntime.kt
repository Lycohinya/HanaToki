package com.tinyyana.hanatoki.content

import com.tinyyana.hanatoki.HanaTokiCore
import com.tinyyana.hanatoki.api.port.CuePort
import com.tinyyana.hanatoki.api.port.DialogPort
import com.tinyyana.hanatoki.map.StructureTemplate
import com.tinyyana.hanatoki.stage.DungeonBehavior
import com.tinyyana.hanatoki.stage.StageContext
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** 註冊完成、驗證過的內容:規格 + 已讀入的場景資產(pieceId → 模板)。不可變。 */
class LoadedContent(val spec: ContentSpec, val templates: Map<String, StructureTemplate>)

/**
 * 資料驅動內容的執行期入口(整個 HanaToki 一份):port 查找、tool 物品、每局的 [SessionRun] 登記表。
 * port 每次呼叫都問 ServicesManager(同 [com.tinyyana.hanatoki.api.MusicCue] 的慣例),
 * LycoHanaToki 熱插拔後不會抓到過期的實作。
 */
class DataContentRuntime(private val core: HanaTokiCore) {
    private val runs = ConcurrentHashMap<UUID, SessionRun>()

    val toolItems: ToolItems by lazy { ToolItems(core.plugin, core.texts) }

    fun cuePort(): CuePort? = core.plugin.server.servicesManager.getRegistration(CuePort::class.java)?.provider

    fun dialogPort(): DialogPort? = core.plugin.server.servicesManager.getRegistration(DialogPort::class.java)?.provider

    fun runOf(sessionId: UUID): SessionRun? = runs[sessionId]

    /** 玩家目前所在 session 的 run(不在任何資料驅動副本 = null)。 */
    fun runOfPlayer(playerId: UUID): SessionRun? {
        val session = core.sessionManager.sessionOf(playerId) ?: return null
        return runs[session.sessionId]
    }

    val hasRuns: Boolean get() = runs.isNotEmpty()

    internal fun put(run: SessionRun) { runs[run.sessionId] = run }

    internal fun remove(sessionId: UUID): SessionRun? = runs.remove(sessionId)

    fun behaviorFor(content: LoadedContent): DungeonBehavior = DataDrivenBehavior(core, this, content)

    internal fun newRun(content: LoadedContent, ctx: StageContext): SessionRun = SessionRun(core, this, content, ctx)
}

/**
 * 框架內建的通用 [DungeonBehavior]:行為完全由 [LoadedContent] 的資料決定,內容端不寫 Kotlin。
 * 一座資料驅動副本一個實例;每局的狀態在 [SessionRun]。
 */
class DataDrivenBehavior(
    private val core: HanaTokiCore,
    private val runtime: DataContentRuntime,
    private val content: LoadedContent,
) : DungeonBehavior {

    override fun prepareStage(ctx: StageContext, stageId: String): CompletableFuture<Void> {
        // 整個場景只在起始 stage 的 prepare 放一次(prepareStage 只會對起始 stage 呼叫)。
        val run = runtime.newRun(content, ctx)
        runtime.put(run)
        return try {
            run.prepare().whenComplete { _, error -> if (error != null) runtime.remove(run.sessionId)?.close() }
        } catch (t: Throwable) {
            runtime.remove(run.sessionId)?.close()
            CompletableFuture.failedFuture(t)
        }
    }

    override fun onStageEnter(ctx: StageContext, stageId: String) {
        runtime.runOf(ctx.sessionId)?.startStage(ctx, stageId)
    }

    /** 第一段切片不結算、不發獎:逾時不走預設的 resolve("timeout")。 */
    override fun onStageTimeout(ctx: StageContext, stageId: String) {
        ctx.log("stage $stageId 逾時,資料驅動內容不結算,略過")
    }

    override fun onMemberReady(ctx: StageContext, playerId: UUID) {
        runtime.runOf(ctx.sessionId)?.onMemberReady(playerId)
    }

    override fun onMemberLeft(ctx: StageContext, playerId: UUID, reason: String) {
        runtime.runOf(ctx.sessionId)?.onMemberLeft(playerId)
    }

    override fun onActorDeath(ctx: StageContext, actorId: String) {
        runtime.runOf(ctx.sessionId)?.onActorDeath(actorId)
    }

    override fun onSessionEnd(ctx: StageContext, reason: String) {
        runtime.remove(ctx.sessionId)?.close()
    }
}
