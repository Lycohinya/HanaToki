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
     * 載體實體型別([org.bukkit.entity.EntityType] 的常數名)。預設 `"MANNEQUIN"` = 原本唯一的
     * 選項,既有內容零影響。
     *
     * ## 為什麼放寬(2026-08-24,第二個真實案例逼出來的)
     *
     * 刀塚的少女是站樁演出型 Boss,`Mannequin` 剛好。蒼櫻的樹靈不是:它是一隻**有 AI、會追著
     * 玩家跑、被牽引在競技場裡**的原版 `Ravager`,那正是那一關的手感來源。與其為第二種 Boss
     * 另開一套控制器,不如把 actor 的載體變成一個欄位——生成/消失/換裝/名牌/血量/受傷/死亡
     * 這些操作對兩者是同一件事(ARCH §12「先有案例再抽象」:現在有兩個案例了)。
     *
     * 只有 `Mannequin` 才有的欄位([immovable]、[skinTexture]、描述行)對其他型別自動略過。
     */
    var entityType: String = "MANNEQUIN"

    /**
     * 是否啟用實體自己的 AI。預設 false = 演出用站樁 actor(既有行為)。
     * 有 AI 的 Boss(蒼櫻樹靈)設 true——牠的移動與索敵就是原版怪物邏輯,不是自製尋路。
     */
    var ai: Boolean = false

    /** 是否與玩家碰撞。預設 false(演出 actor 不擋路)。 */
    var collidable: Boolean = false

    /** 發光輪廓(隔著地形也看得到 Boss 在哪)。預設 false。 */
    var glowing: Boolean = false

    /**
     * 隱形。預設 false。
     *
     * 存在理由(2026-08-24):Boss 的外觀改成 ItemDisplay 骨架之後,載體實體本身只剩下
     * **hitbox 與受傷判定**這兩個職責——它必須還在(不然打不到、算不了血),但不能被看見
     * (不然骨架與載體會重疊成兩個人)。`setInvisible` 只藏實體本體,hitbox 一格不變,
     * 所以「看得到的大小」與「打得到的範圍」仍然是對齊的(Yana 的硬要求)。
     *
     * ⚠ 裝備欄的東西**不會**跟著隱形(原版行為)。要全隱形就別給它任何裝備。
     */
    var invisible: Boolean = false

    /**
     * 是否在玩家走遠時被自動清除。null(預設)= 不動實體自己的預設值。
     * 常駐副本的 Boss 要設 false:競技場很大,玩家繞到外圈時 Boss 不該憑空消失。
     */
    var removeWhenFarAway: Boolean? = null

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
