package io.toolbox.host.permissions

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolPermissionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ToolPermissionsViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun toolsAreReadOnlyWhileCollectedAndTheLastListIsKept() = runTest(dispatcher) {
        val fixture = PermissionTestFixture(backgroundScope)
        val model = ToolPermissionsViewModel(fixture, fixture, fixture).also(models::add)
        assertFalse(model.state.value.toolsLoaded)
        assertTrue(model.state.value.tools.isEmpty())

        val collector = launch { model.state.collect {} }
        runCurrent()
        assertTrue(model.state.value.toolsLoaded)
        assertEquals(listOf(PERMISSION_TOOL_ID), model.state.value.tools.map { it.metadata.id })

        collector.cancel()
        runCurrent()
        fixture.installed.value = null
        runCurrent()
        assertEquals(listOf(PERMISSION_TOOL_ID), model.state.value.tools.map { it.metadata.id })

        val again = launch { model.state.collect {} }
        runCurrent()
        assertTrue(model.state.value.toolsLoaded)
        assertTrue(model.state.value.tools.isEmpty())
        again.cancel()
    }
}
