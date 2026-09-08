package io.toolbox.host.runtime

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkDomainHandler
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Removes domain approvals saved by older hosts; they no longer control access. */
internal class UserNetworkDomainStore(private val repository: ToolKvRepository) {
    suspend fun clear(toolId: String) {
        NetworkDomainInvalidation.cancel(toolId)
        when (repository.remove(toolId, "toolbox.host.v1.network.domains")) {
            is DataResult.Success, is DataResult.Failure.NotFound -> Unit
            is DataResult.Failure -> throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "历史网络权限数据未清除，请重试。")
        }
    }
}

/** Cancels active network streams when the network grant is disabled or the tool is removed. */
internal object NetworkDomainInvalidation {
    private val listeners = ConcurrentHashMap<String, ConcurrentHashMap<Any, () -> Unit>>()
    fun register(toolId: String, owner: Any, cancel: () -> Unit) { listeners.getOrPut(toolId) { ConcurrentHashMap() }[owner] = cancel }
    fun unregister(toolId: String, owner: Any) { listeners[toolId]?.remove(owner) }
    fun cancel(toolId: String) { listeners[toolId]?.values?.forEach { it() } }
}

/** Legacy API adapter. Network permission is the sole destination authorization. */
internal class HostNetworkDomainHandler(
    /** Checks the latest installed version, network declaration and network grant. */
    private val validate: suspend () -> Long,
) : RuntimeNetworkDomainHandler {
    private val active = AtomicBoolean(true)
    fun close() { active.set(false) }

    override suspend fun authorizeDomain(domain: String): Boolean {
        checkAccess()
        return true
    }

    override suspend fun listDomains(): List<String> {
        checkAccess()
        return emptyList()
    }

    private suspend fun checkAccess() {
        checkSession()
        validate()
        checkSession()
    }

    private fun checkSession() {
        if (!active.get()) throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "工具会话已结束。")
    }
}
