package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.PermissionGrant
import io.toolbox.tool.packagekit.ToolPackageInspector
import java.io.File

interface ToolPackageLifecycle {
    suspend fun install(
        inspectionSessionId: String,
        initialGrants: List<PermissionGrant>,
    ): InstallLifecycleResult

    suspend fun rollback(toolId: String): RollbackLifecycleResult

    suspend fun uninstall(toolId: String): UninstallLifecycleResult

    suspend fun recover(): RecoveryLifecycleResult
}

object ToolPackageLifecycles {
    fun create(
        privateFilesDirectory: File,
        inspector: ToolPackageInspector,
        catalog: CatalogLifecycleRepository,
    ): ToolPackageLifecycle = DefaultToolPackageLifecycle(
        filesRoot = privateFilesDirectory.toPath(),
        inspector = inspector,
        catalog = catalog,
    )
}

sealed interface InstallLifecycleResult {
    data class Committed(val toolId: String, val versionCode: Int) : InstallLifecycleResult
    data class AlreadyCommitted(val toolId: String, val versionCode: Int) : InstallLifecycleResult
    data class CommittedRecoveryPending(
        val toolId: String,
        val versionCode: Int,
        val reason: LifecycleFailure,
    ) : InstallLifecycleResult
    data object InspectionNotFound : InstallLifecycleResult
    data object InspectionBusy : InstallLifecycleResult
    data class Failed(val reason: LifecycleFailure) : InstallLifecycleResult
}

sealed interface RollbackLifecycleResult {
    data class RolledBack(val toolId: String, val versionCode: Int) : RollbackLifecycleResult
    data class CommittedRecoveryPending(
        val toolId: String,
        val versionCode: Int,
        val reason: LifecycleFailure,
    ) : RollbackLifecycleResult
    data class Failed(val reason: LifecycleFailure) : RollbackLifecycleResult
}

sealed interface UninstallLifecycleResult {
    data class Uninstalled(val toolId: String) : UninstallLifecycleResult
    data class AlreadyAbsent(val toolId: String) : UninstallLifecycleResult
    data class CommittedRecoveryPending(val toolId: String, val reason: LifecycleFailure) : UninstallLifecycleResult
    data class Failed(val reason: LifecycleFailure) : UninstallLifecycleResult
}

sealed interface RecoveryLifecycleResult {
    data object Recovered : RecoveryLifecycleResult
    data class Pending(val reason: LifecycleFailure) : RecoveryLifecycleResult
}

data class LifecycleFailure(
    val code: LifecycleFailureCode,
    val message: String,
)

enum class LifecycleFailureCode {
    BUSY,
    RECOVERY_REQUIRED,
    INSPECTION_REJECTED,
    GRANT_PLAN_INVALID,
    CATALOG_REJECTED,
    FILE_COLLISION,
    FILE_INTEGRITY_MISMATCH,
    STORAGE_FAILURE,
}
