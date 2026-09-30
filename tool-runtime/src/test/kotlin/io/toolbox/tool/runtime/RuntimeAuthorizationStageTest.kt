package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAuthorizationStageTest {
    private val identity = RuntimeSessionIdentity(
        toolId = "com.example.authorization",
        versionCode = 1,
        generation = "generation",
        hostVersion = "0.8.27",
        nonce = "nonce",
        exactOrigin = "https://authorization.toolbox.invalid/",
        declaredCapabilities = setOf("clipboard.write", "network"),
    )

    @Test
    fun capabilityCallReadsThreeVersionsAndTwoGrantsAcrossAdmissionAndForegroundDispatch() = runBlocking {
        val calls = mutableListOf<String>()
        val state = RecordingGrantState(calls)
        val dispatcher = dispatcher(state, calls)

        assertTrue(dispatch(dispatcher) is RuntimeRpcResponse.Success)
        assertEquals(listOf("version", "grant", "permission", "admit", "foreground",
            "version", "grant", "permission", "version", "write"), calls)
    }

    @Test
    fun revokedGrantDuringForegroundDispatchPreventsTheWrite() = runBlocking {
        val calls = mutableListOf<String>()
        val state = RecordingGrantState(calls)
        val dispatcher = dispatcher(state, calls) { state.granted = false }

        val response = dispatch(dispatcher) as RuntimeRpcResponse.Failure
        assertEquals(RuntimeRpcErrorCode.PERMISSION_DENIED, response.error.code)
        assertEquals(2, calls.count { it == "version" })
        assertEquals(2, calls.count { it == "grant" })
        assertTrue("A revoked call must not reach the handler", "write" !in calls)
    }

    @Test
    fun replacementDuringSecondGrantReadIsCaughtBeforeTheWrite() = runBlocking {
        val calls = mutableListOf<String>()
        val state = RecordingGrantState(calls)
        state.onGrantRead = { count -> if (count == 2) state.versionCode = 2 }

        val response = dispatch(dispatcher(state, calls)) as RuntimeRpcResponse.Failure
        assertEquals(RuntimeRpcErrorCode.INVALID_SESSION, response.error.code)
        assertEquals(3, calls.count { it == "version" })
        assertTrue("A replaced tool must not reach the handler", "write" !in calls)
    }

    @Test
    fun quotaDenialStopsBeforeTheSecondAuthorizationStage() = runBlocking {
        val calls = mutableListOf<String>()
        val state = RecordingGrantState(calls)
        val response = dispatch(dispatcher(state, calls, quotaAllowed = false)) as RuntimeRpcResponse.Failure

        assertEquals(RuntimeRpcErrorCode.QUOTA_EXCEEDED, response.error.code)
        assertEquals(listOf("version", "grant", "permission", "admit"), calls)
    }

    @Test
    fun capabilityFreeReadyStillRechecksVersionAfterAdmission() = runBlocking {
        val calls = mutableListOf<String>()
        val state = RecordingGrantState(calls)
        val dispatcher = RuntimeRpcDispatcher(identity, policy(state, calls), RuntimeM1Handlers())
        val request = RuntimeRpcRequest("request", "ready", identity.nonce, identity.toolId,
            identity.versionCode, identity.generation, RpcValue.ObjectValue(emptyMap()), 100)

        assertTrue(dispatcher.dispatch(request, RuntimeInboundContext(identity.exactOrigin, true)) is RuntimeRpcResponse.Success)
        assertEquals(listOf("version", "admit", "version"), calls)
    }

    @Test
    fun replacementAfterNetworkReadRejectsDeliveryAndReleasesTheBody() = runBlocking {
        val calls = mutableListOf<String>()
        val state = RecordingGrantState(calls)
        var released = false
        val network = RuntimeNetworkHandler {
            state.versionCode = 2
            RuntimeNetworkResponse(200, emptyMap(), "body", release = { released = true })
        }
        val dispatcher = RuntimeRpcDispatcher(identity, policy(state, calls), RuntimeM1Handlers(),
            RuntimeM2Handlers(network = network))
        val request = RuntimeRpcRequest("request", "network.request", identity.nonce, identity.toolId,
            identity.versionCode, identity.generation,
            RpcValue.ObjectValue(mapOf("url" to RpcValue.StringValue("https://example.test"))), 100)

        val response = dispatcher.dispatch(request, RuntimeInboundContext(identity.exactOrigin, true))
            as RuntimeRpcResponse.Failure
        assertEquals(RuntimeRpcErrorCode.INVALID_SESSION, response.error.code)
        assertTrue(released)
    }

    @Test
    fun navigatedDocumentCannotInvokeAfterSuspendedForegroundOrAuthorization() = runBlocking {
        for (pauseAt in listOf("foreground", "second grant")) {
            val calls = mutableListOf<String>()
            val state = RecordingGrantState(calls)
            val basePolicy = policy(state, calls)
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var paused = false
            var currentEpoch = 1
            val suspendingPolicy = object : RuntimeAuthorizationPolicy by basePolicy {
                override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId): Boolean {
                    val granted = basePolicy.isGranted(identity, capability)
                    if (pauseAt == "second grant" && !paused && calls.count { it == "grant" } == 2) {
                        paused = true
                        entered.complete(Unit)
                        resume.await()
                    }
                    return granted
                }
            }
            val dispatcher = RuntimeRpcDispatcher(
                identity = identity,
                authorization = suspendingPolicy,
                handlers = RuntimeM1Handlers(clipboardWrite = RuntimeClipboardWriteHandler { calls += "write" }),
                foregroundInteractionGuard = {
                    calls += "foreground"
                    if (pauseAt == "foreground" && !paused) {
                        paused = true
                        entered.complete(Unit)
                        resume.await()
                    }
                },
            )
            val stale = async {
                dispatch(dispatcher) {
                    if (currentEpoch != 1) throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "The document navigated")
                }
            }
            try {
                withTimeout(2_000) { entered.await() }
                currentEpoch = 2
                resume.complete(Unit)
                val response = withTimeout(2_000) { stale.await() } as RuntimeRpcResponse.Failure
                assertEquals(RuntimeRpcErrorCode.CANCELLED, response.error.code)
                assertTrue("A stale $pauseAt request must not write", "write" !in calls)

                assertTrue(dispatch(dispatcher) {
                    if (currentEpoch != 2) throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "The document navigated")
                } is RuntimeRpcResponse.Success)
                assertEquals(1, calls.count { it == "write" })
            } finally {
                resume.complete(Unit)
                stale.cancelAndJoin()
            }
        }
    }

    private fun dispatcher(
        state: RecordingGrantState,
        calls: MutableList<String>,
        quotaAllowed: Boolean = true,
        onForegroundDispatch: () -> Unit = {},
    ) = RuntimeRpcDispatcher(
        identity = identity,
        authorization = policy(state, calls, quotaAllowed),
        handlers = RuntimeM1Handlers(clipboardWrite = RuntimeClipboardWriteHandler { calls += "write" }),
        foregroundInteractionGuard = { calls += "foreground"; onForegroundDispatch() },
    )

    private suspend fun dispatch(
        dispatcher: RuntimeRpcDispatcher,
        beforeInvoke: (String) -> Unit = {},
    ): RuntimeRpcResponse =
        dispatcher.dispatch(
            RuntimeRpcRequest("request", "clipboard.writeText", identity.nonce, identity.toolId,
                identity.versionCode, identity.generation,
                RpcValue.ObjectValue(mapOf("text" to RpcValue.StringValue("saved"))), 100),
            RuntimeInboundContext(identity.exactOrigin, true),
            beforeInvoke,
        )

    private fun policy(state: RecordingGrantState, calls: MutableList<String>, quotaAllowed: Boolean = true) =
        DefaultRuntimeAuthorizationPolicy(
            state = state,
            systemPermissions = RuntimeSystemPermissionChecker { calls += "permission"; true },
            quota = RuntimeQuotaChecker { _: RuntimeSessionIdentity, _: MethodDescriptor, _: Int ->
                calls += "admit"
                if (quotaAllowed) RuntimePolicyDecision.Allowed
                else RuntimePolicyDecision.Denied(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Quota denied")
            },
        )

    private class RecordingGrantState(private val calls: MutableList<String>) : RuntimeGrantStateSource {
        var versionCode = 1
        var granted = true
        var onGrantRead: (Int) -> Unit = {}
        private var grantReads = 0

        override suspend fun currentVersionCode(toolId: String): Int {
            calls += "version"
            return versionCode
        }

        override suspend fun isGranted(toolId: String, capability: ToolBoxCapabilityId): Boolean {
            calls += "grant"
            onGrantRead(++grantReads)
            return granted
        }
    }
}
