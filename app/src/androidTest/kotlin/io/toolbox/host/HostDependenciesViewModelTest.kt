package io.toolbox.host

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostDependenciesViewModelTest {
    @Test
    fun maintenanceWaitsForHostFirstFrameAndFailureDoesNotReplaceReady() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val maintenanceStarted = CompletableDeferred<Unit>()
        val maintenanceMayFinish = CompletableDeferred<Unit>()
        val maintenanceFinished = CompletableDeferred<Unit>()
        val maintenanceRuns = AtomicInteger()
        val store = ViewModelStore()
        lateinit var viewModel: HostDependenciesViewModel

        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(
                store,
                HostDependenciesFactory(
                    application = application,
                    maintenance = HostRuntimeMaintenance {
                        maintenanceRuns.incrementAndGet()
                        maintenanceStarted.complete(Unit)
                        maintenanceMayFinish.await()
                        maintenanceFinished.complete(Unit)
                        throw IllegalStateException("fixture maintenance failure")
                    },
                ),
            )[HostDependenciesViewModel::class.java]
        }

        try {
            val ready = withTimeout(10_000) {
                viewModel.state.first { it is HostBootstrapState.Ready }
            }
            assertTrue("Maintenance must not run before the host reports its first frame", !maintenanceStarted.isCompleted)
            assertEquals(0, maintenanceRuns.get())

            instrumentation.runOnMainSync(viewModel::onHostFirstFrame)
            withTimeout(5_000) { maintenanceStarted.await() }

            maintenanceMayFinish.complete(Unit)
            withTimeout(5_000) { maintenanceFinished.await() }
            instrumentation.waitForIdleSync()

            assertSame(ready, viewModel.state.value)
            assertEquals(1, maintenanceRuns.get())
        } finally {
            maintenanceMayFinish.complete(Unit)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync(store::clear)
        }
    }

    private class HostDependenciesFactory(
        private val application: Application,
        private val maintenance: HostRuntimeMaintenance,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HostDependenciesViewModel(application, maintenance) as T
    }
}
