package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeImageShareRpcTest {
    private val identity = RuntimeSessionIdentity(
        "io.example.share", 1, "generation", "0.8.0", "nonce",
        "https://image.toolbox.invalid", setOf("share"),
    )
    private val transferId = RpcValue.StringValue("image-" + "f".repeat(32))
    private var granted = true
    private var foreground = true
    private val chunks = mutableListOf<ByteArray>()
    private var started = 0
    private var completed = 0
    private var cancelled = 0
    private var beforeFinishGuard: () -> Unit = {}
    private val dispatcher = RuntimeRpcDispatcher(identity, object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = granted
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }, RuntimeCoreHandlers(), systemHandlers = RuntimeSystemHandlers(shareImage = object : RuntimeImageShareHandler {
        override suspend fun begin(id: String, format: RuntimeImageShareFormat, length: Int) { started++ }
        override suspend fun write(id: String, offset: Int, bytes: ByteArray) { chunks += bytes }
        override suspend fun finish(id: String, beforeLaunch: suspend () -> Unit) {
            beforeFinishGuard()
            beforeLaunch()
            completed++
        }
        override suspend fun cancel(id: String) { cancelled++ }
        override fun resetForNavigation() = Unit
        override fun close() = Unit
    }), foregroundInteractionGuard = {
        if (!foreground) throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "background")
    })

    @Test fun uploadRejectsPathsOversizeAndNonBinaryParameters() = runBlocking {
        assertFailure(RuntimeRpcErrorCode.INVALID_REQUEST, call("begin", "url" to RpcValue.StringValue("https://example.test/photo.png"), *beginArgs()))
        assertFailure(RuntimeRpcErrorCode.INVALID_MIME, call("begin", *beginArgs(mime = "image/svg+xml")))
        assertFailure(RuntimeRpcErrorCode.QUOTA_EXCEEDED, call("begin", *beginArgs(length = RUNTIME_IMAGE_SHARE_MAX_BYTES + 1)))
        assertTrue(call("begin", *beginArgs()) is RuntimeRpcResponse.Success)
        assertEquals(1, started)
        val data = byteArrayOf(0, 1, -1)
        assertFailure(RuntimeRpcErrorCode.INVALID_REQUEST, call("write", "offset" to RpcValue.Number(0.0), "content" to RpcValue.StringValue("abc")))
        assertFailure(RuntimeRpcErrorCode.INVALID_REQUEST, call("write", "offset" to RpcValue.Number(0.0), "content" to binary(ByteArray(IMAGE_SHARE_CHUNK_BYTES + 1))))
        assertTrue(call("write", "offset" to RpcValue.Number(0.0), "content" to binary(data)) is RuntimeRpcResponse.Success)
        assertArrayEquals(data, chunks.single())
    }

    @Test fun latePermissionAndVisibilityChecksProtectChooserButAllowCleanup() = runBlocking {
        beforeFinishGuard = { granted = false }
        assertFailure(RuntimeRpcErrorCode.PERMISSION_DENIED, call("finish"))
        granted = true
        beforeFinishGuard = { foreground = false }
        assertFailure(RuntimeRpcErrorCode.SESSION_ENDED, call("finish"))
        assertEquals(0, completed)
        granted = false
        assertTrue(call("cancel") is RuntimeRpcResponse.Success)
        assertEquals(1, cancelled)
        foreground = true
        granted = true
        beforeFinishGuard = {}
        assertTrue(call("finish") is RuntimeRpcResponse.Success)
        assertEquals(1, completed)
    }

    private fun beginArgs(mime: String = "image/png", length: Int = 16) = arrayOf(
        "mimeType" to RpcValue.StringValue(mime), "length" to RpcValue.Number(length.toDouble()),
    )

    private fun binary(data: ByteArray) = RpcValue.ObjectValue(
        mapOf("base64" to RpcValue.StringValue(Base64.getEncoder().encodeToString(data))),
    )

    private suspend fun call(method: String, vararg params: Pair<String, RpcValue>): RuntimeRpcResponse =
        dispatcher.dispatch(
            RuntimeRpcRequest("request-1", "share.image.$method", identity.nonce, identity.toolId, 1, identity.generation,
                RpcValue.ObjectValue(mapOf("transferId" to transferId, *params)), 100),
            RuntimeInboundContext(identity.exactOrigin, true),
        )

    private fun assertFailure(code: RuntimeRpcErrorCode, response: RuntimeRpcResponse) {
        assertTrue("Expected $code, received $response", response is RuntimeRpcResponse.Failure)
        assertEquals(code, (response as RuntimeRpcResponse.Failure).error.code)
    }
}
