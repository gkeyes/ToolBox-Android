package io.toolbox.host.runtime

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvValue
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStandardStorageTest {
    @Test
    fun largeValuesRoundTripAcrossHandlersAndOnlyTheirRowsAreRead() = runBlocking {
        val data = installedData()
        val reads = mutableListOf<String>()
        val repository = object : ToolKvRepository by data.keyValues {
            override fun observe(toolId: String, key: String): Flow<ToolKvValue?> {
                reads += key
                return data.keyValues.observe(toolId, key)
            }
        }
        val storage = handler(repository)
        val article = RpcValue.StringValue("文章😀\\\"\n".repeat(300_000))
        storage.set("article", article)
        storage.set("article.index", RpcValue.Null)
        storage.set("文章/😀", RpcValue.Bool(true))
        assertEquals(setOf("article", "article.index", "文章/😀"), storage.keys().toSet())
        reads.clear()
        assertEquals(RpcValue.Bool(true), handler(repository).get("文章/😀"))
        assertEquals(1, reads.size)
        assertEquals(article, handler(repository).get("article"))
        for (key in repository.keys(TOOL_ID)) {
            assertTrue(repository.observe(TOOL_ID, key).first()!!.bytes < 2 * 1024 * 1024)
        }
        storage.set("article", RpcValue.StringValue("small"))
        assertEquals(3, repository.keys(TOOL_ID).size)
        storage.remove("article.index")
        assertNull(storage.get("article.index"))
    }

    @Test
    fun legacyReadsAndAtomicMigrationPreserveValuesAndSecureNamespace() = runBlocking {
        val repository = installedData().keyValues
        val legacy = "{\"old\":{\"items\":[1,true,null]},\"large\":\"${"x".repeat(300_000)}\"}"
        assertEquals(DataResult.Success(Unit), repository.put(TOOL_ID, LEGACY, legacy, 1))
        assertEquals(DataResult.Success(Unit), repository.put(TOOL_ID, SECURE, "ciphertext", 1))
        val storage = handler(repository)
        assertEquals(setOf("old", "large"), storage.keys().toSet())
        assertEquals(RpcValue.StringValue("x".repeat(300_000)), storage.get("large"))
        storage.set("added", RpcValue.StringValue("new"))
        assertNull(repository.observe(TOOL_ID, LEGACY).first())
        assertEquals(RpcValue.ObjectValue(mapOf("items" to RpcValue.ArrayValue(listOf(RpcValue.Number(1.0), RpcValue.Bool(true), RpcValue.Null)))), storage.get("old"))
        assertEquals(RpcValue.StringValue("x".repeat(300_000)), storage.get("large"))
        storage.clear()
        assertEquals(emptyList<String>(), storage.keys())
        assertEquals(listOf(SECURE), repository.keys(TOOL_ID))
    }

    @Test
    fun quotaFailurePreservesOldChunksAndFailedMigrationPreservesLegacy() = runBlocking {
        val repository = installedData().keyValues
        val storage = handler(repository, 400_000)
        val old = RpcValue.StringValue("a".repeat(250_000))
        storage.set("key", old)
        expectFailure(RuntimeRpcErrorCode.QUOTA_EXCEEDED) { storage.set("key", RpcValue.StringValue("b".repeat(500_000))) }
        assertEquals(old, storage.get("key"))
        storage.clear()
        val legacy = "{\"keep\":\"${"c".repeat(350_000)}\"}"
        repository.put(TOOL_ID, LEGACY, legacy, 1)
        expectFailure(RuntimeRpcErrorCode.QUOTA_EXCEEDED) { storage.set("added", RpcValue.StringValue("d".repeat(100_000))) }
        assertEquals(legacy, repository.observe(TOOL_ID, LEGACY).first()!!.valueJson)
        assertEquals(listOf(LEGACY), repository.keys(TOOL_ID))
        storage.remove("keep")
        assertEquals(emptyList<String>(), storage.keys())
    }

    @Test
    fun deniedOperationsDoNotReadMigrateOrClearAndEncodedKeysCannotEscapeNamespace() = runBlocking {
        val repository = installedData().keyValues
        repository.put(TOOL_ID, LEGACY, "{\"old\":1}", 1)
        repository.put(TOOL_ID, SECURE, "ciphertext", 1)
        val denied = StandardToolKvStorageHandler(TOOL_ID, repository, QUOTA, { 1 }, { false })
        expectFailure(RuntimeRpcErrorCode.PERMISSION_DENIED) { denied.get("old") }
        expectFailure(RuntimeRpcErrorCode.PERMISSION_DENIED) { denied.set("new", RpcValue.Null) }
        expectFailure(RuntimeRpcErrorCode.PERMISSION_DENIED) { denied.clear() }
        assertEquals(setOf(LEGACY, SECURE), repository.keys(TOOL_ID).toSet())
        val allowed = handler(repository)
        for (key in listOf(LEGACY, SECURE, "../a", "a.b", "a/b", "a", "\uD800", "?")) allowed.set(key, RpcValue.StringValue(key))
        for (key in listOf(LEGACY, SECURE, "../a", "a.b", "a/b", "a", "\uD800", "?")) assertEquals(RpcValue.StringValue(key), allowed.get(key))
        expectFailure(RuntimeRpcErrorCode.INVALID_REQUEST) { allowed.set("bad\nkey", RpcValue.Null) }
        allowed.clear()
        assertEquals("ciphertext", repository.observe(TOOL_ID, SECURE).first()!!.valueJson)
    }

    private suspend fun installedData(): CoreDataRepositories = InMemoryCoreData.create().also { data ->
        val attempt = CatalogInstallAttempt(
            "tx", ToolMetadata(TOOL_ID, "Storage test", SecurityProfile.STRICT, 1),
            ToolVersion(TOOL_ID, 1, "1.0.0", BundleLocator("tools/test/current"), 1, "hash", 1), emptyList(),
        )
        data.installs.begin(InstallTransaction("tx", TOOL_ID, 1, InstallTransactionState.PREPARING, 1, 1))
        assertTrue(data.lifecycle.commitInstall(attempt) is DataResult.Success)
    }

    private fun handler(repository: ToolKvRepository, quota: Long = QUOTA) =
        StandardToolKvStorageHandler(TOOL_ID, repository, quota, { 1 })

    private suspend fun expectFailure(code: RuntimeRpcErrorCode, action: suspend () -> Unit) {
        try {
            action()
            error("Expected $code")
        } catch (failure: RuntimeHandlerException) {
            assertEquals(code, failure.errorCode)
        }
    }

    private companion object {
        const val TOOL_ID = "io.toolbox.storage.test"
        const val QUOTA = 32L * 1024 * 1024
        val LEGACY = ToolStorageNamespace.Standard.documentKey
        val SECURE = ToolStorageNamespace.Secure.documentKey
    }
}
