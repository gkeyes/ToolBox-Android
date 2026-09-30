package io.toolbox.host.background

import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.TaskRunResult
import io.toolbox.host.HostBackgroundOperations
import io.toolbox.host.HostPermissionSideEffects
import kotlinx.coroutines.flow.Flow

internal class BackgroundHostOperations(
    private val coordinator: BackgroundTaskCoordinator,
) : HostBackgroundOperations, HostPermissionSideEffects {
    override fun observeActiveTasks(toolId: String): Flow<List<BackgroundTask>> = coordinator.activeTasks(toolId)

    override fun observeRecentHistory(toolId: String): Flow<io.toolbox.core.data.BackgroundTaskHistoryPage> = coordinator.recentHistory(toolId)

    override fun observeHistoryThrough(toolId: String, createdAt: Long, taskId: String): Flow<List<BackgroundTask>> =
        coordinator.historyThrough(toolId, createdAt, taskId)

    override suspend fun historyBefore(toolId: String, createdAt: Long, taskId: String): io.toolbox.core.data.BackgroundTaskHistoryPage =
        coordinator.historyBefore(toolId, createdAt, taskId)

    override fun observeResult(taskId: String): Flow<TaskRunResult?> = coordinator.result(taskId)

    override suspend fun cancel(toolId: String, taskId: String) = coordinator.cancel(toolId, taskId)

    override suspend fun cancelTool(toolId: String) = coordinator.cancelTool(toolId)

    override suspend fun cancelAll(toolIds: Collection<String>) = coordinator.cancelAll(toolIds)

    override suspend fun onCapabilityDisabled(toolId: String, capability: String) =
        coordinator.revokeCapability(toolId, capability)
}
