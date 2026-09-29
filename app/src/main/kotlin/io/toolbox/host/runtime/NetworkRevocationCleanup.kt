package io.toolbox.host.runtime

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.concurrent.ConcurrentHashMap

/** Cancels active streams and removes obsolete approval data when network access is revoked. */
internal class NetworkRevocationCleanup(private val repository: ToolKvRepository) {
    suspend fun clear(toolId: String) {
        NetworkStreamCancellation.cancel(toolId)
        when (repository.remove(toolId, "toolbox.host.v1.network.domains")) {
            is DataResult.Success, is DataResult.Failure.NotFound -> Unit
            is DataResult.Failure -> throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "历史网络权限数据未清除，请重试。")
        }
    }
}

/** Cancels active network streams when the network grant is disabled or the tool is removed. */
internal object NetworkStreamCancellation {
    private val listeners = ConcurrentHashMap<String, ConcurrentHashMap<Any, () -> Unit>>()
    fun register(toolId: String, owner: Any, cancel: () -> Unit) { listeners.getOrPut(toolId) { ConcurrentHashMap() }[owner] = cancel }
    fun unregister(toolId: String, owner: Any) { listeners[toolId]?.remove(owner) }
    fun cancel(toolId: String) { listeners[toolId]?.values?.forEach { it() } }
}
