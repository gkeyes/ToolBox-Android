package io.toolbox.host.runtime

import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ToolStorageNamespace(val documentKey: String) {
    Standard("toolbox.runtime.v1.standard.values"),
    Secure("toolbox.runtime.v1.secure.values"),
}

/** No runtime is admitted while the host holds these locks for a version replacement. */
internal suspend fun <T> withRuntimeStorageQuiescent(toolId: String, action: suspend () -> T): T =
    ToolRuntimeStorageLocks.mutexFor(toolId, ToolStorageNamespace.Standard).withLock {
        ToolRuntimeStorageLocks.mutexFor(toolId, ToolStorageNamespace.Secure).withLock { action() }
    }

/** Revocation denies queued callers and drains the already admitted ordinary operation. */
internal suspend fun awaitRuntimeStandardStorageIdle(toolId: String) {
    ToolRuntimeStorageLocks.mutexFor(toolId, ToolStorageNamespace.Standard).withLock { }
}

internal suspend fun <T> withRuntimeStorageAccess(
    toolId: String,
    namespace: ToolStorageNamespace,
    canAccess: suspend () -> Boolean,
    action: suspend () -> T,
): T = ToolRuntimeStorageLocks.mutexFor(toolId, namespace).withLock {
    // Recheck under the same lock used by revocation, not only before RPC dispatch.
    if (!canAccess()) throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Storage permission is disabled")
    action()
}

internal object ToolRuntimeStorageLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun mutexFor(toolId: String, namespace: ToolStorageNamespace): Mutex =
        locks.getOrPut("${namespace.name}:$toolId") { Mutex() }
}
