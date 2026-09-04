package io.toolbox.core.data.db

import androidx.room.withTransaction
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
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.DeleteToolCatalogOutcome
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
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets

internal class RoomCatalogRepository(
    private val database: ToolBoxDatabase,
) : CatalogRepository {
    override fun observeCatalogProjection(): Flow<List<CatalogEntry>> =
        database.tools().observeAll().map { rows -> rows.map(InstalledToolProjection::toCatalogEntry) }

    override fun observeTools(): Flow<List<InstalledTool>> =
        database.tools().observeAll().map { rows -> rows.map(InstalledToolProjection::toDomain) }

    override fun observeTool(toolId: String): Flow<InstalledTool?> =
        database.tools().observe(toolId).map { it?.toDomain() }
}

internal class RoomCatalogLifecycleRepository(
    private val database: ToolBoxDatabase,
    private val commitHook: CatalogCommitHook = CatalogCommitHook.None,
) : CatalogLifecycleRepository {
    override suspend fun findCommittedInstall(transactionId: String): DataResult<CommittedInstall?> {
        if (!transactionId.isValidTransactionId()) return DataResult.Failure.InvalidInput("transactionId")
        return storageQuery("findCommittedInstall") {
            database.installs().get(transactionId)?.takeIf {
                it.state == InstallTransactionState.COMPLETED.name
            }?.let { CommittedInstall(it.toolId, it.versionCode) }
        }
    }

    override suspend fun commitInstall(attempt: CatalogInstallAttempt): DataResult<CommitInstallOutcome> {
        validateAttempt(attempt)?.let { return it }
        return try {
            database.withTransaction {
                val transaction = database.installs().get(attempt.transactionId)
                    ?: return@withTransaction DataResult.Failure.NotFound("installTransaction")
                if (transaction.toolId != attempt.metadata.id || transaction.versionCode != attempt.version.versionCode) {
                    return@withTransaction DataResult.Failure.DuplicateTransaction(attempt.transactionId)
                }
                if (transaction.state == InstallTransactionState.COMPLETED.name) {
                    val current = database.versions().get(attempt.metadata.id)
                    return@withTransaction if (current == attempt.version.toEntity()) {
                        DataResult.Success(CommitInstallOutcome.AlreadyCommitted)
                    } else {
                        DataResult.Failure.DuplicateTransaction(attempt.transactionId)
                    }
                }
                if (transaction.state !in setOf(
                        InstallTransactionState.PREPARING.name,
                        InstallTransactionState.COMMITTING.name,
                    )
                ) return@withTransaction DataResult.Failure.InvalidState("installTransaction")

                val existingTool = database.tools().get(attempt.metadata.id)
                val existingVersion = database.versions().get(attempt.metadata.id)
                if ((existingTool == null) != (existingVersion == null)) {
                    return@withTransaction DataResult.Failure.InvalidState("catalog")
                }
                if (existingVersion != null) {
                    when {
                        attempt.version.versionCode == existingVersion.versionCode ->
                            return@withTransaction DataResult.Failure.DuplicateVersion(
                                attempt.metadata.id,
                                attempt.version.versionCode,
                            )
                        attempt.version.versionCode < existingVersion.versionCode ->
                            return@withTransaction DataResult.Failure.NonMonotonicVersion(
                                attempt.metadata.id,
                                attempt.version.versionCode,
                                existingVersion.versionCode,
                            )
                    }
                }

                if (transaction.state == InstallTransactionState.PREPARING.name) {
                    database.installs().transition(
                        attempt.transactionId,
                        listOf(InstallTransactionState.PREPARING.name),
                        InstallTransactionState.COMMITTING.name,
                        transaction.updatedAt,
                        null,
                    )
                }
                if (existingTool == null) {
                    database.tools().insert(attempt.metadata.toEntity())
                } else {
                    database.tools().update(
                        attempt.metadata.copy(
                            installedAt = existingTool.installedAt,
                            pinnedOrder = existingTool.pinnedOrder,
                            categoryId = existingTool.categoryId,
                        ).toEntity(lastOpenedAt = existingTool.lastOpenedAt),
                    )
                }
                database.versions().put(attempt.version.toEntity())
                if (existingVersion != null) {
                    database.backgroundTasks().deleteForTool(attempt.metadata.id)
                }
                val nextGrants = if (existingVersion == null) {
                    attempt.initialGrants.map(PermissionGrant::toEntity)
                } else {
                    val previousGrants = database.grants().getForTool(attempt.metadata.id).associateBy { it.capability }
                    attempt.initialGrants.map { declared ->
                        previousGrants[declared.capability] ?: declared.copy(granted = false).toEntity()
                    }
                }
                database.grants().deleteAll(attempt.metadata.id)
                database.grants().insertAll(nextGrants)
                commitHook.beforeCommit()
                val completed = database.installs().transition(
                    attempt.transactionId,
                    listOf(InstallTransactionState.COMMITTING.name),
                    InstallTransactionState.COMPLETED.name,
                    maxOf(transaction.updatedAt, attempt.version.installedAt),
                    null,
                )
                if (completed != 1) return@withTransaction DataResult.Failure.InvalidState("installTransaction")
                DataResult.Success(CommitInstallOutcome.Committed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("commitInstall")
        }
    }

    override suspend fun deleteToolCatalog(toolId: String): DataResult<DeleteToolCatalogOutcome> = try {
        database.withTransaction {
            database.backgroundTasks().deleteForTool(toolId)
            database.installs().deleteForTool(toolId)
            val deleted = database.tools().delete(toolId)
            DataResult.Success(
                if (deleted == 1) DeleteToolCatalogOutcome.Deleted else DeleteToolCatalogOutcome.AlreadyAbsent,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("deleteToolCatalog")
    }
}

internal class RoomCatalogOrganizationRepository(
    private val database: ToolBoxDatabase,
) : CatalogOrganizationRepository {
    override suspend fun setPinnedOrder(toolId: String, pinnedOrder: Int?): DataResult<Unit> {
        if (toolId.isBlank()) return DataResult.Failure.InvalidInput("toolId")
        if (pinnedOrder != null && pinnedOrder < 0) return DataResult.Failure.InvalidInput("pinnedOrder")
        return update("setPinnedOrder") { database.tools().setPinnedOrder(toolId, pinnedOrder) }
    }

    override suspend fun setCategory(toolId: String, categoryId: String?): DataResult<Unit> {
        if (toolId.isBlank()) return DataResult.Failure.InvalidInput("toolId")
        if (!categoryId.isValidCategoryId()) return DataResult.Failure.InvalidInput("categoryId")
        return update("setCategory") { database.tools().setCategory(toolId, categoryId) }
    }

    override suspend fun recordOpened(toolId: String, timestamp: Long): DataResult<Unit> {
        if (toolId.isBlank()) return DataResult.Failure.InvalidInput("toolId")
        if (timestamp < 0) return DataResult.Failure.InvalidInput("timestamp")
        return update("recordOpened") { database.tools().recordOpened(toolId, timestamp) }
    }

    private suspend fun update(operation: String, block: suspend () -> Int): DataResult<Unit> = try {
        if (block() == 1) DataResult.Success(Unit) else DataResult.Failure.NotFound("tool")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure(operation)
    }
}

internal class RoomPermissionGrantRepository(
    private val database: ToolBoxDatabase,
) : PermissionGrantRepository {
    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> =
        database.grants().observeForTool(toolId).map { rows -> rows.map(PermissionGrantEntity::toDomain) }

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> = try {
        database.withTransaction {
            if (grant.capability.isBlank()) return@withTransaction DataResult.Failure.InvalidInput("capability")
            if (database.tools().get(grant.toolId) == null) {
                return@withTransaction DataResult.Failure.NotFound("tool")
            }
            database.grants().put(grant.toEntity())
            DataResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("putGrant")
    }

    override suspend fun revoke(toolId: String, capability: String): DataResult<Unit> = try {
        database.withTransaction {
            if (database.tools().get(toolId) == null) return@withTransaction DataResult.Failure.NotFound("tool")
            database.grants().delete(toolId, capability)
            DataResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("revokeGrant")
    }
}

internal class RoomToolKvRepository(
    private val database: ToolBoxDatabase,
) : ToolKvRepository {
    override fun observe(toolId: String, key: String): Flow<ToolKvValue?> =
        database.keyValues().observe(toolId, key).map { it?.toDomain() }

    override suspend fun put(toolId: String, key: String, valueJson: String, updatedAt: Long): DataResult<Unit> {
        if (key.isBlank()) return DataResult.Failure.InvalidInput("key")
        return try {
            database.withTransaction {
                if (database.tools().get(toolId) == null) return@withTransaction DataResult.Failure.NotFound("tool")
                val current = database.keyValues().get(toolId, key)
                val bytes = valueJson.toByteArray(StandardCharsets.UTF_8).size
                val attempted = database.keyValues().bytesUsed(toolId) - (current?.bytes ?: 0) + bytes
                if (attempted > CoreDataLimits.TOOL_KV_BYTES) {
                    return@withTransaction DataResult.Failure.QuotaExceeded(
                        CoreDataLimits.TOOL_KV_BYTES,
                        attempted,
                    )
                }
                database.keyValues().put(ToolKvEntity(toolId, key, valueJson, updatedAt, bytes))
                DataResult.Success(Unit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("putToolKv")
        }
    }

    override suspend fun remove(toolId: String, key: String): DataResult<Unit> = try {
        database.withTransaction {
            if (database.tools().get(toolId) == null) return@withTransaction DataResult.Failure.NotFound("tool")
            database.keyValues().delete(toolId, key)
            DataResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("removeToolKv")
    }

    override suspend fun bytesUsed(toolId: String): Long = database.keyValues().bytesUsed(toolId)
}

internal class RoomInstallTransactionRepository(
    private val database: ToolBoxDatabase,
) : InstallTransactionRepository {
    override fun observeIncomplete(): Flow<List<InstallTransaction>> =
        database.installs().observeIncomplete().map { rows -> rows.map(InstallTransactionEntity::toDomain) }

    override suspend fun get(transactionId: String): DataResult<InstallTransaction?> {
        if (!transactionId.isValidTransactionId()) return DataResult.Failure.InvalidInput("transactionId")
        return storageQuery("getInstallTransaction") { database.installs().get(transactionId)?.toDomain() }
    }

    override suspend fun begin(transaction: InstallTransaction): DataResult<Unit> {
        validateTransaction(transaction)?.let { return it }
        return try {
            database.installs().insert(transaction.toEntity())
            DataResult.Success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.DuplicateTransaction(transaction.id)
        }
    }

    override suspend fun markCommitting(transactionId: String, updatedAt: Long): DataResult<Unit> = transition(
        transactionId,
        listOf(InstallTransactionState.PREPARING),
        InstallTransactionState.COMMITTING,
        updatedAt,
        null,
    )

    override suspend fun fail(transactionId: String, updatedAt: Long, failureCode: String): DataResult<Unit> {
        if (failureCode.isBlank()) return DataResult.Failure.InvalidInput("failureCode")
        return transition(
            transactionId,
            listOf(InstallTransactionState.PREPARING, InstallTransactionState.COMMITTING),
            InstallTransactionState.FAILED,
            updatedAt,
            failureCode,
        )
    }

    private suspend fun transition(
        transactionId: String,
        expected: List<InstallTransactionState>,
        next: InstallTransactionState,
        updatedAt: Long,
        failureCode: String?,
    ): DataResult<Unit> {
        if (!transactionId.isValidTransactionId()) return DataResult.Failure.InvalidInput("transactionId")
        if (updatedAt < 0) return DataResult.Failure.InvalidInput("updatedAt")
        return try {
            if (
                database.installs().transition(
                    transactionId,
                    expected.map(InstallTransactionState::name),
                    next.name,
                    updatedAt,
                    failureCode,
                ) == 1
            ) DataResult.Success(Unit) else if (database.installs().get(transactionId) == null) {
                DataResult.Failure.NotFound("installTransaction")
            } else {
                DataResult.Failure.InvalidState("installTransaction")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("transitionInstallTransaction")
        }
    }
}

internal class RoomBackgroundTaskRepository(
    private val database: ToolBoxDatabase,
) : BackgroundTaskRepository {
    override fun observeTasks(toolId: String): Flow<List<BackgroundTask>> =
        database.backgroundTasks().observeForTool(toolId).map { rows -> rows.map(BackgroundTaskEntity::toDomain) }

    override fun observeResult(taskId: String): Flow<TaskRunResult?> =
        database.taskResults().observe(taskId).map { it?.toDomain() }

    override suspend fun getTask(taskId: String): DataResult<BackgroundTask?> {
        if (!taskId.isValidTaskId()) return DataResult.Failure.InvalidInput("taskId")
        return storageQuery("getBackgroundTask") { database.backgroundTasks().get(taskId)?.toDomain() }
    }

    override suspend fun create(task: BackgroundTask): DataResult<Unit> {
        validateTask(task)?.let { return it }
        return try {
            database.withTransaction {
                val version = database.versions().get(task.toolId)
                    ?: return@withTransaction DataResult.Failure.NotFound("tool")
                if (version.versionCode != task.versionCode) {
                    return@withTransaction DataResult.Failure.InvalidInput("versionCode")
                }
                if (database.backgroundTasks().getActiveByKey(task.toolId, task.key) != null) {
                    return@withTransaction DataResult.Failure.DuplicateTaskKey(task.toolId, task.key)
                }
                database.backgroundTasks().insert(task.toEntity())
                DataResult.Success(Unit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("createBackgroundTask")
        }
    }

    override suspend fun markRunning(taskId: String, updatedAt: Long, runAttempt: Int): DataResult<Unit> {
        if (runAttempt < 1) return DataResult.Failure.InvalidInput("runAttempt")
        return transitionTask(
            taskId,
            listOf(TaskState.QUEUED),
            TaskState.RUNNING,
            updatedAt,
            null,
            runAttempt,
        )
    }

    override suspend fun deferRetry(
        taskId: String,
        updatedAt: Long,
        nextRunAt: Long,
        runAttempt: Int,
    ): DataResult<Unit> {
        if (nextRunAt < updatedAt) return DataResult.Failure.InvalidInput("nextRunAt")
        if (runAttempt < 1) return DataResult.Failure.InvalidInput("runAttempt")
        return transitionTask(
            taskId,
            listOf(TaskState.RUNNING),
            TaskState.QUEUED,
            updatedAt,
            nextRunAt,
            runAttempt,
        )
    }

    override suspend fun requeueInterruptedRun(taskId: String, updatedAt: Long): DataResult<Unit> {
        if (!taskId.isValidTaskId()) return DataResult.Failure.InvalidInput("taskId")
        if (updatedAt < 0) return DataResult.Failure.InvalidInput("updatedAt")
        return try {
            if (database.backgroundTasks().requeueInterruptedRun(taskId, updatedAt) == 1) {
                DataResult.Success(Unit)
            } else {
                val current = database.backgroundTasks().get(taskId)
                when {
                    current == null -> DataResult.Failure.NotFound("backgroundTask")
                    current.state != TaskState.RUNNING.name -> DataResult.Failure.InvalidState("backgroundTask")
                    else -> DataResult.Failure.InvalidInput("updatedAt")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("requeueInterruptedBackgroundTask")
        }
    }

    override suspend fun finishRun(
        taskId: String,
        result: TaskRunResult,
        nextState: TaskState,
        nextRunAt: Long?,
    ): DataResult<Unit> {
        if (result.taskId != taskId) return DataResult.Failure.InvalidInput("result.taskId")
        if (result.outcome == io.toolbox.core.data.RunOutcome.CANCELLED) {
            return DataResult.Failure.InvalidInput("result.outcome")
        }
        validateResult(result)?.let { return it }
        if (result.attemptCount < 1) return DataResult.Failure.InvalidInput("attemptCount")
        return try {
            database.withTransaction {
                val task = database.backgroundTasks().get(taskId)
                    ?: return@withTransaction DataResult.Failure.NotFound("backgroundTask")
                val validNextState = if (task.periodic) nextState == TaskState.QUEUED else nextState == TaskState.COMPLETED
                if (!validNextState || task.state != TaskState.RUNNING.name) {
                    return@withTransaction DataResult.Failure.InvalidState("backgroundTask")
                }
                database.taskResults().put(result.toEntity())
                val nextRunAttempt = if (task.periodic) 0 else result.attemptCount
                val changed = database.backgroundTasks().transition(
                    taskId,
                    listOf(TaskState.RUNNING.name),
                    nextState.name,
                    result.completedAt,
                    nextRunAt,
                    nextRunAttempt,
                )
                if (changed == 1) DataResult.Success(Unit) else DataResult.Failure.InvalidState("backgroundTask")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("finishBackgroundTaskRun")
        }
    }

    override suspend fun finishCancelled(taskId: String, result: TaskRunResult): DataResult<Unit> {
        if (result.taskId != taskId) return DataResult.Failure.InvalidInput("result.taskId")
        if (result.outcome != io.toolbox.core.data.RunOutcome.CANCELLED) {
            return DataResult.Failure.InvalidInput("result.outcome")
        }
        validateResult(result)?.let { return it }
        return try {
            database.withTransaction {
                val task = database.backgroundTasks().get(taskId)
                    ?: return@withTransaction DataResult.Failure.NotFound("backgroundTask")
                if (task.state == TaskState.COMPLETED.name || task.state == TaskState.CANCELLED.name) {
                    return@withTransaction DataResult.Failure.InvalidState("backgroundTask")
                }
                database.taskResults().put(result.toEntity())
                val changed = database.backgroundTasks().transition(
                    taskId,
                    listOf(TaskState.QUEUED.name, TaskState.RUNNING.name),
                    TaskState.CANCELLED.name,
                    result.completedAt,
                    null,
                    result.attemptCount,
                )
                if (changed == 1) DataResult.Success(Unit) else DataResult.Failure.InvalidState("backgroundTask")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("finishCancelledBackgroundTask")
        }
    }

    override suspend fun cancel(taskId: String, updatedAt: Long): DataResult<Unit> {
        val task = when (val result = getTask(taskId)) {
            is DataResult.Success -> result.value ?: return DataResult.Failure.NotFound("backgroundTask")
            is DataResult.Failure -> return result
        }
        if (task.state == TaskState.CANCELLED) return DataResult.Success(Unit)
        if (task.state == TaskState.COMPLETED) return DataResult.Failure.InvalidState("backgroundTask")
        return transitionTask(
            taskId,
            listOf(TaskState.QUEUED, TaskState.RUNNING),
            TaskState.CANCELLED,
            updatedAt,
            null,
            task.runAttempt,
        )
    }

    override suspend fun pruneResultsCompletedBefore(cutoffMillis: Long): DataResult<Int> {
        if (cutoffMillis < 0) return DataResult.Failure.InvalidInput("cutoffMillis")
        return storageQuery("pruneBackgroundTaskResults") {
            database.taskResults().deleteCompletedBefore(cutoffMillis)
        }
    }

    override suspend fun deleteForTool(toolId: String): DataResult<Unit> = try {
        database.backgroundTasks().deleteForTool(toolId)
        DataResult.Success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("deleteBackgroundTasks")
    }

    private suspend fun transitionTask(
        taskId: String,
        expected: List<TaskState>,
        next: TaskState,
        updatedAt: Long,
        nextRunAt: Long?,
        runAttempt: Int,
    ): DataResult<Unit> {
        if (!taskId.isValidTaskId()) return DataResult.Failure.InvalidInput("taskId")
        if (updatedAt < 0) return DataResult.Failure.InvalidInput("updatedAt")
        return try {
            if (
                database.backgroundTasks().transition(
                    taskId,
                    expected.map(TaskState::name),
                    next.name,
                    updatedAt,
                    nextRunAt,
                    runAttempt,
                ) == 1
            ) DataResult.Success(Unit) else if (database.backgroundTasks().get(taskId) == null) {
                DataResult.Failure.NotFound("backgroundTask")
            } else {
                DataResult.Failure.InvalidState("backgroundTask")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("transitionBackgroundTask")
        }
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

private fun validateTask(task: BackgroundTask): DataResult.Failure? = when {
    !task.taskId.isValidTaskId() -> DataResult.Failure.InvalidInput("taskId")
    task.toolId.isBlank() -> DataResult.Failure.InvalidInput("toolId")
    task.versionCode < 1 -> DataResult.Failure.InvalidInput("versionCode")
    !task.key.isValidTaskKey() -> DataResult.Failure.InvalidInput("key")
    task.specJson.toByteArray(StandardCharsets.UTF_8).size > CoreDataLimits.MAX_TASK_SPEC_BYTES ->
        DataResult.Failure.QuotaExceeded(
            CoreDataLimits.MAX_TASK_SPEC_BYTES.toLong(),
            task.specJson.toByteArray(StandardCharsets.UTF_8).size.toLong(),
        )
    task.state != TaskState.QUEUED -> DataResult.Failure.InvalidInput("state")
    task.createdAt < 0 || task.updatedAt < task.createdAt -> DataResult.Failure.InvalidInput("updatedAt")
    task.runAttempt != 0 -> DataResult.Failure.InvalidInput("runAttempt")
    task.periodic && (task.intervalMinutes == null || task.intervalMinutes < 15) ->
        DataResult.Failure.InvalidInput("intervalMinutes")
    !task.periodic && task.intervalMinutes != null -> DataResult.Failure.InvalidInput("intervalMinutes")
    else -> null
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

private suspend fun <T> storageQuery(operation: String, block: suspend () -> T): DataResult<T> = try {
    DataResult.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    DataResult.Failure.StorageFailure(operation)
}
