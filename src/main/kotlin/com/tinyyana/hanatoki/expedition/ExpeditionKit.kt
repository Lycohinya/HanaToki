package com.tinyyana.hanatoki.expedition

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * 整備包物品契約(已定案,見主 repo `AGENTS.md`「整備包物品契約」)——**namespace 字面 `lophinya`**,
 * 六個 key 全部是 `PersistentDataType.STRING`(UUID/enum/十進位數字都存成字串)。
 *
 * HanaToki 只讀這幾個字面 key,不依賴 LycoLib 的 `ExpeditionKitKeys`/`ExpeditionEvidence` 型別
 * (ARCH 的跨插件 primitive-only 規則);LycoHanaToki 才用 LycoLib 型別組見證。
 */
object ExpeditionKitPdc {
    const val NAMESPACE = "lophinya"
    const val KIT = "kit"
    const val DESK = "desk"
    const val ABILITY = "ability"
    const val PACK_REVISION = "pack_revision"
    const val OWNER = "owner"
    const val PACKED_AT = "packed_at"
}

/**
 * 物品識別(LycoItems 既有的 key,不是整備包契約的一部分——這裡只是借用它填見證的
 * `product` 欄位,讓見證裡的產品是真的物品 id,不是硬寫字串)。主命名空間是 `lycoitems`,
 * `lycoquest` 是舊命名空間 fallback。
 */
object LycoItemsIdentityPdc {
    const val PRIMARY_NAMESPACE = "lycoitems"
    const val LEGACY_NAMESPACE = "lycoquest"
    const val CUSTOM_ITEM = "custom_item"
}

/**
 * 一份已經通過驗證的整備包快照(primitive-only 純資料)。
 *
 * @param kitId `lophinya:kit` 的值。
 * @param packedAtMs `lophinya:packed_at`(十進位 long 字串)。
 * @param product [LycoItemsIdentityPdc] 讀到的物品 id(例如 `preparation_lining`)。
 */
data class ExpeditionKitData(
    val kitId: UUID,
    val deskId: UUID,
    val ability: String,
    val packRevision: Long,
    val owner: UUID,
    val packedAtMs: Long,
    val product: String,
)

/**
 * 從一件 [ItemStack] 讀出整備包資料。
 *
 * **任何一個 key 缺失或解析不了 → 回傳 null,絕不套預設值**(契約原文,含 [product]——
 * 讀不到就整份拒絕,不補空字串)。呼叫端把 null 當成「這不是整備包」,不特殊處理
 * ——物品照舊當一般永久物品看待。
 */
object ExpeditionKitReader {
    fun read(item: ItemStack): ExpeditionKitData? {
        val meta = item.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        fun str(namespace: String, key: String): String? =
            pdc.get(NamespacedKey(namespace, key), PersistentDataType.STRING)?.takeIf { it.isNotBlank() }
        fun str(key: String): String? = str(ExpeditionKitPdc.NAMESPACE, key)
        fun uuid(key: String): UUID? = str(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        fun long(key: String): Long? = str(key)?.let { it.toLongOrNull() }

        val kitId = uuid(ExpeditionKitPdc.KIT) ?: return null
        val deskId = uuid(ExpeditionKitPdc.DESK) ?: return null
        val ability = str(ExpeditionKitPdc.ABILITY) ?: return null
        val packRevision = long(ExpeditionKitPdc.PACK_REVISION) ?: return null
        val owner = uuid(ExpeditionKitPdc.OWNER) ?: return null
        val packedAtMs = long(ExpeditionKitPdc.PACKED_AT) ?: return null
        val product = str(LycoItemsIdentityPdc.PRIMARY_NAMESPACE, LycoItemsIdentityPdc.CUSTOM_ITEM)
            ?: str(LycoItemsIdentityPdc.LEGACY_NAMESPACE, LycoItemsIdentityPdc.CUSTOM_ITEM)
            ?: return null
        return ExpeditionKitData(kitId, deskId, ability, packRevision, owner, packedAtMs, product)
    }
}
