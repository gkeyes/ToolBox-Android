package io.toolbox.core.data.memory

import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.BackgroundTaskRepository
import io.toolbox.core.data.CatalogCommitHook
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CommitInstallOutcome
import io.toolbox.core.data.CommittedInstall
import io.toolbox.core.data.CoreDataLimits
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.DeleteToolCatalogOutcome
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionRepository
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvValue
import io.toolbox.core.data.isValidCategoryId
import io.toolbox.core.data.isValidTaskId
import io.toolbox.core.data.isValidTaskKey
import io.toolbox.core.data.isValidTransactionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets

object InMemoryCoreData {
    fun create(initialSettings: HostSettings = HostSettings()): CoreDataRepositories =
        createForTest(initialSettings).repositories

    internal fun createForTest(
        initialSettings: HostSettings = HostSettings(),
        commitHook: CatalogCommitHook = CatalogCommitHook.None,
    ): InMemoryCoreDataStores {
        val state = InMemoryCoreState()
        return InMemoryCoreDataStores(
            repositories = CoreDataRepositories(
                catalog = InMemoryCatalogRepository(state),
                lifecycle = InMemoryCatalogLifecycleRepository(state, commitHook),
                organization = InMemoryCatalogOrganizationRepository(state),
                grants = InMemoryPermissionGrantRepository(state),
                keyValues = InMemoryToolKvRepository(state),
                installs = InMemoryInstallTransactionRepository(state),
                backgroundTasks = InMemoryBackgroundTaskRepository(state),
                settings = InMemoryHostSettingsRepository(initialSettings),
            ),
        )
    }
}

data class InMemoryCoreDataStores(val repositories: CoreDataRepositories)

private class InMemoryCoreState {
    val mutex = Mutex()
    val tools = MutableStateFlow<Map<String, InstalledTool>>(emptyMap())
    val grants = MutableStateFlow<Map<Pair<String, String>, PermissionGrant>>(emptyMap())
    val keyValues = MutableStateFlow<Map<Pair<String, String>, ToolKvValue>>(emptyMap())
    val installs = MutableStateFlow<Map<String, InstallTransaction>>(emptyMap())
    val tasks = MutableStateFlow<Map<String, BackgroundTask>>(emptyMap())
    val results = MutableStateFlow<Map<String, TaskRunResult>>(emptyMap())
}

private class InMemoryCatalogRepository(private val state: InMemoryCoreState) : CatalogRepository {
    override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = state.tools.map { tools ->
        tools.values.sortedForCatalog().map { tool ->
            CatalogEntry(
                toolId = tool.metadata.id,
                name = tool.metadata.name,
                securityProfile = tool.metadata.securityProfile,
                installedAt = tool.metadata.installedAt,
                lastOpenedAt = tool.lastOpenedAt,
                pinnedOrder = tool.metadata.pinnedOrder,
                categoryId = tool.metadata.categoryId,
                versionCode = tool.currentVersion.versionCode,
                version = tool.currentVersion.version,
                bundleBytes = tool.currentVersion.bundleBytes,
            )
        }
    }

    override fun observeTools(): Flow<List<InstalledTool>> = state.tools.map { it.values.sortedForCatalog() }
    override fun observeTool(toolId: String): Flow<InstalledTool?> = state.tools.map { it[toolId] }

    private fun Collection<InstalledTool>.sortedForCatalog() = sortedWith(
        compareBy<InstalledTool> { it.metadata.pinnedOrder == null }
            .thenBy { it.metadata.pinnedOrder }
            .thenByDescending { it.metadata.installedAt }
            .thenBy { it.metadata.id },
    )
}

private class InMemoryCatalogLifecycleRepository(
    private val state: InMemoryCoreState,
    private val commitHook: CatalogCommitHook,
) : CatalogLifecycleRepository {
    override suspend fun findCommittedInstall(transactionId: String): DataResult<CommittedInstall?> =
        state.mutex.withLock {
            if (!transactionId.isValidTransactionId()) {
                return@withLock DataResult.Failure.InvalidInput("transactionId")
            }
            DataResult.Success(
                state.installs.value[transactionId]?.takeIf {
                    it.state == InstallTransactionState.COMPLETED
                }?.let { CommittedInstall(it.toolId, it.versionCode) },
            )
        }

    override suspend fun commitInstall(attempt: CatalogInstallAttempt): DataResult<CommitInstallOutcome> =
        state.mutex.withLock {
            validateAttempt(attempt)?.let { return@withLock it }
            val transaction = state.installs.value[attempt.transactionId]
                ?: return@withLock DataResult.Failure.NotFound("installTransaction")
            if (transaction.toolId != attempt.metadata.id || transaction.versionCode != attempt.version.versionCode) {
                return@withLock DataResult.Failure.DuplicateTransaction(attempt.transactionId)
            }
            if (transaction.state == InstallTransactionState.COMPLETED) {
                val current = state.tools.value[attempt.metadata.id]
                return@withLock if (current?.currentVersion == attempt.version) {
                    DataResult.Success(CommitInstallOutcome.AlreadyCommitted)
                } else {
                    DataResult.Failure.DuplicateTransaction(attempt.transactionId)
                }
            }
            if (transaction.state !in setOf(InstallTransactionState.PREPARING, InstallTransactionState.COMMITTING)) {
                return@withLock DataResult.Failure.InvalidState("installTransaction")
            }
            val existing = state.tools.value[attempt.metadata.id]
            if (existing != null) {
                when {
                    attempt.version.versionCode == existing.currentVersion.versionCode ->
                        return@withLock DataResult.Failure.DuplicateVersion(
                            attempt.metadata.id,
                            attempt.version.versionCode,
                        )
                    attempt.version.versionCode < existing.currentVersion.versionCode ->
                        return@withLock DataResult.Failure.NonMonotonicVersion(
                            attempt.metadata.id,
                            attempt.version.versionCode,
                            existing.currentVersion.versionCode,
                        )
                }
            }
            val nextMetadata = if (existing == null) attempt.metadata else attempt.metadata.copy(
                installedAt = existing.metadata.installedAt,
                pinnedOrder = existing.metadata.pinnedOrder,
                categoryId = existing.metadata.categoryId,
            )
            val nextTool = InstalledTool(nextMetadata, attempt.version, existing?.lastOpenedAt)
            val installedGrants = if (existing == null) {
                attempt.initialGrants
            } else {
                attempt.initialGrants.map { declared ->
                    state.grants.value[declared.toolId to declared.capability] ?: declared.copy(granted = false)
                }
            }
            val nextGrants = state.grants.value.filterKeys { it.first != attempt.metadata.id } +
                installedGrants.associateBy { it.toolId to it.capability }
            val replacedTaskIds = if (existing == null) {
                emptySet()
            } else {
                state.tasks.value.values
                    .filter { it.toolId == attempt.metadata.id }
                    .mapTo(mutableSetOf()) { it.taskId }
            }
            val nextTasks = if (replacedTaskIds.isEmpty()) {
                state.tasks.value
            } else {
                state.tasks.value.filterKeys { it !in replacedTaskIds }
            }
            val nextResults = if (replacedTaskIds.isEmpty()) {
                state.results.value
            } else {
                state.results.value.filterKeys { it !in replacedTaskIds }
            }
            try {
                commitHook.beforeCommit()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock DataResult.Failure.StorageFailure("commitInstall")
            }
            state.tools.value = state.tools.value + (attempt.metadata.id to nextTool)
            state.grants.value = nextGrants
            state.tasks.value = nextTasks
            state.results.value = nextResults
            state.installs.value = state.installs.value + (
                attempt.transactionId to transaction.copy(
                    state = InstallTransactionState.COMPLETED,
                    updatedAt = maxOf(transaction.updatedAt, attempt.version.installedAt),
                )
            )
            DataResult.Success(CommitInstallOutcome.Committed)
        }

    override suspend fun deleteToolCatalog(toolId: String): DataResult<DeleteToolCatalogOutcome> =
        state.mutex.withLock {
            val existed = toolId in state.tools.value
            state.tools.value = state.tools.value - toolId
            state.grants.value = state.grants.value.filterKeys { it.first != toolId }
            state.keyValues.value = state.keyValues.value.filterKeys { it.first != toolId }
            state.installs.value = state.installs.value.filterValues { it.toolId != toolId }
            val taskIds = state.tasks.value.values.filter { it.toolId == toolId }.mapTo(mutableSetOf()) { it.taskId }
            state.tasks.value = state.tasks.value.filterValues { it.toolId != toolId }
            state.results.value = state.results.value.filterKeys { it !in taskIds }
            DataResult.Success(
                if (existed) DeleteToolCatalogOutcome.Deleted else DeleteToolCatalogOutcome.AlreadyAbsent,
            )
        }
}

private class InMemoryCatalogOrganizationRepository(
    private val state: InMemoryCoreState,
) : CatalogOrganizationRepository {
    override suspend fun setPinnedOrder(toolId: String, pinnedOrder: Int?): DataResult<Unit> =
        update(toolId, "pinnedOrder", pinnedOrder == null || pinnedOrder >= 0) {
            it.copy(metadata = it.metadata.copy(pinnedOrder = pinnedOrder))
        }

    override suspend fun setCategory(toolId: String, categoryId: String?): DataResult<Unit> =
        update(toolId, "categoryId", categoryId.isValidCategoryId()) {
            it.copy(metadata = it.metadata.copy(categoryId = categoryId))
        }

    override suspend fun recordOpened(toolId: String, timestamp: Long): DataResult<Unit> =
        update(toolId, "timestamp", timestamp >= 0) { it.copy(lastOpenedAt = timestamp) }

    private suspend fun update(
        toolId: String,
        field: String,
        valid: Boolean,
        transform: (InstalledTool) -> InstalledTool,
    ): DataResult<Unit> = state.mutex.withLock {
        if (toolId.isBlank()) return@withLock DataResult.Failure.InvalidInput("toolId")
        if (!valid) return@withLock DataResult.Failure.InvalidInput(field)
        val current = state.tools.value[toolId] ?: return@withLock DataResult.Failure.NotFound("tool")
        state.tools.value = state.tools.value + (toolId to transform(current))
        DataResult.Success(Unit)
    }
}

private class InMemoryPermissionGrantRepository(private val state: InMemoryCoreState) : PermissionGrantRepository {
    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> = state.grants.map { grants ->
        grants.values.filter { it.toolId == toolId }.sortedBy(PermissionGrant::capability)
    }

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> = putChecked(grant, null)

    override suspend fun putForVersion(grant: PermissionGrant, expectedVersionCode: Int): DataResult<Unit> =
        putChecked(grant, expectedVersionCode)

    private suspend fun putChecked(grant: PermissionGrant, expectedVersionCode: Int?): DataResult<Unit> = state.mutex.withLock {
        if (grant.capability.isBlank()) return@withLock DataResult.Failure.InvalidInput("capability")
        if (grant.toolId !in state.tools.value) return@withLock DataResult.Failure.NotFound("tool")
        if (expectedVersionCode != null && state.tools.value[grant.toolId]?.currentVersion?.versionCode != expectedVersionCode) {
            return@withLock DataResult.Failure.InvalidInput("versionCode")
        }
        state.grants.value = state.grants.value + ((grant.toolId to grant.capability) to grant)
        DataResult.Success(Unit)
    }

    override suspend fun revoke(toolId: String, capability: String): DataResult<Unit> = state.mutex.withLock {
        if (toolId !in state.tools.value) return@withLock DataResult.Failure.NotFound("tool")
        state.grants.value = state.grants.value - (toolId to capability)
        DataResult.Success(Unit)
    }
}

private class InMemoryToolKvRepository(private val state: InMemoryCoreState) : ToolKvRepository {
    override fun observe(toolId: String, key: String): Flow<ToolKvValue?> =
        state.keyValues.map { it[toolId to key] }

    override suspend fun put(toolId: String, key: String, valueJson: String, updatedAt: Long): DataResult<Unit> =
        state.mutex.withLock {
            if (key.isBlank()) return@withLock DataResult.Failure.InvalidInput("key")
            if (toolId !in state.tools.value) return@withLock DataResult.Failure.NotFound("tool")
            val mapKey = toolId to key
            val bytes = valueJson.toByteArray(StandardCharsets.UTF_8).size
            val attempted = state.keyValues.value.filterKeys { it.first == toolId }.values
                .sumOf { it.bytes.toLong() } - (state.keyValues.value[mapKey]?.bytes ?: 0) + bytes
            if (attempted > CoreDataLimits.TOOL_KV_BYTES) {
                return@withLock DataResult.Failure.QuotaExceeded(CoreDataLimits.TOOL_KV_BYTES, attempted)
            }
            state.keyValues.value = state.keyValues.value + (mapKey to ToolKvValue(key, valueJson, updatedAt))
            DataResult.Success(Unit)
        }

    override suspend fun remove(toolId: String, key: String): DataResult<Unit> = state.mutex.withLock {
        if (toolId !in state.tools.value) return@withLock DataResult.Failure.NotFound("tool")
        state.keyValues.value = state.keyValues.value - (toolId to key)
        DataResult.Success(Unit)
    }

    override suspend fun bytesUsed(toolId: String): Long = state.mutex.withLock {
        state.keyValues.value.filterKeys { it.first == toolId }.values.sumOf { it.bytes.toLong() }
    }
}

private class InMemoryInstallTransactionRepository(
    private val state: InMemoryCoreState,
) : InstallTransactionRepository {
    override fun observeIncomplete(): Flow<List<InstallTransaction>> = state.installs.map { installs ->
        installs.values.filter {
            it.state == InstallTransactionState.PREPARING || it.state == InstallTransactionState.COMMITTING
        }.sortedBy(InstallTransaction::startedAt)
    }

    override suspend fun get(transactionId: String): DataResult<InstallTransaction?> = state.mutex.withLock {
        if (!transactionId.isValidTransactionId()) return@withLock DataResult.Failure.InvalidInput("transactionId")
        DataResult.Success(state.installs.value[transactionId])
    }

    override suspend fun begin(transaction: InstallTransaction): DataResult<Unit> = state.mutex.withLock {
        validateTransaction(transaction)?.let { return@withLock it }
        if (transaction.id in state.installs.value) {
            return@withLock DataResult.Failure.DuplicateTransaction(transaction.id)
        }
        state.installs.value = state.installs.value + (transaction.id to transaction)
        DataResult.Success(Unit)
    }

    override suspend fun markCommitting(transactionId: String, updatedAt: Long): DataResult<Unit> =
        transition(transactionId, setOf(InstallTransactionState.PREPARING), InstallTransactionState.COMMITTING, updatedAt)

    override suspend fun fail(transactionId: String, updatedAt: Long, failureCode: String): DataResult<Unit> {
        if (failureCode.isBlank()) return DataResult.Failure.InvalidInput("failureCode")
        return transition(
            transactionId,
            setOf(InstallTransactionState.PREPARING, InstallTransactionState.COMMITTING),
            InstallTransactionState.FAILED,
            updatedAt,
            failureCode,
        )
    }

    private suspend fun transition(
        transactionId: String,
        expected: Set<InstallTransactionState>,
        next: InstallTransactionState,
        updatedAt: Long,
        failureCode: String? = null,
    ): DataResult<Unit> = state.mutex.withLock {
        if (!transactionId.isValidTransactionId()) return@withLock DataResult.Failure.InvalidInput("transactionId")
        val current = state.installs.value[transactionId]
            ?: return@withLock DataResult.Failure.NotFound("installTransaction")
        if (current.state !in expected) return@withLock DataResult.Failure.InvalidState("installTransaction")
        if (updatedAt < current.updatedAt) return@withLock DataResult.Failure.InvalidInput("updatedAt")
        state.installs.value = state.installs.value + (
            transactionId to current.copy(state = next, updatedAt = updatedAt, failureCode = failureCode)
        )
        DataResult.Success(Unit)
    }
}

private class InMemoryBackgroundTaskRepository(
    private val state: InMemoryCoreState,
) : BackgroundTaskRepository {
    override fun observeTasks(toolId: String): Flow<List<BackgroundTask>> = state.tasks.map { tasks ->
        tasks.values.filter { it.toolId == toolId }
            .sortedWith(compareByDescending<BackgroundTask> { it.createdAt }.thenBy { it.taskId })
    }

    override fun observeResult(taskId: String): Flow<TaskRunResult?> = state.results.map { it[taskId] }

    override suspend fun getTask(taskId: String): DataResult<BackgroundTask?> = state.mutex.withLock {
        if (!taskId.isValidTaskId()) return@withLock DataResult.Failure.InvalidInput("taskId")
        DataResult.Success(state.tasks.value[taskId])
    }

    override suspend fun create(task: BackgroundTask): DataResult<Unit> = state.mutex.withLock {
        validateTask(task)?.let { return@withLock it }
        val tool = state.tools.value[task.toolId] ?: return@withLock DataResult.Failure.NotFound("tool")
        if (tool.currentVersion.versionCode != task.versionCode) {
            return@withLock DataResult.Failure.InvalidInput("versionCode")
        }
        if (task.taskId in state.tasks.value) return@withLock DataResult.Failure.InvalidInput("taskId")
        if (state.tasks.value.values.any {
                it.toolId == task.toolId && it.key == task.key && it.state in setOf(TaskState.QUEUED, TaskState.RUNNING)
            }
        ) return@withLock DataResult.Failure.DuplicateTaskKey(task.toolId, task.key)
        state.tasks.value = state.tasks.value + (task.taskId to task)
        DataResult.Success(Unit)
    }

    override suspend fun markRunning(taskId: String, updatedAt: Long, runAttempt: Int): DataResult<Unit> {
        if (runAttempt < 1) return DataResult.Failure.InvalidInput("runAttempt")
        return transition(taskId, setOf(TaskState.QUEUED), TaskState.RUNNING, updatedAt, null, runAttempt)
    }

    override suspend fun deferRetry(
        taskId: String,
        updatedAt: Long,
        nextRunAt: Long,
        runAttempt: Int,
    ): DataResult<Unit> {
        if (nextRunAt < updatedAt) return DataResult.Failure.InvalidInput("nextRunAt")
        if (runAttempt < 1) return DataResult.Failure.InvalidInput("runAttempt")
        return transition(
            taskId,
            setOf(TaskState.RUNNING),
            TaskState.QUEUED,
            updatedAt,
            nextRunAt,
            runAttempt,
        )
    }

    override suspend fun requeueInterruptedRun(taskId: String, updatedAt: Long): DataResult<Unit> =
        state.mutex.withLock {
            if (!taskId.isValidTaskId()) return@withLock DataResult.Failure.InvalidInput("taskId")
            if (updatedAt < 0) return@withLock DataResult.Failure.InvalidInput("updatedAt")
            val current = state.tasks.value[taskId]
                ?: return@withLock DataResult.Failure.NotFound("backgroundTask")
            if (current.state != TaskState.RUNNING) {
                return@withLock DataResult.Failure.InvalidState("backgroundTask")
            }
            if (updatedAt < current.updatedAt) {
                return@withLock DataResult.Failure.InvalidInput("updatedAt")
            }
            state.tasks.value = state.tasks.value + (
                taskId to current.copy(
                    state = TaskState.QUEUED,
                    updatedAt = updatedAt,
                    nextRunAt = updatedAt,
                )
            )
            DataResult.Success(Unit)
        }

    override suspend fun finishRun(
        taskId: String,
        result: TaskRunResult,
        nextState: TaskState,
        nextRunAt: Long?,
    ): DataResult<Unit> = state.mutex.withLock {
        if (result.taskId != taskId) return@withLock DataResult.Failure.InvalidInput("result.taskId")
        if (result.outcome == io.toolbox.core.data.RunOutcome.CANCELLED) {
            return@withLock DataResult.Failure.InvalidInput("result.outcome")
        }
        validateResult(result)?.let { return@withLock it }
        if (result.attemptCount < 1) return@withLock DataResult.Failure.InvalidInput("attemptCount")
        val current = state.tasks.value[taskId] ?: return@withLock DataResult.Failure.NotFound("backgroundTask")
        val validNext = if (current.periodic) nextState == TaskState.QUEUED else nextState == TaskState.COMPLETED
        if (current.state != TaskState.RUNNING || !validNext) {
            return@withLock DataResult.Failure.InvalidState("backgroundTask")
        }
        state.results.value = state.results.value + (taskId to result)
        val nextRunAttempt = if (current.periodic) 0 else result.attemptCount
        state.tasks.value = state.tasks.value + (
            taskId to current.copy(
                state = nextState,
                updatedAt = result.completedAt,
                nextRunAt = nextRunAt,
                runAttempt = nextRunAttempt,
            )
        )
        DataResult.Success(Unit)
    }

    override suspend fun finishCancelled(taskId: String, result: TaskRunResult): DataResult<Unit> =
        state.mutex.withLock {
            if (result.taskId != taskId) return@withLock DataResult.Failure.InvalidInput("result.taskId")
            if (result.outcome != io.toolbox.core.data.RunOutcome.CANCELLED) {
                return@withLock DataResult.Failure.InvalidInput("result.outcome")
            }
            validateResult(result)?.let { return@withLock it }
            val current = state.tasks.value[taskId]
                ?: return@withLock DataResult.Failure.NotFound("backgroundTask")
            if (current.state !in setOf(TaskState.QUEUED, TaskState.RUNNING)) {
                return@withLock DataResult.Failure.InvalidState("backgroundTask")
            }
            state.results.value = state.results.value + (taskId to result)
            state.tasks.value = state.tasks.value + (
                taskId to current.copy(
                    state = TaskState.CANCELLED,
                    updatedAt = result.completedAt,
                    nextRunAt = null,
                    runAttempt = result.attemptCount,
                )
            )
            DataResult.Success(Unit)
        }

    override suspend fun cancel(taskId: String, updatedAt: Long): DataResult<Unit> {
        val current = when (val found = getTask(taskId)) {
            is DataResult.Success -> found.value ?: return DataResult.Failure.NotFound("backgroundTask")
            is DataResult.Failure -> return found
        }
        if (current.state == TaskState.CANCELLED) return DataResult.Success(Unit)
        if (current.state == TaskState.COMPLETED) return DataResult.Failure.InvalidState("backgroundTask")
        return transition(
            taskId,
            setOf(TaskState.QUEUED, TaskState.RUNNING),
            TaskState.CANCELLED,
            updatedAt,
            null,
            current.runAttempt,
        )
    }

    override suspend fun pruneResultsCompletedBefore(cutoffMillis: Long): DataResult<Int> =
        state.mutex.withLock {
            if (cutoffMillis < 0) return@withLock DataResult.Failure.InvalidInput("cutoffMillis")
            val expired = state.results.value.filterValues { it.completedAt < cutoffMillis }.keys
            state.results.value = state.results.value.filterKeys { it !in expired }
            DataResult.Success(expired.size)
        }

    override suspend fun deleteForTool(toolId: String): DataResult<Unit> = state.mutex.withLock {
        val ids = state.tasks.value.values.filter { it.toolId == toolId }.mapTo(mutableSetOf()) { it.taskId }
        state.tasks.value = state.tasks.value.filterValues { it.toolId != toolId }
        state.results.value = state.results.value.filterKeys { it !in ids }
        DataResult.Success(Unit)
    }

    private suspend fun transition(
        taskId: String,
        expected: Set<TaskState>,
        next: TaskState,
        updatedAt: Long,
        nextRunAt: Long?,
        runAttempt: Int,
    ): DataResult<Unit> = state.mutex.withLock {
        if (!taskId.isValidTaskId()) return@withLock DataResult.Failure.InvalidInput("taskId")
        val current = state.tasks.value[taskId] ?: return@withLock DataResult.Failure.NotFound("backgroundTask")
        if (current.state !in expected) return@withLock DataResult.Failure.InvalidState("backgroundTask")
        if (updatedAt < current.updatedAt) return@withLock DataResult.Failure.InvalidInput("updatedAt")
        state.tasks.value = state.tasks.value + (
            taskId to current.copy(
                state = next,
                updatedAt = updatedAt,
                nextRunAt = nextRunAt,
                runAttempt = runAttempt,
            )
        )
        DataResult.Success(Unit)
    }
}

class InMemoryHostSettingsRepository(initial: HostSettings = HostSettings()) : HostSettingsRepository {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    override val settings: Flow<HostSettings> = state

    override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> = mutex.withLock {
        state.value = transform(state.value)
        DataResult.Success(Unit)
    }
}

private fun validateAttempt(attempt: CatalogInstallAttempt): DataResult.Failure? {
    if (!attempt.transactionId.isValidTransactionId()) return DataResult.Failure.InvalidInput("transactionId")
    if (attempt.metadata.id.isBlank() || attempt.metadata.id != attempt.version.toolId) {
        return DataResult.Failure.InvalidInput("toolId")
    }
    if (attempt.version.versionCode < 1) return DataResult.Failure.InvalidInput("versionCode")
    if (attempt.version.version.isBlank()) return DataResult.Failure.InvalidInput("version")
    if (attempt.version.bundleBytes < 0) return DataResult.Failure.InvalidInput("bundleBytes")
    if (attempt.version.integrityHash.isBlank()) return DataResult.Failure.InvalidInput("integrityHash")
    if (attempt.initialGrants.any { it.toolId != attempt.metadata.id || it.capability.isBlank() }) {
        return DataResult.Failure.InvalidInput("grant")
    }
    if (attempt.initialGrants.map(PermissionGrant::capability).toSet().size != attempt.initialGrants.size) {
        return DataResult.Failure.InvalidInput("grant.capability")
    }
    return null
}

private fun validateTransaction(transaction: InstallTransaction): DataResult.Failure? = when {
    !transaction.id.isValidTransactionId() -> DataResult.Failure.InvalidInput("transactionId")
    transaction.toolId.isBlank() -> DataResult.Failure.InvalidInput("toolId")
    transaction.versionCode < 1 -> DataResult.Failure.InvalidInput("versionCode")
    transaction.state != InstallTransactionState.PREPARING -> DataResult.Failure.InvalidInput("state")
    transaction.startedAt < 0 || transaction.updatedAt < transaction.startedAt ->
        DataResult.Failure.InvalidInput("updatedAt")
    transaction.failureCode != null -> DataResult.Failure.InvalidInput("failureCode")
    else -> null
}

private fun validateTask(task: BackgroundTask): DataResult.Failure? {
    val specBytes = task.specJson.toByteArray(StandardCharsets.UTF_8).size
    return when {
        !task.taskId.isValidTaskId() -> DataResult.Failure.InvalidInput("taskId")
        task.toolId.isBlank() -> DataResult.Failure.InvalidInput("toolId")
        task.versionCode < 1 -> DataResult.Failure.InvalidInput("versionCode")
        !task.key.isValidTaskKey() -> DataResult.Failure.InvalidInput("key")
        specBytes > CoreDataLimits.MAX_TASK_SPEC_BYTES -> DataResult.Failure.QuotaExceeded(
            CoreDataLimits.MAX_TASK_SPEC_BYTES.toLong(),
            specBytes.toLong(),
        )
        task.state != TaskState.QUEUED -> DataResult.Failure.InvalidInput("state")
        task.createdAt < 0 || task.updatedAt < task.createdAt -> DataResult.Failure.InvalidInput("updatedAt")
        task.runAttempt != 0 -> DataResult.Failure.InvalidInput("runAttempt")
        task.periodic && (task.intervalMinutes == null || task.intervalMinutes < 15) ->
            DataResult.Failure.InvalidInput("intervalMinutes")
        !task.periodic && task.intervalMinutes != null -> DataResult.Failure.InvalidInput("intervalMinutes")
        else -> null
    }
}

private fun validateResult(result: TaskRunResult): DataResult.Failure? {
    val bytes = result.payloadJson?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
    return when {
        !result.taskId.isValidTaskId() -> DataResult.Failure.InvalidInput("taskId")
        result.completedAt < 0 -> DataResult.Failure.InvalidInput("completedAt")
        result.attemptCount < 0 -> DataResult.Failure.InvalidInput("attemptCount")
        bytes > CoreDataLimits.MAX_TASK_RESULT_BYTES -> DataResult.Failure.QuotaExceeded(
            CoreDataLimits.MAX_TASK_RESULT_BYTES.toLong(),
            bytes.toLong(),
        )
        else -> null
    }
}
