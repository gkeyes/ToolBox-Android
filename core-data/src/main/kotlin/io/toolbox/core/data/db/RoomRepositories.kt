package io.toolbox.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import io.toolbox.core.data.AuditEvent
import io.toolbox.core.data.AuditRepository
import io.toolbox.core.data.CatalogCommitHook
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogLifecycleSnapshot
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CommittedInstall
import io.toolbox.core.data.CommitInstallOutcome
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.DeleteToolCatalogOutcome
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets

internal class RoomCatalogRepository(
    private val database: ToolBoxDatabase,
) : CatalogRepository {
    override fun observeCatalogProjection() =
        database.tools().observeCatalogProjection().map { rows -> rows.map(CatalogProjection::toDomain) }

    override fun observeTools(): Flow<List<InstalledTool>> =
        database.tools().observeAll().map { rows -> rows.map(ToolEntity::toDomain) }

    override fun observeTool(toolId: String): Flow<InstalledTool?> =
        database.tools().observe(toolId).map { it?.toDomain() }

    override fun observeVersions(toolId: String): Flow<List<ToolVersion>> =
        database.versions().observeForTool(toolId).map { rows -> rows.map(ToolVersionEntity::toDomain) }
}

internal class RoomCatalogLifecycleRepository(
    private val database: ToolBoxDatabase,
    private val commitHook: CatalogCommitHook = CatalogCommitHook.None,
) : CatalogLifecycleRepository {
    override suspend fun snapshot(toolId: String): DataResult<CatalogLifecycleSnapshot> = try {
        database.withTransaction {
            DataResult.Success(
                CatalogLifecycleSnapshot(
                    toolId = toolId,
                    tool = database.tools().get(toolId)?.toDomain(),
                    versions = database.versions().getAll(toolId).map(ToolVersionEntity::toDomain),
                    grants = database.grants().getAll(toolId).map(PermissionGrantEntity::toDomain),
                ),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("snapshotCatalog")
    }

    override suspend fun findCommittedInstall(
        sourceSessionId: String,
    ): DataResult<CommittedInstall?> {
        if (!sourceSessionId.isValidSourceSessionId()) {
            return DataResult.Failure.InvalidInput("sourceSessionId")
        }
        return try {
            val version = database.versions().getBySourceSessionId(sourceSessionId)
            DataResult.Success(version?.let { CommittedInstall(it.toolId, it.versionCode) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("findCommittedInstall")
        }
    }

    override suspend fun commitInstall(
        attempt: CatalogInstallAttempt,
    ): DataResult<CommitInstallOutcome> {
        validateAttempt(attempt)?.let { return it }
        return try {
            database.withTransaction {
                val version = attempt.version
                val sourceMatch = database.versions().getBySourceSessionId(version.sourceSessionId)
                if (sourceMatch != null) {
                    return@withTransaction if (sourceMatch.matches(attempt)) {
                        DataResult.Success(CommitInstallOutcome.AlreadyCommitted)
                    } else {
                        DataResult.Failure.DuplicateSourceSession(version.sourceSessionId)
                    }
                }
                if (database.versions().get(version.toolId, version.versionCode) != null) {
                    return@withTransaction DataResult.Failure.DuplicateVersion(
                        version.toolId,
                        version.versionCode,
                    )
                }
                val existing = database.tools().get(version.toolId)
                val existingVersions = database.versions().getAll(version.toolId)
                if ((existing == null) != existingVersions.isEmpty()) {
                    return@withTransaction DataResult.Failure.LifecycleConflict(version.toolId)
                }
                val maximum = existingVersions.maxOfOrNull(ToolVersionEntity::versionCode)
                if (maximum != null && version.versionCode <= maximum) {
                    return@withTransaction DataResult.Failure.NonMonotonicVersion(
                        version.toolId,
                        version.versionCode,
                        maximum,
                    )
                }
                if (!signatureContinuityAllows(existingVersions, version)) {
                    return@withTransaction DataResult.Failure.SignatureContinuityViolation(version.toolId)
                }

                if (existing == null) {
                    database.tools().insert(attempt.metadata.toEntity(activeVersionCode = version.versionCode))
                } else {
                    database.tools().update(existing.withIdentity(version.identity, version.versionCode))
                }
                database.versions().insert(version.toEntity())
                database.grants().deleteAll(version.toolId)
                database.grants().insertAll(attempt.initialGrants.map(PermissionGrant::toEntity))
                commitHook.beforeCommit()
                DataResult.Success(CommitInstallOutcome.Committed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteConstraintException) {
            DataResult.Failure.StorageFailure("commitInstall")
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("commitInstall")
        }
    }

    override suspend fun compensateInstall(
        attempt: CatalogInstallAttempt,
        snapshot: CatalogLifecycleSnapshot,
    ): DataResult<Unit> {
        if (snapshot.toolId != attempt.metadata.id) return DataResult.Failure.InvalidInput("snapshot.toolId")
        return try {
            database.withTransaction {
                val version = attempt.version
                val currentTool = database.tools().get(version.toolId)
                    ?: return@withTransaction DataResult.Failure.LifecycleConflict(version.toolId)
                val installedAttempt = database.versions().get(version.toolId, version.versionCode)
                    ?: return@withTransaction DataResult.Failure.LifecycleConflict(version.toolId)
                if (
                    currentTool.activeVersionCode != version.versionCode ||
                    installedAttempt.launchState != LaunchState.PENDING.name ||
                    !installedAttempt.matches(attempt) ||
                    database.versions().getAll(version.toolId)
                        .filterNot { it.versionCode == version.versionCode }
                        .map(ToolVersionEntity::toDomain) != snapshot.versions
                ) {
                    return@withTransaction DataResult.Failure.LifecycleConflict(version.toolId)
                }

                if (snapshot.tool == null) {
                    database.tools().delete(version.toolId)
                } else {
                    database.tools().update(
                        snapshot.tool.metadata.toEntity(
                            activeVersionCode = snapshot.tool.activeVersionCode,
                            lastOpenedAt = snapshot.tool.lastOpenedAt,
                        ),
                    )
                    database.versions().delete(version.toolId, version.versionCode)
                    database.grants().deleteAll(version.toolId)
                    database.grants().insertAll(snapshot.grants.map(PermissionGrant::toEntity))
                }
                DataResult.Success(Unit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("compensateInstall")
        }
    }

    override suspend fun markActiveVersionStable(toolId: String, versionCode: Int): DataResult<Unit> = try {
        database.withTransaction {
            val tool = database.tools().get(toolId)
                ?: return@withTransaction DataResult.Failure.NotFound("tool")
            val version = database.versions().get(toolId, versionCode)
                ?: return@withTransaction DataResult.Failure.NotFound("toolVersion")
            if (tool.activeVersionCode != versionCode || version.launchState != LaunchState.PENDING.name) {
                return@withTransaction DataResult.Failure.LifecycleConflict(toolId)
            }
            database.versions().setLaunchState(toolId, versionCode, LaunchState.STABLE.name)
            DataResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("markActiveVersionStable")
    }

    override suspend fun rollbackToPreviousStable(toolId: String): DataResult<RollbackOutcome> = try {
        database.withTransaction {
            val tool = database.tools().get(toolId)
                ?: return@withTransaction DataResult.Failure.NotFound("tool")
            val activeCode = tool.activeVersionCode
                ?: return@withTransaction DataResult.Failure.LifecycleConflict(toolId)
            val active = database.versions().get(toolId, activeCode)
                ?: return@withTransaction DataResult.Failure.LifecycleConflict(toolId)
            val target = database.versions().greatestLowerStable(toolId, activeCode)
                ?: return@withTransaction DataResult.Failure.NotFound("stableToolVersion")
            if (active.launchState == LaunchState.PENDING.name) {
                database.versions().setLaunchState(toolId, activeCode, LaunchState.FAILED.name)
            }
            database.tools().update(tool.withIdentity(target.toDomain().identity, target.versionCode))
            DataResult.Success(RollbackOutcome(target.versionCode))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("rollbackCatalog")
    }

    override suspend fun deleteToolCatalog(toolId: String): DataResult<DeleteToolCatalogOutcome> = try {
        database.withTransaction {
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
        return update("setPinnedOrder", toolId) { database.tools().setPinnedOrder(toolId, pinnedOrder) }
    }

    override suspend fun setCategory(toolId: String, categoryId: String?): DataResult<Unit> {
        if (toolId.isBlank()) return DataResult.Failure.InvalidInput("toolId")
        if (!categoryId.isValidCategoryId()) return DataResult.Failure.InvalidInput("categoryId")
        return update("setCategory", toolId) { database.tools().setCategory(toolId, categoryId) }
    }

    override suspend fun recordOpened(toolId: String, timestamp: Long): DataResult<Unit> {
        if (toolId.isBlank()) return DataResult.Failure.InvalidInput("toolId")
        if (timestamp < 0) return DataResult.Failure.InvalidInput("timestamp")
        return update("recordOpened", toolId) { database.tools().recordOpened(toolId, timestamp) }
    }

    private suspend fun update(
        operation: String,
        toolId: String,
        block: suspend () -> Int,
    ): DataResult<Unit> = try {
        if (block() == 1) DataResult.Success(Unit) else DataResult.Failure.NotFound("tool")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure(operation)
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

private fun signatureContinuityAllows(
    existing: List<ToolVersionEntity>,
    newVersion: ToolVersion,
): Boolean {
    val signedKey = existing.asSequence()
        .map(ToolVersionEntity::toDomain)
        .map(ToolVersion::identity)
        .firstOrNull { it.signatureState.isSigned() }
        ?.publisherKeyId
        ?: return true
    return newVersion.identity.signatureState.isSigned() && newVersion.identity.publisherKeyId == signedKey
}

private fun SignatureState.isSigned(): Boolean =
    this == SignatureState.VERIFIED_TRUSTED || this == SignatureState.VERIFIED_UNKNOWN

private fun ToolVersionEntity.matches(attempt: CatalogInstallAttempt): Boolean {
    val expected = attempt.version.toEntity()
    return copy(launchState = expected.launchState) == expected
}

internal class RoomPermissionGrantRepository(private val database: ToolBoxDatabase) :
    PermissionGrantRepository {
    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> =
        database.grants().observeForTool(toolId).map { rows -> rows.map(PermissionGrantEntity::toDomain) }

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> = try {
        database.withTransaction {
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

    override suspend fun revoke(toolId: String, permission: String): DataResult<Unit> = try {
        database.withTransaction {
            if (database.tools().get(toolId) == null) {
                return@withTransaction DataResult.Failure.NotFound("tool")
            }
            database.grants().delete(toolId, permission)
            DataResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("revokeGrant")
    }
}

internal class RoomToolKvRepository(private val database: ToolBoxDatabase) : ToolKvRepository {
    override fun observe(toolId: String, key: String): Flow<ToolKvValue?> =
        database.keyValues().observe(toolId, key).map { it?.toDomain() }

    override suspend fun put(
        toolId: String,
        key: String,
        valueJson: String,
        updatedAt: Long,
        quotaBytes: Long,
    ): DataResult<Unit> {
        if (key.isBlank()) return DataResult.Failure.InvalidInput("key")
        if (quotaBytes < 0) return DataResult.Failure.InvalidInput("quotaBytes")
        val bytes = valueJson.toByteArray(StandardCharsets.UTF_8).size
        return try {
            database.withTransaction {
                if (database.tools().get(toolId) == null) {
                    return@withTransaction DataResult.Failure.NotFound("tool")
                }
                val previousBytes = database.keyValues().get(toolId, key)?.bytes ?: 0
                val attempted = database.keyValues().bytesUsed(toolId) - previousBytes + bytes
                if (attempted > quotaBytes) {
                    return@withTransaction DataResult.Failure.QuotaExceeded(quotaBytes, attempted)
                }
                database.keyValues().put(ToolKvEntity(toolId, key, valueJson, updatedAt, bytes))
                DataResult.Success(Unit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("putKeyValue")
        }
    }

    override suspend fun remove(toolId: String, key: String): DataResult<Unit> = try {
        database.withTransaction {
            if (database.tools().get(toolId) == null) {
                return@withTransaction DataResult.Failure.NotFound("tool")
            }
            database.keyValues().delete(toolId, key)
            DataResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("removeKeyValue")
    }

    override suspend fun bytesUsed(toolId: String): Long = database.keyValues().bytesUsed(toolId)
}

internal class RoomPublisherRepository(private val database: ToolBoxDatabase) : PublisherRepository {
    override fun observePublishers(): Flow<List<Publisher>> =
        database.publishers().observeAll().map { rows -> rows.map(PublisherEntity::toDomain) }

    override suspend fun put(publisher: Publisher): DataResult<Unit> = storageResult("putPublisher") {
        database.publishers().put(publisher.toEntity())
    }
}

internal class RoomAuditRepository(private val database: ToolBoxDatabase) : AuditRepository {
    override fun observeRecent(limit: Int): Flow<List<AuditEvent>> =
        database.audit().observeRecent(limit.coerceAtLeast(0)).map { rows -> rows.map(AuditLogEntity::toDomain) }

    override suspend fun append(event: AuditEvent): DataResult<Long> = try {
        DataResult.Success(database.audit().insert(event.toEntity()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("appendAudit")
    }

    override suspend fun deleteBefore(timestamp: Long): DataResult<Int> = try {
        DataResult.Success(database.audit().deleteBefore(timestamp))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("deleteAudit")
    }
}

internal class RoomRuntimeSessionRepository(private val database: ToolBoxDatabase) :
    RuntimeSessionRepository {
    override fun observeOpenSessions(): Flow<List<RuntimeSession>> =
        database.sessions().observeOpen().map { rows -> rows.map(RuntimeSessionEntity::toDomain) }

    override suspend fun start(session: RuntimeSession): DataResult<Unit> = try {
        database.withTransaction {
            if (database.tools().get(session.toolId) == null) {
                return@withTransaction DataResult.Failure.NotFound("tool")
            }
            if (database.sessions().get(session.sessionId) != null) {
                return@withTransaction DataResult.Failure.InvalidInput("sessionId")
            }
            database.sessions().insert(session.toEntity())
            DataResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLiteConstraintException) {
        DataResult.Failure.InvalidInput("sessionId")
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("startSession")
    }

    override suspend fun finish(
        sessionId: String,
        endedAt: Long,
        exitReason: String,
    ): DataResult<Unit> = try {
        if (database.sessions().finish(sessionId, endedAt, exitReason) == 1) {
            DataResult.Success(Unit)
        } else {
            DataResult.Failure.NotFound("runtimeSession")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("finishSession")
    }
}
