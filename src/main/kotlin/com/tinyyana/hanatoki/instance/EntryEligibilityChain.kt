package com.tinyyana.hanatoki.instance

import com.tinyyana.hanatoki.api.EntryEligibility
import java.util.UUID

/**
 * [EntryAdmission.Ports.eligibilityBlock] 的實作:逐一詢問所有 [EntryEligibility] provider。
 *
 * - 任一 provider 回非空白字串 → 擋下,回傳該字串(第一個擋下的為準,順序同 ServicesManager 的
 *   priority 排序)。
 * - provider 丟例外(含跨插件的 `LinkageError`)→ **擋下**,記 warning,原因用引擎文字
 *   [errorReason]:資格查不到時寧可讓玩家稍後再試,也不讓內容規則被例外繞過。
 * - 沒有 provider 或全部回 null/空白 → null(不擋)。
 *
 * 不碰 Bukkit:provider 清單與 log 經建構參數注入,單元測試直接打(見 EntryEligibilityChainTest)。
 */
internal class EntryEligibilityChain(
    private val providers: () -> List<Provider>,
    private val warn: (String) -> Unit,
    private val errorReason: () -> String,
) {
    /** 一個 provider 與它所屬插件的名字(log 用)。 */
    class Provider(val owner: String, val eligibility: EntryEligibility)

    fun block(playerId: UUID, dungeonId: String): String? {
        for (provider in providers()) {
            val reason = try {
                provider.eligibility.block(playerId, dungeonId)
            } catch (error: Exception) {
                return failed(provider, playerId, dungeonId, error)
            } catch (error: LinkageError) {
                return failed(provider, playerId, dungeonId, error)
            }
            if (!reason.isNullOrBlank()) return reason
        }
        return null
    }

    private fun failed(provider: Provider, playerId: UUID, dungeonId: String, error: Throwable): String {
        warn(
            "[HanaToki] EntryEligibility provider ${provider.owner} 查詢失敗,先擋下進場 " +
                "(player=$playerId dungeon=$dungeonId):${error.javaClass.name}: ${error.message}",
        )
        return errorReason()
    }
}
