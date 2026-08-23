package com.tinyyana.hanatoki.folia

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.Plugin

/**
 * ARCH §5.1②「instance.submit(action)」入口:instance 的純邏輯狀態變更(stage/session/
 * trigger 判定)一律序列化在 anchor 所在的 region。**不得**在傳進這裡的 action 裡直接呼叫
 * `setBlock`/`spawnEntity`/entity 操作——那些一律改用 [WorldOp]。
 */
object InstanceDispatch {
    fun submit(plugin: Plugin, anchor: Location, action: () -> Unit) {
        if (Bukkit.isOwnedByCurrentRegion(anchor)) {
            action()
        } else {
            Bukkit.getRegionScheduler().execute(plugin, anchor, action)
        }
    }
}
