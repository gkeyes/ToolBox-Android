package io.toolbox.host.permissions

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostPermissionSideEffects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal enum class PermissionMutationResult { Saved, Outdated, WriteFailed, CleanupFailed }

/** Finite accepted writes belong to the host, not to the page awaiting their result. */
internal class PermissionMutationRunner(
    private val packages: HostPackageOperations,
    private val grants: PermissionGrantRepository,
    private val sideEffects: HostPermissionSideEffects,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val pending = mutableMapOf<String, Deferred<PermissionMutationResult>>()
    internal val activeToolCount: Int get() = synchronized(pending) { pending.size }

    fun submit(toolId: String, capability: String, enabled: Boolean, expectedVersion: Int): Deferred<PermissionMutationResult> =
        enqueue(toolId) { apply(toolId, capability, enabled, expectedVersion) }

    private fun enqueue(toolId: String, action: suspend () -> PermissionMutationResult): Deferred<PermissionMutationResult> =
        synchronized(pending) {
            val previous = pending[toolId]
            val mutation = scope.async(start = CoroutineStart.LAZY) {
                previous?.join()
                action()
            }
            pending[toolId] = mutation
            mutation.invokeOnCompletion {
                synchronized(pending) {
                    if (pending[toolId] === mutation) pending.remove(toolId)
                }
            }
            mutation.start()
            mutation
        }

    private suspend fun apply(
        toolId: String,
        capability: String,
        enabled: Boolean,
        expectedVersion: Int,
    ): PermissionMutationResult = try {
        val current = packages.installedManifest(toolId) as? HostInstalledManifestResult.Found
        if (current == null || current.manifest.versionCode != expectedVersion ||
            current.manifest.permissions.none { it.capability == capability }
        ) {
            PermissionMutationResult.Outdated
        } else if (grants.observeGrants(toolId).first().any { it.capability == capability && it.granted == enabled }) {
            PermissionMutationResult.Saved
        } else {
            // Retry a failed secure wipe before making old secrets readable again.
            if (enabled && capability == "storage.secure") sideEffects.onCapabilityDisabled(toolId, capability)
            when (grants.putForVersion(PermissionGrant(toolId, capability, enabled, now()), expectedVersion)) {
                is DataResult.Success -> {
                    // Deny new calls before waiting for in-flight work and cleanup.
                    if (!enabled) withContext(NonCancellable) {
                        sideEffects.onCapabilityDisabled(toolId, capability)
                    }
                    PermissionMutationResult.Saved
                }
                is DataResult.Failure -> PermissionMutationResult.WriteFailed
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PermissionMutationResult.CleanupFailed
    }
}
