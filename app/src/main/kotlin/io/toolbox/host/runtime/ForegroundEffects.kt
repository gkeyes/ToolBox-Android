package io.toolbox.host.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recheck after Main dispatch; the guarded platform effect must not suspend. */
internal suspend fun <T> performForegroundEffect(requireForegroundRuntime: () -> Unit, effect: () -> T): T =
    withContext(Dispatchers.Main.immediate) {
        requireForegroundRuntime()
        effect()
    }
