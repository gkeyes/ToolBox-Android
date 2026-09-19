package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeNetworkBudgetTest {
    private val identity = RuntimeSessionIdentity("com.example.network", 1, "generation", "0.8.0", "nonce",
        "https://network.toolbox.invalid/", setOf("network"))
    private var granted = true
    private val authorization = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = true
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId) = granted
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }

    @Test
    fun parserPreservesSafeLongBudgetsAndRejectsUnsafeNumbers() = runBlocking {
        var received: Long? = null
        val dispatcher = dispatcher(RuntimeNetworkHandler { request ->
            received = request.maxResponseBytes
            RuntimeNetworkResponse(200, emptyMap(), "")
        })
        listOf(null, 1.0, 2_147_483_648.0, 9_007_199_254_740_991.0).forEach { budget ->
            val params = linkedMapOf<String, RpcValue>("url" to RpcValue.StringValue("https://example.test"))
            budget?.let { params["maxResponseBytes"] = RpcValue.Number(it) }
            val response = dispatch(dispatcher, "network.request", params)
            assertTrue(response is RuntimeRpcResponse.Success)
            (response as RuntimeRpcResponse.Success).release()
            assertEquals(budget?.toLong(), received)
        }
        listOf(0.0, -1.0, 1.5, 9_007_199_254_740_992.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { budget ->
            val response = dispatch(dispatcher, "network.request", mapOf("url" to RpcValue.StringValue("https://example.test"),
                "maxResponseBytes" to RpcValue.Number(budget)))
            assertEquals(RuntimeRpcErrorCode.INVALID_REQUEST, (response as RuntimeRpcResponse.Failure).error.code)
        }
    }

    @Test
    fun negotiatedChunksRemainBoundedByEnvelopeAndRetainedThroughResponseDelivery() = runBlocking {
        var asked = 0
        var releases = 0
        val network = object : RuntimeNetworkHandler {
            override suspend fun request(request: RuntimeNetworkRequest) = RuntimeNetworkResponse(200, emptyMap(), "")
            override suspend fun readStream(streamId: String, maxChunkBytes: Int): RuntimeNetworkStreamChunk {
                asked = maxChunkBytes
                return RuntimeNetworkStreamChunk(ByteArray(minOf(maxChunkBytes, 32 * 1024)), false, 3_000_000_000) { releases++ }
            }
        }
        val response = dispatch(dispatcher(network), "network.readStream", mapOf(
            "streamId" to RpcValue.StringValue("stream-" + "a".repeat(32)), "expectedChunkBytes" to RpcValue.Number(128 * 1024.0)))
        assertTrue(response is RuntimeRpcResponse.Success)
        assertEquals(128 * 1024, asked)
        assertEquals(0, releases)
        val payload = (response as RuntimeRpcResponse.Success).result as RpcValue.ObjectValue
        assertEquals(RpcValue.Number(3_000_000_000.0), payload.value["receivedBytes"])
        assertTrue(RuntimeRpcJson.encodeResponse(response).isNotEmpty())
        response.release()
        response.release()
        assertEquals(1, releases)
        assertEquals(64 * 1024, runtimeNetworkStreamRawBudget("id", 1024 * 1024))
        assertTrue(runtimeNetworkStreamRawBudget("id", 4096, 9_007_199_254_740_991) < 4096)
    }

    @Test
    fun authorizationLossAfterBodyReadReleasesPayloadAndRejectsResult() = runBlocking {
        var released = false
        val response = dispatch(dispatcher(RuntimeNetworkHandler {
            granted = false
            RuntimeNetworkResponse(200, emptyMap(), "private", release = { released = true })
        }), "network.request", mapOf("url" to RpcValue.StringValue("https://example.test")))
        assertTrue(response is RuntimeRpcResponse.Failure)
        assertTrue(released)
    }

    private fun dispatcher(network: RuntimeNetworkHandler) = RuntimeRpcDispatcher(identity, authorization,
        RuntimeM1Handlers(), RuntimeM2Handlers(network = network))
    private suspend fun dispatch(dispatcher: RuntimeRpcDispatcher, method: String, params: Map<String, RpcValue>) =
        dispatcher.dispatch(RuntimeRpcRequest("request-id", method, identity.nonce, identity.toolId, identity.versionCode,
            identity.generation, RpcValue.ObjectValue(params), 512), RuntimeInboundContext(identity.exactOrigin, true))
}
