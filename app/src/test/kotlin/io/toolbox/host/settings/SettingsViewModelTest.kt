package io.toolbox.host.settings

import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.ThemeStyle
import io.toolbox.host.HostBackgroundOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<SettingsViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun rapidAppearanceChangesCommitInSelectionOrderAndKeepOtherSettings() = runTest(dispatcher) {
        val repository = RecordingSettingsRepository(
            HostSettings(backgroundEnabled = false, reduceTransparency = true),
        )
        val viewModel = createViewModel(repository)
        runCurrent()

        viewModel.selectThemeStyle(ThemeStyle.MIUIX)
        viewModel.selectThemeStyle(ThemeStyle.LIQUID_GLASS)
        viewModel.selectTheme(ThemeMode.DARK)
        viewModel.selectTheme(ThemeMode.MONET_LIGHT)
        advanceUntilIdle()

        assertEquals(ThemeStyle.LIQUID_GLASS, viewModel.state.value.settings.themeStyle)
        assertEquals(ThemeMode.MONET_LIGHT, viewModel.state.value.settings.theme)
        assertTrue(viewModel.state.value.settings.reduceTransparency)
        assertFalse(viewModel.state.value.settings.backgroundEnabled)
        assertEquals(4, repository.updates.size)
    }

    @Test
    fun failedAppearanceChangeKeepsEffectiveThemeAndCanBeRetried() = runTest(dispatcher) {
        val repository = RecordingSettingsRepository(HostSettings()).apply { failNextUpdate = true }
        val viewModel = createViewModel(repository)
        runCurrent()

        viewModel.selectThemeStyle(ThemeStyle.MIUIX)
        advanceUntilIdle()
        assertEquals(ThemeStyle.LIQUID_GLASS, viewModel.state.value.settings.themeStyle)
        assertEquals("设置未保存，请重试。", viewModel.state.value.error)
        assertTrue(viewModel.state.value.canRetry)

        viewModel.retryAppearanceUpdate()
        advanceUntilIdle()
        assertEquals(ThemeStyle.MIUIX, viewModel.state.value.settings.themeStyle)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.canRetry)
    }

    private fun createViewModel(repository: HostSettingsRepository) =
        SettingsViewModel(repository, EmptyCatalogRepository, NoOpBackgroundOperations)
            .also(viewModels::add)
}

private class RecordingSettingsRepository(initial: HostSettings) : HostSettingsRepository {
    private val mutableSettings = MutableStateFlow(initial)
    override val settings: Flow<HostSettings> = mutableSettings
    val updates = mutableListOf<HostSettings>()
    var failNextUpdate = false

    override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> {
        val next = transform(mutableSettings.value)
        if (failNextUpdate) {
            failNextUpdate = false
            return DataResult.Failure.StorageFailure("injected")
        }
        updates += next
        mutableSettings.value = next
        return DataResult.Success(Unit)
    }
}

private object EmptyCatalogRepository : CatalogRepository {
    override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = flowOf(emptyList())
    override fun observeTools(): Flow<List<InstalledTool>> = flowOf(emptyList())
    override fun observeTool(toolId: String): Flow<InstalledTool?> = flowOf(null)
}

private object NoOpBackgroundOperations : HostBackgroundOperations {
    override fun observeTasks(toolId: String): Flow<List<BackgroundTask>> = emptyFlow()
    override fun observeResult(taskId: String): Flow<TaskRunResult?> = emptyFlow()
    override suspend fun cancel(toolId: String, taskId: String): Boolean = false
    override suspend fun cancelTool(toolId: String) = Unit
    override suspend fun cancelAll(toolIds: Collection<String>) = Unit
}
