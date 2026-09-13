package com.tinyyana.hanatoki

import com.tinyyana.hanatoki.api.ContentRegistration
import com.tinyyana.hanatoki.config.DungeonDefinition
import com.tinyyana.hanatoki.stage.DungeonBehavior
import com.tinyyana.hanatoki.stage.DungeonBehaviorRegistry
import com.tinyyana.hanatoki.world.DungeonWorldProvisioner
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.concurrent.CompletableFuture

/** Serializes registration and admission; cleanup always runs asynchronously outside the monitor. */
internal class ContentLifecycle(private val core: HanaTokiCore) {
    val lock = Any()
    private val registrations = mutableMapOf<String, Registration>()
    private val knownOwners = mutableSetOf<String>()

    fun isKnownOwner(name: String): Boolean = synchronized(lock) { name in knownOwners }

    fun accepting(id: String): Boolean = synchronized(lock) { registrations[id]?.closing == null }

    fun register(owner: Plugin, file: File, texts: Map<String, String>, behaviors: Map<String, DungeonBehavior>): CompletableFuture<ContentRegistration> {
        val result = CompletableFuture<ContentRegistration>()
        DungeonWorldProvisioner.runOnGlobalRegion(core.plugin, Runnable {
            try {
                require(owner.isEnabled) { "Content owner is disabled: ${owner.name}" }
                require(file.isFile) { "Missing content definitions: $file" }
                val ids = YamlConfiguration.loadConfiguration(file).getConfigurationSection("dungeons")?.getKeys(false)?.toSet()
                    ?: error("Missing dungeons section: $file")
                require(ids.isNotEmpty() && ids.containsAll(behaviors.keys)) { "Behavior IDs must belong to definitions" }
                synchronized(lock) {
                    require(ids.none { registrations.containsKey(it) || core.registry.definitions.containsKey(it) }) { "Dungeon already registered or draining: $ids" }
                    require(registrations.values.none { registration -> registration.textEntries.keys.any(texts::containsKey) }) { "Content text namespace already registered" }
                    try {
                        core.registry.loadAdditional(file, core.slotPool)
                        require(ids.all { core.registry.definitions.containsKey(it) && core.slotPool.totalCount(it) > 0 }) {
                            "Content definition has no available world/slots: $ids"
                        }
                    } catch (error: Throwable) {
                        ids.forEach { id -> core.registry.definitions[id]?.let { core.registry.unregister(id, it, core.slotPool) } }
                        throw error
                    }
                    val definitions = ids.associateWith { core.registry.definitions[it] ?: error("Definition not loaded: $it") }
                    val registration = Registration(owner.name, definitions, HashMap(texts), HashMap(behaviors))
                    knownOwners += owner.name
                    ids.forEach { registrations[it] = registration }
                    behaviors.forEach { (id, behavior) -> DungeonBehaviorRegistry.register(id, behavior) }
                    core.texts.merge(texts)
                    result.complete(registration)
                }
            } catch (error: Throwable) { result.completeExceptionally(error) }
        })
        return result
    }

    fun closeOwner(ownerName: String): CompletableFuture<Void> {
        val handles = synchronized(lock) { registrations.values.filter { it.ownerName == ownerName }.distinct() }
        return CompletableFuture.allOf(*handles.map { it.closeAsync() }.toTypedArray())
    }

    private inner class Registration(
        val ownerName: String,
        val definitions: Map<String, DungeonDefinition>,
        val textEntries: Map<String, String>,
        val behaviors: MutableMap<String, DungeonBehavior>,
    ) : ContentRegistration {
        var closing: CompletableFuture<Void>? = null
        override fun closeAsync(): CompletableFuture<Void> {
            val done = synchronized(lock) {
                closing?.let { return it }
                CompletableFuture<Void>().also { closing = it }
            }
            core.drainContent(definitions.keys).whenComplete { _, error ->
                if (error != null) { done.completeExceptionally(error); return@whenComplete }
                DungeonWorldProvisioner.runOnGlobalRegion(core.plugin, Runnable {
                    synchronized(lock) {
                        definitions.forEach { (id, definition) ->
                            if (registrations[id] === this) {
                                behaviors[id]?.let { DungeonBehaviorRegistry.unregister(id, it) }
                                core.registry.unregister(id, definition, core.slotPool)
                                registrations.remove(id)
                            }
                        }
                        core.texts.removeEntries(textEntries)
                        behaviors.clear()
                    }
                    done.complete(null)
                })
            }
            return done
        }
    }
}
