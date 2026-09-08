package io.toolbox.host.runtime

import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UserNetworkDomainsTest {
    @Test fun legacyMethodsUseTheCurrentNetworkGrantForEveryCall() = runBlocking {
        var granted = true
        var validations = 0
        val handler = HostNetworkDomainHandler {
            validations++
            if (!granted) throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Network disabled")
            1L
        }
        for (domain in listOf("api.example.com", "localhost", "198.18.7.58", "192.168.1.2")) {
            assertTrue(handler.authorizeDomain(domain))
        }
        assertEquals(emptyList<String>(), handler.listDomains())
        assertEquals(5, validations)
        granted = false
        assertEquals(RuntimeRpcErrorCode.PERMISSION_DENIED,
            (runCatching { handler.authorizeDomain("api.example.com") }.exceptionOrNull() as RuntimeHandlerException).errorCode)
        assertEquals(RuntimeRpcErrorCode.PERMISSION_DENIED,
            (runCatching { handler.listDomains() }.exceptionOrNull() as RuntimeHandlerException).errorCode)
    }

    @Test fun sessionEndingDuringGrantReadRejectsLegacyResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val handler = HostNetworkDomainHandler {
            started.complete(Unit)
            finish.await()
            1L
        }
        val pending = async { runCatching { handler.authorizeDomain("api.example.com") } }
        started.await()
        handler.close()
        finish.complete(Unit)
        assertEquals(RuntimeRpcErrorCode.SESSION_ENDED,
            (pending.await().exceptionOrNull() as RuntimeHandlerException).errorCode)
        assertEquals(RuntimeRpcErrorCode.SESSION_ENDED,
            (runCatching { handler.listDomains() }.exceptionOrNull() as RuntimeHandlerException).errorCode)
    }
}
