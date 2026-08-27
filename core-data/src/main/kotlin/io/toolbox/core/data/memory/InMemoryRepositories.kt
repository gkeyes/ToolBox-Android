package io.toolbox.core.data.memory

import io.toolbox.core.data.AuditEvent
import io.toolbox.core.data.AuditRepository
import io.toolbox.core.data.CatalogCommitHook
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.Publisher
import io.toolbox.core.data.PublisherRepository
import io.toolbox.core.data.RuntimeSession
import io.toolbox.core.data.RuntimeSessionRepository
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvValue
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.validationError
import io.toolbox.core.data.withPersistedDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets

object InMemoryCoreData {
    fun create(initialSettings: HostSettings = HostSettings()): CoreDataRepositories =
        create(initialSettings, CatalogCommitHook.None)

    internal fun create(
        initialSettings: HostSettings = HostSettings(),
        commitHook: CatalogCommitHook,
    ): CoreDataRepositories {
        val ownership = InMemoryToolOwnership()
        return CoreDataRepositories(
            catalog = InMemoryCatalogRepository(ownership, commitHook),
            grants = InMemoryPermissionGrantRepository(ownership),
            keyValues = InMemoryToolKvRepository(ownership),
            publishers = InMemoryPublisherRepository(),
            audit = InMemoryAuditRepository(),
            sessions = InMemoryRuntimeSessionRepository(ownership),
            settings = InMemoryHostSettingsRepository(initialSettings),
        )
    }
}

private class InMemoryToolOwnership {
    val toolIds = MutableStateFlow<Set<String>>(emptySet())
    fun contains(toolId: String): Boolean = toolId in toolIds.value
}

private class InMemoryCatalogRepository(
    private val ownership: InMemoryToolOwnership,
    private val commitHook: CatalogCommitHook,
) : CatalogRepository {
    private val mutex = Mutex()
    private val tools = MutableStateFlow<Map<String, InstalledTool>>(emptyMap())
    private val versions = MutableStateFlow<Map<Pair<String, Int>, ToolVersion>>(emptyMap())

    override fun observeTools(): Flow<List<InstalledTool>> = tools.map { values ->
        values.values.sortedWith(compareBy<InstalledTool> { it.metadata.pinnedOrder == null }
            .thenBy { it.metadata.pinnedOrder }
            .thenByDescending { it.metadata.installedAt }
            .thenBy { it.metadata.id })
    }

    override fun observeTool(toolId: String): Flow<InstalledTool?> = tools.map { it[toolId] }

    override fun observeVersions(toolId: String): Flow<List<ToolVersion>> = versions.map { values ->
        values.values.filter { it.toolId == toolId }.sortedByDescending { it.versionCode }
    }

    override suspend fun registerVersion(
        metadata: ToolMetadata,
        version: ToolVersion,
    ): DataResult<Unit> = mutex.withLock {
        if (metadata.id != version.toolId) return@withLock DataResult.Failure.InvalidInput("toolId")
        val key = version.toolId to version.versionCode
        if (key in versions.value) {
            return@withLock DataResult.Failure.DuplicateVersion(version.toolId, version.versionCode)
        }
        val existing = tools.value[metadata.id]
        tools.value = tools.value + (
            metadata.id to InstalledTool(metadata, existing?.activeVersionCode, existing?.lastOpenedAt)
        )
        versions.value = versions.value + (key to version)
        ownership.toolIds.value = ownership.toolIds.value + metadata.id
        DataResult.Success(Unit)
    }

    override suspend fun activateVersion(
        toolId: String,
        versionCode: Int,
    ): DataResult<Unit> = mutex.withLock {
        val key = toolId to versionCode
        val target = versions.value[key]
            ?: return@withLock DataResult.Failure.NotFound("toolVersion")
        val tool = tools.value[toolId]
            ?: return@withLock DataResult.Failure.NotFound("tool")
        val nextTools = tools.value + (toolId to tool.copy(activeVersionCode = versionCode))
        val nextVersions = versions.value + (key to target.copy(launchState = LaunchState.STABLE))
        try {
            commitHook.beforeCommit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock DataResult.Failure.StorageFailure("activateVersion")
        }
        tools.value = nextTools
        versions.value = nextVersions
        DataResult.Success(Unit)
    }
}

private class InMemoryToolKvRepository(
    private val ownership: InMemoryToolOwnership,
) : ToolKvRepository {
    private val mutex = Mutex()
    private val values = MutableStateFlow<Map<Pair<String, String>, ToolKvValue>>(emptyMap())

    override fun observe(toolId: String, key: String): Flow<ToolKvValue?> =
        values.map { it[toolId to key] }

    override suspend fun put(
        toolId: String,
        key: String,
        valueJson: String,
        updatedAt: Long,
        quotaBytes: Long,
    ): DataResult<Unit> = mutex.withLock {
        if (key.isBlank()) return@withLock DataResult.Failure.InvalidInput("key")
        if (quotaBytes < 0) return@withLock DataResult.Failure.InvalidInput("quotaBytes")
        if (!ownership.contains(toolId)) return@withLock DataResult.Failure.NotFound("tool")
        val mapKey = toolId to key
        val newBytes = valueJson.toByteArray(StandardCharsets.UTF_8).size
        val attempted = values.value
            .filterKeys { it.first == toolId }
            .values
            .sumOf { it.bytes.toLong() } - (values.value[mapKey]?.bytes ?: 0) + newBytes
        if (attempted > quotaBytes) {
            return@withLock DataResult.Failure.QuotaExceeded(quotaBytes, attempted)
        }
        values.value = values.value + (mapKey to ToolKvValue(key, valueJson, updatedAt))
        DataResult.Success(Unit)
    }

    override suspend fun remove(toolId: String, key: String): DataResult<Unit> = mutex.withLock {
        if (!ownership.contains(toolId)) return@withLock DataResult.Failure.NotFound("tool")
        values.value = values.value - (toolId to key)
        DataResult.Success(Unit)
    }

    override suspend fun bytesUsed(toolId: String): Long = mutex.withLock {
        values.value.filterKeys { it.first == toolId }.values.sumOf { it.bytes.toLong() }
    }
}

private class InMemoryPermissionGrantRepository(
    private val ownership: InMemoryToolOwnership,
) : PermissionGrantRepository {
    private val values = MutableStateFlow<Map<Pair<String, String>, PermissionGrant>>(emptyMap())
    private val mutex = Mutex()

    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> = values.map { map ->
        map.values.filter { it.toolId == toolId }.sortedBy { it.permission }
    }

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> = mutex.withLock {
        if (!ownership.contains(grant.toolId)) return@withLock DataResult.Failure.NotFound("tool")
        values.value = values.value + ((grant.toolId to grant.permission) to grant)
        DataResult.Success(Unit)
    }

    override suspend fun revoke(toolId: String, permission: String): DataResult<Unit> = mutex.withLock {
        if (!ownership.contains(toolId)) return@withLock DataResult.Failure.NotFound("tool")
        values.value = values.value - (toolId to permission)
        DataResult.Success(Unit)
    }
}

private class InMemoryPublisherRepository : PublisherRepository {
    private val values = MutableStateFlow<Map<String, Publisher>>(emptyMap())
    private val mutex = Mutex()

    override fun observePublishers(): Flow<List<Publisher>> =
        values.map { it.values.sortedWith(compareBy(Publisher::displayName, Publisher::keyId)) }

    override suspend fun put(publisher: Publisher): DataResult<Unit> = mutex.withLock {
        values.value = values.value + (publisher.keyId to publisher)
        DataResult.Success(Unit)
    }
}

private class InMemoryAuditRepository : AuditRepository {
    private val values = MutableStateFlow<List<AuditEvent>>(emptyList())
    private val mutex = Mutex()
    private var nextId = 1L

    override fun observeRecent(limit: Int): Flow<List<AuditEvent>> = values.map { events ->
        events.sortedWith(compareByDescending<AuditEvent> { it.timestamp }.thenByDescending { it.id })
            .take(limit.coerceAtLeast(0))
    }

    override suspend fun append(event: AuditEvent): DataResult<Long> = mutex.withLock {
        val id = nextId++
        values.value = values.value + event.copy(id = id)
        DataResult.Success(id)
    }

    override suspend fun deleteBefore(timestamp: Long): DataResult<Int> = mutex.withLock {
        val retained = values.value.filter { it.timestamp >= timestamp }
        val removed = values.value.size - retained.size
        values.value = retained
        DataResult.Success(removed)
    }
}

private class InMemoryRuntimeSessionRepository(
    private val ownership: InMemoryToolOwnership,
) : RuntimeSessionRepository {
    private val values = MutableStateFlow<Map<String, RuntimeSession>>(emptyMap())
    private val mutex = Mutex()

    override fun observeOpenSessions(): Flow<List<RuntimeSession>> = values.map { map ->
        map.values.filter { it.endedAt == null }.sortedByDescending { it.startedAt }
    }

    override suspend fun start(session: RuntimeSession): DataResult<Unit> = mutex.withLock {
        if (!ownership.contains(session.toolId)) return@withLock DataResult.Failure.NotFound("tool")
        if (session.sessionId in values.value) {
            return@withLock DataResult.Failure.InvalidInput("sessionId")
        }
        values.value = values.value + (session.sessionId to session)
        DataResult.Success(Unit)
    }

    override suspend fun finish(
        sessionId: String,
        endedAt: Long,
        exitReason: String,
    ): DataResult<Unit> = mutex.withLock {
        val current = values.value[sessionId]
            ?: return@withLock DataResult.Failure.NotFound("runtimeSession")
        if (current.endedAt != null) {
            return@withLock DataResult.Failure.NotFound("runtimeSession")
        }
        values.value = values.value + (
            sessionId to current.copy(endedAt = endedAt, exitReason = exitReason)
        )
        DataResult.Success(Unit)
    }
}

class InMemoryHostSettingsRepository(initial: HostSettings = HostSettings()) : HostSettingsRepository {
    private val state = MutableStateFlow(initial.withPersistedDefaults())
    private val mutex = Mutex()
    override val settings: Flow<HostSettings> = state

    override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> = mutex.withLock {
        val next = transform(state.value)
        next.validationError()?.let { field ->
            return@withLock DataResult.Failure.InvalidInput(field)
        }
        state.value = next
        DataResult.Success(Unit)
    }
}
