package io.toolbox.host.catalog

import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogOrganizationRepository
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.SecurityProfile
import io.toolbox.host.HostDeleteResult
import io.toolbox.host.HostExampleInstallResult
import io.toolbox.host.HostImportResult
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.tool.packagekit.PackageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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
class CatalogViewModelTest {
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
    fun launchRequestedBeforeCatalogLoadOpensToolAfterCatalogArrives() = runTest(mainDispatcher) {
        val catalog = FakeCatalogRepository()
        val organization = RecordingCatalogOrganizationRepository()
        val viewModel = CatalogViewModel(
            catalog = catalog,
            organization = organization,
            packageOperations = UnusedHostPackageOperations,
            now = { 123L },
        )

        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        catalog.entries.emit(listOf(catalogEntry()))
        advanceUntilIdle()

        assertEquals(
            CatalogNavigationIntent.RequestRuntimeLaunch(TOOL_ID),
            viewModel.navigation.first(),
        )
        assertEquals(listOf(TOOL_ID to 123L), organization.opened)
    }

    @Test
    fun catalogPresentationSortsRecentToolsAndCapsTheMediumLayoutAtThree() = runTest(mainDispatcher) {
        val catalog = FakeCatalogRepository()
        val viewModel = CatalogViewModel(
            catalog = catalog,
            organization = RecordingCatalogOrganizationRepository(),
            packageOperations = UnusedHostPackageOperations,
        )

        catalog.entries.emit(
            listOf(
                catalogEntry("io.toolbox.one", "工具一", 10L),
                catalogEntry("io.toolbox.two", "工具二", 40L),
                catalogEntry("io.toolbox.three", "工具三", 20L),
                catalogEntry("io.toolbox.four", "工具四", 30L),
                catalogEntry("io.toolbox.never", "未打开", null),
            ),
        )
        advanceUntilIdle()

        assertEquals(MAX_RECENT_TOOL_COUNT, viewModel.state.value.recentTools.size)
        assertEquals(
            listOf("io.toolbox.two", "io.toolbox.four", "io.toolbox.three"),
            viewModel.state.value.recentTools.map(CatalogTool::toolId),
        )
        assertEquals(2, COMPACT_RECENT_TOOL_COUNT)
    }

    @Test
    fun searchFiltersThePreparedListAndMarksRecentToolsHidden() = runTest(mainDispatcher) {
        val catalog = FakeCatalogRepository()
        val viewModel = CatalogViewModel(
            catalog = catalog,
            organization = RecordingCatalogOrganizationRepository(),
            packageOperations = UnusedHostPackageOperations,
        )
        catalog.entries.emit(
            listOf(
                catalogEntry("io.toolbox.notes", "快速笔记", 30L),
                catalogEntry("io.toolbox.notify", "通知实验室", 20L),
                catalogEntry("io.toolbox.calc", "仓位计算器", 10L),
            ),
        )
        advanceUntilIdle()

        viewModel.dispatch(CatalogAction.SetQuery("  note  "))

        assertTrue(viewModel.state.value.isSearching)
        assertEquals(listOf("io.toolbox.notes"), viewModel.state.value.visibleTools.map(CatalogTool::toolId))

        viewModel.dispatch(CatalogAction.SetQuery(""))

        assertFalse(viewModel.state.value.isSearching)
        assertEquals(3, viewModel.state.value.visibleTools.size)
    }
}

private const val TOOL_ID = "io.toolbox.notificationlab"

private fun catalogEntry(
    toolId: String = TOOL_ID,
    name: String = "通知实验室",
    lastOpenedAt: Long? = null,
) = CatalogEntry(
    toolId = toolId,
    name = name,
    securityProfile = SecurityProfile.STRICT,
    installedAt = 1L,
    lastOpenedAt = lastOpenedAt,
    pinnedOrder = null,
    categoryId = null,
    versionCode = 1,
    version = "1.0.0",
    bundleBytes = 1024L,
)

private class FakeCatalogRepository : CatalogRepository {
    val entries = MutableSharedFlow<List<CatalogEntry>>(replay = 1)

    override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = entries
    override fun observeTools(): Flow<List<InstalledTool>> = emptyFlow()
    override fun observeTool(toolId: String): Flow<InstalledTool?> = emptyFlow()
}

private class RecordingCatalogOrganizationRepository : CatalogOrganizationRepository {
    val opened = mutableListOf<Pair<String, Long>>()

    override suspend fun setPinnedOrder(toolId: String, pinnedOrder: Int?): DataResult<Unit> =
        error("not used")

    override suspend fun setCategory(toolId: String, categoryId: String?): DataResult<Unit> =
        error("not used")

    override suspend fun recordOpened(toolId: String, timestamp: Long): DataResult<Unit> {
        opened += toolId to timestamp
        return DataResult.Success(Unit)
    }
}

private object UnusedHostPackageOperations : HostPackageOperations {
    override suspend fun importPackage(input: PackageInput): HostImportResult = error("not used")
    override suspend fun installedManifest(toolId: String): HostInstalledManifestResult = error("not used")
    override suspend fun deleteTool(toolId: String): HostDeleteResult = error("not used")
    override suspend fun installBundledExamples(): HostExampleInstallResult = error("not used")
}
