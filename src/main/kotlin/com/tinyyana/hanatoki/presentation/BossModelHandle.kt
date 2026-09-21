package com.tinyyana.hanatoki.presentation

import org.bukkit.Location

/**
 * ARCH 慣例(比照 [com.tinyyana.hanatoki.actor.ActorHandle]):Boss 外觀呈現的操作面,
 * 引擎中立——這個檔案不 import 任何 BetterModel 的 class,消費端插件也不需要。
 *
 * 跨插件介面:不使用 Kotlin 預設參數(理由同 `ActorHandle`/`StageContext` KDoc 的既有記錄:
 * 帶預設值的介面方法在不同 classloader 之間會產生 `NoSuchMethodError`)。回呼參數用
 * [Runnable] 而不是 Kotlin 的函式型別,同一個理由——避免消費端因為 Kotlin 版本不同踩到
 * `kotlin.jvm.functions.FunctionN` 的二進位相容問題。
 */
interface BossModelHandle : AutoCloseable {
    /** 這個 handle 目前綁的 BetterModel 模型 id(建立時傳入的那個)。 */
    val modelId: String

    /** false = tracker 已關閉/已遺失(例如 `/bettermodel reload` 之後沒能重建的靜態模型)。 */
    val isActive: Boolean

    /**
     * 播放一段動畫。[loop] = true 用 LOOP,false 用 PLAY_ONCE。[onEnd] 只在 [loop] = false
     * 時有意義,動畫真正播完(不是被別的動畫蓋掉)才會被呼叫;傳 null 就不要回呼。
     *
     * @return false = 這個模型沒有叫這個名字的動畫,或這個 handle 已經不 active
     */
    fun play(animation: String, loop: Boolean, onEnd: Runnable?): Boolean

    /** 等同 `play(animation, false, null)`。 */
    fun play(animation: String): Boolean

    /** 停止一段動畫。找不到就是 no-op。 */
    fun stop(animation: String)

    /**
     * 設定「基底」loop 動畫——handle 記住它,`/bettermodel reload` 後 tracker 需要重建時
     * 會自動恢復播放(見 `presentation.bettermodel.BetterModelBossModels` 的 reload 處理)。
     * 內部行為等同 `play(animation, true, null)`。
     */
    fun setBase(animation: String)

    /**
     * 讀取一個 locator 骨頭(`loc_*`)目前的世界座標。空骨頭在模型裡沒有顯示,只是一個
     * 座標標記(手部、骰子、光環...)。找不到這個名字的骨頭,或 handle 不 active 時回 null。
     *
     * ⚠ 這是唯一**不經 Folia 派工、任何執行緒都能直接呼叫**的方法——只讀 BetterModel 自己
     * 管理的執行緒安全狀態,不碰任何 Bukkit world/entity API(細節與來源見
     * `presentation.bettermodel.BetterModelBossModels` KDoc)。
     */
    fun locator(name: String): Location?

    /** 設定整體縮放倍率(1.0 = 模型原始大小)。 */
    fun scale(value: Float)

    /** 移除這個模型的顯示。冪等——重複呼叫是 no-op。 */
    override fun close()
}
