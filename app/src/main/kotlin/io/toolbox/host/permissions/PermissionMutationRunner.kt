package io.toolbox.host.permissions

import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataMutationLock
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.ToolVersion
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostPermissionSideEffects
import io.toolbox.host.backup.BackupRuntimeGate
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

internal data class PermissionCleanupRetry(
    val version: ToolVersion,
    val revokedGrant: PermissionGrant,
)

internal sealed interface PermissionMutationResult {
    data class Saved(val grant: PermissionGrant?) : PermissionMutationResult
    data object Outdated : PermissionMutationResult
    data object WriteFailed : PermissionMutationResult
    data class CleanupFailed(val retry: PermissionCleanupRetry) : PermissionMutationResult
}

/** Finite accepted writes belong to the host, not to the page awaiting their result. */
internal class PermissionMutationRunner(
    private val packages: HostPackageOperations,
    private val grants: PermissionGrantRepository,
    private val sideEffects: HostPermissionSideEffects,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val mutationLock: DataMutationLock = DataMutationLock(),
) {
    private val pending = mutableMapOf<String, Deferred<PermissionMutationResult>>()

    fun submit(
        toolId: String,
        capability: String,
        enabled: Boolean,
        expectedVersion: ToolVersion,
        expectedGrant: PermissionGrant?,
    ): Deferred<PermissionMutationResult> = enqueue(toolId) {
        if (!matches(toolId, capability, expectedVersion, expectedGrant)) {
            PermissionMutationResult.Outdated
        } else if (expectedGrant?.granted == enabled) {
            PermissionMutationResult.Saved(expectedGrant)
        } else {
            var previousGrant = expectedGrant
            // Old secrets must be cleared before a new grant makes them readable.
            if (enabled && capability == "storage.secure") {
                if (previousGrant == null) {
                    // Missing grants must not skip the wipe or fall back to the UI's
                    // default enabled display when clearing orphaned secure data fails.
                    val denied = PermissionGrant(toolId, capability, false, now())
                    if (grants.putForVersion(denied, expectedVersion.versionCode) !is DataResult.Success) {
                        return@enqueue PermissionMutationResult.WriteFailed
                    }
                    previousGrant = denied
                }
                val cleanup = cleanup(PermissionCleanupRetry(expectedVersion, previousGrant))
                if (cleanup !is PermissionMutationResult.Saved) return@enqueue cleanup
            }
            // Distinct authorization records also invalidate OFF -> ON -> OFF retries
            // when several writes happen within one wall-clock millisecond.
            val timestamp = previousGrant?.let { maxOf(now(), Math.addExact(it.updatedAt, 1L)) } ?: now()
            val grant = PermissionGrant(toolId, capability, enabled, timestamp)
            when (grants.putForVersion(grant, expectedVersion.versionCode)) {
                is DataResult.Success -> if (enabled) {
                    PermissionMutationResult.Saved(grant)
                } else {
                    // Deny new calls before waiting for in-flight work and cleanup.
                    withContext(NonCancellable) { cleanup(PermissionCleanupRetry(expectedVersion, grant)) }
                }
                is DataResult.Failure -> PermissionMutationResult.WriteFailed
            }
        }
    }

    /** This operation never writes a grant, including a temporary enabled value. */
    fun retryCleanup(retry: PermissionCleanupRetry): Deferred<PermissionMutationResult> =
        enqueue(retry.revokedGrant.toolId) {
            val grant = retry.revokedGrant
            if (grant.granted || !matches(grant.toolId, grant.capability, retry.version, grant)) {
                PermissionMutationResult.Outdated
            } else {
                withContext(NonCancellable) { cleanup(retry) }
            }
        }

    private fun enqueue(toolId: String, action: suspend () -> PermissionMutationResult): Deferred<PermissionMutationResult> =
        synchronized(pending) {
            val previous = pending[toolId]
            val mutation = scope.async(start = CoroutineStart.LAZY) {
                previous?.join()
                // Both operations use the same FIFO and restore admission gate. Hold
                // the outer package lock through cleanup so replacement cannot race it.
                // This is HostDependencies.packageMutations, NOT repositories.mutations:
                // runtime/storage work drains before taking the separate repository lock.
                try {
                    BackupRuntimeGate.worker { mutationLock.run(action) } ?: PermissionMutationResult.WriteFailed
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PermissionMutationResult.WriteFailed
                }
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

    private suspend fun matches(
        toolId: String,
        capability: String,
        expectedVersion: ToolVersion,
        expectedGrant: PermissionGrant?,
    ): Boolean {
        if (expectedVersion.toolId != toolId || catalog.observeTool(toolId).first()?.currentVersion != expectedVersion) return false
        val current = packages.installedManifest(toolId) as? HostInstalledManifestResult.Found ?: return false
        if (current.manifest.toolId != toolId || current.manifest.versionCode != expectedVersion.versionCode ||
            current.manifest.versionName != expectedVersion.version ||
            current.manifest.permissions.none { it.capability == capability }
        ) return false
        return grants.observeGrants(toolId).first().firstOrNull { it.capability == capability } == expectedGrant
    }

    private suspend fun cleanup(retry: PermissionCleanupRetry): PermissionMutationResult = try {
        sideEffects.onCapabilityDisabled(retry.revokedGrant.toolId, retry.revokedGrant.capability)
        PermissionMutationResult.Saved(retry.revokedGrant)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PermissionMutationResult.CleanupFailed(retry)
    }
}
