package io.toolbox.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ToolDao {
    @Query(
        """
        SELECT
            tools.id AS toolId,
            tools.name AS name,
            tools.signatureState AS signatureState,
            tools.publisherKeyId AS publisherKeyId,
            tools.securityProfile AS securityProfile,
            tools.installedAt AS installedAt,
            tools.lastOpenedAt AS lastOpenedAt,
            tools.pinnedOrder AS pinnedOrder,
            tools.categoryId AS categoryId,
            tool_versions.versionCode AS activeVersionCode,
            tool_versions.version AS activeVersionName,
            tool_versions.bundleBytes AS bundleBytes,
            tool_versions.launchState AS launchState
        FROM tools
        LEFT JOIN tool_versions
            ON tool_versions.toolId = tools.id
            AND tool_versions.versionCode = tools.activeVersionCode
        ORDER BY tools.pinnedOrder IS NULL, tools.pinnedOrder, tools.installedAt DESC, tools.id
        """,
    )
    fun observeCatalogProjection(): Flow<List<CatalogProjection>>

    @Query("SELECT * FROM tools ORDER BY pinnedOrder IS NULL, pinnedOrder, installedAt DESC, id")
    fun observeAll(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tools WHERE id = :toolId")
    fun observe(toolId: String): Flow<ToolEntity?>

    @Query("SELECT * FROM tools WHERE id = :toolId")
    suspend fun get(toolId: String): ToolEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ToolEntity): Long

    @Update
    suspend fun update(entity: ToolEntity)

    @Query("UPDATE tools SET activeVersionCode = :versionCode WHERE id = :toolId")
    suspend fun setActiveVersion(toolId: String, versionCode: Int?): Int

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
    @Query("SELECT * FROM tool_versions WHERE toolId = :toolId ORDER BY versionCode DESC")
    fun observeForTool(toolId: String): Flow<List<ToolVersionEntity>>

    @Query("SELECT * FROM tool_versions WHERE toolId = :toolId AND versionCode = :versionCode")
    suspend fun get(toolId: String, versionCode: Int): ToolVersionEntity?

    @Query("SELECT * FROM tool_versions WHERE sourceSessionId = :sourceSessionId")
    suspend fun getBySourceSessionId(sourceSessionId: String): ToolVersionEntity?

    @Query("SELECT * FROM tool_versions WHERE toolId = :toolId ORDER BY versionCode DESC")
    suspend fun getAll(toolId: String): List<ToolVersionEntity>

    @Query("SELECT MAX(versionCode) FROM tool_versions WHERE toolId = :toolId")
    suspend fun maxVersionCode(toolId: String): Int?

    @Query("SELECT * FROM tool_versions WHERE toolId = :toolId AND versionCode < :versionCode AND launchState = 'STABLE' ORDER BY versionCode DESC LIMIT 1")
    suspend fun greatestLowerStable(toolId: String, versionCode: Int): ToolVersionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ToolVersionEntity)

    @Query("UPDATE tool_versions SET launchState = :launchState WHERE toolId = :toolId AND versionCode = :versionCode")
    suspend fun setLaunchState(toolId: String, versionCode: Int, launchState: String): Int

    @Query("DELETE FROM tool_versions WHERE toolId = :toolId AND versionCode = :versionCode")
    suspend fun delete(toolId: String, versionCode: Int): Int
}

@Dao
internal interface PermissionGrantDao {
    @Query("SELECT * FROM permission_grants WHERE toolId = :toolId ORDER BY permission")
    fun observeForTool(toolId: String): Flow<List<PermissionGrantEntity>>

    @Query("SELECT * FROM permission_grants WHERE toolId = :toolId ORDER BY permission")
    suspend fun getAll(toolId: String): List<PermissionGrantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PermissionGrantEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<PermissionGrantEntity>)

    @Query("DELETE FROM permission_grants WHERE toolId = :toolId")
    suspend fun deleteAll(toolId: String): Int

    @Query("DELETE FROM permission_grants WHERE toolId = :toolId AND permission = :permission")
    suspend fun delete(toolId: String, permission: String): Int
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
internal interface PublisherDao {
    @Query("SELECT * FROM publishers ORDER BY displayName, keyId")
    fun observeAll(): Flow<List<PublisherEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PublisherEntity)
}

@Dao
internal interface AuditDao {
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>

    @Insert
    suspend fun insert(entity: AuditLogEntity): Long

    @Query("DELETE FROM audit_logs WHERE timestamp < :timestamp")
    suspend fun deleteBefore(timestamp: Long): Int
}

@Dao
internal interface RuntimeSessionDao {
    @Query("SELECT * FROM runtime_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC")
    fun observeOpen(): Flow<List<RuntimeSessionEntity>>

    @Query("SELECT * FROM runtime_sessions WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): RuntimeSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuntimeSessionEntity)

    @Query("UPDATE runtime_sessions SET endedAt = :endedAt, exitReason = :exitReason WHERE sessionId = :sessionId AND endedAt IS NULL")
    suspend fun finish(sessionId: String, endedAt: Long, exitReason: String): Int
}
