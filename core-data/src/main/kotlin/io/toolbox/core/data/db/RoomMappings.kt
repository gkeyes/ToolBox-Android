package io.toolbox.core.data.db

import io.toolbox.core.data.AuditEvent
import io.toolbox.core.data.AuditRisk
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogEntry
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
import io.toolbox.core.data.ToolVersionIdentity
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

internal fun CatalogProjection.toDomain() = CatalogEntry(
    toolId = toolId,
    name = name,
    signatureState = SignatureState.valueOf(signatureState),
    publisherKeyId = publisherKeyId,
    securityProfile = SecurityProfile.valueOf(securityProfile),
    installedAt = installedAt,
    lastOpenedAt = lastOpenedAt,
    pinnedOrder = pinnedOrder,
    categoryId = categoryId,
    activeVersionCode = activeVersionCode,
    activeVersionName = activeVersionName,
    bundleBytes = bundleBytes,
    launchState = launchState?.let(LaunchState::valueOf),
)

internal fun ToolVersion.toEntity() = ToolVersionEntity(
    toolId = toolId,
    versionCode = versionCode,
    version = version,
    bundleLocator = bundleLocator.value,
    bundleBytes = bundleBytes,
    integrityHash = integrityHash,
    installedAt = installedAt,
    launchState = launchState.name,
    sourceSessionId = sourceSessionId,
    name = identity.name,
    signatureState = identity.signatureState.name,
    publisherKeyId = identity.publisherKeyId,
    securityProfile = identity.securityProfile.name,
)

internal fun ToolVersionEntity.toDomain() = ToolVersion(
    toolId, versionCode, version, BundleLocator(bundleLocator), bundleBytes, integrityHash, installedAt,
    LaunchState.valueOf(launchState), sourceSessionId,
    ToolVersionIdentity(
        name = name,
        signatureState = SignatureState.valueOf(signatureState),
        publisherKeyId = publisherKeyId,
        securityProfile = SecurityProfile.valueOf(securityProfile),
    ),
)

internal fun ToolEntity.withIdentity(identity: ToolVersionIdentity, activeVersionCode: Int?) = copy(
    name = identity.name,
    activeVersionCode = activeVersionCode,
    signatureState = identity.signatureState.name,
    publisherKeyId = identity.publisherKeyId,
    securityProfile = identity.securityProfile.name,
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
