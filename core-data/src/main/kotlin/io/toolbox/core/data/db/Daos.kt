package io.toolbox.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val INSTALLED_TOOL_SELECT = """
    SELECT tools.id AS id,
           tools.name AS name,
           tools.securityProfile AS securityProfile,
           tools.installedAt AS installedAt,
           tools.lastOpenedAt AS lastOpenedAt,
           tools.pinnedOrder AS pinnedOrder,
           tools.categoryId AS categoryId,
           tool_versions.versionCode AS versionCode,
           tool_versions.version AS version,
           tool_versions.bundleLocator AS bundleLocator,
           tool_versions.bundleBytes AS bundleBytes,
           tool_versions.integrityHash AS integrityHash,
           tool_versions.installedAt AS versionInstalledAt
    FROM tools
    INNER JOIN tool_versions ON tool_versions.toolId = tools.id
"""

@Dao
internal interface ToolDao {
    @Query("$INSTALLED_TOOL_SELECT ORDER BY tools.pinnedOrder IS NULL, tools.pinnedOrder, tools.installedAt DESC, tools.id")
    fun observeAll(): Flow<List<InstalledToolProjection>>

    @Query("$INSTALLED_TOOL_SELECT WHERE tools.id = :toolId")
    fun observe(toolId: String): Flow<InstalledToolProjection?>

    @Query("SELECT * FROM tools WHERE id = :toolId")
    suspend fun get(toolId: String): ToolEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ToolEntity)

    @Update
    suspend fun update(entity: ToolEntity)

    @Query("UPDATE tools SET pinnedOrder = :pinnedOrder WHERE id = :toolId")
    suspend fun setPinnedOrder(toolId: String, pinnedOrder: Int?): Int

    @Query("UPDATE tools SET categoryId = :categoryId WHERE id = :toolId")
    suspend fun setCategory(toolId: String, categoryId: String?): Int

    @Query("UPDATE tools SET lastOpenedAt = :timestamp WHERE id = :toolId")
    suspend fun recordOpened(toolId: String, timestamp: Long): Int

    @Query("DELETE FROM tools WHERE id = :toolId")
    suspend fun delete(toolId: String): Int
}

@Dao
internal interface ToolVersionDao {
    @Query("SELECT * FROM tool_versions WHERE toolId = :toolId")
    suspend fun get(toolId: String): ToolVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ToolVersionEntity)
}

@Dao
internal interface PermissionGrantDao {
    @Query("SELECT * FROM permission_grants WHERE toolId = :toolId ORDER BY capability")
    fun observeForTool(toolId: String): Flow<List<PermissionGrantEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PermissionGrantEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<PermissionGrantEntity>)

    @Query("DELETE FROM permission_grants WHERE toolId = :toolId")
    suspend fun deleteAll(toolId: String): Int

    @Query("DELETE FROM permission_grants WHERE toolId = :toolId AND capability = :capability")
    suspend fun delete(toolId: String, capability: String): Int
}

@Dao
internal interface ToolKvDao {
    @Query("SELECT * FROM tool_kv WHERE toolId = :toolId AND `key` = :key")
    fun observe(toolId: String, key: String): Flow<ToolKvEntity?>

    @Query("SELECT * FROM tool_kv WHERE toolId = :toolId AND `key` = :key")
    suspend fun get(toolId: String, key: String): ToolKvEntity?

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM tool_kv WHERE toolId = :toolId")
    suspend fun bytesUsed(toolId: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ToolKvEntity)

    @Query("DELETE FROM tool_kv WHERE toolId = :toolId AND `key` = :key")
    suspend fun delete(toolId: String, key: String): Int
}

@Dao
internal interface InstallTransactionDao {
    @Query("SELECT * FROM install_transactions WHERE state IN ('PREPARING', 'COMMITTING') ORDER BY startedAt")
    fun observeIncomplete(): Flow<List<InstallTransactionEntity>>

    @Query("SELECT * FROM install_transactions WHERE id = :transactionId")
    suspend fun get(transactionId: String): InstallTransactionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: InstallTransactionEntity)

    @Query("UPDATE install_transactions SET state = :state, updatedAt = :updatedAt, failureCode = :failureCode WHERE id = :transactionId AND state IN (:expectedStates)")
    suspend fun transition(
        transactionId: String,
        expectedStates: List<String>,
        state: String,
        updatedAt: Long,
        failureCode: String?,
    ): Int

    @Query("DELETE FROM install_transactions WHERE toolId = :toolId")
    suspend fun deleteForTool(toolId: String): Int
}

@Dao
internal interface BackgroundTaskDao {
    @Query("SELECT * FROM background_tasks WHERE toolId = :toolId ORDER BY createdAt DESC, taskId")
    fun observeForTool(toolId: String): Flow<List<BackgroundTaskEntity>>

    @Query("SELECT * FROM background_tasks WHERE taskId = :taskId")
    suspend fun get(taskId: String): BackgroundTaskEntity?

    @Query("SELECT * FROM background_tasks WHERE toolId = :toolId AND `key` = :key AND state IN ('QUEUED', 'RUNNING') LIMIT 1")
    suspend fun getActiveByKey(toolId: String, key: String): BackgroundTaskEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BackgroundTaskEntity)

    @Query("UPDATE background_tasks SET state = :nextState, updatedAt = :updatedAt, nextRunAt = :nextRunAt, runAttempt = :runAttempt WHERE taskId = :taskId AND state IN (:expectedStates)")
    suspend fun transition(
        taskId: String,
        expectedStates: List<String>,
        nextState: String,
        updatedAt: Long,
        nextRunAt: Long?,
        runAttempt: Int,
    ): Int

    @Query("UPDATE background_tasks SET state = 'QUEUED', updatedAt = :updatedAt, nextRunAt = :updatedAt WHERE taskId = :taskId AND state = 'RUNNING' AND updatedAt <= :updatedAt")
    suspend fun requeueInterruptedRun(taskId: String, updatedAt: Long): Int

    @Query("DELETE FROM background_tasks WHERE toolId = :toolId")
    suspend fun deleteForTool(toolId: String): Int
}

@Dao
internal interface TaskResultDao {
    @Query("SELECT * FROM task_results WHERE taskId = :taskId")
    fun observe(taskId: String): Flow<TaskResultEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: TaskResultEntity)

    @Query("DELETE FROM task_results WHERE completedAt < :cutoffMillis")
    suspend fun deleteCompletedBefore(cutoffMillis: Long): Int
}
