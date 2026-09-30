package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RuntimeNetworkMediaRpcTest {
    private val identity = RuntimeSessionIdentity("com.example.media", 1, "generation", "0.8.0", "nonce",
        "https://media.toolbox.invalid/", setOf("network"))
    private val sessionId = "media-" + "a".repeat(32)
    private val sourceUrl = "https://cdn.example.test/audio.mp3"
    private val playerUrl = identity.exactOrigin + ".toolbox/media/" + "b".repeat(32)
    private var current = true
    private var granted = true
    private var systemPermission = true
    private var grantChecks = 0
    private var systemChecks = 0
    private val authorization = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = current
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId): Boolean {
            grantChecks++
            return granted
        }
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>): Boolean {
            systemChecks++
            return systemPermission
        }
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }

    private open inner class Network : RuntimeNetworkHandler {
        val opened = mutableListOf<Triple<String, String, String?>>()
        val closed = mutableListOf<String>()
        var cancelled = 0
        var response = RuntimeNetworkMediaSession(sessionId, playerUrl)
        override suspend fun request(request: RuntimeNetworkRequest): RuntimeNetworkResponse = error("Unexpected full request")
        override suspend fun openMedia(sessionId: String, url: String, kind: String?): RuntimeNetworkMediaSession {
            opened += Triple(sessionId, url, kind)
            return response
        }
        override suspend fun closeMedia(sessionId: String) { closed += sessionId }
        override fun cancelStreams() { cancelled++ }
    }

    @Test
    fun opensMediaForEachKindWithAnIndependentOpaqueRoute() = runBlocking {
        val network = Network()
        val dispatcher = dispatcher(network)
        listOf(null, "audio", "video").forEach { kind ->
            val params = openParams() + (kind?.let { mapOf("kind" to text(it)) } ?: emptyMap())
            val response = dispatch(dispatcher, "network.openMedia", params) as RuntimeRpcResponse.Success
            assertEquals(RpcValue.ObjectValue(mapOf("sessionId" to text(sessionId), "url" to text(playerUrl))), response.result)
            assertEquals(Triple(sessionId, sourceUrl, kind), network.opened.last())
        }
        assertTrue(network.closed.isEmpty())
    }

    @Test
    fun rejectsInvalidIdsUrlsKindsAndExtraParametersBeforeOpening() = runBlocking {
        val network = Network()
        val dispatcher = dispatcher(network)
        val invalid = listOf(
            openParams() - "sessionId",
            openParams() + ("sessionId" to text("media-" + "A".repeat(32))),
            openParams() + ("sessionId" to text("media-" + "a".repeat(31))),
            openParams() + ("sessionId" to text("stream-" + "a".repeat(32))),
            openParams() + ("sessionId" to RpcValue.Number(1.0)),
            openParams() + ("url" to text("http://cdn.example.test/audio.mp3")),
            openParams() + ("url" to text("https://user:password@cdn.example.test/audio.mp3")),
            openParams() + ("url" to text("https:///audio.mp3")),
            openParams() + ("url" to text("https://cdn.example.test/bad path")),
            openParams() + ("url" to text(" https://cdn.example.test/audio.mp3")),
            openParams() + ("url" to text("https://cdn.example.test/audio.mp3\n")),
            openParams() + ("url" to RpcValue.Null),
            openParams() + ("kind" to text("Audio")),
            openParams() + ("kind" to text("")),
            openParams() + ("kind" to RpcValue.Null),
            openParams() + ("headers" to RpcValue.ObjectValue(emptyMap())),
        )
        invalid.forEach { assertFailure(RuntimeRpcErrorCode.INVALID_REQUEST, dispatch(dispatcher, "network.openMedia", it)) }
        assertTrue(network.opened.isEmpty())
        assertTrue(network.closed.isEmpty())
    }

    @Test
    fun rejectsForgedSessionResponsesAndClosesTheRequestedSession() = runBlocking {
        val network = Network()
        val dispatcher = dispatcher(network)
        val invalidUrls = listOf(
            "https://other.toolbox.invalid/.toolbox/media/" + "b".repeat(32),
            identity.exactOrigin + ".toolbox/media/" + "B".repeat(32),
            identity.exactOrigin + ".toolbox/media/" + "b".repeat(31),
            playerUrl + "/audio.mp3",
            playerUrl + "?url=https://cdn.example.test/audio.mp3",
            playerUrl + "#fragment",
            playerUrl.replace("https://", "https://user@"),
            playerUrl.replace("https://", "http://"),
            playerUrl.replace(".invalid/", ".invalid:443/"),
            "malformed player URL",
        )
        invalidUrls.forEach { url ->
            network.response = RuntimeNetworkMediaSession(sessionId, url)
            assertFailure(RuntimeRpcErrorCode.INVALID_REQUEST, dispatch(dispatcher, "network.openMedia", openParams()))
            assertEquals(sessionId, network.closed.last())
        }
        network.response = RuntimeNetworkMediaSession("media-" + "c".repeat(32), playerUrl)
        assertFailure(RuntimeRpcErrorCode.INVALID_REQUEST, dispatch(dispatcher, "network.openMedia", openParams()))
        assertEquals(invalidUrls.size + 1, network.closed.size)
        assertTrue(network.closed.all { it == sessionId })
    }

    @Test
    fun authorizationLossAfterOpenClosesMediaBeforeDelivering() = runBlocking {
        listOf(RuntimeRpcErrorCode.INVALID_SESSION, RuntimeRpcErrorCode.PERMISSION_DENIED, RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED).forEach { code ->
            current = true
            granted = true
            systemPermission = true
            val network = object : Network() {
                override suspend fun openMedia(sessionId: String, url: String, kind: String?): RuntimeNetworkMediaSession {
                    val session = super.openMedia(sessionId, url, kind)
                    when (code) {
                        RuntimeRpcErrorCode.INVALID_SESSION -> current = false
                        RuntimeRpcErrorCode.PERMISSION_DENIED -> granted = false
                        else -> systemPermission = false
                    }
                    return session
                }
            }
            assertFailure(code, dispatch(dispatcher(network), "network.openMedia", openParams()))
            assertEquals(listOf(sessionId), network.closed)
            assertEquals(1, network.cancelled)
        }
    }

    @Test
    fun closeStillRunsAfterGrantAndSystemPermissionRevocation() = runBlocking {
        granted = false
        systemPermission = false
        val network = Network()
        assertTrue(dispatch(dispatcher(network), "network.closeMedia", mapOf("sessionId" to text(sessionId))) is RuntimeRpcResponse.Success)
        assertEquals(listOf(sessionId), network.closed)
        assertEquals(0, grantChecks)
        assertEquals(0, systemChecks)
    }

    @Test
    fun closingPreservesCurrentIdentityMainFrameAndOriginChecks() = runBlocking {
        val network = Network()
        val dispatcher = dispatcher(network)
        val request = request("network.closeMedia", mapOf("sessionId" to text(sessionId)))
        assertFailure(RuntimeRpcErrorCode.INVALID_SESSION, dispatcher.dispatch(request.copy(nonce = "other"), inbound()))
        assertFailure(RuntimeRpcErrorCode.WRONG_ORIGIN, dispatcher.dispatch(request, RuntimeInboundContext("https://other.toolbox.invalid/", true)))
        assertFailure(RuntimeRpcErrorCode.NOT_MAIN_FRAME, dispatcher.dispatch(request, RuntimeInboundContext(identity.exactOrigin, false)))
        current = false
        assertFailure(RuntimeRpcErrorCode.INVALID_SESSION, dispatcher.dispatch(request, inbound()))
        assertTrue(network.closed.isEmpty())
    }

    @Test
    fun closingRejectsExtraParametersAndInvalidSessionIds() = runBlocking {
        granted = false
        val network = Network()
        val dispatcher = dispatcher(network)
        listOf(emptyMap(), mapOf("sessionId" to text("media-short")),
            mapOf("sessionId" to text(sessionId), "url" to text(sourceUrl))).forEach {
            assertFailure(RuntimeRpcErrorCode.INVALID_REQUEST, dispatch(dispatcher, "network.closeMedia", it))
        }
        assertTrue(network.closed.isEmpty())
    }

    @Test
    fun openFailureClosesMediaWithoutMaskingItsError() = runBlocking {
        val network = object : Network() {
            override suspend fun openMedia(sessionId: String, url: String, kind: String?): RuntimeNetworkMediaSession =
                throw RuntimeHandlerException(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, "Source unavailable")
            override suspend fun closeMedia(sessionId: String) {
                super.closeMedia(sessionId)
                throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Already closed")
            }
        }
        assertFailure(RuntimeRpcErrorCode.NETWORK_UNAVAILABLE, dispatch(dispatcher(network), "network.openMedia", openParams()))
        assertEquals(listOf(sessionId), network.closed)
    }

    @Test
    fun coroutineCancellationDuringPostOpenCheckCompletesCleanup() = runBlocking {
        val checking = CompletableDeferred<Unit>()
        val network = object : Network() {
            override suspend fun closeMedia(sessionId: String) {
                delay(1)
                super.closeMedia(sessionId)
            }
        }
        val policy = object : RuntimeAuthorizationPolicy by authorization {
            override suspend fun isCurrent(identity: RuntimeSessionIdentity): Boolean {
                if (network.opened.isNotEmpty()) {
                    checking.complete(Unit)
                    awaitCancellation()
                }
                return true
            }
        }
        val pending = async { dispatch(dispatcher(network, policy), "network.openMedia", openParams()) }
        try {
            withTimeout(2_000) { checking.await() }
            pending.cancelAndJoin()
            assertEquals(listOf(sessionId), network.closed)
        } finally { pending.cancelAndJoin() }
    }

    private fun dispatcher(network: RuntimeNetworkHandler, policy: RuntimeAuthorizationPolicy = authorization) =
        RuntimeRpcDispatcher(identity, policy, RuntimeM1Handlers(), RuntimeM2Handlers(network = network))
    private fun text(value: String) = RpcValue.StringValue(value)
    private fun openParams(): Map<String, RpcValue> = mapOf("sessionId" to text(sessionId), "url" to text(sourceUrl))
    private fun request(method: String, params: Map<String, RpcValue>) = RuntimeRpcRequest("request-id", method,
        identity.nonce, identity.toolId, identity.versionCode, identity.generation, RpcValue.ObjectValue(params), 512)
    private fun inbound() = RuntimeInboundContext(identity.exactOrigin, true)
    private suspend fun dispatch(dispatcher: RuntimeRpcDispatcher, method: String, params: Map<String, RpcValue>) =
        dispatcher.dispatch(request(method, params), inbound())
    private fun assertFailure(code: RuntimeRpcErrorCode, response: RuntimeRpcResponse) {
        assertTrue(response is RuntimeRpcResponse.Failure)
        assertEquals(code, (response as RuntimeRpcResponse.Failure).error.code)
    }
}
