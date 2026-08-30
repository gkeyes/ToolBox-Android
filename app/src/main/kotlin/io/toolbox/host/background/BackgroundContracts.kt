package io.toolbox.host.background

import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

internal const val MAX_ACTIVE_TASKS_PER_TOOL = 8
internal const val MAX_PERIODIC_TASKS_PER_TOOL = 4
internal const val MIN_PERIODIC_INTERVAL_MINUTES = 15L
internal const val MAX_RESULT_BYTES = 256 * 1024
internal const val TASK_RESULT_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L

internal object BackgroundRetryPolicy {
    const val MAX_RETRIES = 3

    fun shouldRetry(attempt: Int): Boolean = attempt in 1..MAX_RETRIES
}

internal data class BackgroundRunCompletion(
    val nextState: TaskState,
    val nextRunAt: Long?,
)

internal fun BackgroundTask.completionAfterRun(nowMillis: Long): BackgroundRunCompletion =
    if (periodic) {
        BackgroundRunCompletion(
            nextState = TaskState.QUEUED,
            nextRunAt = intervalMinutes?.let { nowMillis + it * 60_000L },
        )
    } else {
        BackgroundRunCompletion(nextState = TaskState.COMPLETED, nextRunAt = null)
    }

internal enum class BackgroundReconciliationAction {
    SCHEDULE_QUEUED,
    REQUEUE_RUNNING_AND_SCHEDULE,
}

internal fun BackgroundTask.reconciliationAction(
    hasUnfinishedWork: Boolean,
): BackgroundReconciliationAction? = when {
    hasUnfinishedWork -> null
    state == TaskState.QUEUED -> BackgroundReconciliationAction.SCHEDULE_QUEUED
    state == TaskState.RUNNING -> BackgroundReconciliationAction.REQUEUE_RUNNING_AND_SCHEDULE
    else -> null
}

data class BackgroundExecutionPolicy(
    val toolId: String,
    val versionCode: Int,
    val backgroundEnabled: Boolean,
    val backgroundDeclared: Boolean,
    val backgroundGranted: Boolean,
    val networkDeclared: Boolean,
    val networkGranted: Boolean,
    val notificationsDeclared: Boolean,
    val notificationsGranted: Boolean,
    val notificationSystemPermissionGranted: Boolean,
    val allowedNetworkHosts: Set<String>,
    val allowNetworkRedirects: Boolean,
    val networkTimeoutMillis: Long,
    val maxNetworkResponseBytes: Int,
) {
    val canRunBackground: Boolean
        get() = backgroundEnabled && backgroundDeclared && backgroundGranted
}

fun interface BackgroundAuthorization {
    suspend fun policyFor(toolId: String, versionCode: Int): BackgroundExecutionPolicy?
}

data class BackgroundManifestPolicy(
    val toolId: String,
    val versionCode: Int,
    val declaredCapabilities: Set<String>,
    val networkHosts: Set<String>,
    val allowNetworkRedirects: Boolean,
    val networkTimeoutMillis: Long,
    val maxNetworkResponseBytes: Int,
)

fun interface BackgroundManifestPolicyResolver {
    suspend fun resolve(toolId: String, versionCode: Int): BackgroundManifestPolicy?
}

fun interface BackgroundNotificationPermissionChecker {
    fun isGranted(): Boolean
}

class RepositoryBackgroundAuthorization(
    private val repositories: CoreDataRepositories,
    private val manifestResolver: BackgroundManifestPolicyResolver,
    private val notificationPermission: BackgroundNotificationPermissionChecker,
) : BackgroundAuthorization {
    override suspend fun policyFor(toolId: String, versionCode: Int): BackgroundExecutionPolicy? {
        val installed = repositories.catalog.observeTool(toolId).first() ?: return null
        if (installed.currentVersion.versionCode != versionCode) return null
        val manifest = manifestResolver.resolve(toolId, versionCode) ?: return null
        if (manifest.toolId != toolId || manifest.versionCode != versionCode) return null
        val grants = repositories.grants.observeGrants(toolId).first()
            .associate { it.capability to it.granted }
        val declared = manifest.declaredCapabilities
        val settings = repositories.settings.settings.first()

        return BackgroundExecutionPolicy(
            toolId = toolId,
            versionCode = versionCode,
            backgroundEnabled = settings.backgroundEnabled,
            backgroundDeclared = "background.tasks" in declared,
            backgroundGranted = grants["background.tasks"] == true,
            networkDeclared = "network" in declared,
            networkGranted = grants["network"] == true,
            notificationsDeclared = "notifications" in declared,
            notificationsGranted = grants["notifications"] == true,
            notificationSystemPermissionGranted = notificationPermission.isGranted(),
            allowedNetworkHosts = manifest.networkHosts,
            allowNetworkRedirects = manifest.allowNetworkRedirects,
            networkTimeoutMillis = manifest.networkTimeoutMillis,
            maxNetworkResponseBytes = manifest.maxNetworkResponseBytes,
        )
    }
}

interface BackgroundNotificationGateway {
    suspend fun post(toolId: String, notificationId: String, title: String, body: String): NotificationResult
    suspend fun cancel(toolId: String, notificationId: String)
    suspend fun cancelTool(toolId: String)
}

sealed interface NotificationResult {
    data object Posted : NotificationResult
    data class Rejected(val errorCode: String) : NotificationResult
}

fun interface BackgroundClock {
    fun nowMillis(): Long
}

data class BackgroundWorkerDependencies(
    val repositories: CoreDataRepositories,
    val authorization: BackgroundAuthorization,
    val networkProxy: ToolNetworkProxy,
    val notifications: BackgroundNotificationGateway,
    val clock: BackgroundClock = BackgroundClock(System::currentTimeMillis),
)

sealed interface BackgroundTaskRequest {
    val key: String

    data class HttpGet(
        override val key: String,
        val url: String,
        val allowRedirects: Boolean = false,
    ) : BackgroundTaskRequest

    data class Notify(
        override val key: String,
        val title: String,
        val body: String,
        val notificationId: String = key,
    ) : BackgroundTaskRequest
}

sealed interface EnqueueResult {
    data class Enqueued(val taskId: String) : EnqueueResult
    data class Rejected(val errorCode: String) : EnqueueResult
}

interface BackgroundTaskReader {
    fun tasks(toolId: String): Flow<List<BackgroundTask>>
    fun result(taskId: String): Flow<TaskRunResult?>
}

@Serializable
internal data class StoredBackgroundSpec(
    val url: String? = null,
    val title: String? = null,
    val body: String? = null,
    val notificationId: String? = null,
    val allowRedirects: Boolean = false,
)

interface BackgroundWorkerDependencyOwner {
    fun backgroundWorkerDependencies(): BackgroundWorkerDependencies
}

class BackgroundWorkerDependencyRegistry(
    factory: () -> BackgroundWorkerDependencies,
) : BackgroundWorkerDependencyOwner {
    private val dependencies = lazy(LazyThreadSafetyMode.SYNCHRONIZED, factory)

    override fun backgroundWorkerDependencies(): BackgroundWorkerDependencies = dependencies.value
}
