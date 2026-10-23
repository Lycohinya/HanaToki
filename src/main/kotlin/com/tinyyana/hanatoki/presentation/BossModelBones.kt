package com.tinyyana.hanatoki.presentation

/**
 * [BossModelHandle] 的選配能力(rc.23):顯示／隱藏模型的某根骨骼(連同子骨骼)。獨立介面是為了不改動跨插件的
 * [BossModelHandle] 簽章;使用端以 `handle as? BossModelBones` 判斷有沒有(BetterModel 實作有,其他實作可以沒有)。
 *
 * 用在 Boss 的光環與寶石:場邊燈亮一盞碎一顆寶石(隱藏 `gem_n`)、護盾碎時隱藏光環。
 */
interface BossModelBones {
    /** 找不到骨骼或 handle 已不 active 時回 false(不丟例外)。 */
    fun setBoneVisible(bone: String, visible: Boolean): Boolean
}
