package io.toolbox.host

import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.TaskRunResult
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RuntimeM2Handlers
import io.toolbox.tool.runtime.RuntimePreparationCode
import kotlinx.coroutines.flow.Flow

internal data class HostManifestPermission(
    val capability: String,
    val reason: String,
    val required: Boolean,
)

internal data class HostInstalledManifest(
    val toolId: String,
    val toolName: String,
    val versionCode: Int,
    val versionName: String,
    val permissions: List<HostManifestPermission>,
)

internal sealed interface HostInstalledManifestResult {
    data class Found(val manifest: HostInstalledManifest) : HostInstalledManifestResult
    data object NotInstalled : HostInstalledManifestResult
    data class Failed(val code: RuntimePreparationCode, val message: String) : HostInstalledManifestResult
}

internal sealed interface HostImportResult {
    data class Installed(val toolId: String, val toolName: String) : HostImportResult
    data class Failed(val code: String, val message: String) : HostImportResult
}

internal sealed interface HostDeleteResult {
    data object Deleted : HostDeleteResult
    data object AlreadyAbsent : HostDeleteResult
    data class Failed(val code: String, val message: String) : HostDeleteResult
}

internal sealed interface HostExampleInstallResult {
    data class Installed(val count: Int) : HostExampleInstallResult
    data class Failed(val code: String, val message: String) : HostExampleInstallResult
}

internal interface HostPackageOperations {
    suspend fun importPackage(input: PackageInput): HostImportResult
    suspend fun installedManifest(toolId: String): HostInstalledManifestResult
    suspend fun deleteTool(toolId: String): HostDeleteResult
    suspend fun installBundledExamples(): HostExampleInstallResult
}

internal interface HostBackgroundOperations {
    fun observeTasks(toolId: String): Flow<List<BackgroundTask>>
    fun observeResult(taskId: String): Flow<TaskRunResult?>
    suspend fun cancel(toolId: String, taskId: String): Boolean
    suspend fun cancelTool(toolId: String)
    suspend fun releaseRuntime(toolId: String) = cancelTool(toolId)
    suspend fun cancelAll(toolIds: Collection<String>)
}

internal interface HostPermissionSideEffects {
    suspend fun onCapabilityDisabled(toolId: String, capability: String)
}

internal interface HostPackageMaintenance {
    suspend fun recoverPendingMutations()
}

internal interface HostBackgroundMaintenance {
    suspend fun reconcile()
}

internal interface HostRuntimeM2HandlerFactory {
    fun createHandlers(runtime: PreparedToolRuntime): RuntimeM2Handlers
}
