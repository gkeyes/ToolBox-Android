package io.toolbox.host.background

import io.toolbox.tool.packagekit.InstalledManifestNetwork
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkMethod
import io.toolbox.tool.runtime.RuntimeNetworkRequest
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNetworkGatewayTest {
    @Test
    fun longWaitUsesTheSmallerRequestAndManifestBudgetAndKeepsTheDefault() = runTest {
        for ((declared, requested, expected) in listOf(
            Triple(300_000, 300_000L, 300_000L),
            Triple(90_000, 300_000L, 90_000L),
            Triple(300_000, 1_000L, 1_000L),
            Triple(300_000, null, 30_000L),
        )) {
            var actualTimeout = 0L
            val gateway = RuntimeNetworkGateway(
                proxy = ToolNetworkProxy(ToolNetworkTransport { request, timeout ->
                    actualTimeout = timeout
                    response(request, "{}".toResponseBody("application/json".toMediaType()))
                }),
                policy = InstalledManifestNetwork(setOf("api.github.com"), false, 4_096, declared),
                bridgePayloadBytes = 4_096,
            )
            assertEquals(200, gateway.request(request(4_096).copy(timeoutMillis = requested)).status)
            assertEquals(expected, actualTimeout)
        }
    }

    @Test
    fun jsonBetweenTheOldBase64BudgetAndTheDeclaredLimitIsReturnedIntact() = runTest {
        for (size in listOf(800_000, 1_500_000)) {
            val body = "{\"data\":\"${"x".repeat(size)}\"}"
            val limit = if (size < 1_000_000) 1_048_576 else 4_194_304
            val gateway = gateway(limit, ToolNetworkTransport { request, _ ->
                response(request, body.toResponseBody("application/json".toMediaType()))
            })

            val actual = gateway.request(request(limit))

            assertEquals(200, actual.status)
            assertEquals(body, actual.body)
        }
    }

    @Test
    fun responseQuotaAndBlockedDestinationHaveDifferentErrors() = runTest {
        var connections = 0
        val gateway = gateway(4_096, ToolNetworkTransport { request, _ ->
            connections += 1
            response(request, "x".repeat(4_097).toResponseBody())
        })
        val quota = failure { gateway.request(request(4_096)) }
        assertEquals(RuntimeRpcErrorCode.QUOTA_EXCEEDED, quota.errorCode)
        assertTrue(quota.message.contains("4096"))

        val blocked = failure {
            gateway.request(request(4_096).copy(url = "https://undeclared.example.com/"))
        }
        assertEquals(RuntimeRpcErrorCode.NETWORK_BLOCKED, blocked.errorCode)
        assertTrue(blocked.message.contains("allowDomains"))
        assertEquals(1, connections)
    }

    @Test
    fun connectionAndBodyReadFailuresKeepTypedErrorsWithoutTransportDetails() = runTest {
        for (duringBody in listOf(false, true)) {
            for (timeout in listOf(false, true)) {
                val error = if (timeout) SocketTimeoutException("secret-in-transport-error")
                    else IOException("secret-in-transport-error")
                val gateway = gateway(4_096, ToolNetworkTransport { request, _ ->
                    if (!duringBody) throw error
                    response(request, failingBody(error))
                })

                val actual = failure { gateway.request(request(4_096)) }

                assertEquals(
                    if (timeout) RuntimeRpcErrorCode.NETWORK_TIMEOUT else RuntimeRpcErrorCode.NETWORK_UNAVAILABLE,
                    actual.errorCode,
                )
                assertFalse(actual.message.contains("secret-in-transport-error"))
            }
        }
    }

    private fun gateway(limit: Int, transport: ToolNetworkTransport) = RuntimeNetworkGateway(
        proxy = ToolNetworkProxy(transport),
        policy = InstalledManifestNetwork(setOf("api.github.com"), false, limit, 30_000),
        bridgePayloadBytes = limit,
    )

    private fun request(limit: Int) = RuntimeNetworkRequest(
        url = "https://api.github.com/repos/example/repo/actions/runs?per_page=100",
        method = RuntimeNetworkMethod.GET,
        maxResponseBytes = limit,
    )

    private fun response(request: Request, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()

    private fun failingBody(error: IOException) = object : ResponseBody() {
        private val failingSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw error
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun contentLength(): Long = -1L
        override fun contentType() = "application/json".toMediaType()
        override fun source() = failingSource
    }

    private suspend fun failure(action: suspend () -> Unit): RuntimeHandlerException {
        try {
            action()
        } catch (error: RuntimeHandlerException) {
            return error
        }
        error("Expected a typed network failure")
    }
}
