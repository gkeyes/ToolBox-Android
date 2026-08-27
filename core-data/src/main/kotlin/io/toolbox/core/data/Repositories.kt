package io.toolbox.core.data

import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun observeTools(): Flow<List<InstalledTool>>
    fun observeTool(toolId: String): Flow<InstalledTool?>
    fun observeVersions(toolId: String): Flow<List<ToolVersion>>
    suspend fun registerVersion(metadata: ToolMetadata, version: ToolVersion): DataResult<Unit>
    suspend fun activateVersion(toolId: String, versionCode: Int): DataResult<Unit>
}

interface PermissionGrantRepository {
    fun observeGrants(toolId: String): Flow<List<PermissionGrant>>
    suspend fun put(grant: PermissionGrant): DataResult<Unit>
    suspend fun revoke(toolId: String, permission: String): DataResult<Unit>
}

interface ToolKvRepository {
    fun observe(toolId: String, key: String): Flow<ToolKvValue?>
    suspend fun put(
        toolId: String,
        key: String,
        valueJson: String,
        updatedAt: Long,
        quotaBytes: Long,
    ): DataResult<Unit>
    suspend fun remove(toolId: String, key: String): DataResult<Unit>
    suspend fun bytesUsed(toolId: String): Long
}

interface PublisherRepository {
    fun observePublishers(): Flow<List<Publisher>>
    suspend fun put(publisher: Publisher): DataResult<Unit>
}

interface AuditRepository {
    fun observeRecent(limit: Int): Flow<List<AuditEvent>>
    suspend fun append(event: AuditEvent): DataResult<Long>
    suspend fun deleteBefore(timestamp: Long): DataResult<Int>
}

interface RuntimeSessionRepository {
    fun observeOpenSessions(): Flow<List<RuntimeSession>>
    suspend fun start(session: RuntimeSession): DataResult<Unit>
    suspend fun finish(sessionId: String, endedAt: Long, exitReason: String): DataResult<Unit>
}

interface HostSettingsRepository {
    val settings: Flow<HostSettings>
    suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit>
}

data class CoreDataRepositories(
    val catalog: CatalogRepository,
    val grants: PermissionGrantRepository,
    val keyValues: ToolKvRepository,
    val publishers: PublisherRepository,
    val audit: AuditRepository,
    val sessions: RuntimeSessionRepository,
    val settings: HostSettingsRepository,
)
