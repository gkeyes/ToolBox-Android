package io.toolbox.host.runtime

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.api.ToolBoxCapabilityId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeGrantSnapshotTest {
    private val toolId = "io.example.snapshot"

    @Test
    fun repeatedChecksReuseOneSnapshotUntilTheEpochAdvances() = runBlocking {
        val store = CountingStore()
        var epoch = 1L
        val source = RepositoryRuntimeGrantStateSource(store, store) { epoch }

        repeat(8) {
            assertEquals(1, source.currentVersionCode(toolId))
            assertTrue(source.isGranted(toolId, ToolBoxCapabilityId.NETWORK))
        }
        assertEquals(1, store.toolReads)
        assertEquals(1, store.grantReads)

        store.networkGranted = false
        store.versionCode = 2
        epoch++
        assertFalse(source.isGranted(toolId, ToolBoxCapabilityId.NETWORK))
        assertEquals(2, source.currentVersionCode(toolId))
        assertEquals(2, store.toolReads)
        assertEquals(2, store.grantReads)
    }

    private inner class CountingStore : CatalogRepository, PermissionGrantRepository {
        var toolReads = 0
        var grantReads = 0
        var versionCode = 1
        var networkGranted = true

        override fun observeTool(toolId: String): Flow<InstalledTool?> = flow {
            toolReads++
            emit(InstalledTool(
                ToolMetadata(toolId, "Snapshot", SecurityProfile.STRICT, 1L),
                ToolVersion(toolId, versionCode, "1.0.$versionCode", BundleLocator("tools/snapshot"), 1, "hash", 1L),
                null,
            ))
        }

        override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> = flow {
            grantReads++
            emit(listOf(PermissionGrant(toolId, "network", networkGranted, 1L)))
        }

        override fun observeTools(): Flow<List<InstalledTool>> = flowOf(emptyList())
        override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = flowOf(emptyList())
        override suspend fun put(grant: PermissionGrant): DataResult<Unit> = error("unused")
        override suspend fun putForVersion(grant: PermissionGrant, expectedVersionCode: Int): DataResult<Unit> = error("unused")
    }
}
