package io.toolbox.host.runtime

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvSnapshot
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeStorageMutation
import io.toolbox.tool.runtime.RuntimeStorageSet
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeStandardStorageInstrumentedTest {
    @Test
    fun legacyMigrationAndMultiMegabyteValueReopenThroughProductionRoomWithoutOversizedRows() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "runtime-storage-${UUID.randomUUID()}"
        var stores = CoreDataFactory.create(context, "$name.db", name)
        try {
            val attempt = CatalogInstallAttempt(
                "tx", ToolMetadata(TOOL_ID, "Storage test", SecurityProfile.STRICT, 1),
                ToolVersion(TOOL_ID, 1, "1.0.0", BundleLocator("tools/test/current"), 1, "hash", 1), emptyList(),
            )
            stores.repositories.installs.begin(InstallTransaction("tx", TOOL_ID, 1, InstallTransactionState.PREPARING, 1, 1))
            assertTrue(stores.repositories.lifecycle.commitInstall(attempt) is DataResult.Success)
            val legacy = "{\"previous\":\"${"x".repeat(600_000)}\"}"
            assertEquals(DataResult.Success(Unit), stores.repositories.keyValues.put(TOOL_ID, ToolStorageNamespace.Standard.documentKey, legacy, 1))
            val storage = StandardToolKvStorageHandler(TOOL_ID, stores.repositories.keyValues, { 1 })
            val article = RpcValue.StringValue("正文😀\\\"\n".repeat(300_000))
            storage.set("full-article", article)
            assertNull(stores.repositories.keyValues.observe(TOOL_ID, ToolStorageNamespace.Standard.documentKey).first())
            stores.close()
            stores = CoreDataFactory.create(context, "$name.db", name)
            val reopened = StandardToolKvStorageHandler(TOOL_ID, stores.repositories.keyValues, { 2 })
            assertEquals(article, reopened.get("full-article"))
            assertEquals(RpcValue.StringValue("x".repeat(600_000)), reopened.get("previous"))
            assertEquals(setOf("previous", "full-article"), reopened.keys().toSet())
            for (key in stores.repositories.keyValues.keys(TOOL_ID)) {
                assertTrue(stores.repositories.keyValues.observe(TOOL_ID, key).first()!!.bytes < 2 * 1024 * 1024)
            }
            reopened.set("full-article", RpcValue.StringValue("replacement"))
            assertEquals(RpcValue.StringValue("replacement"), reopened.get("full-article"))
            reopened.remove("previous")
            assertEquals(1, stores.repositories.keyValues.keys(TOOL_ID).size)
            stores.repositories.lifecycle.deleteToolCatalog(TOOL_ID)
            assertEquals(emptyList<String>(), stores.repositories.keyValues.keys(TOOL_ID))
        } finally {
            stores.close()
            context.deleteDatabase("$name.db")
        }
    }

    @Test
    fun encryptedMultiMegabyteDocumentReopensWithKeystoreAndRevocationRemovesEveryChunk() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "runtime-secure-${UUID.randomUUID()}"
        var stores = CoreDataFactory.create(context, "$name.db", name)
        try {
            val attempt = CatalogInstallAttempt(
                "tx", ToolMetadata(TOOL_ID, "Secure storage test", SecurityProfile.STRICT, 1),
                ToolVersion(TOOL_ID, 1, "1.0.0", BundleLocator("tools/test/current"), 1, "hash", 1), emptyList(),
            )
            stores.repositories.installs.begin(InstallTransaction("tx", TOOL_ID, 1, InstallTransactionState.PREPARING, 1, 1))
            assertTrue(stores.repositories.lifecycle.commitInstall(attempt) is DataResult.Success)
            stores.repositories.keyValues.put(TOOL_ID, "ordinary", "keep", 1)
            val storage = createRuntimeSecureStorageHandler(TOOL_ID, stores.repositories.keyValues, { 1 }, { true })
            storage.set("previous", RpcValue.StringValue("kept"))
            val envelopes = RuntimeSecureEnvelopeStorage(TOOL_ID, stores.repositories.keyValues)
            val legacy = envelopes.read()!!
            envelopes.clear()
            stores.repositories.keyValues.put(TOOL_ID, ToolStorageNamespace.Secure.documentKey, legacy, 1)
            val secret = RpcValue.StringValue("secret-marker-".repeat(240_000))
            storage.set("large", secret)
            assertTrue(envelopes.read()!!.length > 2 * 1024 * 1024)
            stores.close()
            stores = CoreDataFactory.create(context, "$name.db", name)
            var granted = true
            val reopened = createRuntimeSecureStorageHandler(TOOL_ID, stores.repositories.keyValues, { 2 }, { granted })
            assertEquals(secret, reopened.get("large"))
            assertEquals(RpcValue.StringValue("kept"), reopened.get("previous"))
            for (key in stores.repositories.keyValues.keys(TOOL_ID)) {
                val row = stores.repositories.keyValues.observe(TOOL_ID, key).first()!!
                assertTrue(row.bytes <= 128 * 1024)
                assertTrue(!row.valueJson.contains("secret-marker-"))
            }
            reopened.set("large", RpcValue.StringValue("short"))
            assertEquals(RpcValue.StringValue("short"), reopened.get("large"))
            assertEquals(3, stores.repositories.keyValues.keys(TOOL_ID).size)
            granted = false
            try {
                reopened.set("denied", RpcValue.Null)
                error("Revoked secure storage must reject writes")
            } catch (failure: RuntimeHandlerException) {
                assertEquals(RuntimeRpcErrorCode.PERMISSION_DENIED, failure.errorCode)
            }
            assertTrue(clearRuntimeSecureStorage(TOOL_ID, stores.repositories.keyValues))
            assertEquals(listOf("ordinary"), stores.repositories.keyValues.keys(TOOL_ID))
            granted = true
            assertNull(reopened.get("large"))
        } finally {
            clearRuntimeSecureStorage(TOOL_ID, stores.repositories.keyValues)
            stores.close()
            context.deleteDatabase("$name.db")
        }
    }

    @Test
    fun batchMigrationReplacementAndInjectedSqlFailureAreAtomicAndToolIsolated() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "runtime-batch-${UUID.randomUUID()}"
        var stores = CoreDataFactory.create(context, "$name.db", name)
        try {
            for (toolId in listOf(TOOL_ID, "$TOOL_ID.other")) {
                val transaction = "tx-$toolId"
                stores.repositories.installs.begin(InstallTransaction(transaction, toolId, 1, InstallTransactionState.PREPARING, 1, 1))
                assertTrue(stores.repositories.lifecycle.commitInstall(CatalogInstallAttempt(
                    transaction, ToolMetadata(toolId, "Batch test", SecurityProfile.STRICT, 1),
                    ToolVersion(toolId, 1, "1.0.0", BundleLocator("tools/test/current"), 1, "hash", 1), emptyList(),
                )) is DataResult.Success)
            }
            val repository = stores.repositories.keyValues
            val other = StandardToolKvStorageHandler("$TOOL_ID.other", repository, { 1 })
            other.set("article", RpcValue.StringValue("other-tool"))
            val legacy = "{\"keep\":\"${"旧正文".repeat(80_000)}\",\"remove\":true}"
            repository.put(TOOL_ID, ToolStorageNamespace.Standard.documentKey, legacy, 1)
            repository.put(TOOL_ID, ToolStorageNamespace.Secure.documentKey, "secure-marker", 1)
            val storage = StandardToolKvStorageHandler(TOOL_ID, repository, { 2 })
            val article = RpcValue.StringValue("完整😀正文".repeat(140_000))
            storage.apply(RuntimeStorageMutation(set = listOf(RuntimeStorageSet("article", article)), remove = listOf("remove")))
            assertNull(repository.observe(TOOL_ID, ToolStorageNamespace.Standard.documentKey).first())
            val beforeKeys = repository.keys(TOOL_ID)
            val before = storage.getMany(listOf("article", "keep", "missing", "article"))
            assertEquals(article, before.first())
            assertEquals(before.first(), before.last())
            assertNull(before[2])
            assertEquals(listOf(RpcValue.StringValue("other-tool")), other.getMany(listOf("article")))
            SQLiteDatabase.openDatabase(context.getDatabasePath("$name.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
                fun installFault() = sql.execSQL("""
                    CREATE TRIGGER injected_storage_failure BEFORE INSERT ON tool_kv
                    WHEN NEW.valueJson LIKE '%fail-this-write%'
                    BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END
                """.trimIndent())
                installFault()
                expectFailure(RuntimeRpcErrorCode.INTERNAL_ERROR) {
                    storage.apply(RuntimeStorageMutation(set = listOf(
                        RuntimeStorageSet("article", RpcValue.StringValue("short")),
                        RuntimeStorageSet("fail", RpcValue.StringValue("fail-this-write")),
                    ), remove = listOf("keep")))
                }
                assertEquals(before, storage.getMany(listOf("article", "keep", "missing", "article")))
                assertEquals(beforeKeys, repository.keys(TOOL_ID))
                sql.execSQL("DROP TRIGGER injected_storage_failure")
                storage.apply(RuntimeStorageMutation(set = listOf(RuntimeStorageSet("article", RpcValue.StringValue("short"))), remove = listOf("keep")))
                assertEquals(2, repository.keys(TOOL_ID).size) // One ordinary row plus the secure namespace.
                storage.clear()
                repository.put(TOOL_ID, ToolStorageNamespace.Standard.documentKey, legacy, 3)
                installFault()
                expectFailure(RuntimeRpcErrorCode.INTERNAL_ERROR) {
                    storage.apply(RuntimeStorageMutation(set = listOf(RuntimeStorageSet("fail", RpcValue.StringValue("fail-this-write"))), remove = listOf("remove")))
                }
                assertEquals(legacy, repository.observe(TOOL_ID, ToolStorageNamespace.Standard.documentKey).first()!!.valueJson)
                assertEquals(setOf(ToolStorageNamespace.Standard.documentKey, ToolStorageNamespace.Secure.documentKey), repository.keys(TOOL_ID).toSet())
                sql.execSQL("DROP TRIGGER injected_storage_failure")
            }
            stores.close()
            stores = CoreDataFactory.create(context, "$name.db", name)
            val reopened = StandardToolKvStorageHandler(TOOL_ID, stores.repositories.keyValues, { 4 })
            assertEquals(listOf(RpcValue.Bool(true), null), reopened.getMany(listOf("remove", "fail")))
            reopened.apply(RuntimeStorageMutation(set = listOf(RuntimeStorageSet("article", article)), remove = listOf("remove")))
            assertEquals(listOf(article, null), reopened.getMany(listOf("article", "remove")))
            assertEquals("secure-marker", stores.repositories.keyValues.observe(TOOL_ID, ToolStorageNamespace.Secure.documentKey).first()!!.valueJson)
        } finally {
            stores.close()
            context.deleteDatabase("$name.db")
        }
    }

    @Test
    fun productionRoomSnapshotsAndPermissionRevocationRemainConsistentAcrossBatches() = runBlocking(Dispatchers.IO) {
        withTimeout(30_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "runtime-batch-snapshot-${UUID.randomUUID()}"
            val stores = CoreDataFactory.create(context, "$name.db", name)
            try {
                stores.repositories.installs.begin(InstallTransaction("tx", TOOL_ID, 1, InstallTransactionState.PREPARING, 1, 1))
                assertTrue(stores.repositories.lifecycle.commitInstall(CatalogInstallAttempt(
                    "tx", ToolMetadata(TOOL_ID, "Snapshot test", SecurityProfile.STRICT, 1),
                    ToolVersion(TOOL_ID, 1, "1.0.0", BundleLocator("tools/test/current"), 1, "hash", 1), emptyList(),
                )) is DataResult.Success)
                val backing = stores.repositories.keyValues
                val oldRows = (0..299).associate { "raw-$it" to "old-$it" }
                val newRows = (0..299).associate { "raw-$it" to "new-$it" }
                assertEquals(DataResult.Success(Unit), backing.replace(TOOL_ID, emptySet(), oldRows, 1))
                val writerStarted = CompletableDeferred<Unit>()
                val writer = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    writerStarted.complete(Unit)
                    backing.replace(TOOL_ID, oldRows.keys, newRows, 2)
                }
                backing.readSnapshot(TOOL_ID) { snapshot ->
                    assertEquals("old-0", snapshot.getMany(setOf("raw-0"))["raw-0"]!!.valueJson)
                    writer.start()
                    writerStarted.await()
                    assertEquals(oldRows, snapshot.getMany(oldRows.keys).mapValues { (_, row) -> row.valueJson })
                }
                assertEquals(DataResult.Success(Unit), writer.await())
                assertEquals(newRows, backing.readSnapshot(TOOL_ID) { it.getMany(newRows.keys).mapValues { (_, row) -> row.valueJson } })
                stores.repositories.grants.put(PermissionGrant(TOOL_ID, "storage", true, 3))
                val prepared = CompletableDeferred<Unit>()
                val resumeCommit = CompletableDeferred<Unit>()
                val repository = object : ToolKvRepository by backing {
                    override suspend fun <T> readSnapshot(toolId: String, action: suspend (ToolKvSnapshot) -> T): T {
                        val result = backing.readSnapshot(toolId, action)
                        prepared.complete(Unit)
                        resumeCommit.await()
                        return result
                    }
                }
                val storage = StandardToolKvStorageHandler(TOOL_ID, repository, { 4 }, canAccess = {
                    stores.repositories.grants.observeGrants(TOOL_ID).first().any { it.capability == "storage" && it.granted }
                })
                val mutation = async(Dispatchers.IO) {
                    expectFailure(RuntimeRpcErrorCode.PERMISSION_DENIED) {
                        storage.apply(RuntimeStorageMutation(set = listOf(RuntimeStorageSet("never-committed", RpcValue.Bool(true)))))
                    }
                }
                prepared.await()
                assertEquals(DataResult.Success(Unit), stores.repositories.grants.put(PermissionGrant(TOOL_ID, "storage", false, 5)))
                val drained = async(Dispatchers.IO) { awaitRuntimeStandardStorageIdle(TOOL_ID) }
                resumeCommit.complete(Unit)
                mutation.await()
                drained.await()
                expectFailure(RuntimeRpcErrorCode.PERMISSION_DENIED) { storage.getMany(listOf("never-committed")) }
                assertEquals(oldRows.keys, backing.keys(TOOL_ID).toSet())
            } finally {
                stores.close()
                context.deleteDatabase("$name.db")
            }
        }
    }

    private suspend fun expectFailure(code: RuntimeRpcErrorCode, action: suspend () -> Unit) {
        try {
            action()
            error("Expected $code")
        } catch (failure: RuntimeHandlerException) {
            assertEquals(code, failure.errorCode)
        }
    }

    private companion object {
        const val TOOL_ID = "io.toolbox.storage.instrumented"
    }
}
