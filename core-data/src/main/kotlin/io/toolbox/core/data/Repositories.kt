package io.toolbox.core.data

import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun observeCatalogProjection(): Flow<List<CatalogEntry>>
    fun observeTools(): Flow<List<InstalledTool>>
    fun observeTool(toolId: String): Flow<InstalledTool?>
}

interface CatalogLifecycleRepository {
    suspend fun findCommittedInstall(transactionId: String): DataResult<CommittedInstall?>
    suspend fun commitInstall(attempt: CatalogInstallAttempt): DataResult<CommitInstallOutcome>
    suspend fun deleteToolCatalog(toolId: String): DataResult<DeleteToolCatalogOutcome>
}

interface CatalogOrganizationRepository {
    suspend fun setPinnedOrder(toolId: String, pinnedOrder: Int?): DataResult<Unit>
    suspend fun setCategory(toolId: String, categoryId: String?): DataResult<Unit>
    suspend fun recordOpened(toolId: String, timestamp: Long): DataResult<Unit>
}

interface PermissionGrantRepository {
    fun observeGrants(toolId: String): Flow<List<PermissionGrant>>
    suspend fun put(grant: PermissionGrant): DataResult<Unit>
    suspend fun revoke(toolId: String, capability: String): DataResult<Unit>
}

interface ToolKvRepository {
    fun observe(toolId: String, key: String): Flow<ToolKvValue?>
    suspend fun put(toolId: String, key: String, valueJson: String, updatedAt: Long): DataResult<Unit>
    suspend fun remove(toolId: String, key: String): DataResult<Unit>
    suspend fun bytesUsed(toolId: String): Long
}

interface InstallTransactionRepository {
    fun observeIncomplete(): Flow<List<InstallTransaction>>
    suspend fun get(transactionId: String): DataResult<InstallTransaction?>
    suspend fun begin(transaction: InstallTransaction): DataResult<Unit>
    suspend fun markCommitting(transactionId: String, updatedAt: Long): DataResult<Unit>
    suspend fun fail(transactionId: String, updatedAt: Long, failureCode: String): DataResult<Unit>
}

interface BackgroundTaskRepository {
    fun observeTasks(toolId: String): Flow<List<BackgroundTask>>
    fun observeResult(taskId: String): Flow<TaskRunResult?>
    suspend fun getTask(taskId: String): DataResult<BackgroundTask?>
    suspend fun create(task: BackgroundTask): DataResult<Unit>
    suspend fun markRunning(taskId: String, updatedAt: Long, runAttempt: Int): DataResult<Unit>
    suspend fun deferRetry(
        taskId: String,
        updatedAt: Long,
        nextRunAt: Long,
        runAttempt: Int,
    ): DataResult<Unit>
    suspend fun requeueInterruptedRun(taskId: String, updatedAt: Long): DataResult<Unit>
    suspend fun finishRun(
        taskId: String,
        result: TaskRunResult,
        nextState: TaskState,
        nextRunAt: Long?,
    ): DataResult<Unit>
    suspend fun finishCancelled(taskId: String, result: TaskRunResult): DataResult<Unit>
    suspend fun cancel(taskId: String, updatedAt: Long): DataResult<Unit>
    suspend fun pruneResultsCompletedBefore(cutoffMillis: Long): DataResult<Int>
    suspend fun deleteForTool(toolId: String): DataResult<Unit>
}

interface HostSettingsRepository {
    val settings: Flow<HostSettings>
    suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit>
}

data class CoreDataRepositories(
    val catalog: CatalogRepository,
    val lifecycle: CatalogLifecycleRepository,
    val organization: CatalogOrganizationRepository,
    val grants: PermissionGrantRepository,
    val keyValues: ToolKvRepository,
    val installs: InstallTransactionRepository,
    val backgroundTasks: BackgroundTaskRepository,
    val settings: HostSettingsRepository,
)
