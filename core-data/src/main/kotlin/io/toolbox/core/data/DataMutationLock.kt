package io.toolbox.core.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One process-wide admission boundary. Do not launch detached child writers inside run(). */
class DataRecoveryRequiredException : IllegalStateException("RESTORE_RECOVERY_REQUIRED")

class DataMutationLock {
    private val mutex = Mutex()
    @Volatile private var blocked = false
    private class Owner(val locks: Set<DataMutationLock>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Owner>
    }
    suspend fun <T> run(action: suspend () -> T): T {
        val held = currentCoroutineContext()[Owner]?.locks.orEmpty()
        if (this in held) return action()
        return mutex.withLock {
            if (blocked) throw DataRecoveryRequiredException()
            withContext(Owner(held + this)) { action() }
        }
    }
    suspend fun <T> write(action: suspend () -> DataResult<T>): DataResult<T> = try { run(action) }
        catch (_: DataRecoveryRequiredException) { DataResult.Failure.StorageFailure("RESTORE_RECOVERY_REQUIRED") }
    fun blockUntilRestart() { blocked = true }
}

/** Decorates existing repositories, without a second database/settings implementation. */
fun CoreDataRepositories.withMutationLock(): CoreDataRepositories {
    val raw = this
    val lock = mutations
    return copy(
        lifecycle = object : CatalogLifecycleRepository by raw.lifecycle {
            override suspend fun commitInstall(attempt: CatalogInstallAttempt) = lock.write { raw.lifecycle.commitInstall(attempt) }
            override suspend fun deleteToolCatalog(toolId: String) = lock.write { raw.lifecycle.deleteToolCatalog(toolId) }
        },
        organization = object : CatalogOrganizationRepository by raw.organization {
            override suspend fun recordOpened(toolId: String, timestamp: Long) = lock.write { raw.organization.recordOpened(toolId, timestamp) }
        },
        grants = object : PermissionGrantRepository by raw.grants {
            override suspend fun put(grant: PermissionGrant) = lock.write { raw.grants.put(grant) }
            override suspend fun putForVersion(grant: PermissionGrant, expectedVersionCode: Int) = lock.write { raw.grants.putForVersion(grant, expectedVersionCode) }
            override suspend fun revoke(toolId: String, capability: String) = lock.write { raw.grants.revoke(toolId, capability) }
        },
        keyValues = object : ToolKvRepository by raw.keyValues {
            override suspend fun put(toolId: String, key: String, valueJson: String, updatedAt: Long) = lock.write { raw.keyValues.put(toolId, key, valueJson, updatedAt) }
            override suspend fun replace(toolId: String, removeKeys: Set<String>, values: Map<String, String>, updatedAt: Long) = lock.write { raw.keyValues.replace(toolId, removeKeys, values, updatedAt) }
            override suspend fun remove(toolId: String, key: String) = lock.write { raw.keyValues.remove(toolId, key) }
        },
        installs = object : InstallTransactionRepository by raw.installs {
            override suspend fun begin(transaction: InstallTransaction) = lock.write { raw.installs.begin(transaction) }
            override suspend fun markCommitting(transactionId: String, updatedAt: Long) = lock.write { raw.installs.markCommitting(transactionId, updatedAt) }
            override suspend fun fail(transactionId: String, updatedAt: Long, failureCode: String) = lock.write { raw.installs.fail(transactionId, updatedAt, failureCode) }
        },
        backgroundTasks = object : BackgroundTaskRepository by raw.backgroundTasks {
            override suspend fun create(task: BackgroundTask) = lock.write { raw.backgroundTasks.create(task) }
            override suspend fun markRunning(taskId: String, updatedAt: Long, runAttempt: Int) = lock.write { raw.backgroundTasks.markRunning(taskId, updatedAt, runAttempt) }
            override suspend fun deferRetry(taskId: String, updatedAt: Long, nextRunAt: Long, runAttempt: Int) = lock.write { raw.backgroundTasks.deferRetry(taskId, updatedAt, nextRunAt, runAttempt) }
            override suspend fun requeueInterruptedRun(taskId: String, updatedAt: Long) = lock.write { raw.backgroundTasks.requeueInterruptedRun(taskId, updatedAt) }
            override suspend fun finishRun(taskId: String, result: TaskRunResult, nextState: TaskState, nextRunAt: Long?) = lock.write { raw.backgroundTasks.finishRun(taskId, result, nextState, nextRunAt) }
            override suspend fun finishCancelled(taskId: String, result: TaskRunResult) = lock.write { raw.backgroundTasks.finishCancelled(taskId, result) }
            override suspend fun cancel(taskId: String, updatedAt: Long) = lock.write { raw.backgroundTasks.cancel(taskId, updatedAt) }
            override suspend fun pruneResultsCompletedBefore(cutoffMillis: Long) = lock.write { raw.backgroundTasks.pruneResultsCompletedBefore(cutoffMillis) }
            override suspend fun deleteForTool(toolId: String) = lock.write { raw.backgroundTasks.deleteForTool(toolId) }
        },
        settings = object : HostSettingsRepository by raw.settings {
            override suspend fun update(transform: (HostSettings) -> HostSettings) = lock.write { raw.settings.update(transform) }
        },
    )
}
