package com.tinyyana.hanatoki.content

import java.util.UUID

/**
 * rc.32 探索收集物([CollectibleSpec])的純邏輯(不碰 Bukkit，可單元測試):
 * 逐人可見記帳 [CollectViewerBook]、本局收集帳 [CollectLedger]、first／found 判定與距離量法 [CollectRules]。
 */

/**
 * 一個收集物的**逐人可見**狀態機。實體生成時對所有人隱藏，只對「在線成員且本局還沒收過」的人開；
 * 記帳是「我已對這位玩家的哪個 Player 物件開過」。重連後是新的 Player 物件(可見表是空的)，記帳不符就重開；
 * 離場、斷線、局結束的記帳要清掉。開／關是非同步的，只有**真的套用成功**才 [markShown]／[markHidden]；沒成功就下一輪再試。
 */
class CollectViewerBook {
    private val shown = HashMap<UUID, Any>()

    /** [show] 要開、[hide] 要關(那個 Player 物件還在線才有意義)、[drop] 記帳作廢(人已不在線或換了物件，沒東西可關)。 */
    data class Plan(val show: List<UUID>, val hide: List<UUID>, val drop: List<UUID>) {
        val isEmpty: Boolean get() = show.isEmpty() && hide.isEmpty() && drop.isEmpty()
    }

    /**
     * @param live 部件目前存在且生成完成。false = 沒有東西可開，已開過的記帳整批作廢。
     * @param online 目前在線的成員 → 代表那個 Player 物件的識別憑據(比的是 `===`)。
     * @param collected 本局已收過的成員。
     */
    fun plan(live: Boolean, online: Map<UUID, Any>, collected: Set<UUID>): Plan {
        if (!live) return Plan(emptyList(), emptyList(), shown.keys.toList())
        val show = ArrayList<UUID>()
        val hide = ArrayList<UUID>()
        val drop = ArrayList<UUID>()
        for ((id, token) in online) {
            if (id in collected) continue
            if (shown[id] !== token) show += id
        }
        for ((id, token) in shown) {
            val now = online[id]
            when {
                now == null || now !== token -> drop += id
                id in collected -> hide += id
            }
        }
        return Plan(show, hide, drop)
    }

    fun markShown(id: UUID, token: Any) { shown[id] = token }

    fun markHidden(id: UUID) { shown.remove(id) }

    fun isShown(id: UUID): Boolean = id in shown

    fun forget(id: UUID) { shown.remove(id) }

    fun clear() = shown.clear()

    fun shownIds(): Set<UUID> = shown.keys.toSet()
}

/** 本局誰收過哪個藏點。收過就不能再收(離場重進也一樣，局結束才清)。 */
class CollectLedger {
    private val bySpot = HashMap<String, MutableSet<UUID>>()
    private val byPlayer = HashMap<UUID, LinkedHashSet<String>>()

    /** 記一筆；true = 這位玩家本局第一次收這個藏點。 */
    fun add(playerId: UUID, spotId: String): Boolean {
        if (!bySpot.getOrPut(spotId) { HashSet() }.add(playerId)) return false
        byPlayer.getOrPut(playerId) { LinkedHashSet() }.add(spotId)
        return true
    }

    fun has(playerId: UUID, spotId: String): Boolean = bySpot[spotId]?.contains(playerId) == true

    fun collectedBy(spotId: String): Set<UUID> = bySpot[spotId]?.toSet() ?: emptySet()

    fun spotsOf(playerId: UUID): Set<String> = byPlayer[playerId]?.toSet() ?: emptySet()

    fun clear() { bySpot.clear(); byPlayer.clear() }
}

object CollectRules {
    /** stats 的藏點鍵前綴:`collect.<物件 id>`(整局合計)與 `collect.<物件 id>.<玩家 UUID>`(逐人，結算讀這個)。 */
    const val STAT_PREFIX = "collect"

    fun statName(spotId: String): String = "$STAT_PREFIX.$spotId"

    /** [prior] = 終身已收過的藏點(null = 不知道:沒有 port、查詢失敗或還沒回來)。不知道一律當第一次。 */
    fun isFirst(prior: Set<String>?, spotId: String): Boolean = prior == null || spotId !in prior

    /** `{found}`:終身進度(已知 [prior] 時 = 聯集)，不知道就只算本局。 */
    fun found(prior: Set<String>?, thisRun: Set<String>): Int = if (prior == null) thisRun.size else (prior + thisRun).size

    /** 收下時 ActionBar 的 texts key:第一次用 [CollectibleSpec.foundFirstKey]，重訪用 foundRepeatKey(沒寫退回 first)。 */
    fun textKey(spec: CollectibleSpec, first: Boolean): String? =
        if (first) spec.foundFirstKey else (spec.foundRepeatKey ?: spec.foundFirstKey)

    fun textParams(spec: CollectibleSpec, found: Int, total: Int): Map<String, String> =
        mapOf("n" to spec.label, "found" to found.toString(), "total" to total.toString())

    /**
     * 玩家(腳底座標 [px],[py],[pz])是否夠近:量**身體中心**(腳 + 0.9)到浮空實體中心([lx],[ly] + [propHeight],[lz])的距離 <= [radius]。
     * [lx]/[ly]/[lz] 是 marker 的方塊底面中心。
     */
    fun within(px: Double, py: Double, pz: Double, lx: Double, ly: Double, lz: Double, propHeight: Double, radius: Double): Boolean {
        val dx = px - lx
        val dy = (py + 0.9) - (ly + propHeight)
        val dz = pz - lz
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
}
