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
    fun readyDoesNotWaitForRuntimeMaintenanceAndMaintenanceFailureDoesNotReplaceReady() = runBlocking {
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
            withTimeout(10_000) { maintenanceStarted.await() }
            val ready = viewModel.state.value
            assertTrue("Runtime maintenance must start only after Ready is published", ready is HostBootstrapState.Ready)

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
