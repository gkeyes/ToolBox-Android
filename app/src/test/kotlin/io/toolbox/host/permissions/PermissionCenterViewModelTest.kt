package io.toolbox.host.permissions

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.host.HostInstalledManifest
import io.toolbox.host.HostManifestPermission
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostPermissionSideEffects
import io.toolbox.tool.packagekit.PackageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionCenterViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun permissionListUsesManifestAndToggleWritesGrantState() = runTest(mainDispatcher) {
        val grants = FakePermissionGrantRepository(
            PermissionGrant(
                toolId = TOOL_ID,
                capability = "storage",
                granted = true,
                updatedAt = 100L,
            ),
        )
        val sideEffects = RecordingPermissionSideEffects()
        val viewModel = PermissionCenterViewModel(
            toolId = TOOL_ID,
            packages = FakeHostPackageOperations,
            grants = grants,
            sideEffects = sideEffects,
            now = { 200L },
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.loaded)
        assertEquals("工具示例", viewModel.state.value.toolName)
        assertEquals(
            listOf("storage", "clipboard.write"),
            viewModel.state.value.items.map(PermissionItem::capability),
        )
        assertTrue(viewModel.state.value.items.first().enabled)

        viewModel.setEnabled("storage", false)
        advanceUntilIdle()

        assertEquals(
            listOf(PermissionGrant(TOOL_ID, "storage", false, 200L)),
            grants.putCalls,
        )
        assertEquals(listOf("$TOOL_ID:storage"), sideEffects.disabled)
        assertFalse(viewModel.state.value.items.first().enabled)

        viewModel.setEnabled("unknown", true)
        advanceUntilIdle()
        assertEquals(1, grants.putCalls.size)
    }

    @Test
    fun unknownCapabilityCannotBeMutated() = runTest(mainDispatcher) {
        val grants = FakePermissionGrantRepository()
        val viewModel = PermissionCenterViewModel(
            toolId = TOOL_ID,
            packages = FakeHostPackageOperations,
            grants = grants,
            sideEffects = RecordingPermissionSideEffects(),
        )
        advanceUntilIdle()

        viewModel.setEnabled("network", true)
        advanceUntilIdle()

        assertTrue(grants.putCalls.isEmpty())
    }
}

private const val TOOL_ID = "io.toolbox.example"

private object FakeHostPackageOperations : HostPackageOperations {
    override suspend fun importPackage(input: PackageInput): io.toolbox.host.HostImportResult =
        error("not used")

    override suspend fun installedManifest(toolId: String): HostInstalledManifest? =
        if (toolId == TOOL_ID) {
            HostInstalledManifest(
                toolId = TOOL_ID,
                toolName = "工具示例",
                versionCode = 1,
                versionName = "1.0.0",
                permissions = listOf(
                    HostManifestPermission("storage", "保存工具数据", required = false),
                    HostManifestPermission("clipboard.write", "复制计算结果", required = false),
                ),
            )
        } else {
            null
        }

    override suspend fun deleteTool(toolId: String): io.toolbox.host.HostDeleteResult = error("not used")

    override suspend fun installBundledExamples(): io.toolbox.host.HostExampleInstallResult = error("not used")
}

private class RecordingPermissionSideEffects : HostPermissionSideEffects {
    val disabled = mutableListOf<String>()

    override suspend fun onCapabilityDisabled(toolId: String, capability: String) {
        disabled += "$toolId:$capability"
    }
}

private class FakePermissionGrantRepository(vararg initial: PermissionGrant) : PermissionGrantRepository {
    private val values = MutableStateFlow(initial.toList())
    val putCalls = mutableListOf<PermissionGrant>()

    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> = values

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> {
        putCalls += grant
        values.value = values.value.filterNot { it.toolId == grant.toolId && it.capability == grant.capability } + grant
        return DataResult.Success(Unit)
    }

    override suspend fun revoke(toolId: String, capability: String): DataResult<Unit> =
        error("not used")
}
