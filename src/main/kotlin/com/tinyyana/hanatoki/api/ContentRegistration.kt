package com.tinyyana.hanatoki.api

import java.util.concurrent.CompletableFuture

/** Stop admission immediately; completion means the target's sessions and registrations are drained.
 * Await asynchronously before disabling/replacing the plugin. Never block a Folia region thread.
 */
interface ContentRegistration {
    fun closeAsync(): CompletableFuture<Void>
}
