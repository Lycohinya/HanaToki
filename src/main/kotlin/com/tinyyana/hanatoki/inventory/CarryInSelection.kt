package com.tinyyana.hanatoki.inventory

import com.tinyyana.hanatoki.config.CarryInDef
import java.util.UUID

/**
 * 一個「可能是可攜入物」的候選格位——純資料,不碰 [org.bukkit.inventory.ItemStack]/PDC,
 * 讓攜入選取的規則(哪些格位入選、`max` 怎麼吃)可以脫離 Bukkit 單元測試
 * (跟 [com.tinyyana.hanatoki.config.DungeonDefinitionParser] 同一個理由)。
 *
 * @param pdcValues 這一格物品身上,每個 `namespace:key` 對應的 PDC STRING 值(讀不到的 key 不出現)。
 */
data class CarryCandidate(val slotIndex: Int, val pdcValues: Map<String, String>)

/** 一次成功的攜入選取:哪一格、哪一條規則、識別值解析出來的 [kitId]。 */
data class CarrySelection(val slotIndex: Int, val rule: CarryInDef, val kitId: UUID)

/**
 * 純函數:給定快照裡每一格的 PDC 候選資料與規則清單,決定哪些格位入選攜入。
 *
 * ## 規則
 *
 * - 每條規則各自算 `max`,彼此不共用配額。
 * - 同一格位不會被兩條規則重複選中([select] 內用 `chosenSlots` 擋掉)——不然同一件物品
 *   會在 stripped snapshot 裡被拿掉兩次卻只登記一筆 escrow,或反過來多登記一筆。
 * - 識別值(規則 `pdcNamespace:pdcKey` 對應的字串)讀不到,或不能解析成 [UUID] 的格位,
 *   一律跳過(fail closed,不當成攜入——那一格照舊進永久背包快照)。
 * - 掃描順序 = `candidates` 的順序(呼叫端通常照快照的格位順序給),同規則內先出現的先入選。
 */
object CarryInSelection {
    fun select(candidates: List<CarryCandidate>, rules: List<CarryInDef>): List<CarrySelection> {
        if (rules.isEmpty() || candidates.isEmpty()) return emptyList()
        val chosenSlots = mutableSetOf<Int>()
        val out = mutableListOf<CarrySelection>()
        for (rule in rules) {
            val key = "${rule.pdcNamespace}:${rule.pdcKey}"
            var count = 0
            for (candidate in candidates) {
                if (count >= rule.max) break
                if (candidate.slotIndex in chosenSlots) continue
                val raw = candidate.pdcValues[key] ?: continue
                val kitId = runCatching { UUID.fromString(raw) }.getOrNull() ?: continue
                chosenSlots += candidate.slotIndex
                count++
                out += CarrySelection(candidate.slotIndex, rule, kitId)
            }
        }
        return out
    }
}
