package io.toolbox.host

import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskRunResultPreview
import io.toolbox.core.data.preview
import io.toolbox.host.background.BackgroundCancellationResult
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.lifecycle.PackageImportControl
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RuntimeServiceHandlers
import io.toolbox.tool.runtime.RuntimePreparationCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    data object Cancelled : HostImportResult
    data class Installed(val toolId: String, val toolName: String) : HostImportResult
    data class ConfirmationRequired(val confirmation: HostImportConfirmation) : HostImportResult
    data class Failed(val code: String, val message: String) : HostImportResult
}

internal data class HostImportConfirmation(
    val id: String,
    val toolId: String,
    val toolName: String,
    val installedVersionName: String,
    val installedVersionCode: Int,
    val incomingVersionName: String,
    val incomingVersionCode: Int,
    val kind: HostImportConfirmationKind,
)

internal enum class HostImportConfirmationKind { SAME_VERSION, DOWNGRADE, UPDATE }

internal sealed interface HostImportCancellationResult {
    data object Cancelled : HostImportCancellationResult
    data class Failed(val code: String, val message: String) : HostImportCancellationResult
}

internal sealed interface HostDeleteResult {
    data object Deleted : HostDeleteResult
    data object AlreadyAbsent : HostDeleteResult
    data class Failed(val code: String, val message: String) : HostDeleteResult
}

internal sealed interface HostExampleInstallResult {
    /** [count] examples are available; [updated] of them were replaced by a newer bundled version, [alreadyCurrent] were left as they were. */
    data class Installed(val count: Int, val updated: Int = 0, val alreadyCurrent: Int = 0) : HostExampleInstallResult
    data class Failed(val code: String, val message: String) : HostExampleInstallResult
}

internal interface HostPackageOperations {
    suspend fun importPackage(input: PackageInput, control: PackageImportControl = PackageImportControl()): HostImportResult
    suspend fun confirmImport(confirmationId: String, control: PackageImportControl = PackageImportControl()): HostImportResult
    suspend fun cancelImport(confirmationId: String): HostImportCancellationResult
    suspend fun installedManifest(toolId: String): HostInstalledManifestResult
    suspend fun deleteTool(toolId: String): HostDeleteResult
    suspend fun installBundledExamples(): HostExampleInstallResult
}

internal interface HostBackgroundOperations {
    fun observeActiveTasks(toolId: String): Flow<List<BackgroundTask>>
    fun observeRecentHistory(toolId: String): Flow<io.toolbox.core.data.BackgroundTaskHistoryPage>
    fun observeHistoryThrough(toolId: String, createdAt: Long, taskId: String): Flow<List<BackgroundTask>>
    suspend fun historyBefore(toolId: String, createdAt: Long, taskId: String): io.toolbox.core.data.BackgroundTaskHistoryPage
    fun observeResult(taskId: String): Flow<TaskRunResult?>
    /** Only the start of the payload; production implementations read just that prefix from storage. */
    fun observeResultPreview(taskId: String): Flow<TaskRunResultPreview?> =
        observeResult(taskId).map { it?.preview() }
    suspend fun cancel(toolId: String, taskId: String): BackgroundCancellationResult
    suspend fun cancelTool(toolId: String)
    suspend fun releaseRuntime(toolId: String) = cancelTool(toolId)
    suspend fun cancelAll(toolIds: Collection<String>)
    /** Removes everything the tool posted, including its channel (cancelAll stops tasks only). */
    suspend fun clearNotifications(toolId: String) = Unit
}

internal interface HostPermissionSideEffects {
    suspend fun onCapabilityDisabled(toolId: String, capability: String)
}

internal interface HostPackageMaintenance {
    /** Returns a user-facing message when an interrupted install or update could not be recovered. */
    suspend fun recoverPendingMutations(): String?
}

internal interface HostBackgroundMaintenance {
    suspend fun reconcile()
}

internal interface HostRuntimeServiceHandlerFactory {
    fun createHandlers(runtime: PreparedToolRuntime): RuntimeServiceHandlers
}
