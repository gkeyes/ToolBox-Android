package io.toolbox.host.runtime

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.Collections
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                override suspend fun replace(toolId: String, removeKeys: Set<String>, values: Map<String, String>, updatedAt: Long): DataResult<Unit> {
                    assertEquals(setOf(ToolStorageNamespace.Secure.documentKey), removeKeys)
                    assertTrue(values.isEmpty())
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
            override suspend fun replace(toolId: String, removeKeys: Set<String>, values: Map<String, String>, updatedAt: Long): DataResult<Unit> = DataResult.Success(Unit)
        }
        assertFalse(clearRuntimeSecureStorage("secure-test", repository) { false })
        assertTrue(clearRuntimeSecureStorage("secure-test", repository) { true })
        val failed = object : ToolKvRepository by repository {
            override suspend fun replace(toolId: String, removeKeys: Set<String>, values: Map<String, String>, updatedAt: Long): DataResult<Unit> =
                DataResult.Failure.StorageFailure("remove")
        }
        var keyDeletionCalled = false
        assertFalse(clearRuntimeSecureStorage("secure-test", failed) { keyDeletionCalled = true; true })
        assertFalse(keyDeletionCalled)
    }

    @Test
    fun encryptedChunksReopenShrinkAndCleanupWithoutTouchingOrdinaryStorage() = runBlocking {
        val repository = installedRepository()
        repository.put(TOOL_ID, "ordinary", "keep", 1)
        val legacy = encryptedEnvelope("legacy secret")
        repository.put(TOOL_ID, ROOT_KEY, legacy, 1)
        val storage = RuntimeSecureEnvelopeStorage(TOOL_ID, repository)
        assertEquals(legacy, storage.read())
        val secret = "private-marker-".repeat(180_000)
        val encrypted = encryptedEnvelope(secret)
        assertTrue(encrypted.length > 2 * 1024 * 1024)
        assertEquals(DataResult.Success(Unit), storage.write(encrypted, 2))
        assertEquals(encrypted, RuntimeSecureEnvelopeStorage(TOOL_ID, repository).read())
        for (key in repository.keys(TOOL_ID)) {
            val row = repository.observe(TOOL_ID, key).first()!!
            assertTrue(row.bytes <= 128 * 1024)
            assertFalse(row.valueJson.contains("private-marker-"))
        }
        assertEquals(DataResult.Success(Unit), storage.write(legacy, 3))
        assertEquals(2, repository.keys(TOOL_ID).count { it.startsWith(ROOT_KEY) })
        assertEquals(legacy, storage.read())
        assertTrue(clearRuntimeSecureStorage(TOOL_ID, repository) { true })
        assertNull(storage.read())
        assertEquals(listOf("ordinary"), repository.keys(TOOL_ID))
        // Cleanup also handles the old, single-row encrypted format.
        repository.put(TOOL_ID, ROOT_KEY, legacy, 4)
        assertTrue(clearRuntimeSecureStorage(TOOL_ID, repository) { true })
        assertEquals(listOf("ordinary"), repository.keys(TOOL_ID))
    }

    @Test
    fun failedReplacementAndRevocationKeepCiphertextAndKeyIntact() = runBlocking {
        val backing = installedRepository()
        var failWrites = false
        val repository = object : ToolKvRepository by backing {
            override suspend fun replace(toolId: String, removeKeys: Set<String>, values: Map<String, String>, updatedAt: Long): DataResult<Unit> =
                if (failWrites) DataResult.Failure.StorageFailure("injected")
                else backing.replace(toolId, removeKeys, values, updatedAt)
        }
        val storage = RuntimeSecureEnvelopeStorage(TOOL_ID, repository)
        val old = encryptedEnvelope("old".repeat(800_000))
        assertEquals(DataResult.Success(Unit), storage.write(old, 1))
        val oldKeys = repository.keys(TOOL_ID)
        failWrites = true
        assertTrue(storage.write(encryptedEnvelope("replacement"), 2) is DataResult.Failure.StorageFailure)
        assertEquals(old, storage.read())
        assertEquals(oldKeys, repository.keys(TOOL_ID))
        var keyDeleted = false
        assertFalse(clearRuntimeSecureStorage(TOOL_ID, repository) { keyDeleted = true; true })
        assertFalse(keyDeleted)
        assertEquals(old, storage.read())
        failWrites = false
        storage.clear()
        backing.put(TOOL_ID, ROOT_KEY, old, 3)
        failWrites = true
        assertTrue(storage.write(encryptedEnvelope("migration"), 4) is DataResult.Failure.StorageFailure)
        assertEquals(old, storage.read())
        assertEquals(listOf(ROOT_KEY), repository.keys(TOOL_ID))
    }

    @Test
    fun corruptChunkIndexAndMissingChunksFailClosed() = runBlocking {
        val repository = installedRepository()
        val storage = RuntimeSecureEnvelopeStorage(TOOL_ID, repository)
        storage.write(encryptedEnvelope("data"), 1)
        val chunk = repository.keys(TOOL_ID).single { it.startsWith("$ROOT_KEY.chunk.") }
        repository.remove(TOOL_ID, chunk)
        expectUnreadable { storage.read() }
        for (header in listOf(
            "{\"format\":\"toolbox.secure.chunks\",\"v\":2,\"chunks\":1,\"length\":4}",
            "{\"format\":\"toolbox.secure.chunks\",\"v\":1,\"chunks\":0,\"length\":4}",
            "{\"format\":\"toolbox.secure.chunks\",\"v\":1,\"chunks\":2,\"length\":4}",
        )) {
            repository.put(TOOL_ID, ROOT_KEY, header, 2)
            expectUnreadable { storage.read() }
        }
    }

    private suspend fun installedRepository(): ToolKvRepository {
        val data = InMemoryCoreData.create()
        val attempt = CatalogInstallAttempt(
            "tx", ToolMetadata(TOOL_ID, "Secure storage test", SecurityProfile.STRICT, 1),
            ToolVersion(TOOL_ID, 1, "1.0.0", BundleLocator("tools/test/current"), 1, "hash", 1), emptyList(),
        )
        data.installs.begin(InstallTransaction("tx", TOOL_ID, 1, InstallTransactionState.PREPARING, 1, 1))
        assertTrue(data.lifecycle.commitInstall(attempt) is DataResult.Success)
        return data.keyValues
    }

    private fun encryptedEnvelope(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val ciphertext = encoder.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
        val iv = encoder.encodeToString(cipher.iv)
        return "{\"v\":1,\"iv\":\"$iv\",\"ciphertext\":\"$ciphertext\"}"
    }

    private suspend fun expectUnreadable(action: suspend () -> Unit) {
        try {
            action()
            error("Expected invalid encrypted storage to fail")
        } catch (failure: RuntimeHandlerException) {
            assertEquals(RuntimeRpcErrorCode.INTERNAL_ERROR, failure.errorCode)
        }
    }

    private companion object {
        const val TOOL_ID = "io.toolbox.secure.test"
        val ROOT_KEY = ToolStorageNamespace.Secure.documentKey
    }

}
