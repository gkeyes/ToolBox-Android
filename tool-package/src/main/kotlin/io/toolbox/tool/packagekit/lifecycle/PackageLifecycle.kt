package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstallTransactionRepository
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageLimits
import io.toolbox.tool.packagekit.PackageRejection
import java.io.File

interface ToolPackageManager {
    suspend fun recoverPendingMutations(
        cleanup: ToolStateCleanup = ToolStateCleanup.None,
    ): PackageRecoveryResult

    suspend fun importAndInstall(
        input: PackageInput,
        cleanup: ToolStateCleanup = ToolStateCleanup.None,
    ): PackageInstallResult

    suspend fun confirmInstall(
        confirmationId: String,
        cleanup: ToolStateCleanup = ToolStateCleanup.None,
    ): PackageInstallResult

    suspend fun cancelInstall(confirmationId: String): PackageOperationFailure?

    suspend fun uninstall(
        toolId: String,
        cleanup: ToolStateCleanup = ToolStateCleanup.None,
    ): PackageUninstallResult
}

interface ToolStateCleanup {
    /** Holds the host's runtime/storage barrier until publication, commit or rollback has finished. */
    suspend fun <T> withVersionReplacement(
        toolId: String,
        previousVersionCode: Int,
        nextVersionCode: Int,
        action: suspend () -> T,
    ): T {
        beforeVersionReplacement(toolId, previousVersionCode, nextVersionCode)
        return action()
    }

    suspend fun beforeVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int) = Unit

    /** Retryable metadata/cache invalidation only. Never erase data or stop a later runtime here. */
    suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int)

    suspend fun beforeUninstall(toolId: String) = Unit

    suspend fun afterUninstall(toolId: String)

    data object None : ToolStateCleanup {
        override suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int) = Unit
        override suspend fun afterUninstall(toolId: String) = Unit
    }
}

object ToolPackageManagers {
    fun create(
        privateFilesDirectory: File,
        catalog: CatalogRepository,
        lifecycle: CatalogLifecycleRepository,
        transactions: InstallTransactionRepository,
        limits: PackageLimits = PackageLimits(),
        supportedCapabilities: Set<String> = SupportedToolCapabilities.All,
        hostVersion: String = "0.3.3",
    ): ToolPackageManager = DefaultToolPackageManager(
        filesRoot = privateFilesDirectory.toPath(),
        catalog = catalog,
        lifecycle = lifecycle,
        transactions = transactions,
        limits = limits,
        supportedCapabilities = supportedCapabilities,
        hostVersion = hostVersion,
    )
}

sealed interface PackageInstallResult {
    data class Installed(val toolId: String, val versionCode: Int, val updated: Boolean) : PackageInstallResult
    data class ConfirmationRequired(val confirmation: PackageVersionConfirmation) : PackageInstallResult
    data class Rejected(val rejection: PackageRejection) : PackageInstallResult
    data class Failed(val failure: PackageOperationFailure) : PackageInstallResult
}

data class PackageVersionConfirmation(
    val id: String,
    val toolId: String,
    val toolName: String,
    val installedVersionName: String,
    val installedVersionCode: Int,
    val incomingVersionName: String,
    val incomingVersionCode: Int,
    val kind: PackageVersionConfirmationKind,
)

enum class PackageVersionConfirmationKind { SAME_VERSION, DOWNGRADE, UPDATE }

sealed interface PackageUninstallResult {
    data class Uninstalled(val toolId: String) : PackageUninstallResult
    data class AlreadyAbsent(val toolId: String) : PackageUninstallResult
    data class Failed(val failure: PackageOperationFailure) : PackageUninstallResult
}

sealed interface PackageRecoveryResult {
    data object Recovered : PackageRecoveryResult
    data class Failed(val failure: PackageOperationFailure) : PackageRecoveryResult
}

data class PackageOperationFailure(val code: PackageOperationFailureCode, val message: String)

enum class PackageOperationFailureCode {
    BUSY,
    CONFIRMATION_EXPIRED,
    SIGNING_IDENTITY_CHANGED,
    UNSUPPORTED_REQUIRED_CAPABILITY,
    UNSUPPORTED_HOST_VERSION,
    DATA_FAILURE,
    STORAGE_FAILURE,
    CLEANUP_FAILURE,
}

object SupportedToolCapabilities {
    val All = setOf(
        "storage",
        "storage.secure",
        "clipboard.write",
        "clipboard.read",
        "share",
        "browser",
        "files.open",
        "files.save",
        "network",
        "device.basic",
        "haptics",
        "notifications",
        "shortcuts",
        "camera",
        "location",
        "background.tasks",
        "background.runtime",
        "location.background",
        "alarms",
    )
}
