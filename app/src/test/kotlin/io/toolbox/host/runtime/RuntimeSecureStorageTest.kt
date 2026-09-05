package io.toolbox.host.runtime

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSecureStorageTest {
    @Test
    fun revocationWaitsForWriterAndQueuedOperationsRecheckGrant() = runBlocking {
        withTimeout(5_000) {
            val effects = Collections.synchronizedList(mutableListOf<String>())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = object : ToolKvRepository by InMemoryCoreData.create().keyValues {
                override suspend fun remove(toolId: String, key: String): DataResult<Unit> {
                    assertEquals(ToolStorageNamespace.Secure.documentKey, key)
                    effects += "remove"
                    return DataResult.Success(Unit)
                }
            }
            val writer = async(Dispatchers.Default) {
                withRuntimeStorageAccess("secure-test", ToolStorageNamespace.Secure, { true }) {
                    entered.complete(Unit)
                    release.await()
                    effects += "write"
                }
            }
            entered.await()
            val cleanup = async(Dispatchers.Default) {
                clearRuntimeSecureStorage("secure-test", repository) { effects += "delete-key"; true }
            }
            val denied = async(Dispatchers.Default) {
                try {
                    withRuntimeStorageAccess("secure-test", ToolStorageNamespace.Secure, { false }) {
                        error("Denied operation must not execute")
                    }
                } catch (failure: RuntimeHandlerException) {
                    assertEquals(RuntimeRpcErrorCode.PERMISSION_DENIED, failure.errorCode)
                }
            }
            assertFalse(cleanup.isCompleted)
            release.complete(Unit)
            writer.await()
            assertTrue(cleanup.await())
            denied.await()
            assertEquals(listOf("write", "remove", "delete-key"), effects.toList())
        }
    }

    @Test
    fun cleanupReportsDatabaseAndKeystoreFailures() = runBlocking {
        val repository = object : ToolKvRepository by InMemoryCoreData.create().keyValues {
            override suspend fun remove(toolId: String, key: String): DataResult<Unit> = DataResult.Success(Unit)
        }
        assertFalse(clearRuntimeSecureStorage("secure-test", repository) { false })
        assertTrue(clearRuntimeSecureStorage("secure-test", repository) { true })
        val failed = object : ToolKvRepository by repository {
            override suspend fun remove(toolId: String, key: String): DataResult<Unit> =
                DataResult.Failure.StorageFailure("remove")
        }
        var keyDeletionCalled = false
        assertFalse(clearRuntimeSecureStorage("secure-test", failed) { keyDeletionCalled = true; true })
        assertFalse(keyDeletionCalled)
    }
}
