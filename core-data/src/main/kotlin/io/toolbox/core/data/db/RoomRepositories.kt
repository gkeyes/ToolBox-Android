package io.toolbox.core.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import io.toolbox.core.data.AuditEvent
import io.toolbox.core.data.AuditRepository
import io.toolbox.core.data.CatalogCommitHook
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets

internal class RoomCatalogRepository(
    private val database: ToolBoxDatabase,
    private val commitHook: CatalogCommitHook = CatalogCommitHook.None,
) : CatalogRepository {
    override fun observeTools(): Flow<List<InstalledTool>> =
        database.tools().observeAll().map { rows -> rows.map(ToolEntity::toDomain) }

    override fun observeTool(toolId: String): Flow<InstalledTool?> =
        database.tools().observe(toolId).map { it?.toDomain() }

    override fun observeVersions(toolId: String): Flow<List<ToolVersion>> =
        database.versions().observeForTool(toolId).map { rows -> rows.map(ToolVersionEntity::toDomain) }

    override suspend fun registerVersion(
        metadata: ToolMetadata,
        version: ToolVersion,
    ): DataResult<Unit> {
        if (metadata.id != version.toolId) return DataResult.Failure.InvalidInput("toolId")
        return try {
            database.withTransaction {
                if (database.versions().get(version.toolId, version.versionCode) != null) {
                    return@withTransaction DataResult.Failure.DuplicateVersion(
                        version.toolId,
                        version.versionCode,
                    )
                }
                val existing = database.tools().get(metadata.id)
                if (existing == null) {
                    database.tools().insert(metadata.toEntity())
                } else {
                    database.tools().update(
                        metadata.toEntity(
                            activeVersionCode = existing.activeVersionCode,
                            lastOpenedAt = existing.lastOpenedAt,
                        ),
                    )
                }
                database.versions().insert(version.toEntity())
                DataResult.Success(Unit)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteConstraintException) {
            DataResult.Failure.DuplicateVersion(version.toolId, version.versionCode)
        } catch (_: Exception) {
            DataResult.Failure.StorageFailure("registerVersion")
        }
    }

    override suspend fun activateVersion(
        toolId: String,
        versionCode: Int,
    ): DataResult<Unit> = try {
        database.withTransaction {
            val version = database.versions().get(toolId, versionCode)
                ?: return@withTransaction DataResult.Failure.NotFound("toolVersion")
            if (database.tools().setActiveVersion(toolId, versionCode) != 1) {
                return@withTransaction DataResult.Failure.NotFound("tool")
            }
            database.versions().setLaunchState(toolId, versionCode, LaunchState.STABLE.name)
            commitHook.beforeCommit()
            DataResult.Success(Unit)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("activateVersion")
    }
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
