package com.tinyyana.hanatoki.presentation

import java.util.concurrent.ConcurrentHashMap

/**
 * owner -> handle 的登記表,純邏輯不碰 Bukkit/BetterModel(比照 [com.tinyyana.hanatoki.actor.ActorController]
 * 用字串 key 分組的作法)。`presentation.bettermodel.BetterModelBossModels` 用它記帳,單元測試
 * 也直接測這裡(用假的 [AutoCloseable] handle,不用碰 Bukkit/BetterModel 的任何 class)。
 */
class BossModelOwnerRegistry<H : AutoCloseable> {
    private val byKey = ConcurrentHashMap<String, H>()
    private val keysByOwner = ConcurrentHashMap<String, MutableSet<String>>()

    /** 同一個 key 重複 put 會先關掉舊的(呼叫端目前一律用新產生的 key,這條是防呆)。 */
    fun put(owner: String, key: String, handle: H) {
        keysByOwner.computeIfAbsent(owner) { ConcurrentHashMap.newKeySet() }.add(key)
        byKey.put(key, handle)?.close()
    }

    /** 只從表裡移除,不呼叫 close()——呼叫端(handle 自己的 close())決定要不要關,避免重複關閉。 */
    fun remove(key: String): H? = byKey.remove(key)

    /** 這個 owner 名下目前有沒有任何 handle(找不到就是 false)。 */
    fun hasOwner(owner: String): Boolean = keysByOwner[owner]?.isNotEmpty() == true

    fun closeOwner(owner: String) {
        val keys = keysByOwner.remove(owner) ?: return
        keys.forEach { k -> byKey.remove(k)?.close() }
    }

    fun closeAll() {
        keysByOwner.clear()
        val all = byKey.keys.toList().mapNotNull { byKey.remove(it) }
        all.forEach { it.close() }
    }

    fun size(): Int = byKey.size
}
