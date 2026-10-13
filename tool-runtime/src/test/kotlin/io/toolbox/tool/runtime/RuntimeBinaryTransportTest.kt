package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeBinaryTransportTest {
    private val identity = RuntimeSessionIdentity(
        toolId = "com.example.binary",
        versionCode = 1,
        generation = "generation",
        hostVersion = "0.8.32",
        nonce = "nonce",
        exactOrigin = "https://binary.toolbox.invalid/",
        declaredCapabilities = emptySet(),
    )
    private val allowAll = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = true
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) =
            RuntimePolicyDecision.Allowed
    }
    // SHA-256 of the bytes 01 02 03.
    private val digest = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"

    @Test
    fun base64WrapperAndLegacyNumberArrayHashTheSameBytes() = runBlocking {
        assertEquals(digest, sha256(RpcValue.ObjectValue(mapOf("base64" to RpcValue.StringValue("AQID")))))
        assertEquals(digest, sha256(RpcValue.ArrayValue(listOf(1.0, 2.0, 3.0).map(RpcValue::Number))))
    }

    @Test
    fun malformedBase64IsAnInvalidRequest() = runBlocking {
        val response = dispatch(RpcValue.ObjectValue(mapOf("base64" to RpcValue.StringValue("not base64!"))))
        assertEquals(RuntimeRpcErrorCode.INVALID_REQUEST, (response as RuntimeRpcResponse.Failure).error.code)
    }

    @Test
    fun decoderAcceptsOnlyTheExactWrapperAndChecksSizeBeforeDecoding() {
        assertArrayEquals(byteArrayOf(1, 2, 3), decodeRuntimeBase64Bytes(wrapper("AQID"), 3))
        assertNull(decodeRuntimeBase64Bytes(wrapper("AQIDBAUG"), 3))
        assertNull(decodeRuntimeBase64Bytes(RpcValue.ObjectValue(mapOf(
            "base64" to RpcValue.StringValue("AQID"), "extra" to RpcValue.Null,
        )), 3))
    }

    private fun wrapper(text: String) = RpcValue.ObjectValue(mapOf("base64" to RpcValue.StringValue(text)))

    private suspend fun sha256(value: RpcValue): String {
        val response = dispatch(value) as RuntimeRpcResponse.Success
        return ((response.result as RpcValue.ObjectValue).value.getValue("hex") as RpcValue.StringValue).value
    }

    private suspend fun dispatch(value: RpcValue): RuntimeRpcResponse =
        RuntimeRpcDispatcher(identity, allowAll, RuntimeCoreHandlers()).dispatch(
            RuntimeRpcRequest("request", "crypto.sha256", identity.nonce, identity.toolId, identity.versionCode,
                identity.generation, RpcValue.ObjectValue(mapOf("value" to value)), 100),
            RuntimeInboundContext(identity.exactOrigin, true),
        )
}
