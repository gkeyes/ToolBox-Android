package io.toolbox.host

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.WorkManager
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import io.toolbox.host.background.AndroidNotificationGateway
import io.toolbox.host.background.BackgroundAuthorization
import io.toolbox.host.background.BackgroundExecutionPolicy
import io.toolbox.host.background.BackgroundHostOperations
import io.toolbox.host.background.BackgroundManifestPolicy
import io.toolbox.host.background.BackgroundManifestPolicyResolver
import io.toolbox.host.background.BackgroundNotificationPermissionChecker
import io.toolbox.host.background.BackgroundTaskCoordinator
import io.toolbox.host.background.BackgroundTaskRequest
import io.toolbox.host.background.EnqueueResult
import io.toolbox.host.background.NetworkExecution
import io.toolbox.host.background.RepositoryBackgroundAuthorization
import io.toolbox.host.background.ToolNetworkProxy
import io.toolbox.host.runtime.ForegroundCapabilityBroker
import io.toolbox.host.runtime.clearRuntimeSecureStorage
import io.toolbox.tool.packagekit.InstalledManifest
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageRejection
import io.toolbox.tool.packagekit.lifecycle.PackageInstallResult
import io.toolbox.tool.packagekit.lifecycle.PackageOperationFailure
import io.toolbox.tool.packagekit.lifecycle.PackageRecoveryResult
import io.toolbox.tool.packagekit.lifecycle.PackageUninstallResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageManager
import io.toolbox.tool.packagekit.lifecycle.ToolPackageManagers
import io.toolbox.tool.packagekit.lifecycle.ToolStateCleanup
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RuntimeBackgroundRunOutcome
import io.toolbox.tool.runtime.RuntimeBackgroundTaskError
import io.toolbox.tool.runtime.RuntimeBackgroundTaskHandler
import io.toolbox.tool.runtime.RuntimeBackgroundTaskOperation
import io.toolbox.tool.runtime.RuntimeBackgroundTaskRunResult
import io.toolbox.tool.runtime.RuntimeBackgroundTaskSpec
import io.toolbox.tool.runtime.RuntimeBackgroundTaskState
import io.toolbox.tool.runtime.RuntimeBackgroundTaskSummary
import io.toolbox.tool.runtime.RuntimeBridgeConfiguration
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeM2Handlers
import io.toolbox.tool.runtime.RuntimeNetworkHandler
import io.toolbox.tool.runtime.RuntimeNetworkMethod
import io.toolbox.tool.runtime.RuntimeNetworkRequest
import io.toolbox.tool.runtime.RuntimeNetworkResponse
import io.toolbox.tool.runtime.RuntimeNotificationHandler
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeDataCleaner
import io.toolbox.tool.runtime.RuntimeDataCleanupExecution
import io.toolbox.tool.runtime.ToolRuntimePreparer
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

internal class ProductionHostPackageOperations(
    private val application: Application,
    private val repositories: CoreDataRepositories,
    private val runtimeDataCleaner: RuntimeDataCleaner,
    private val background: HostBackgroundOperations,
) : HostPackageOperations, HostPackageMaintenance {
    private val packages: ToolPackageManager = ToolPackageManagers.create(
        privateFilesDirectory = application.filesDir,
        catalog = repositories.catalog,
        lifecycle = repositories.lifecycle,
        transactions = repositories.installs,
    )
    private val runtimePreparer = ToolRuntimePreparer(application.filesDir)
    private val cleanup = object : ToolStateCleanup {
        override suspend fun afterVersionReplacement(
            toolId: String,
            previousVersionCode: Int,
            nextVersionCode: Int,
        ) {
            clearRuntimeState(toolId, removeShortcut = false)
        }

        override suspend fun afterUninstall(toolId: String) {
            clearRuntimeState(toolId, removeShortcut = true)
        }
    }

    override suspend fun recoverPendingMutations() {
        when (packages.recoverPendingMutations(cleanup)) {
            PackageRecoveryResult.Recovered -> Unit
            is PackageRecoveryResult.Failed -> Unit
        }
    }

    override suspend fun importPackage(input: PackageInput): HostImportResult = when (
        val result = packages.importAndInstall(input, cleanup)
    ) {
        is PackageInstallResult.Installed -> {
            val name = repositories.catalog.observeTool(result.toolId).first()?.metadata?.name ?: "工具"
            HostImportResult.Installed(result.toolId, name)
        }

        is PackageInstallResult.Rejected -> HostImportResult.Failed(
            code = result.rejection.code.name,
            message = importFailureMessage(result.rejection),
        )

        is PackageInstallResult.Failed -> HostImportResult.Failed(
            code = result.failure.code.name,
            message = importFailureMessage(result.failure),
        )
    }

    override suspend fun installedManifest(toolId: String): HostInstalledManifest? = withContext(Dispatchers.IO) {
        val tool = repositories.catalog.observeTool(toolId).first() ?: return@withContext null
        val runtime = (runtimePreparer.prepare(toolId, tool) as? io.toolbox.tool.runtime.RuntimePreparationResult.Prepared)
            ?.runtime
            ?: return@withContext null
        runtime.installedManifest.toHostManifest(tool.currentVersion.version)
    }

    override suspend fun deleteTool(toolId: String): HostDeleteResult = when (val result = packages.uninstall(toolId, cleanup)) {
        is PackageUninstallResult.Uninstalled -> HostDeleteResult.Deleted
        is PackageUninstallResult.AlreadyAbsent -> HostDeleteResult.AlreadyAbsent
        is PackageUninstallResult.Failed -> HostDeleteResult.Failed(
            code = result.failure.code.name,
            message = "删除未完成，请重试。",
        )
    }

    override suspend fun installBundledExamples(): HostExampleInstallResult {
        var available = 0
        for (asset in BUNDLED_EXAMPLES) {
            when (val result = packages.importAndInstall(AssetPackageInput(application, asset), cleanup)) {
                is PackageInstallResult.Installed -> available += 1
                is PackageInstallResult.Failed -> {
                    if (result.failure.code.name == "VERSION_NOT_NEWER") {
                        available += 1
                    } else {
                        return HostExampleInstallResult.Failed(
                            code = result.failure.code.name,
                            message = "范例安装未完成，请重试。",
                        )
                    }
                }
                is PackageInstallResult.Rejected -> return HostExampleInstallResult.Failed(
                    code = result.rejection.code.name,
                    message = "范例工具包无法安装。",
                )
            }
        }
        return HostExampleInstallResult.Installed(available)
    }

    private suspend fun clearRuntimeState(toolId: String, removeShortcut: Boolean) {
        background.cancelTool(toolId)
        if (removeShortcut) ForegroundCapabilityBroker.clearToolShortcut(application, toolId)
        when (
            runtimeDataCleaner.clearThenRun(toolId) {
                check(clearRuntimeSecureStorage(toolId, repositories.keyValues))
            }
        ) {
            is RuntimeDataCleanupExecution.Completed -> Unit
            is RuntimeDataCleanupExecution.Rejected -> error("Runtime state cleanup could not be completed")
        }
    }

    private fun InstalledManifest.toHostManifest(versionName: String) = HostInstalledManifest(
        toolId = id,
        toolName = name,
        versionCode = versionCode,
        versionName = versionName,
        permissions = permissionDeclarations.map {
            HostManifestPermission(it.name, it.reason, it.required)
        },
    )

    private fun importFailureMessage(rejection: PackageRejection): String = when (rejection.code.name) {
        "SIGNATURE_INVALID" -> "工具包签名无效，无法安装。"
        else -> "工具包无法安装，请检查文件后重试。"
    }

    private fun importFailureMessage(failure: PackageOperationFailure): String = when (failure.code.name) {
        "VERSION_NOT_NEWER" -> "此工具已安装相同或更高版本。"
        "BUSY" -> "正在处理另一个工具包，请稍后重试。"
        else -> "安装未完成，请重试。"
    }

    private companion object {
        val BUNDLED_EXAMPLES = listOf(
            "position-calculator.tbx",
            "quick-notes.tbx",
            "background-task-demo.tbx",
        )
    }
}

private class AssetPackageInput(
    private val context: Context,
    override val displayName: String,
) : PackageInput {
    override fun openStream(): InputStream = context.assets.open(displayName)
}

internal class ProductionHostBackgroundOperations(
    context: Context,
    repositories: CoreDataRepositories,
) : HostBackgroundOperations,
    HostPermissionSideEffects,
    HostBackgroundMaintenance,
    HostRuntimeM2HandlerFactory {
    private val applicationContext = context.applicationContext
    private val network = ToolNetworkProxy()
    private val notifications = AndroidNotificationGateway(applicationContext)
    private val coordinator = BackgroundTaskCoordinator(
        workManager = WorkManager.getInstance(applicationContext),
        repositories = repositories,
        authorization = createBackgroundAuthorization(applicationContext, repositories),
        notifications = notifications,
    )
    private val delegate = BackgroundHostOperations(coordinator)

    override fun observeTasks(toolId: String) = delegate.observeTasks(toolId)

    override fun observeResult(taskId: String) = delegate.observeResult(taskId)

    override suspend fun cancel(toolId: String, taskId: String): Boolean = delegate.cancel(toolId, taskId)

    override suspend fun cancelTool(toolId: String) = delegate.cancelTool(toolId)

    override suspend fun cancelAll(toolIds: Collection<String>) = delegate.cancelAll(toolIds)

    override suspend fun onCapabilityDisabled(toolId: String, capability: String) =
        delegate.onCapabilityDisabled(toolId, capability)

    override suspend fun reconcile() = coordinator.reconcile()

    override fun createHandlers(runtime: PreparedToolRuntime): RuntimeM2Handlers = RuntimeM2Handlers(
        network = RuntimeNetworkHandler { request -> networkRequest(runtime, request) },
        notifications = object : RuntimeNotificationHandler {
            override suspend fun post(notificationId: String, title: String, body: String) {
                when (val result = notifications.post(runtime.toolId, notificationId, title, body)) {
                    io.toolbox.host.background.NotificationResult.Posted -> Unit
                    is io.toolbox.host.background.NotificationResult.Rejected -> throw RuntimeHandlerException(
                        result.errorCode.toRuntimeErrorCode(),
                        "通知未能发送。",
                    )
                }
            }

            override suspend fun cancel(notificationId: String) {
                notifications.cancel(runtime.toolId, notificationId)
            }
        },
        background = RuntimeBackgroundTaskAdapter(runtime, coordinator),
    )

    private suspend fun networkRequest(
        runtime: PreparedToolRuntime,
        request: RuntimeNetworkRequest,
    ): RuntimeNetworkResponse {
        if (request.method != RuntimeNetworkMethod.GET) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "POST requests are not available yet")
        }
        val policy = runtime.installedManifest.network ?: throw RuntimeHandlerException(
            RuntimeRpcErrorCode.NETWORK_BLOCKED,
            "This tool does not declare network access",
        )
        return when (
            val result = network.httpGet(
                url = request.url,
                allowedHosts = policy.allowDomains,
                allowRedirects = policy.allowRedirects,
                timeoutMillis = policy.timeoutMs.toLong(),
                maxResponseBytes = policy.maxResponseBytes,
            )
        ) {
            is NetworkExecution.Success -> RuntimeNetworkResponse(
                status = result.statusCode,
                headers = buildMap {
                    result.contentType?.let { put("content-type", it) }
                    put("x-toolbox-final-url", result.finalUrl)
                },
                body = result.body,
            )

            is NetworkExecution.RetryableFailure -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.NETWORK_BLOCKED,
                "网络请求暂时不可用。",
            )

            is NetworkExecution.TerminalFailure -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.NETWORK_BLOCKED,
                "网络请求被阻止。",
            )
        }
    }
}

internal fun createBackgroundWorkerDependencies(
    context: Context,
    repositories: CoreDataRepositories,
) = io.toolbox.host.background.BackgroundWorkerDependencies(
    repositories = repositories,
    authorization = createBackgroundAuthorization(context, repositories),
    networkProxy = ToolNetworkProxy(),
    notifications = AndroidNotificationGateway(context),
)

private fun createBackgroundAuthorization(
    context: Context,
    repositories: CoreDataRepositories,
): BackgroundAuthorization = RepositoryBackgroundAuthorization(
    repositories = repositories,
    manifestResolver = ActiveBundleBackgroundManifestResolver(context, repositories.catalog),
    notificationPermission = BackgroundNotificationPermissionChecker {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    },
)

private class ActiveBundleBackgroundManifestResolver(
    context: Context,
    private val catalog: CatalogRepository,
) : BackgroundManifestPolicyResolver {
    private val preparer = ToolRuntimePreparer(context.applicationContext.filesDir)

    override suspend fun resolve(toolId: String, versionCode: Int): BackgroundManifestPolicy? = withContext(Dispatchers.IO) {
        val installed = catalog.observeTool(toolId).first() ?: return@withContext null
        if (installed.currentVersion.versionCode != versionCode) return@withContext null
        val runtime = (preparer.prepare(toolId, installed) as? io.toolbox.tool.runtime.RuntimePreparationResult.Prepared)
            ?.runtime
            ?: return@withContext null
        val manifest = runtime.installedManifest
        val network = manifest.network
        BackgroundManifestPolicy(
            toolId = manifest.id,
            versionCode = manifest.versionCode,
            declaredCapabilities = manifest.permissions,
            networkHosts = network?.allowDomains.orEmpty(),
            allowNetworkRedirects = network?.allowRedirects == true,
            networkTimeoutMillis = network?.timeoutMs?.toLong() ?: 15_000L,
            maxNetworkResponseBytes = network?.maxResponseBytes ?: 256 * 1024,
        )
    }
}

private class RuntimeBackgroundTaskAdapter(
    private val runtime: PreparedToolRuntime,
    private val coordinator: BackgroundTaskCoordinator,
) : RuntimeBackgroundTaskHandler {
    override suspend fun enqueue(spec: RuntimeBackgroundTaskSpec): String =
        create(spec, intervalMinutes = null)

    override suspend fun schedulePeriodic(spec: RuntimeBackgroundTaskSpec, intervalMinutes: Long): String =
        create(spec, intervalMinutes)

    override suspend fun list(): List<RuntimeBackgroundTaskSummary> =
        coordinator.tasks(runtime.toolId).first().map { task ->
            RuntimeBackgroundTaskSummary(
                taskId = task.taskId,
                key = task.key,
                state = task.state.toRuntimeState(),
                periodic = task.periodic,
                nextRunAt = task.nextRunAt,
            )
        }

    override suspend fun getResult(taskId: String): RuntimeBackgroundTaskRunResult? {
        val task = coordinator.tasks(runtime.toolId).first().firstOrNull { it.taskId == taskId } ?: return null
        val result = coordinator.result(taskId).first() ?: return null
        return result.toRuntimeResult(task)
    }

    override suspend fun cancel(taskId: String): Boolean = coordinator.cancel(runtime.toolId, taskId)

    private suspend fun create(spec: RuntimeBackgroundTaskSpec, intervalMinutes: Long?): String {
        validateSupportedSchedule(spec)
        val request = when (val operation = spec.operation) {
            is RuntimeBackgroundTaskOperation.HttpGet -> BackgroundTaskRequest.HttpGet(
                key = spec.key,
                url = operation.url,
            )

            is RuntimeBackgroundTaskOperation.Notify -> BackgroundTaskRequest.Notify(
                key = spec.key,
                title = operation.title,
                body = operation.body,
            )
        }
        val result = if (intervalMinutes == null) {
            coordinator.enqueue(runtime.toolId, runtime.versionCode, request)
        } else {
            coordinator.schedulePeriodic(runtime.toolId, runtime.versionCode, request, intervalMinutes)
        }
        return when (result) {
            is EnqueueResult.Enqueued -> result.taskId
            is EnqueueResult.Rejected -> throw RuntimeHandlerException(
                result.errorCode.toRuntimeErrorCode(),
                "后台任务无法创建。",
            )
        }
    }

    private fun validateSupportedSchedule(spec: RuntimeBackgroundTaskSpec) {
        if (spec.earliestAt != null) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "指定运行时间暂不支持")
        }
        val constraints = spec.constraints ?: return
        if (constraints.requiresCharging == true || constraints.batteryNotLow == true) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "此任务约束暂不支持")
        }
        if (constraints.network == io.toolbox.tool.runtime.RuntimeNetworkConstraint.NONE &&
            spec.operation is RuntimeBackgroundTaskOperation.HttpGet
        ) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "网络任务需要网络连接")
        }
    }
}

private fun TaskState.toRuntimeState(): RuntimeBackgroundTaskState = when (this) {
    TaskState.QUEUED -> RuntimeBackgroundTaskState.QUEUED
    TaskState.RUNNING -> RuntimeBackgroundTaskState.RUNNING
    TaskState.COMPLETED -> RuntimeBackgroundTaskState.COMPLETED
    TaskState.CANCELLED -> RuntimeBackgroundTaskState.CANCELLED
}

private fun TaskRunResult.toRuntimeResult(
    task: io.toolbox.core.data.BackgroundTask,
): RuntimeBackgroundTaskRunResult {
    val payload = payloadJson?.let { encoded ->
        runCatching { JSONTokener(encoded).nextValue() as? JSONObject }.getOrNull()
    }
    return RuntimeBackgroundTaskRunResult(
        taskId = taskId,
        outcome = when (outcome) {
            RunOutcome.SUCCEEDED -> RuntimeBackgroundRunOutcome.SUCCEEDED
            RunOutcome.FAILED -> RuntimeBackgroundRunOutcome.FAILED
            RunOutcome.CANCELLED -> RuntimeBackgroundRunOutcome.CANCELLED
        },
        completedAt = completedAt,
        status = payload?.takeIf { task.operation == io.toolbox.core.data.BackgroundOperation.HTTP_GET }
            ?.optInt("statusCode")
            ?.takeIf { it > 0 },
        body = payload?.takeIf { task.operation == io.toolbox.core.data.BackgroundOperation.HTTP_GET }
            ?.optString("body")
            ?.takeIf { it.isNotEmpty() },
        error = errorCode?.let { code ->
            RuntimeBackgroundTaskError(code.toRuntimeErrorCode(), "后台任务未成功完成。")
        },
    )
}

private fun String.toRuntimeErrorCode(): RuntimeRpcErrorCode = when {
    this == "CANCELLED" -> RuntimeRpcErrorCode.CANCELLED
    this == "NOT_FOUND" -> RuntimeRpcErrorCode.NOT_FOUND
    startsWith("DUPLICATE") -> RuntimeRpcErrorCode.DUPLICATE_TASK
    startsWith("NETWORK") || startsWith("HTTP_") || this == "REDIRECTS_DISABLED" -> RuntimeRpcErrorCode.NETWORK_BLOCKED
    this == "SYSTEM_PERMISSION_DENIED" -> RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED
    this == "INVALID_INPUT" || this == "INVALID_NOTIFICATION" || this == "INTERVAL_TOO_SHORT" ->
        RuntimeRpcErrorCode.INVALID_REQUEST
    this == "TASK_QUOTA_EXCEEDED" || this == "PERIODIC_TASK_QUOTA_EXCEEDED" ->
        RuntimeRpcErrorCode.QUOTA_EXCEEDED
    this == "BACKGROUND_NOT_ALLOWED" || this == "NOTIFICATIONS_NOT_ALLOWED" ->
        RuntimeRpcErrorCode.PERMISSION_DENIED
    else -> RuntimeRpcErrorCode.INTERNAL_ERROR
}
