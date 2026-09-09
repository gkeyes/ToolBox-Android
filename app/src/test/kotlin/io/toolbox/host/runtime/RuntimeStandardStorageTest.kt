package io.toolbox.host.runtime

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolKvSnapshot
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvValue
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeStorageMutation
import io.toolbox.tool.runtime.RuntimeStorageSet
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
    fun writeFailurePreservesOldChunksAndFailedMigrationPreservesLegacy() = runBlocking {
        val backing = installedData().keyValues
        var failWrites = false
        val repository = object : ToolKvRepository by backing {
            override suspend fun replace(
                toolId: String,
                removeKeys: Set<String>,
                values: Map<String, String>,
                updatedAt: Long,
            ): DataResult<Unit> = if (failWrites) {
                DataResult.Failure.StorageFailure("injected")
            } else {
                backing.replace(toolId, removeKeys, values, updatedAt)
            }
        }
        val storage = handler(repository)
        val old = RpcValue.StringValue("a".repeat(250_000))
        storage.set("key", old)
        failWrites = true
        expectFailure(RuntimeRpcErrorCode.INTERNAL_ERROR) { storage.set("key", RpcValue.StringValue("b".repeat(500_000))) }
        assertEquals(old, storage.get("key"))
        failWrites = false
        storage.clear()
        val legacy = "{\"keep\":\"${"c".repeat(350_000)}\"}"
        repository.put(TOOL_ID, LEGACY, legacy, 1)
        failWrites = true
        expectFailure(RuntimeRpcErrorCode.INTERNAL_ERROR) { storage.set("added", RpcValue.StringValue("d".repeat(100_000))) }
        assertEquals(legacy, repository.observe(TOOL_ID, LEGACY).first()!!.valueJson)
        assertEquals(listOf(LEGACY), repository.keys(TOOL_ID))
        failWrites = false
        storage.remove("keep")
        assertEquals(emptyList<String>(), storage.keys())
    }

    @Test
    fun deniedOperationsDoNotReadMigrateOrClearAndEncodedKeysCannotEscapeNamespace() = runBlocking {
        val repository = installedData().keyValues
        repository.put(TOOL_ID, LEGACY, "{\"old\":1}", 1)
        repository.put(TOOL_ID, SECURE, "ciphertext", 1)
        val denied = StandardToolKvStorageHandler(TOOL_ID, repository, { 1 }, { false })
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

    @Test
    fun batchesPreserveOrderNullsAndIsolationWhileReplacingOnlyAffectedChunks() = runBlocking {
        val repository = installedData().keyValues
        val storage = handler(repository)
        storage.apply(RuntimeStorageMutation(set = listOf(
            RuntimeStorageSet("article", RpcValue.StringValue("😀正文".repeat(100_000))),
            RuntimeStorageSet("remove", RpcValue.Bool(true)),
            RuntimeStorageSet("null", RpcValue.Null),
        )))
        repository.put(TOOL_ID, SECURE, "ciphertext", 1)
        storage.apply(RuntimeStorageMutation(
            set = listOf(RuntimeStorageSet("article", RpcValue.StringValue("short")), RuntimeStorageSet("added", RpcValue.Number(2.0))),
            remove = listOf("remove"),
        ))
        assertEquals(listOf(RpcValue.Number(2.0), null, RpcValue.StringValue("short"), RpcValue.Null, RpcValue.Number(2.0)),
            storage.getMany(listOf("added", "missing", "article", "null", "added")))
        assertEquals(4, repository.keys(TOOL_ID).size)
        assertEquals("ciphertext", repository.observe(TOOL_ID, SECURE).first()!!.valueJson)
        assertEquals(emptyList<RpcValue?>(), storage.getMany(emptyList()))
        storage.apply(RuntimeStorageMutation())
        assertEquals(4, repository.keys(TOOL_ID).size)
    }

    @Test
    fun malformedBatchesRejectBeforeReadingOrWritingAndRecheckRevocationBeforeCommit() = runBlocking {
        val backing = installedData().keyValues
        var reads = 0
        var writes = 0
        var granted = true
        var revokeAfterRead = false
        val repository = object : ToolKvRepository by backing {
            override suspend fun <T> readSnapshot(toolId: String, action: suspend (ToolKvSnapshot) -> T): T {
                reads++
                return backing.readSnapshot(toolId, action).also { if (revokeAfterRead) granted = false }
            }
            override suspend fun replace(toolId: String, removeKeys: Set<String>, values: Map<String, String>, updatedAt: Long): DataResult<Unit> {
                writes++
                return backing.replace(toolId, removeKeys, values, updatedAt)
            }
        }
        val storage = StandardToolKvStorageHandler(TOOL_ID, repository, { 1 }, { granted })
        val item = RuntimeStorageSet("same", RpcValue.Null)
        val invalid = listOf(
            RuntimeStorageMutation(set = listOf(item, item)),
            RuntimeStorageMutation(remove = listOf("same", "same")),
            RuntimeStorageMutation(set = listOf(item), remove = listOf("same")),
            RuntimeStorageMutation(set = listOf(item, RuntimeStorageSet("bad\nkey", RpcValue.Bool(true)))),
            RuntimeStorageMutation(remove = List(257) { "key$it" }),
        )
        for (mutation in invalid) expectFailure(RuntimeRpcErrorCode.INVALID_REQUEST) { storage.apply(mutation) }
        expectFailure(RuntimeRpcErrorCode.INVALID_REQUEST) { storage.getMany(List(257) { "key$it" }) }
        expectFailure(RuntimeRpcErrorCode.INVALID_REQUEST) { storage.getMany(listOf("good", "bad\nkey")) }
        assertEquals(0, reads)
        assertEquals(0, writes)
        revokeAfterRead = true
        expectFailure(RuntimeRpcErrorCode.PERMISSION_DENIED) { storage.apply(RuntimeStorageMutation(set = listOf(item))) }
        assertEquals(0, writes)
        expectFailure(RuntimeRpcErrorCode.PERMISSION_DENIED) { storage.getMany(listOf("same")) }
        assertEquals(1, reads)
        assertEquals(emptyList<String>(), backing.keys(TOOL_ID))
    }

    @Test
    fun batchSnapshotRemainsConsistentWhenRowsChangeBetweenHeaderAndChunkQueries() = runBlocking {
        val backing = installedData().keyValues
        val storage = handler(backing)
        suspend fun rows(): Map<String, String> {
            val keys = backing.keys(TOOL_ID).toSet()
            return backing.readSnapshot(TOOL_ID) { it.getMany(keys).mapValues { (_, row) -> row.valueJson } }
        }
        val old = RpcValue.StringValue("old".repeat(100_000))
        val fresh = RpcValue.StringValue("new".repeat(150_000))
        storage.apply(RuntimeStorageMutation(set = listOf(RuntimeStorageSet("body", old), RuntimeStorageSet("version", RpcValue.Number(1.0)))))
        val oldRows = rows()
        storage.apply(RuntimeStorageMutation(set = listOf(RuntimeStorageSet("body", fresh), RuntimeStorageSet("version", RpcValue.Number(2.0)))))
        val freshRows = rows()
        backing.replace(TOOL_ID, freshRows.keys, oldRows, 1)
        var batchReads = 0
        val repository = object : ToolKvRepository by backing {
            override fun observe(toolId: String, key: String): Flow<ToolKvValue?> = error("Batch reads must use snapshot queries")
            override suspend fun <T> readSnapshot(toolId: String, action: suspend (ToolKvSnapshot) -> T): T =
                backing.readSnapshot(toolId) { snapshot ->
                    action(object : ToolKvSnapshot {
                        override suspend fun getMany(keys: Set<String>): Map<String, ToolKvValue> {
                            batchReads++
                            val result = snapshot.getMany(keys)
                            if (batchReads == 1) backing.replace(TOOL_ID, oldRows.keys, freshRows, 2)
                            return result
                        }
                    })
                }
        }
        assertEquals(listOf(old, RpcValue.Number(1.0)), handler(repository).getMany(listOf("body", "version")))
        assertEquals(2, batchReads)
        assertEquals(listOf(fresh, RpcValue.Number(2.0)), storage.getMany(listOf("body", "version")))
    }

    @Test
    fun batchResponseLimitIncludesRepeatedValuesAndLeavesDataIntact() = runBlocking {
        val repository = installedData().keyValues
        val writer = handler(repository)
        val value = RpcValue.StringValue("正文".repeat(20_000))
        writer.set("article", value)
        val reader = StandardToolKvStorageHandler(TOOL_ID, repository, { 1 }, maxBatchResponseBytes = 150_000)
        assertEquals(listOf(value), reader.getMany(listOf("article")))
        expectFailure(RuntimeRpcErrorCode.QUOTA_EXCEEDED) { reader.getMany(listOf("article", "article")) }
        assertEquals(value, writer.get("article"))
    }

    private suspend fun installedData(): CoreDataRepositories = InMemoryCoreData.create().also { data ->
        val attempt = CatalogInstallAttempt(
            "tx", ToolMetadata(TOOL_ID, "Storage test", SecurityProfile.STRICT, 1),
            ToolVersion(TOOL_ID, 1, "1.0.0", BundleLocator("tools/test/current"), 1, "hash", 1), emptyList(),
        )
        data.installs.begin(InstallTransaction("tx", TOOL_ID, 1, InstallTransactionState.PREPARING, 1, 1))
        assertTrue(data.lifecycle.commitInstall(attempt) is DataResult.Success)
    }

    private fun handler(repository: ToolKvRepository) =
        StandardToolKvStorageHandler(TOOL_ID, repository, { 1 })

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
        val LEGACY = ToolStorageNamespace.Standard.documentKey
        val SECURE = ToolStorageNamespace.Secure.documentKey
    }
}
