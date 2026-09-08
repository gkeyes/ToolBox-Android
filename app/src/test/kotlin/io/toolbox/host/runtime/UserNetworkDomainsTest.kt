package io.toolbox.host.runtime

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvValue
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class UserNetworkDomainsTest {
    @Test fun exactDomainNormalizationRejectsUrlIpWildcardsAndMalformedHosts() {
        assertEquals("api.example.com", normalizeUserNetworkDomain("API.Example.COM"))
        for (input in listOf("https://api.example.com", "*.example.com", "127.0.0.1", "2130706433", "[::1]",
            "0x7f.0x0.0x0.0x1", "api.example.com:443", "user@api.example.com", "api.example.com/path",
            "api.example.com.", " api.example.com", "api..example.com", "-api.example.com", "api.local", "api.internal")) {
            try { normalizeUserNetworkDomain(input); fail(input) }
            catch (error: RuntimeHandlerException) { assertEquals(input, RuntimeRpcErrorCode.INVALID_REQUEST, error.errorCode) }
        }
    }

    @Test fun storeIsIsolatedByToolVersionAndHostNamespace() = runBlocking {
        val repository = MemoryKv()
        val store = UserNetworkDomainStore(repository)
        store.authorize("domains-isolation-a", 1, "api.example.com", store.epoch("domains-isolation-a")) {}
        assertEquals(listOf("api.example.com"), store.list("domains-isolation-a", 1))
        assertTrue(store.list("domains-isolation-b", 1).isEmpty())
        assertTrue(store.list("domains-isolation-a", 2).isEmpty())
        assertEquals(setOf("toolbox.host.v1.network.domains"), repository.values.keys.map { it.second }.toSet())
        store.revoke("domains-isolation-a", 1, "api.example.com")
        assertTrue(store.list("domains-isolation-a", 1).isEmpty())
    }

    @Test fun noWriteBeforeConfirmationAndDenialDoesNotPersist() = runBlocking {
        val repository = MemoryKv()
        val store = UserNetworkDomainStore(repository)
        val confirmation = CompletableDeferred<Boolean>()
        val handler = handler("domains-confirm", store, confirm = { _, domain ->
            assertEquals("api.example.com", domain)
            confirmation.await()
        })
        val pending = async { handler.authorizeDomain("API.EXAMPLE.COM") }
        yield()
        assertEquals(0, repository.writes)
        confirmation.complete(false)
        assertFalse(pending.await())
        assertEquals(0, repository.writes)
        assertTrue(handler("domains-accepted", store).authorizeDomain("api.example.com"))
        assertEquals(listOf("api.example.com"), store.list("domains-accepted", 1))
    }

    @Test fun pendingApprovalCannotSurviveGrantRevisionDeclarationForegroundOrSessionChanges() = runBlocking {
        for (change in listOf("revision", "declaration", "foreground", "session", "clear", "revoke")) {
            val store = UserNetworkDomainStore(MemoryKv())
            val toolId = "domains-expired-$change"
            var revision = 1L
            var declared = true
            var foreground = true
            val confirmation = CompletableDeferred<Boolean>()
            val handler = handler(toolId, store, foreground = { foreground }, confirm = { _, _ -> confirmation.await() }, validate = {
                if (!declared) throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Declaration changed")
                revision
            })
            val pending = async { runCatching { handler.authorizeDomain("api.example.com") } }
            yield()
            when (change) {
                "revision" -> revision++
                "declaration" -> declared = false
                "foreground" -> foreground = false
                "session" -> handler.close()
                "clear" -> store.clear(toolId)
                "revoke" -> store.revoke(toolId, 1, "api.example.com")
            }
            confirmation.complete(true)
            assertTrue(change, pending.await().exceptionOrNull() is RuntimeHandlerException)
            assertTrue(change, store.list(toolId, 1).isEmpty())
        }
    }

    @Test fun sessionEndingDuringFinalManifestReadCannotPersistApproval() = runBlocking {
        val repository = MemoryKv()
        val store = UserNetworkDomainStore(repository)
        val validationStarted = CompletableDeferred<Unit>()
        val finishValidation = CompletableDeferred<Unit>()
        var calls = 0
        val handler = handler("domains-validation-session", store, validate = {
            calls++
            if (calls == 2) {
                validationStarted.complete(Unit)
                finishValidation.await()
            }
            1L
        })
        val pending = async { runCatching { handler.authorizeDomain("api.example.com") } }
        validationStarted.await()
        handler.close()
        finishValidation.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is RuntimeHandlerException)
        assertEquals(0, repository.writes)
    }

    @Test fun backgroundOrMissingDeclarationNeverShowsConfirmation() = runBlocking {
        val store = UserNetworkDomainStore(MemoryKv())
        var prompts = 0
        val confirm: suspend (String, String) -> Boolean = { _, _ -> prompts++; true }
        val invisible = handler("domains-invisible", store, foreground = { false }, confirm = confirm)
        assertTrue(runCatching { invisible.authorizeDomain("api.example.com") }.exceptionOrNull() is RuntimeHandlerException)
        val denied = handler("domains-denied", store, confirm = confirm, validate = {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "No current network declaration/grant")
        })
        assertTrue(runCatching { denied.authorizeDomain("api.example.com") }.exceptionOrNull() is RuntimeHandlerException)
        assertEquals(0, prompts)
    }

    private fun handler(toolId: String, store: UserNetworkDomainStore, foreground: () -> Boolean = { true },
        confirm: suspend (String, String) -> Boolean = { _, _ -> true }, validate: suspend () -> Long = { 1L }) =
        HostNetworkDomainHandler(toolId, "Test Tool", 1, store, foreground, confirm, validate)

    private class MemoryKv : ToolKvRepository {
        val values = mutableMapOf<Pair<String, String>, MutableStateFlow<ToolKvValue?>>()
        var writes = 0
        override fun observe(toolId: String, key: String) = values.getOrPut(toolId to key) { MutableStateFlow(null) }
        override suspend fun put(toolId: String, key: String, valueJson: String, updatedAt: Long): DataResult<Unit> {
            writes++
            observe(toolId, key).value = ToolKvValue(key, valueJson, updatedAt)
            return DataResult.Success(Unit)
        }
        override suspend fun remove(toolId: String, key: String): DataResult<Unit> {
            observe(toolId, key).value = null
            return DataResult.Success(Unit)
        }
        override suspend fun bytesUsed(toolId: String): Long = 0L
    }
}
