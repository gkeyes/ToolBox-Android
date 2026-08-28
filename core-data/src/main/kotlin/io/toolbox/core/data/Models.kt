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

enum class SignatureState { VERIFIED_TRUSTED, VERIFIED_UNKNOWN, UNSIGNED, INVALID }
enum class SecurityProfile { STRICT, COMPAT }
enum class LaunchState { PENDING, STABLE, FAILED }
enum class GrantState { GRANTED, DENIED, BLOCKED }
enum class GrantScope { ONCE, SESSION, PERSISTENT }
enum class GrantSource { INSTALL, RUNTIME, SETTINGS, POLICY }
enum class PublisherTrustState { UNKNOWN, TRUSTED, BLOCKED }
enum class AuditRisk { LOW, MEDIUM, HIGH }
enum class ThemeMode { SYSTEM, LIGHT, DARK, MONET_SYSTEM, MONET_LIGHT, MONET_DARK }
enum class GlobalSecurityPolicy { STRICT, BALANCED }
enum class HostPage { HOME, TOOLS, SETTINGS }

data class ToolMetadata(
    val id: String,
    val name: String,
    val signatureState: SignatureState,
    val publisherKeyId: String?,
    val securityProfile: SecurityProfile,
    val installedAt: Long,
    val pinnedOrder: Int? = null,
    val categoryId: String? = null,
) {
    init {
        require(signatureState != SignatureState.INVALID) {
            "Invalid signatures cannot enter the installed catalog"
        }
    }
}

data class InstalledTool(
    val metadata: ToolMetadata,
    val activeVersionCode: Int?,
    val lastOpenedAt: Long?,
)

data class ToolVersion(
    val toolId: String,
    val versionCode: Int,
    val version: String,
    val bundleLocator: BundleLocator,
    val bundleBytes: Long,
    val integrityHash: String,
    val installedAt: Long,
    val launchState: LaunchState,
    val sourceSessionId: String,
    val identity: ToolVersionIdentity,
)

data class ToolVersionIdentity(
    val name: String,
    val signatureState: SignatureState,
    val publisherKeyId: String?,
    val securityProfile: SecurityProfile,
) {
    init {
        require(signatureState != SignatureState.INVALID) {
            "Invalid signatures cannot enter version history"
        }
    }
}

data class CatalogInstallAttempt(
    val metadata: ToolMetadata,
    val version: ToolVersion,
    val initialGrants: List<PermissionGrant>,
)

data class CatalogLifecycleSnapshot(
    val toolId: String,
    val tool: InstalledTool?,
    val versions: List<ToolVersion>,
    val grants: List<PermissionGrant>,
)

data class CommittedInstall(val toolId: String, val versionCode: Int)

enum class CommitInstallOutcome { Committed, AlreadyCommitted }
enum class DeleteToolCatalogOutcome { Deleted, AlreadyAbsent }

data class RollbackOutcome(val activeVersionCode: Int)

data class PermissionGrant(
    val toolId: String,
    val permission: String,
    val state: GrantState,
    val scope: GrantScope,
    val grantedAt: Long,
    val expiresAt: Long?,
    val source: GrantSource,
)

data class ToolKvValue(val key: String, val valueJson: String, val updatedAt: Long) {
    val bytes: Int get() = valueJson.toByteArray(StandardCharsets.UTF_8).size
}

data class Publisher(
    val keyId: String,
    val displayName: String,
    val encodedPublicKey: String,
    val trustState: PublisherTrustState,
    val addedAt: Long,
)

data class AuditEvent(
    val id: Long = 0,
    val toolId: String?,
    val sessionId: String?,
    val category: String,
    val action: String,
    val result: String,
    val risk: AuditRisk,
    val targetHost: String?,
    val timestamp: Long,
    val durationMs: Long?,
    val byteCount: Long?,
)

data class RuntimeSession(
    val sessionId: String,
    val toolId: String,
    val origin: String,
    val profileName: String?,
    val nonceHash: String,
    val startedAt: Long,
    val endedAt: Long?,
    val exitReason: String?,
)

data class HostSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val securityPolicy: GlobalSecurityPolicy = GlobalSecurityPolicy.STRICT,
    val auditRetentionDays: Int = 30,
    val developerMode: Boolean = false,
    val defaultStorageQuotaBytes: Long = 2L * 1024L * 1024L,
    val lastPage: HostPage = HostPage.HOME,
)

object HostSettingsLimits {
    const val MIN_AUDIT_RETENTION_DAYS = 1
    const val MAX_AUDIT_RETENTION_DAYS = 365
    const val MIN_STORAGE_QUOTA_BYTES = 65_536L
    const val MAX_STORAGE_QUOTA_BYTES = 52_428_800L
}

internal fun HostSettings.validationError(): String? = when {
    auditRetentionDays !in
        HostSettingsLimits.MIN_AUDIT_RETENTION_DAYS..HostSettingsLimits.MAX_AUDIT_RETENTION_DAYS ->
        "auditRetentionDays"
    defaultStorageQuotaBytes !in
        HostSettingsLimits.MIN_STORAGE_QUOTA_BYTES..HostSettingsLimits.MAX_STORAGE_QUOTA_BYTES ->
        "defaultStorageQuotaBytes"
    else -> null
}

internal fun HostSettings.withPersistedDefaults(): HostSettings = copy(
    auditRetentionDays = auditRetentionDays.takeIf {
        it in HostSettingsLimits.MIN_AUDIT_RETENTION_DAYS..HostSettingsLimits.MAX_AUDIT_RETENTION_DAYS
    } ?: HostSettings().auditRetentionDays,
    defaultStorageQuotaBytes = defaultStorageQuotaBytes.takeIf {
        it in HostSettingsLimits.MIN_STORAGE_QUOTA_BYTES..HostSettingsLimits.MAX_STORAGE_QUOTA_BYTES
    } ?: HostSettings().defaultStorageQuotaBytes,
)

internal const val MAX_SOURCE_SESSION_ID_LENGTH = 128
internal const val MAX_CATEGORY_ID_LENGTH = 128

internal fun String.isValidSourceSessionId(): Boolean =
    isNotBlank() && length <= MAX_SOURCE_SESSION_ID_LENGTH

internal fun String?.isValidCategoryId(): Boolean =
    this == null || (isNotBlank() && length <= MAX_CATEGORY_ID_LENGTH)

sealed interface DataResult<out T> {
    data class Success<T>(val value: T) : DataResult<T>
    sealed interface Failure : DataResult<Nothing> {
        data class InvalidInput(val field: String) : Failure
        data class DuplicateVersion(val toolId: String, val versionCode: Int) : Failure
        data class DuplicateSourceSession(val sourceSessionId: String) : Failure
        data class NonMonotonicVersion(
            val toolId: String,
            val attemptedVersionCode: Int,
            val currentVersionCode: Int,
        ) : Failure
        data class SignatureContinuityViolation(val toolId: String) : Failure
        data class UnsignedPersistentGrant(val toolId: String, val permission: String) : Failure
        data class LifecycleConflict(val toolId: String) : Failure
        data class NotFound(val subject: String) : Failure
        data class QuotaExceeded(val quotaBytes: Long, val attemptedBytes: Long) : Failure
        data class StorageFailure(val operation: String) : Failure
    }
}
