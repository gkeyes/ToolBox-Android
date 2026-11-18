package io.toolbox.tool.runtime

import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeEmbeddedBrowserRpcTest {
    private val identity = RuntimeSessionIdentity("com.example.reader", 1, "generation", "0.9.0", "nonce",
        "https://reader.toolbox.invalid/", setOf("browser"))
    private val id = "embedded-" + "a".repeat(32)
    private var granted = true
    private var current = true
    private var foreground = true
    private var grantReads = 0
    private var foregroundReads = 0
    private var beforeEffect: () -> Unit = {}
    private val effects = mutableListOf<String>()
    private val authorization = object : RuntimeAuthorizationPolicy {
        override suspend fun isCurrent(identity: RuntimeSessionIdentity) = current
        override suspend fun isGranted(identity: RuntimeSessionIdentity, capability: ToolBoxCapabilityId): Boolean {
            grantReads++
            return granted
        }
        override suspend fun hasSystemPermissions(identity: RuntimeSessionIdentity, permissions: Set<String>) = true
        override suspend fun admit(identity: RuntimeSessionIdentity, method: MethodDescriptor, encodedBytes: Int) = RuntimePolicyDecision.Allowed
    }
    private val handler = object : RuntimeEmbeddedBrowserHandler {
        override suspend fun open(url: String, bounds: RuntimeEmbeddedBrowserBounds, beforePresent: suspend () -> Unit): String {
            beforeEffect()
            beforePresent()
            effects += "open:$url"
            return id
        }
        override suspend fun update(id: String, bounds: RuntimeEmbeddedBrowserBounds, beforePresent: suspend () -> Unit) {
            beforeEffect()
            beforePresent()
            effects += "update:$id"
        }
        override suspend fun close(id: String) { effects += "close:$id" }
    }

    @Test
    fun opensAndPositionsWithExistingBrowserPermissionAndFinalForegroundCheck() = runBlocking {
        val dispatcher = dispatcher()
        val open = dispatch(dispatcher, "browser.openEmbedded", bounds() + ("url" to text("https://www.youtube.com/embed/y8-kOYgmNdA")))
        assertEquals(RpcValue.ObjectValue(mapOf("id" to text(id))), (open as RuntimeRpcResponse.Success).result)
        assertTrue(dispatch(dispatcher, "browser.updateEmbedded", bounds() + ("id" to text(id))) is RuntimeRpcResponse.Success)
        assertEquals(listOf("open:https://www.youtube-nocookie.com/embed/y8-kOYgmNdA?autoplay=1&playsinline=1", "update:$id"), effects)
        assertEquals(4, foregroundReads)
    }

    @Test
    fun revocationOrBackgroundingDuringNativeDispatchPreventsTheSurfaceChange() = runBlocking {
        for (method in listOf("browser.openEmbedded", "browser.updateEmbedded")) {
            for (loss in listOf("grant", "version", "foreground")) {
                granted = true; current = true; foreground = true; effects.clear()
                beforeEffect = {
                    when (loss) {
                        "grant" -> granted = false
                        "version" -> current = false
                        else -> foreground = false
                    }
                }
                val params = bounds() + if (method == "browser.openEmbedded") {
                    mapOf("url" to text("https://www.youtube.com/embed/y8-kOYgmNdA"))
                } else mapOf("id" to text(id))
                val response = dispatch(dispatcher(), method, params) as RuntimeRpcResponse.Failure
                assertEquals(when (loss) {
                    "grant" -> RuntimeRpcErrorCode.PERMISSION_DENIED
                    "version" -> RuntimeRpcErrorCode.INVALID_SESSION
                    else -> RuntimeRpcErrorCode.SESSION_ENDED
                }, response.error.code)
                assertTrue(effects.isEmpty())
            }
        }
    }

    @Test
    fun closeRemainsAvailableAfterRevocationWhileHiddenAndAfterFinalSave() = runBlocking {
        val state = RuntimePresentationCoordinator(identity.generation) { 0L }
        val ticket = state.beginClose(null)
        state.completeFlush(ticket.token, true)
        granted = false
        foreground = false
        val lifecycle = object : RuntimeLifecycleHandler {
            override fun stateValue() = state.stateValue()
            override fun checkMethodAvailable(method: String) = state.checkMethodAvailable(method)
            override fun acknowledgeEvents(sequence: Long) = Unit
            override fun completeFlush(token: String, saved: Boolean) = state.completeFlush(token, saved)
        }
        assertTrue(dispatch(dispatcher(lifecycle = lifecycle), "browser.closeEmbedded", mapOf("id" to text(id))) is RuntimeRpcResponse.Success)
        assertEquals(listOf("close:$id"), effects)
        assertEquals(0, grantReads)
        assertEquals(0, foregroundReads)
        assertTrue(runtimeControlMethod("{\"method\":\"browser.closeEmbedded\"}"))
    }

    @Test
    fun cleanupStillRejectsForeignFramesWrongOriginsAndStaleSessions() = runBlocking {
        val request = request("browser.closeEmbedded", mapOf("id" to text(id)))
        val dispatcher = dispatcher()
        val cases = listOf(
            request to RuntimeInboundContext(identity.exactOrigin, false),
            request to RuntimeInboundContext("https://www.youtube-nocookie.com", true),
            request.copy(nonce = "stale") to RuntimeInboundContext(identity.exactOrigin, true),
        )
        cases.forEach { (call, inbound) -> assertTrue(dispatcher.dispatch(call, inbound) is RuntimeRpcResponse.Failure) }
        current = false
        assertTrue(dispatcher.dispatch(request, RuntimeInboundContext(identity.exactOrigin, true)) is RuntimeRpcResponse.Failure)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun unDeclaredBrowserAccessMalformedUrlsAndGeometryNeverReachNative() = runBlocking {
        val valid = bounds() + ("url" to text("https://www.youtube.com/embed/y8-kOYgmNdA"))
        val undeclared = dispatch(dispatcher(identity = identity.copy(declaredCapabilities = emptySet())), "browser.openEmbedded", valid)
        assertEquals(RuntimeRpcErrorCode.NOT_DECLARED, (undeclared as RuntimeRpcResponse.Failure).error.code)
        listOf(
            valid + ("url" to text("https://evil.test/embed/y8-kOYgmNdA")),
            valid + ("x" to RpcValue.Number(Double.NaN)),
            valid + ("clipLeft" to RpcValue.Number(-1.0)),
            valid - "viewportWidth",
            valid + ("javascript" to text("alert(1)")),
        ).forEach { params ->
            val result = dispatch(dispatcher(), "browser.openEmbedded", params) as RuntimeRpcResponse.Failure
            assertEquals(RuntimeRpcErrorCode.INVALID_REQUEST, result.error.code)
        }
        assertTrue(effects.isEmpty())
    }

    private fun dispatcher(identity: RuntimeSessionIdentity = this.identity, lifecycle: RuntimeLifecycleHandler? = null): RuntimeRpcDispatcher {
        val guard: suspend () -> Unit = {
            foregroundReads++
            if (!foreground) throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "Hidden tool")
        }
        return RuntimeRpcDispatcher(identity, authorization, RuntimeCoreHandlers(),
            systemHandlers = RuntimeSystemHandlers(browserEmbedded = handler), lifecycle = lifecycle,
            foregroundInteractionGuard = guard, browserLaunchGuard = guard)
    }

    private fun request(method: String, params: Map<String, RpcValue>) = RuntimeRpcRequest(
        "request", method, identity.nonce, identity.toolId, identity.versionCode, identity.generation, RpcValue.ObjectValue(params), 300,
    )

    private suspend fun dispatch(dispatcher: RuntimeRpcDispatcher, method: String, params: Map<String, RpcValue>) =
        dispatcher.dispatch(request(method, params), RuntimeInboundContext(identity.exactOrigin, true))

    private fun bounds() = mapOf(
        "x" to RpcValue.Number(12.0), "y" to RpcValue.Number(100.0),
        "width" to RpcValue.Number(320.0), "height" to RpcValue.Number(180.0), "viewportWidth" to RpcValue.Number(400.0),
        "clipTop" to RpcValue.Number(0.0), "clipRight" to RpcValue.Number(0.0),
        "clipBottom" to RpcValue.Number(0.0), "clipLeft" to RpcValue.Number(0.0),
    )
    private fun text(value: String) = RpcValue.StringValue(value)
}
