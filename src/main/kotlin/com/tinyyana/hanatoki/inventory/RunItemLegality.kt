package com.tinyyana.hanatoki.inventory

/**
 * 一件物品在某位玩家手上**現在**算什麼。所有執行期的合法性判定(拾取、跨世界/登入掃背包、
 * [ForeignItemWarden] 巡檢、[InstanceItemsImpl.isLegalFor])都問這一個函式,不再各自寫一套。
 *
 * ## 為什麼要統一(2026-09-27 深域多人事故)
 *
 * `instanceId` 是**每位玩家各自一份**的背包交易 token,但深域的起始武器與地面掉落是整隊共用的,
 * 只蓋得了其中一位隊員的 token。以前拾取判定認「同 session 的隊友 token」,巡檢卻只認「自己的
 * token」——同一件合法的隊友物品,撿得起來、兩秒後就被巡檢當成局外物品收走,再被塞進永久背包
 * 快照,還原後又被當成失效物品清掉。兩條防線的規則不一樣,就一定有一邊是錯的。
 *
 * ## 規則(scope-aware + session-aware)
 *
 * - 沒有局內章 → [RunItemVerdict.PERMANENT](永久物品;在 Run 裡它是「局外物品」,由巡檢移出)。
 * - 有局內章,但 token 讀不出來(半殘標記)→ [RunItemVerdict.RUN_ILLEGAL]。
 * - 玩家現在不在任何 active Run → 任何局內物品都 [RunItemVerdict.RUN_ILLEGAL]。
 * - token 屬於玩家**目前這個 session 曾經發出的任何一份 token** → [RunItemVerdict.RUN_LEGAL]。
 *   「曾經」包含已經離場/死亡的隊員:token 原持有人先走了,他蓋過章的共用掉落對還在場的人
 *   仍然合法(舊版只問「現在還在場的隊員」,持有人一退出整隊的東西就一起失效)。
 * - 其他(異局、舊局、重啟前的局)→ [RunItemVerdict.RUN_ILLEGAL]。
 *
 * 純函數,不碰 Bukkit,單元測試直接打(見 RunItemLegalityTest)。
 */
enum class RunItemVerdict {
    /** 永久物品(沒有局內章)。 */
    PERMANENT,

    /** 局內物品,屬於這位玩家目前這一局的 session。 */
    RUN_LEGAL,

    /** 局內物品,但不屬於這位玩家目前的任何一局(失效、異局、半殘)。 */
    RUN_ILLEGAL,
}

object RunItemLegality {

    /**
     * @param scoped 物品有沒有局內章(scope 或 id 任一個在就算,見 [InstanceItemsImpl.isInstanceScoped])
     * @param itemToken 物品上的 instance token;null = 讀不到(半殘)
     * @param playerToken 玩家目前 active 的 token;null = 不在任何 active Run
     * @param sessionTokens 玩家目前這個 session 發出過的全部 token(含他自己的)
     */
    fun classify(
        scoped: Boolean,
        itemToken: String?,
        playerToken: String?,
        sessionTokens: Set<String>,
    ): RunItemVerdict {
        if (!scoped) return RunItemVerdict.PERMANENT
        if (itemToken.isNullOrBlank()) return RunItemVerdict.RUN_ILLEGAL
        if (playerToken == null) return RunItemVerdict.RUN_ILLEGAL
        if (itemToken == playerToken) return RunItemVerdict.RUN_LEGAL
        return if (itemToken in sessionTokens) RunItemVerdict.RUN_LEGAL else RunItemVerdict.RUN_ILLEGAL
    }
}

/**
 * sessionId → 這個 session 發出過的全部 instance token。
 *
 * 只增不減,直到 session 結束([forget])——這正是「token 原持有人先離場,他蓋過章的共用物品
 * 仍然合法」的依據。重啟後是空的:那時所有舊 session 已經不存在,舊局內物品本來就該全部失效。
 */
class SessionTokenRegistry {
    private val tokens = java.util.concurrent.ConcurrentHashMap<java.util.UUID, MutableSet<String>>()

    fun register(sessionId: java.util.UUID, token: java.util.UUID) {
        tokens.computeIfAbsent(sessionId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(token.toString())
    }

    fun tokensOf(sessionId: java.util.UUID): Set<String> = tokens[sessionId] ?: emptySet()

    fun forget(sessionId: java.util.UUID) {
        tokens.remove(sessionId)
    }
}
