package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeToastForegroundTest {
    private val identity = RuntimeSessionIdentity("io.example.toast", 1, "generation", "0.8.0", "nonce",
        "https://toast.toolbox.invalid", emptySet())
    private val shown = mutableListOf<String>()
    private val allowAll = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = true
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }

    private fun dispatcher(guard: suspend () -> Unit) = RuntimeRpcDispatcher(
        identity, allowAll, RuntimeCoreHandlers(toast = RuntimeToastHandler { shown += it }),
        foregroundInteractionGuard = guard,
    )

    private suspend fun RuntimeRpcDispatcher.toast(message: String) = dispatch(
        RuntimeRpcRequest("toast-1", "ui.toast", identity.nonce, identity.toolId, 1, identity.generation,
            RpcValue.ObjectValue(mapOf("message" to RpcValue.StringValue(message))), 100),
        RuntimeInboundContext(identity.exactOrigin, true),
    )

    @Test fun foregroundToolShowsToast() = runBlocking {
        assertTrue(dispatcher {}.toast("saved") is RuntimeRpcResponse.Success)
        assertEquals(listOf("saved"), shown)
    }

    @Test fun backgroundToolToastIsSkippedWithoutError() = runBlocking {
        val hidden = dispatcher { throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "hidden") }
        assertTrue(hidden.toast("saved") is RuntimeRpcResponse.Success)
        assertEquals(emptyList<String>(), shown)
    }

    @Test fun endedSessionStillFails() = runBlocking {
        val ended = dispatcher { throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "ended") }
        val result = ended.toast("saved") as RuntimeRpcResponse.Failure
        assertEquals(RuntimeRpcErrorCode.INVALID_SESSION, result.error.code)
        assertEquals(emptyList<String>(), shown)
    }
}
