package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeTextCompatibilityTest {
    private val identity = RuntimeSessionIdentity("io.example.text", 1, "generation", "0.8.0", "nonce",
        "https://text.toolbox.invalid", setOf("clipboard.write", "share", "notifications", "storage"))
    private val received = mutableListOf<String>()
    private val dispatcher = RuntimeRpcDispatcher(identity, object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = true
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }, RuntimeM1Handlers(clipboardWrite = RuntimeClipboardWriteHandler { received += it }),
        m2Handlers = RuntimeM2Handlers(notifications = object : RuntimeNotificationHandler {
            override suspend fun post(notificationId: String, title: String, body: String) { received += body }
            override suspend fun cancel(notificationId: String) = Unit
        }), m3Handlers = RuntimeM3Handlers(shareText = RuntimeShareTextHandler { received += it }), foregroundInteractionGuard = {})

    @Test fun emptyAndMultilineTextReachRealDispatchHandlers() = runBlocking {
        listOf("", "line one\nline two\t\"quoted\"").forEach { text ->
            listOf("clipboard.writeText", "share.text").forEach { method ->
                assertTrue(call(method, mapOf("text" to RpcValue.StringValue(text))) is RuntimeRpcResponse.Success)
                assertEquals(text, received.last())
            }
            listOf("notifications.post", "notifications.update").forEach { method ->
                assertTrue(call(method, mapOf("id" to RpcValue.StringValue("message"), "title" to RpcValue.StringValue("Title"),
                    "body" to RpcValue.StringValue(text))) is RuntimeRpcResponse.Success)
                assertEquals(text, received.last())
            }
        }
    }
    @Test fun stringTypeAndIdentifierValidationRemain() = runBlocking {
        assertInvalid(call("clipboard.writeText", mapOf("text" to RpcValue.Number(0.0))))
        assertInvalid(call("clipboard.writeText", emptyMap()))
        assertInvalid(call("notifications.post", mapOf("id" to RpcValue.StringValue("bad\nid"), "title" to RpcValue.StringValue("Title"), "body" to RpcValue.StringValue(""))))
    }
    private suspend fun call(method: String, values: Map<String, RpcValue>) = dispatcher.dispatch(
        RuntimeRpcRequest("request-1", method, identity.nonce, identity.toolId, 1, identity.generation, RpcValue.ObjectValue(values), 100),
        RuntimeInboundContext(identity.exactOrigin, true))
    private fun assertInvalid(result: RuntimeRpcResponse) {
        assertEquals(RuntimeRpcErrorCode.INVALID_REQUEST, (result as RuntimeRpcResponse.Failure).error.code)
    }
}
