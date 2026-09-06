package io.toolbox.host.catalog

import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private val createdViewModels = mutableListOf<CatalogViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        mainDispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        catalog: CatalogRepository,
        organization: CatalogOrganizationRepository,
        now: () -> Long = { 123L },
    ) = CatalogViewModel(catalog, organization, UnusedHostPackageOperations, now)
        .also(createdViewModels::add)

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
    fun slowStatisticsDoNotBlockNavigationAndQueuedDuplicatesAreCoalesced() = runTest(mainDispatcher) {
        val catalog = FakeCatalogRepository()
        val writeGate = CompletableDeferred<Unit>()
        val started = mutableListOf<Pair<String, Long>>()
        val organization = RecordingCatalogOrganizationRepository { id, timestamp ->
            started += id to timestamp
            if (started.size == 1) writeGate.await()
            DataResult.Success(Unit)
        }
        var clock = 123L
        val viewModel = createViewModel(catalog, organization, now = { clock })
        catalog.entries.emit(listOf(catalogEntry()))
        runCurrent()

        // No collector yet: a double tap must queue one intent and one statistics write.
        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        runCurrent()
        assertEquals(listOf(TOOL_ID to 123L), started)
        assertTrue(organization.opened.isEmpty())

        val intents = mutableListOf<CatalogNavigationIntent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.navigation.toList(intents) }
        assertEquals(listOf(CatalogNavigationIntent.RequestRuntimeLaunch(TOOL_ID)), intents)
        assertFalse(writeGate.isCompleted)

        // After consumption a later open is valid, even while the earlier write is suspended.
        clock = 456L
        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        clock = 999L
        runCurrent()
        assertEquals(2, intents.size)
        assertEquals(1, started.size)
        writeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(TOOL_ID to 123L, TOOL_ID to 456L), organization.opened)
        assertEquals(2, intents.size) // Completing persistence must never navigate again.
    }

    @Test
    fun statisticsFailuresAreNonBlockingAndSuccessfulRetryClearsTheirFeedback() = runTest(mainDispatcher) {
        val failures: List<suspend () -> DataResult<Unit>> = listOf(
            { DataResult.Failure.StorageFailure("recordOpened") },
            { throw IllegalStateException("simulated storage failure") },
        )
        for (failure in failures) {
            val catalog = FakeCatalogRepository()
            var fail = true
            val organization = RecordingCatalogOrganizationRepository { _, _ ->
                if (fail) failure() else DataResult.Success(Unit)
            }
            val viewModel = createViewModel(catalog, organization)
            val intents = mutableListOf<CatalogNavigationIntent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.navigation.toList(intents) }
            catalog.entries.emit(listOf(catalogEntry()))
            runCurrent()

            viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
            runCurrent()
            assertEquals(listOf(CatalogNavigationIntent.RequestRuntimeLaunch(TOOL_ID)), intents)
            val feedback = viewModel.state.value.feedback as CatalogFeedback.Failure
            assertEquals("RECENT_UPDATE_FAILED", feedback.code)
            assertTrue(feedback.message.contains("最近使用记录未保存"))
            assertTrue(organization.opened.isEmpty())

            fail = false
            viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
            runCurrent()
            assertEquals(2, intents.size)
            assertEquals(listOf(TOOL_ID to 123L), organization.opened)
            assertNull(viewModel.state.value.feedback)
        }
    }

    @Test
    fun cancelledStatisticsDoNotReportAnOpenFailureOrPreventTheNextOpen() = runTest(mainDispatcher) {
        val catalog = FakeCatalogRepository()
        var attempts = 0
        val organization = RecordingCatalogOrganizationRepository { _, _ ->
            if (++attempts == 1) throw CancellationException("cancelled statistics")
            DataResult.Success(Unit)
        }
        val viewModel = createViewModel(catalog, organization)
        val intents = mutableListOf<CatalogNavigationIntent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.navigation.toList(intents) }
        catalog.entries.emit(listOf(catalogEntry()))
        runCurrent()

        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        runCurrent()
        assertEquals(1, intents.size)
        assertNull(viewModel.state.value.feedback)
        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        runCurrent()
        assertEquals(2, intents.size)
        assertEquals(listOf(TOOL_ID to 123L), organization.opened)
        assertNull(viewModel.state.value.feedback)
    }

    @Test
    fun delayedNavigationDropsRemovedToolsAndAllowsANewRequestAfterReinstallation() = runTest(mainDispatcher) {
        val catalog = FakeCatalogRepository()
        val organization = RecordingCatalogOrganizationRepository()
        val viewModel = createViewModel(catalog, organization)
        catalog.entries.emit(listOf(catalogEntry()))
        runCurrent()
        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        runCurrent()

        catalog.entries.emit(emptyList())
        runCurrent()
        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        val intents = mutableListOf<CatalogNavigationIntent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.navigation.toList(intents) }
        runCurrent()
        assertTrue(intents.isEmpty())
        assertEquals(1, organization.opened.size)

        catalog.entries.emit(listOf(catalogEntry().copy(versionCode = 2, version = "2.0.0")))
        runCurrent()
        viewModel.dispatch(CatalogAction.RequestRuntimeLaunch(TOOL_ID))
        runCurrent()
        assertEquals(listOf(CatalogNavigationIntent.RequestRuntimeLaunch(TOOL_ID)), intents)
        assertEquals(2, viewModel.state.value.tools.single().versionCode)
        assertEquals(2, organization.opened.size)
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

private class RecordingCatalogOrganizationRepository(
    private val persist: suspend (String, Long) -> DataResult<Unit> = { _, _ -> DataResult.Success(Unit) },
) : CatalogOrganizationRepository {
    val opened = mutableListOf<Pair<String, Long>>()

    override suspend fun setPinnedOrder(toolId: String, pinnedOrder: Int?): DataResult<Unit> =
        error("not used")

    override suspend fun setCategory(toolId: String, categoryId: String?): DataResult<Unit> =
        error("not used")

    override suspend fun recordOpened(toolId: String, timestamp: Long): DataResult<Unit> {
        val result = persist(toolId, timestamp)
        if (result is DataResult.Success) opened += toolId to timestamp
        return result
    }
}

private object UnusedHostPackageOperations : HostPackageOperations {
    override suspend fun importPackage(input: PackageInput): HostImportResult = error("not used")
    override suspend fun confirmImport(confirmationId: String): HostImportResult = error("not used")
    override suspend fun cancelImport(confirmationId: String): io.toolbox.host.HostImportCancellationResult = error("not used")
    override suspend fun installedManifest(toolId: String): HostInstalledManifestResult = error("not used")
    override suspend fun deleteTool(toolId: String): HostDeleteResult = error("not used")
    override suspend fun installBundledExamples(): HostExampleInstallResult = error("not used")
}
