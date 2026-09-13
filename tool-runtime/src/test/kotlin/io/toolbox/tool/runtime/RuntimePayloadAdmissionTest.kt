package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePayloadAdmissionTest {
    private val identity = RuntimeSessionIdentity(
        "io.toolbox.test", 1, "generation", "test", "nonce", "https://test.invalid",
        setOf("files.save", "clipboard.write", "share"),
    )
    private val authorization = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = true
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }
    private fun request(method: String, params: Map<String, RpcValue>) = RuntimeRpcRequest(
        "payload-test", method, identity.nonce, identity.toolId, identity.versionCode,
        identity.generation, RpcValue.ObjectValue(params), 256,
    )

    @Test
    fun filesAndHashUseNegotiatedEnvelopeRatherThanOneMiB() = runTest {
        val content = "x".repeat(2 * 1024 * 1024)
        var savedBytes = 0
        val files = object : RuntimeFilesHandler {
            override suspend fun open(mimeTypes: List<String>): RuntimeFileToken? = error("unused")
            override suspend fun save(suggestedName: String, mimeType: String, content: ByteArray): RuntimeFileToken? {
                savedBytes = content.size
                return null
            }
            override suspend fun capabilityFor(token: String) = ToolBoxCapabilityId.FILES_SAVE
            override suspend fun consume(token: String, maxBytes: Int): ByteArray = error("unused")
        }
        val dispatcher = RuntimeRpcDispatcher(identity, authorization, RuntimeM1Handlers(),
            m3Handlers = RuntimeM3Handlers(files = files), maxResponseBytes = 4 * 1024 * 1024)
        val inbound = RuntimeInboundContext(identity.exactOrigin, true, 1)
        val save = request("files.save", mapOf("suggestedName" to RpcValue.StringValue("large.txt"),
            "mimeType" to RpcValue.StringValue("text/plain"), "content" to RpcValue.StringValue(content)))
        assertTrue(dispatcher.dispatch(save, inbound) is RuntimeRpcResponse.Success)
        assertEquals(content.length, savedBytes)
        assertTrue(dispatcher.dispatch(request("crypto.sha256", mapOf("value" to RpcValue.StringValue(content))), inbound) is RuntimeRpcResponse.Success)
        val tooLarge = save.copy(params = RpcValue.ObjectValue(save.params.value + ("content" to RpcValue.StringValue("x".repeat(4 * 1024 * 1024 + 1)))))
        assertTrue(dispatcher.dispatch(tooLarge, inbound) is RuntimeRpcResponse.Failure)
        assertEquals(content.length, savedBytes)
    }

    @Test
    fun clipboardAndShareDoNotHaveAnAdditionalSixtyFourKTextQuota() = runTest {
        val text = "x".repeat(64 * 1024 + 1)
        var copied = ""
        var shared = ""
        val dispatcher = RuntimeRpcDispatcher(identity, authorization,
            RuntimeM1Handlers(clipboardWrite = RuntimeClipboardWriteHandler { copied = it }),
            m3Handlers = RuntimeM3Handlers(shareText = RuntimeShareTextHandler { shared = it }))
        val inbound = RuntimeInboundContext(identity.exactOrigin, true, 1)
        val params = mapOf("text" to RpcValue.StringValue(text))
        assertTrue(dispatcher.dispatch(request("clipboard.writeText", params), inbound) is RuntimeRpcResponse.Success)
        assertTrue(dispatcher.dispatch(request("share.text", params), inbound) is RuntimeRpcResponse.Success)
        assertEquals(text, copied)
        assertEquals(text, shared)
    }
}
