package io.toolbox.host.background

import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.TaskRunResult
import io.toolbox.host.HostBackgroundOperations
import io.toolbox.host.HostPermissionSideEffects
import kotlinx.coroutines.flow.Flow

internal class BackgroundHostOperations(
    private val coordinator: BackgroundTaskCoordinator,
) : HostBackgroundOperations, HostPermissionSideEffects {
    override fun observeTasks(toolId: String): Flow<List<BackgroundTask>> = coordinator.tasks(toolId)

    override fun observeResult(taskId: String): Flow<TaskRunResult?> = coordinator.result(taskId)

    override suspend fun cancel(toolId: String, taskId: String): Boolean = coordinator.cancel(toolId, taskId)

    override suspend fun cancelTool(toolId: String) = coordinator.cancelTool(toolId)

    override suspend fun cancelAll(toolIds: Collection<String>) = coordinator.cancelAll(toolIds)

    override suspend fun onCapabilityDisabled(toolId: String, capability: String) =
        coordinator.revokeCapability(toolId, capability)
}
