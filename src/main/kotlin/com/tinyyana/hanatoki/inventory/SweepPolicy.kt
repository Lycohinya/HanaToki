package com.tinyyana.hanatoki.inventory

import java.util.UUID

/** 掃描發生在 session 生命週期的哪一刻。 */
enum class SweepPhase {
    /** 開局:傳送前一次、落地後 60 ticks 再一次。這時場上**有**這一局的玩家。 */
    ENTRY,

    /** 收斂回滾:全員已經還原、送離場地,場上剩下的都是這一局留下來的東西。 */
    CLEANUP,
}

enum class SweepAction { KEEP, REMOVE, RETURN_TO_OWNER }

enum class SweptKind { ITEM, TRIDENT, ARROW }

/**
 * 掃描看到的一個實體,已經從 Bukkit 讀好(分類本身是純函數)。
 *
 * @param ownerId 三叉戟/箭的 owner;掉落物的 thrower
 * @param playerProperty 三叉戟:有 owner 且 pickup = ALLOWED(玩家丟出、可撿回的那種;溺屍的是 DISALLOWED)
 * @param tridentStack 掉落物本身是一把三叉戟(忠誠三叉戟的主人死亡時,原版會把它變成掉落物)
 */
data class SweptEntity(
    val kind: SweptKind,
    val ownerId: UUID?,
    val playerProperty: Boolean = false,
    val instanceScoped: Boolean = false,
    val instanceToken: String? = null,
    val tridentStack: Boolean = false,
)

/**
 * 副本場地掃描的去留判定(2026-09-29 Pandora 忠誠三叉戟遺失)。
 *
 * ## 為什麼不能再「看到 Item/AbstractArrow 一律移除」
 *
 * 開局那兩次掃描的第二次在落地後才跑,玩家這時已經在場上:剛丟出去的三叉戟([org.bukkit.entity.Trident]
 * 也是 AbstractArrow)、剛射的箭、剛丟下的東西都會被當成上一局的殘骸刪掉。收斂那一次則把主人已經
 * 離場的三叉戟刪掉——而主人一旦不在同一個 region,Folia 的 `Projectile.getOwner()` 就回 null,
 * 忠誠不會把它帶回去;pickup = ALLOWED 的忠誠三叉戟又永遠不會自然消失。刪掉就是永久遺失。
 *
 * ## 規則
 *
 * - 玩家的三叉戟:開局時主人是這一局的成員 → 留著(原版忠誠/拾取照常);否則(上一局、別人、收斂時)
 *   → 送回主人的暫存箱,**不刪**。只有這一類會被送回:它是唯一「不會自然消失、主人又撿不回來」的東西。
 * - 其他箭:開局時是這一局成員射的 → 留著;否則移除(原版一分鐘也會消失)。
 * - 帶局內章的掉落物:開局時屬於這一局 session 發出的 token → 留著;否則移除(失效的局內物)。
 * - 沒有章的掉落物:開局時是這一局成員丟的 → 留著;是一把有主人的三叉戟 → 送回;否則移除。
 *
 * 收斂時一律不看成員:成員都已經被送走了,留在場上的只會變成下一局的垃圾(或被下一局的人撿走)。
 */
object SweepPolicy {

    fun decide(entity: SweptEntity, phase: SweepPhase, members: Set<UUID>, sessionTokens: Set<String>): SweepAction {
        val ownedByMember = phase == SweepPhase.ENTRY && entity.ownerId != null && entity.ownerId in members
        return when (entity.kind) {
            SweptKind.TRIDENT -> when {
                !entity.playerProperty || entity.ownerId == null -> SweepAction.REMOVE
                ownedByMember -> SweepAction.KEEP
                else -> SweepAction.RETURN_TO_OWNER
            }
            SweptKind.ARROW -> if (ownedByMember) SweepAction.KEEP else SweepAction.REMOVE
            SweptKind.ITEM -> when {
                entity.instanceScoped ->
                    if (phase == SweepPhase.ENTRY && entity.instanceToken != null && entity.instanceToken in sessionTokens) {
                        SweepAction.KEEP
                    } else {
                        SweepAction.REMOVE
                    }
                ownedByMember -> SweepAction.KEEP
                entity.tridentStack && entity.ownerId != null -> SweepAction.RETURN_TO_OWNER
                else -> SweepAction.REMOVE
            }
        }
    }
}
