package io.toolbox.core.data.db

import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import io.toolbox.core.data.ToolKvValue
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion

internal fun ToolMetadata.toEntity(lastOpenedAt: Long? = null) = ToolEntity(
    id = id,
    name = name,
    securityProfile = securityProfile.name,
    installedAt = installedAt,
    lastOpenedAt = lastOpenedAt,
    pinnedOrder = pinnedOrder,
    categoryId = categoryId,
)

internal fun ToolVersion.toEntity() = ToolVersionEntity(
    toolId = toolId,
    versionCode = versionCode,
    version = version,
    bundleLocator = bundleLocator.value,
    bundleBytes = bundleBytes,
    integrityHash = integrityHash,
    installedAt = installedAt,
)

internal fun ToolVersionEntity.toDomain() = ToolVersion(
    toolId = toolId,
    versionCode = versionCode,
    version = version,
    bundleLocator = BundleLocator(bundleLocator),
    bundleBytes = bundleBytes,
    integrityHash = integrityHash,
    installedAt = installedAt,
)

internal fun InstalledToolProjection.toDomain() = InstalledTool(
    metadata = ToolMetadata(
        id = id,
        name = name,
        securityProfile = SecurityProfile.valueOf(securityProfile),
        installedAt = installedAt,
        pinnedOrder = pinnedOrder,
        categoryId = categoryId,
    ),
    currentVersion = ToolVersion(
        toolId = id,
        versionCode = versionCode,
        version = version,
        bundleLocator = BundleLocator(bundleLocator),
        bundleBytes = bundleBytes,
        integrityHash = integrityHash,
        installedAt = versionInstalledAt,
    ),
    lastOpenedAt = lastOpenedAt,
)

internal fun InstalledToolProjection.toCatalogEntry() = CatalogEntry(
    toolId = id,
    name = name,
    securityProfile = SecurityProfile.valueOf(securityProfile),
    installedAt = installedAt,
    lastOpenedAt = lastOpenedAt,
    pinnedOrder = pinnedOrder,
    categoryId = categoryId,
    versionCode = versionCode,
    version = version,
    bundleBytes = bundleBytes,
)

internal fun PermissionGrant.toEntity() = PermissionGrantEntity(toolId, capability, granted, updatedAt)
internal fun PermissionGrantEntity.toDomain() = PermissionGrant(toolId, capability, granted, updatedAt)
internal fun ToolKvEntity.toDomain() = ToolKvValue(key, valueJson, updatedAt)

internal fun InstallTransaction.toEntity() = InstallTransactionEntity(
    id, toolId, versionCode, state.name, startedAt, updatedAt, failureCode,
)

internal fun InstallTransactionEntity.toDomain() = InstallTransaction(
    id, toolId, versionCode, InstallTransactionState.valueOf(state), startedAt, updatedAt, failureCode,
)

internal fun BackgroundTask.toEntity() = BackgroundTaskEntity(
    taskId, toolId, versionCode, key, operation.name, specJson, periodic, intervalMinutes, state.name,
    createdAt, updatedAt, nextRunAt, runAttempt,
)

internal fun BackgroundTaskEntity.toDomain() = BackgroundTask(
    taskId, toolId, versionCode, key, BackgroundOperation.valueOf(operation), specJson, periodic,
    intervalMinutes, TaskState.valueOf(state), createdAt, updatedAt, nextRunAt, runAttempt,
)

internal fun TaskRunResult.toEntity() = TaskResultEntity(
    taskId, outcome.name, completedAt, payloadJson, errorCode, attemptCount,
)

internal fun TaskResultEntity.toDomain() = TaskRunResult(
    taskId, RunOutcome.valueOf(outcome), completedAt, payloadJson, errorCode, attemptCount,
)
