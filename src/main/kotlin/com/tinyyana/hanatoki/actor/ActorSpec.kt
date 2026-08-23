package com.tinyyana.hanatoki.actor

import org.bukkit.inventory.ItemStack

/**
 * ARCH §2「Actor」的最小描述:一位演出用 NPC 長什麼樣。
 *
 * 實作載體是原版 [org.bukkit.entity.Mannequin](Paper 26.2 起的人形實體,`javap` 對照
 * `paper-api-26.2.build.92-stable.jar` 確認:`LivingEntity` + `setProfile`(玩家 skin)+
 * `getEquipment` + `setImmovable`)——**不是** ArmorStand、不是 Citizens(後者不支援 Folia)。
 * 選它的理由:人形外觀、可裝備、可受傷、沒有尋路 AI,正好對上 ARCH「v1 只做站位 + 對話 +
 * 消失的最小集」與「不預先打造尋路 AI」這兩條邊界。
 *
 * ⚠ 刻意用「無建構子預設值的可變類別 + `apply {}`」而不是帶預設值的 data class:這個型別會被
 * integration 插件(不同 classloader)建構,Kotlin 預設參數會產生 `$default` 合成方法,是全 repo
 * 記錄有案的跨插件 `NoSuchMethodError` 來源(見 `StageContext` KDoc 的同一條註記)。
 */
class ActorSpec {
    /**
     * 玩家皮膚的 `textures` property 值(base64)。null = 用 `Mannequin.defaultProfile()`。
     * [skinSignature] 是 Mojang 簽章,離線取得的皮膚通常沒有——**能不能不帶簽章正常顯示**
     * 屬於未實測事實,由內容層 config 提供、L4 實測後回寫文件,引擎這端不做任何假設。
     */
    var skinTexture: String? = null
    var skinSignature: String? = null

    /** 實體名牌(MiniMessage 原文)。null = 不顯示名牌。 */
    var displayName: String? = null
    var displayNameVisible: Boolean = false

    var mainHand: ItemStack? = null
    var offHand: ItemStack? = null
    var helmet: ItemStack? = null
    var chestplate: ItemStack? = null
    var leggings: ItemStack? = null
    var boots: ItemStack? = null

    /** true = 完全不會被推動/被擊退(演出用站樁);決鬥時的位移一律用 [ActorHandle.teleport]。 */
    var immovable: Boolean = true

    /** true = 不吃任何傷害(純演出 actor);Boss 型 actor 設 false。 */
    var invulnerable: Boolean = true

    /** null = 用實體預設血量。設了就同時改 max health 與當前血量。 */
    var maxHealth: Double? = null

    /** 是否吃重力(演出 actor 站在準備好的地板上時關掉比較穩)。 */
    var gravity: Boolean = false

    /** 是否播放實體自己的音效。演出 actor 一律關掉,音效由內容層自己下 cue。 */
    var silent: Boolean = true
}
