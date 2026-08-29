package io.toolbox.core.data.memory

import io.toolbox.core.data.AuditEvent
import io.toolbox.core.data.AuditRepository
import io.toolbox.core.data.CatalogCommitHook
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogLifecycleSnapshot
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CommittedInstall
import io.toolbox.core.data.CommitInstallOutcome
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.DeleteToolCatalogOutcome
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.GrantScope
import io.toolbox.core.data.GrantState
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.Publisher
import io.toolbox.core.data.PublisherRepository
import io.toolbox.core.data.RuntimeSession
import io.toolbox.core.data.RuntimeSessionRepository
import io.toolbox.core.data.RollbackOutcome
import io.toolbox.core.data.SignatureState
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvValue
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.isValidCategoryId
import io.toolbox.core.data.isValidSourceSessionId
import io.toolbox.core.data.validationError
import io.toolbox.core.data.withPersistedDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
        val state = InMemoryCoreState()
        return CoreDataRepositories(
            catalog = InMemoryCatalogRepository(state),
            lifecycle = InMemoryCatalogLifecycleRepository(state, commitHook),
            organization = InMemoryCatalogOrganizationRepository(state),
            grants = InMemoryPermissionGrantRepository(state),
            keyValues = InMemoryToolKvRepository(state),
            publishers = InMemoryPublisherRepository(),
            audit = InMemoryAuditRepository(),
            sessions = InMemoryRuntimeSessionRepository(state),
            settings = InMemoryHostSettingsRepository(initialSettings),
        )
    }
}

private class InMemoryCoreState {
    val mutex = Mutex()
    val tools = MutableStateFlow<Map<String, InstalledTool>>(emptyMap())
    val versions = MutableStateFlow<Map<Pair<String, Int>, ToolVersion>>(emptyMap())
    val grants = MutableStateFlow<Map<Pair<String, String>, PermissionGrant>>(emptyMap())
    val keyValues = MutableStateFlow<Map<Pair<String, String>, ToolKvValue>>(emptyMap())
    val sessions = MutableStateFlow<Map<String, RuntimeSession>>(emptyMap())

    fun contains(toolId: String): Boolean = toolId in tools.value
}

private class InMemoryCatalogRepository(
    private val state: InMemoryCoreState,
) : CatalogRepository {
    override fun observeCatalogProjection(): Flow<List<CatalogEntry>> =
        combine(state.tools, state.versions) { tools, versions ->
            tools.values.sortedWith(toolOrder).map { tool ->
                val activeVersion = tool.activeVersionCode?.let { versions[tool.metadata.id to it] }
                CatalogEntry(
                    toolId = tool.metadata.id,
                    name = tool.metadata.name,
                    signatureState = tool.metadata.signatureState,
                    publisherKeyId = tool.metadata.publisherKeyId,
                    securityProfile = tool.metadata.securityProfile,
                    installedAt = tool.metadata.installedAt,
                    lastOpenedAt = tool.lastOpenedAt,
                    pinnedOrder = tool.metadata.pinnedOrder,
                    categoryId = tool.metadata.categoryId,
                    activeVersionCode = activeVersion?.versionCode,
                    activeVersionName = activeVersion?.version,
                    bundleBytes = activeVersion?.bundleBytes,
                    launchState = activeVersion?.launchState,
                )
            }
        }

    override fun observeTools(): Flow<List<InstalledTool>> = state.tools.map { values ->
        values.values.sortedWith(toolOrder)
    }

    override fun observeTool(toolId: String): Flow<InstalledTool?> = state.tools.map { it[toolId] }

    override fun observeVersions(toolId: String): Flow<List<ToolVersion>> = state.versions.map { values ->
        values.values.filter { it.toolId == toolId }.sortedByDescending { it.versionCode }
    }

    private companion object {
        val toolOrder = compareBy<InstalledTool> { it.metadata.pinnedOrder == null }
            .thenBy { it.metadata.pinnedOrder }
            .thenByDescending { it.metadata.installedAt }
            .thenBy { it.metadata.id }
    }
}

private class InMemoryCatalogLifecycleRepository(
    private val state: InMemoryCoreState,
    private val commitHook: CatalogCommitHook,
) : CatalogLifecycleRepository {
    override suspend fun snapshot(toolId: String): DataResult<CatalogLifecycleSnapshot> = state.mutex.withLock {
        DataResult.Success(snapshotLocked(toolId))
    }

    override suspend fun findCommittedInstall(
        sourceSessionId: String,
    ): DataResult<CommittedInstall?> = state.mutex.withLock {
        if (!sourceSessionId.isValidSourceSessionId()) {
            return@withLock DataResult.Failure.InvalidInput("sourceSessionId")
        }
        val version = state.versions.value.values.singleOrNull {
            it.sourceSessionId == sourceSessionId
        }
        DataResult.Success(version?.let { CommittedInstall(it.toolId, it.versionCode) })
    }

    override suspend fun commitInstall(
        attempt: CatalogInstallAttempt,
    ): DataResult<CommitInstallOutcome> = state.mutex.withLock {
        validateAttempt(attempt)?.let { return@withLock it }
        val version = attempt.version
        val sourceMatch = state.versions.value.values.firstOrNull {
            it.sourceSessionId == version.sourceSessionId
        }
        if (sourceMatch != null) {
            return@withLock if (sourceMatch.matches(attempt)) {
                DataResult.Success(CommitInstallOutcome.AlreadyCommitted)
            } else {
                DataResult.Failure.DuplicateSourceSession(version.sourceSessionId)
            }
        }
        val key = version.toolId to version.versionCode
        if (key in state.versions.value) {
            return@withLock DataResult.Failure.DuplicateVersion(version.toolId, version.versionCode)
        }
        val existingVersions = state.versions.value.values.filter { it.toolId == version.toolId }
        val existing = state.tools.value[version.toolId]
        if ((existing == null) != existingVersions.isEmpty()) {
            return@withLock DataResult.Failure.LifecycleConflict(version.toolId)
        }
        val maximum = existingVersions.maxOfOrNull(ToolVersion::versionCode)
        if (maximum != null && version.versionCode <= maximum) {
            return@withLock DataResult.Failure.NonMonotonicVersion(
                version.toolId,
                version.versionCode,
                maximum,
            )
        }
        if (!signatureContinuityAllows(existingVersions, version)) {
            return@withLock DataResult.Failure.SignatureContinuityViolation(version.toolId)
        }
        val nextTool = if (existing == null) {
            InstalledTool(attempt.metadata, version.versionCode, null)
        } else {
            existing.copy(
                metadata = existing.metadata.copy(
                    name = version.identity.name,
                    signatureState = version.identity.signatureState,
                    publisherKeyId = version.identity.publisherKeyId,
                    securityProfile = version.identity.securityProfile,
                ),
                activeVersionCode = version.versionCode,
            )
        }
        val nextTools = state.tools.value + (version.toolId to nextTool)
        val nextVersions = state.versions.value + (key to version)
        val nextGrants = state.grants.value.filterKeys { it.first != version.toolId } +
            attempt.initialGrants.associateBy { it.toolId to it.permission }
        try {
            commitHook.beforeCommit()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock DataResult.Failure.StorageFailure("commitInstall")
        }
        state.tools.value = nextTools
        state.versions.value = nextVersions
        state.grants.value = nextGrants
        DataResult.Success(CommitInstallOutcome.Committed)
    }

    override suspend fun compensateInstall(
        attempt: CatalogInstallAttempt,
        snapshot: CatalogLifecycleSnapshot,
    ): DataResult<Unit> = state.mutex.withLock {
        if (snapshot.toolId != attempt.metadata.id) return@withLock DataResult.Failure.InvalidInput("snapshot.toolId")
        val version = attempt.version
        val current = state.tools.value[version.toolId]
            ?: return@withLock DataResult.Failure.LifecycleConflict(version.toolId)
        val installedAttempt = state.versions.value[version.toolId to version.versionCode]
            ?: return@withLock DataResult.Failure.LifecycleConflict(version.toolId)
        val remainingVersions = state.versions.value.values
            .filter { it.toolId == version.toolId && it.versionCode != version.versionCode }
            .sortedByDescending(ToolVersion::versionCode)
        if (
            current.activeVersionCode != version.versionCode ||
            installedAttempt.launchState != LaunchState.PENDING ||
            !installedAttempt.matches(attempt) ||
            remainingVersions != snapshot.versions
        ) return@withLock DataResult.Failure.LifecycleConflict(version.toolId)

        if (snapshot.tool == null) {
            deleteLocked(version.toolId)
        } else {
            state.tools.value = state.tools.value + (version.toolId to snapshot.tool)
            state.versions.value = state.versions.value - (version.toolId to version.versionCode)
            state.grants.value = state.grants.value.filterKeys { it.first != version.toolId } +
                snapshot.grants.associateBy { it.toolId to it.permission }
        }
        DataResult.Success(Unit)
    }

    override suspend fun markActiveVersionStable(toolId: String, versionCode: Int): DataResult<Unit> =
        state.mutex.withLock {
            val tool = state.tools.value[toolId]
                ?: return@withLock DataResult.Failure.NotFound("tool")
            val key = toolId to versionCode
            val version = state.versions.value[key]
                ?: return@withLock DataResult.Failure.NotFound("toolVersion")
            if (tool.activeVersionCode != versionCode || version.launchState != LaunchState.PENDING) {
                return@withLock DataResult.Failure.LifecycleConflict(toolId)
            }
            state.versions.value = state.versions.value + (key to version.copy(launchState = LaunchState.STABLE))
            DataResult.Success(Unit)
        }

    override suspend fun rollbackToPreviousStable(toolId: String): DataResult<RollbackOutcome> =
        state.mutex.withLock {
            val tool = state.tools.value[toolId]
                ?: return@withLock DataResult.Failure.NotFound("tool")
            val activeCode = tool.activeVersionCode
                ?: return@withLock DataResult.Failure.LifecycleConflict(toolId)
            val activeKey = toolId to activeCode
            val active = state.versions.value[activeKey]
                ?: return@withLock DataResult.Failure.LifecycleConflict(toolId)
            val target = state.versions.value.values
                .filter { it.toolId == toolId && it.versionCode < activeCode && it.launchState == LaunchState.STABLE }
                .maxByOrNull(ToolVersion::versionCode)
                ?: return@withLock DataResult.Failure.NotFound("stableToolVersion")
            if (active.launchState == LaunchState.PENDING) {
                state.versions.value = state.versions.value +
                    (activeKey to active.copy(launchState = LaunchState.FAILED))
            }
            state.tools.value = state.tools.value + (
                toolId to tool.copy(
                    metadata = tool.metadata.copy(
                        name = target.identity.name,
                        signatureState = target.identity.signatureState,
                        publisherKeyId = target.identity.publisherKeyId,
                        securityProfile = target.identity.securityProfile,
                    ),
                    activeVersionCode = target.versionCode,
                )
            )
            DataResult.Success(RollbackOutcome(target.versionCode))
        }

    override suspend fun deleteToolCatalog(toolId: String): DataResult<DeleteToolCatalogOutcome> =
        state.mutex.withLock {
            if (!state.contains(toolId)) {
                DataResult.Success(DeleteToolCatalogOutcome.AlreadyAbsent)
            } else {
                deleteLocked(toolId)
                DataResult.Success(DeleteToolCatalogOutcome.Deleted)
            }
        }

    private fun snapshotLocked(toolId: String) = CatalogLifecycleSnapshot(
        toolId = toolId,
        tool = state.tools.value[toolId],
        versions = state.versions.value.values.filter { it.toolId == toolId }
            .sortedByDescending(ToolVersion::versionCode),
        grants = state.grants.value.values.filter { it.toolId == toolId }.sortedBy(PermissionGrant::permission),
    )

    private fun deleteLocked(toolId: String) {
        state.tools.value = state.tools.value - toolId
        state.versions.value = state.versions.value.filterKeys { it.first != toolId }
        state.grants.value = state.grants.value.filterKeys { it.first != toolId }
        state.keyValues.value = state.keyValues.value.filterKeys { it.first != toolId }
        state.sessions.value = state.sessions.value.filterValues { it.toolId != toolId }
    }
}

private class InMemoryCatalogOrganizationRepository(
    private val state: InMemoryCoreState,
) : CatalogOrganizationRepository {
    override suspend fun setPinnedOrder(toolId: String, pinnedOrder: Int?): DataResult<Unit> =
        state.mutex.withLock {
            if (toolId.isBlank()) return@withLock DataResult.Failure.InvalidInput("toolId")
            if (pinnedOrder != null && pinnedOrder < 0) {
                return@withLock DataResult.Failure.InvalidInput("pinnedOrder")
            }
            updateLocked(toolId) { tool ->
                tool.copy(metadata = tool.metadata.copy(pinnedOrder = pinnedOrder))
            }
        }

    override suspend fun setCategory(toolId: String, categoryId: String?): DataResult<Unit> =
        state.mutex.withLock {
            if (toolId.isBlank()) return@withLock DataResult.Failure.InvalidInput("toolId")
            if (!categoryId.isValidCategoryId()) {
                return@withLock DataResult.Failure.InvalidInput("categoryId")
            }
            updateLocked(toolId) { tool ->
                tool.copy(metadata = tool.metadata.copy(categoryId = categoryId))
            }
        }

    override suspend fun recordOpened(toolId: String, timestamp: Long): DataResult<Unit> =
        state.mutex.withLock {
            if (toolId.isBlank()) return@withLock DataResult.Failure.InvalidInput("toolId")
            if (timestamp < 0) return@withLock DataResult.Failure.InvalidInput("timestamp")
            updateLocked(toolId) { tool -> tool.copy(lastOpenedAt = timestamp) }
        }

    private fun updateLocked(
        toolId: String,
        transform: (InstalledTool) -> InstalledTool,
    ): DataResult<Unit> {
        val current = state.tools.value[toolId]
            ?: return DataResult.Failure.NotFound("tool")
        state.tools.value = state.tools.value + (toolId to transform(current))
        return DataResult.Success(Unit)
    }
}

private class InMemoryToolKvRepository(
    private val state: InMemoryCoreState,
) : ToolKvRepository {
    override fun observe(toolId: String, key: String): Flow<ToolKvValue?> =
        state.keyValues.map { it[toolId to key] }

    override suspend fun put(
        toolId: String,
        key: String,
        valueJson: String,
        updatedAt: Long,
        quotaBytes: Long,
    ): DataResult<Unit> = state.mutex.withLock {
        if (key.isBlank()) return@withLock DataResult.Failure.InvalidInput("key")
        if (quotaBytes < 0) return@withLock DataResult.Failure.InvalidInput("quotaBytes")
        if (!state.contains(toolId)) return@withLock DataResult.Failure.NotFound("tool")
        val mapKey = toolId to key
        val newBytes = valueJson.toByteArray(StandardCharsets.UTF_8).size
        val attempted = state.keyValues.value
            .filterKeys { it.first == toolId }
            .values
            .sumOf { it.bytes.toLong() } - (state.keyValues.value[mapKey]?.bytes ?: 0) + newBytes
        if (attempted > quotaBytes) {
            return@withLock DataResult.Failure.QuotaExceeded(quotaBytes, attempted)
        }
        state.keyValues.value = state.keyValues.value + (mapKey to ToolKvValue(key, valueJson, updatedAt))
        DataResult.Success(Unit)
    }

    override suspend fun remove(toolId: String, key: String): DataResult<Unit> = state.mutex.withLock {
        if (!state.contains(toolId)) return@withLock DataResult.Failure.NotFound("tool")
        state.keyValues.value = state.keyValues.value - (toolId to key)
        DataResult.Success(Unit)
    }

    override suspend fun bytesUsed(toolId: String): Long = state.mutex.withLock {
        state.keyValues.value.filterKeys { it.first == toolId }.values.sumOf { it.bytes.toLong() }
    }
}

private class InMemoryPermissionGrantRepository(
    private val state: InMemoryCoreState,
) : PermissionGrantRepository {
    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> = state.grants.map { map ->
        map.values.filter { it.toolId == toolId }.sortedBy { it.permission }
    }

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> = state.mutex.withLock {
        if (!state.contains(grant.toolId)) return@withLock DataResult.Failure.NotFound("tool")
        state.grants.value = state.grants.value + ((grant.toolId to grant.permission) to grant)
        DataResult.Success(Unit)
    }

    override suspend fun revoke(toolId: String, permission: String): DataResult<Unit> = state.mutex.withLock {
        if (!state.contains(toolId)) return@withLock DataResult.Failure.NotFound("tool")
        state.grants.value = state.grants.value - (toolId to permission)
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
    private val state: InMemoryCoreState,
) : RuntimeSessionRepository {
    override fun observeOpenSessions(): Flow<List<RuntimeSession>> = state.sessions.map { map ->
        map.values.filter { it.endedAt == null }.sortedByDescending { it.startedAt }
    }

    override suspend fun start(session: RuntimeSession): DataResult<Unit> = state.mutex.withLock {
        if (!state.contains(session.toolId)) return@withLock DataResult.Failure.NotFound("tool")
        if (session.sessionId in state.sessions.value) {
            return@withLock DataResult.Failure.InvalidInput("sessionId")
        }
        state.sessions.value = state.sessions.value + (session.sessionId to session)
        DataResult.Success(Unit)
    }

    override suspend fun finish(
        sessionId: String,
        endedAt: Long,
        exitReason: String,
    ): DataResult<Unit> = state.mutex.withLock {
        val current = state.sessions.value[sessionId]
            ?: return@withLock DataResult.Failure.NotFound("runtimeSession")
        if (current.endedAt != null) {
            return@withLock DataResult.Failure.NotFound("runtimeSession")
        }
        state.sessions.value = state.sessions.value + (
            sessionId to current.copy(endedAt = endedAt, exitReason = exitReason)
        )
        DataResult.Success(Unit)
    }
}

private fun validateAttempt(attempt: CatalogInstallAttempt): DataResult.Failure? {
    val metadata = attempt.metadata
    val version = attempt.version
    if (metadata.id != version.toolId) return DataResult.Failure.InvalidInput("toolId")
    if (version.versionCode < 1) return DataResult.Failure.InvalidInput("versionCode")
    if (!version.sourceSessionId.isValidSourceSessionId()) {
        return DataResult.Failure.InvalidInput("sourceSessionId")
    }
    if (version.launchState != LaunchState.PENDING) return DataResult.Failure.InvalidInput("launchState")
    if (
        version.identity.name != metadata.name ||
        version.identity.signatureState != metadata.signatureState ||
        version.identity.publisherKeyId != metadata.publisherKeyId ||
        version.identity.securityProfile != metadata.securityProfile
    ) return DataResult.Failure.InvalidInput("versionIdentity")
    val signed = version.identity.signatureState.isSigned()
    if (signed != !version.identity.publisherKeyId.isNullOrBlank()) {
        return DataResult.Failure.InvalidInput("publisherKeyId")
    }
    if (attempt.initialGrants.any { it.toolId != metadata.id }) {
        return DataResult.Failure.InvalidInput("grant.toolId")
    }
    if (attempt.initialGrants.map(PermissionGrant::permission).toSet().size != attempt.initialGrants.size) {
        return DataResult.Failure.InvalidInput("grant.permission")
    }
    if (version.identity.signatureState == SignatureState.UNSIGNED) {
        attempt.initialGrants.firstOrNull {
            it.state == GrantState.GRANTED && it.scope == GrantScope.PERSISTENT
        }?.let { grant ->
            return DataResult.Failure.UnsignedPersistentGrant(metadata.id, grant.permission)
        }
    }
    return null
}

private fun signatureContinuityAllows(existing: List<ToolVersion>, newVersion: ToolVersion): Boolean {
    val signedKey = existing.asSequence()
        .map(ToolVersion::identity)
        .firstOrNull { it.signatureState.isSigned() }
        ?.publisherKeyId
        ?: return true
    return newVersion.identity.signatureState.isSigned() && newVersion.identity.publisherKeyId == signedKey
}

private fun SignatureState.isSigned(): Boolean =
    this == SignatureState.VERIFIED_TRUSTED || this == SignatureState.VERIFIED_UNKNOWN

private fun ToolVersion.matches(attempt: CatalogInstallAttempt): Boolean =
    copy(launchState = attempt.version.launchState) == attempt.version

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
