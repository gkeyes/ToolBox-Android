package io.toolbox.host.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstallTransaction
import io.toolbox.core.data.InstallTransactionState
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.runtime.RpcValue
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    private companion object {
        const val TOOL_ID = "io.toolbox.storage.instrumented"
    }
}
