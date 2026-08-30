package io.toolbox.core.data

import java.nio.charset.StandardCharsets

@JvmInline
value class BundleLocator(val value: String) {
    init {
        require(value.isNotBlank()) { "Bundle locator must not be blank" }
        require(!value.startsWith('/') && '\\' !in value) { "Bundle locator must be relative" }
        require(value.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Bundle locator must use normalized relative segments"
        }
    }
}

enum class SecurityProfile { STRICT, COMPAT }
enum class ThemeMode { SYSTEM, LIGHT, DARK, MONET_SYSTEM, MONET_LIGHT, MONET_DARK }
enum class InstallTransactionState { PREPARING, COMMITTING, COMPLETED, FAILED }
enum class TaskState { QUEUED, RUNNING, COMPLETED, CANCELLED }
enum class RunOutcome { SUCCEEDED, FAILED, CANCELLED }
enum class BackgroundOperation { HTTP_GET, NOTIFY }

data class ToolMetadata(
    val id: String,
    val name: String,
    val securityProfile: SecurityProfile,
    val installedAt: Long,
    val pinnedOrder: Int? = null,
    val categoryId: String? = null,
)

data class ToolVersion(
    val toolId: String,
    val versionCode: Int,
    val version: String,
    val bundleLocator: BundleLocator,
    val bundleBytes: Long,
    val integrityHash: String,
    val installedAt: Long,
)

data class InstalledTool(
    val metadata: ToolMetadata,
    val currentVersion: ToolVersion,
    val lastOpenedAt: Long?,
)

data class CatalogEntry(
    val toolId: String,
    val name: String,
    val securityProfile: SecurityProfile,
    val installedAt: Long,
    val lastOpenedAt: Long?,
    val pinnedOrder: Int?,
    val categoryId: String?,
    val versionCode: Int,
    val version: String,
    val bundleBytes: Long,
)

data class CatalogInstallAttempt(
    val transactionId: String,
    val metadata: ToolMetadata,
    val version: ToolVersion,
    val initialGrants: List<PermissionGrant>,
)

data class CommittedInstall(val toolId: String, val versionCode: Int)

enum class CommitInstallOutcome { Committed, AlreadyCommitted }
enum class DeleteToolCatalogOutcome { Deleted, AlreadyAbsent }

data class PermissionGrant(
    val toolId: String,
    val capability: String,
    val granted: Boolean,
    val updatedAt: Long,
)

data class ToolKvValue(val key: String, val valueJson: String, val updatedAt: Long) {
    val bytes: Int get() = valueJson.toByteArray(StandardCharsets.UTF_8).size
}

data class InstallTransaction(
    val id: String,
    val toolId: String,
    val versionCode: Int,
    val state: InstallTransactionState,
    val startedAt: Long,
    val updatedAt: Long,
    val failureCode: String? = null,
)

data class BackgroundTask(
    val taskId: String,
    val toolId: String,
    val versionCode: Int,
    val key: String,
    val operation: BackgroundOperation,
    val specJson: String,
    val periodic: Boolean,
    val intervalMinutes: Long?,
    val state: TaskState,
    val createdAt: Long,
    val updatedAt: Long,
    val nextRunAt: Long?,
    val runAttempt: Int,
)

data class TaskRunResult(
    val taskId: String,
    val outcome: RunOutcome,
    val completedAt: Long,
    val payloadJson: String?,
    val errorCode: String?,
    val attemptCount: Int,
)

data class HostSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val backgroundEnabled: Boolean = true,
)

object CoreDataLimits {
    const val TOOL_KV_BYTES = 2L * 1024L * 1024L
    const val MAX_TRANSACTION_ID_LENGTH = 128
    const val MAX_CATEGORY_ID_LENGTH = 128
    const val MAX_TASK_ID_LENGTH = 128
    const val MAX_TASK_KEY_LENGTH = 64
    const val MAX_TASK_SPEC_BYTES = 64 * 1024
    const val MAX_TASK_RESULT_BYTES = 256 * 1024
}

internal fun String.isValidTransactionId(): Boolean =
    isNotBlank() && length <= CoreDataLimits.MAX_TRANSACTION_ID_LENGTH

internal fun String?.isValidCategoryId(): Boolean =
    this == null || (isNotBlank() && length <= CoreDataLimits.MAX_CATEGORY_ID_LENGTH)

internal fun String.isValidTaskId(): Boolean =
    isNotBlank() && length <= CoreDataLimits.MAX_TASK_ID_LENGTH

internal fun String.isValidTaskKey(): Boolean =
    isNotBlank() && length <= CoreDataLimits.MAX_TASK_KEY_LENGTH

sealed interface DataResult<out T> {
    data class Success<T>(val value: T) : DataResult<T>

    sealed interface Failure : DataResult<Nothing> {
        data class InvalidInput(val field: String) : Failure
        data class DuplicateVersion(val toolId: String, val versionCode: Int) : Failure
        data class DuplicateTransaction(val transactionId: String) : Failure
        data class DuplicateTaskKey(val toolId: String, val key: String) : Failure
        data class NonMonotonicVersion(
            val toolId: String,
            val attemptedVersionCode: Int,
            val currentVersionCode: Int,
        ) : Failure

        data class InvalidState(val subject: String) : Failure
        data class NotFound(val subject: String) : Failure
        data class QuotaExceeded(val quotaBytes: Long, val attemptedBytes: Long) : Failure
        data class StorageFailure(val operation: String) : Failure
    }
}
