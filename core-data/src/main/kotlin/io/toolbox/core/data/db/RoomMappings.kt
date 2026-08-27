package io.toolbox.core.data.db

import io.toolbox.core.data.AuditEvent
import io.toolbox.core.data.AuditRisk
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.GrantScope
import io.toolbox.core.data.GrantSource
import io.toolbox.core.data.GrantState
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.Publisher
import io.toolbox.core.data.PublisherTrustState
import io.toolbox.core.data.RuntimeSession
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.SignatureState
import io.toolbox.core.data.ToolKvValue
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import kotlinx.coroutines.CancellationException

internal suspend fun storageResult(
    operation: String,
    block: suspend () -> Unit,
): DataResult<Unit> = try {
    block()
    DataResult.Success(Unit)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    DataResult.Failure.StorageFailure(operation)
}

internal fun ToolMetadata.toEntity(
    activeVersionCode: Int? = null,
    lastOpenedAt: Long? = null,
) = ToolEntity(
    id = id,
    name = name,
    activeVersionCode = activeVersionCode,
    signatureState = signatureState.name,
    publisherKeyId = publisherKeyId,
    securityProfile = securityProfile.name,
    installedAt = installedAt,
    lastOpenedAt = lastOpenedAt,
    pinnedOrder = pinnedOrder,
    categoryId = categoryId,
)

internal fun ToolEntity.toDomain() = InstalledTool(
    metadata = ToolMetadata(
        id = id,
        name = name,
        signatureState = SignatureState.valueOf(signatureState),
        publisherKeyId = publisherKeyId,
        securityProfile = SecurityProfile.valueOf(securityProfile),
        installedAt = installedAt,
        pinnedOrder = pinnedOrder,
        categoryId = categoryId,
    ),
    activeVersionCode = activeVersionCode,
    lastOpenedAt = lastOpenedAt,
)

internal fun ToolVersion.toEntity() = ToolVersionEntity(
    toolId, versionCode, version, bundleLocator.value, bundleBytes, integrityHash, installedAt, launchState.name,
)

internal fun ToolVersionEntity.toDomain() = ToolVersion(
    toolId, versionCode, version, BundleLocator(bundleLocator), bundleBytes, integrityHash, installedAt,
    LaunchState.valueOf(launchState),
)

internal fun PermissionGrant.toEntity() = PermissionGrantEntity(
    toolId, permission, state.name, scope.name, grantedAt, expiresAt, source.name,
)

internal fun PermissionGrantEntity.toDomain() = PermissionGrant(
    toolId, permission, GrantState.valueOf(state), GrantScope.valueOf(scope), grantedAt, expiresAt,
    GrantSource.valueOf(source),
)

internal fun ToolKvEntity.toDomain() = ToolKvValue(key, valueJson, updatedAt)

internal fun Publisher.toEntity() =
    PublisherEntity(keyId, displayName, encodedPublicKey, trustState.name, addedAt)

internal fun PublisherEntity.toDomain() = Publisher(
    keyId, displayName, encodedPublicKey, PublisherTrustState.valueOf(trustState), addedAt,
)

internal fun AuditEvent.toEntity() = AuditLogEntity(
    id, toolId, sessionId, category, action, result, risk.name, targetHost, timestamp, durationMs, byteCount,
)

internal fun AuditLogEntity.toDomain() = AuditEvent(
    id, toolId, sessionId, category, action, result, AuditRisk.valueOf(risk), targetHost, timestamp,
    durationMs, byteCount,
)

internal fun RuntimeSession.toEntity() = RuntimeSessionEntity(
    sessionId, toolId, origin, profileName, nonceHash, startedAt, endedAt, exitReason,
)

internal fun RuntimeSessionEntity.toDomain() = RuntimeSession(
    sessionId, toolId, origin, profileName, nonceHash, startedAt, endedAt, exitReason,
)
