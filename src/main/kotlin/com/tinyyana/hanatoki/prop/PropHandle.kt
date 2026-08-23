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

    /**
     * 放一個**骨架部件**:跟 [spawnItem] 同樣是 ItemDisplay,差別在三件事——
     *
     * 1. `itemDisplayTransform` 是 **NONE** 而不是 `GROUND`。`GROUND` 會把模型自己的
     *    display context 疊在你算的 transformation 上,座標永遠對不準(LycoCosplay
     *    `DisplayRenderer` 2026-08-05 查證過的同一個坑)。
     * 2. billboard 固定([Display.Billboard.FIXED]):部件的朝向由 [pose] 決定,不能跟鏡頭轉。
     * 3. 會被標上 scoreboard tag,`/hanatoki admin reset` 與熱插拔之後掃得回來(見 [PART_TAG])。
     *
     * @param teleportDurationTicks 位移補間長度(0–59)。跟著會動的載體走時填成更新間隔。
     */
    fun spawnPart(
        propId: String,
        location: Location,
        item: ItemStack,
        teleportDurationTicks: Int,
    ): CompletableFuture<Void>

    /**
     * 把部件擺到一個姿勢,並讓**客戶端自己補間**過去([Display.setInterpolationDuration])。
     *
     * 這是骨架動畫的唯一入口:一段動作 = 對每個部件依序丟幾個 [pose],每個帶自己的
     * `interpolationTicks`。伺服器只在關鍵影格送封包,中間的每一幀由客戶端算——所以
     * 一段 60 tick 的動作對伺服器來說只有三、四次封包,不是 60 次。
     *
     * ⚠ **參數順序有意義**:實作必須先設 `interpolationDelay = 0` 與 `interpolationDuration`,
     * **再**設 `transformation`,否則這一段會沿用上一段的補間參數
     * (`LycoItems/IaiBladeAnimation.kt` 踩過並寫進註解的坑)。
     *
     * 旋轉用**度數的 pitch/yaw/roll**而不是四元數:內容層寫動作時想的是「前臂往前抬 40 度」,
     * 不是 `Quaternionf(0.34f, …)`。轉換在引擎內做一次,內容層不必碰 joml
     * (也順帶讓這支簽章維持 primitive-only,過 `tools/check-cross-plugin-kotlin.py` 的紅線)。
     *
     * @param tx 相對部件實體位置的位移(格)。骨架的「關節」就是靠這個 + 旋轉組出來的。
     * @param interpolationTicks 補間長度;0 = 立刻跳過去(用在需要「頓一下」的預告拍)。
     */
    fun pose(
        propId: String,
        tx: Float,
        ty: Float,
        tz: Float,
        pitchDegrees: Float,
        yawDegrees: Float,
        rollDegrees: Float,
        scale: Float,
        interpolationTicks: Int,
    ): CompletableFuture<Void>

    /**
     * 同 [pose],但旋轉直接吃**四元數的四個分量**。
     *
     * 存在理由:骨架是**階層**的(手臂掛在軀幹上、刀掛在手上),父節點的旋轉要累加到子節點。
     * 累加只有用四元數才不會有萬向鎖與順序歧義,而把累加完的結果拆回 pitch/yaw/roll 再讓引擎
     * 組回去是多此一舉、還會掉精度。
     *
     * 簽章仍然是 primitive-only(八個 Float + Int),過 `tools/check-cross-plugin-kotlin.py`。
     * 內容層自己用 joml 算(它本來就在 Bukkit 的 classpath 上),這裡只負責送出去。
     */
    fun poseQuaternion(
        propId: String,
        tx: Float,
        ty: Float,
        tz: Float,
        qx: Float,
        qy: Float,
        qz: Float,
        qw: Float,
        scale: Float,
        interpolationTicks: Int,
    ): CompletableFuture<Void>

    /** 把擺設移到新位置(跟隨會動的 actor 用)。找不到就是 no-op。 */
    fun moveTo(propId: String, location: Location): CompletableFuture<Void>

    fun despawn(propId: String): CompletableFuture<Void>

    /** session 結束/admin reset 的清場路徑。 */
    fun despawnAll(): CompletableFuture<Void>

    fun count(): Int

    companion object {
        /**
         * 骨架部件的 scoreboard tag。
         *
         * 存在理由:記憶體登記表在 `/pmxt reload` 之後**會被清空,但實體還在世界裡**——
         * LycoItems 的居合刀身就是這樣留下孤兒 display,Yana 在遊戲裡看得到一顆浮空的刀
         * (2026-08-24 修過的同一個洞)。tag 是「重開之後還認得回來」的唯一憑據。
         *
         * ⚠ 掃描**不能**用 `world.entities`:Folia 上有人在線時那個呼叫會拋跨 region ownership
         * 例外,熱插拔當場炸。照 `IaiBladeAnimation.sweepOrphans` 的逐 chunk +
         * `isOwnedByCurrentRegion` 寫法。
         */
        const val PART_TAG = "hanatoki_rig_part"
    }
}
