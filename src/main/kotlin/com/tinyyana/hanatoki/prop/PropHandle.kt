package com.tinyyana.hanatoki.prop

import org.bukkit.Location
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture

/**
 * 場地擺設(ARCH §2「Actor」的無生命版本):不會受傷、不會動、只是站在那裡構成畫面的東西。
 *
 * ## 為什麼不是方塊
 *
 * 場地本體是方塊(走 `StageContext.mutate`,自動記 diff、整局回滾)。但**自訂造型的擺設**
 * 沒辦法用方塊做:這個專案的資源包 pipeline 只支援 item 模型,`build.py` 完全沒有 blockstate/
 * 方塊模型那條路(`docs/research/RESOURCE_PACK_ARCHITECTURE.md` 明文寫「不加自訂方塊」)。
 * 既有的自訂 3D 物件(LycoCosplay 的笠/狐面/獸耳)一律是「item 模型 + 顯示實體」,這裡沿用
 * 同一條路:`ItemDisplay` 掛一個帶 `custom_model_data` 的 ItemStack。
 *
 * 額外的好處:擺設是實體不是方塊,**不進 diff log**——session 結束時跟 actor/encounter 走
 * 同一條清場路徑一次移除,不需要為了幾十朵花在回滾佇列裡塞幾十筆。
 *
 * 跨插件介面規則同 [com.tinyyana.hanatoki.stage.StageContext]:簽章只用 JDK/Bukkit 型別。
 */
interface PropHandle {

    /**
     * 放一個 item 造型的擺設。
     *
     * @param propId 同一個 session 內唯一;重複用同一個 id 會先移除舊的。
     * @param scale 等比縮放(1.0 = 原版掉落物大小)。
     * @param yawDegrees 水平旋轉,讓同一種擺設排在一起時不會像複製貼上。
     * @param fixedBillboard true = 造型固定朝向(不跟著鏡頭轉),植物/擺設一律 true。
     */
    fun spawnItem(
        propId: String,
        location: Location,
        item: ItemStack,
        scale: Float,
        yawDegrees: Float,
        fixedBillboard: Boolean,
    ): CompletableFuture<Void>

    /**
     * 放一個**方塊**造型的擺設([org.bukkit.entity.BlockDisplay])。
     *
     * 跟 [spawnItem] 並存而不是取代:item 造型走資源包的 `custom_model_data` 管道(自訂 3D 物件),
     * 方塊造型走原版方塊外觀——蒼櫻的 Boss 外觀就是三塊浮空的原木/櫻花葉/蒼白葉,那不需要資源包。
     *
     * @param blockData `Bukkit.createBlockData` 吃的字串(例如 `"minecraft:cherry_leaves"`);
     *   解析失敗時記警告並不生成。
     * @param teleportDurationTicks 客戶端補間長度。跟著會動的 actor 走時要設成跟更新頻率一樣
     *   (例如每 4 tick 更新一次就填 4),不然造型會用瞬移而不是滑動。
     */
    fun spawnBlock(
        propId: String,
        location: Location,
        blockData: String,
        teleportDurationTicks: Int,
    ): CompletableFuture<Void>

    /** 把擺設移到新位置(跟隨會動的 actor 用)。找不到就是 no-op。 */
    fun moveTo(propId: String, location: Location): CompletableFuture<Void>

    fun despawn(propId: String): CompletableFuture<Void>

    /** session 結束/admin reset 的清場路徑。 */
    fun despawnAll(): CompletableFuture<Void>

    fun count(): Int
}
