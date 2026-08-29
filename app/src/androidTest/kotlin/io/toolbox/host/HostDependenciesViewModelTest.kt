package io.toolbox.host

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.CoreDataStores
import io.toolbox.tool.packagekit.ToolPackageInspector
import io.toolbox.tool.packagekit.ToolPackageInspectors
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycles
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.ToolRuntimePreparer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    fun readyDefersNonCoreFactoriesUntilTheirFirstRequiredAccess() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val inspectorCreates = AtomicInteger()
        val lifecycleCreates = AtomicInteger()
        val createdInspector = AtomicReference<ToolPackageInspector?>()
        val lifecycleInspector = AtomicReference<ToolPackageInspector?>()
        val runtimePreparerCreates = AtomicInteger()
        val runtimeProfileManagerCreates = AtomicInteger()
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
                        it.reapMarkedOrphanProfiles(emptySet())
                        maintenanceStarted.complete(Unit)
                        maintenanceMayFinish.await()
                        maintenanceFinished.complete(Unit)
                        throw IllegalStateException("fixture maintenance failure")
                    },
                    dependenciesFactory = HostDependenciesFactory { app, stores ->
                        deferredDependencies(
                            application = app,
                            stores = stores,
                            inspectorCreates = inspectorCreates,
                            lifecycleCreates = lifecycleCreates,
                            createdInspector = createdInspector,
                            lifecycleInspector = lifecycleInspector,
                            runtimePreparerCreates = runtimePreparerCreates,
                            runtimeProfileManagerCreates = runtimeProfileManagerCreates,
                        )
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
            assertEquals(0, inspectorCreates.get())
            assertEquals(0, lifecycleCreates.get())
            assertEquals(0, runtimePreparerCreates.get())
            assertEquals(0, runtimeProfileManagerCreates.get())

            val dependencies = (ready as HostBootstrapState.Ready).dependencies
            dependencies.inspector
            dependencies.lifecycle
            dependencies.runtimeDataCleaner
            dependencies.runtimePermitProvider
            assertEquals(0, inspectorCreates.get())
            assertEquals(0, lifecycleCreates.get())
            assertEquals(0, runtimePreparerCreates.get())
            assertEquals(0, runtimeProfileManagerCreates.get())

            instrumentation.runOnMainSync(viewModel::onHostFirstFrame)
            withTimeout(5_000) { maintenanceStarted.await() }
            assertEquals(0, inspectorCreates.get())
            assertEquals(0, lifecycleCreates.get())
            assertEquals(0, runtimePreparerCreates.get())
            assertEquals(1, runtimeProfileManagerCreates.get())

            dependencies.inspector.discard("missing-session")
            assertEquals(1, inspectorCreates.get())
            assertEquals(0, lifecycleCreates.get())
            dependencies.lifecycle.recover()
            assertEquals(1, lifecycleCreates.get())
            assertSame(createdInspector.get(), lifecycleInspector.get())
            dependencies.runtimePreparer
            assertEquals(1, runtimePreparerCreates.get())

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

    private fun deferredDependencies(
        application: Application,
        stores: CoreDataStores,
        inspectorCreates: AtomicInteger,
        lifecycleCreates: AtomicInteger,
        createdInspector: AtomicReference<ToolPackageInspector?>,
        lifecycleInspector: AtomicReference<ToolPackageInspector?>,
        runtimePreparerCreates: AtomicInteger,
        runtimeProfileManagerCreates: AtomicInteger,
    ): HostDependencies = HostDependencies(
        repositories = stores.repositories,
        inspectorFactory = {
            inspectorCreates.incrementAndGet()
            ToolPackageInspectors.create(File(application.filesDir, "inspection-sessions").toPath())
                .also(createdInspector::set)
        },
        lifecycleFactory = { inspector ->
            lifecycleCreates.incrementAndGet()
            lifecycleInspector.set(inspector)
            ToolPackageLifecycles.create(application.filesDir, inspector, stores.repositories.lifecycle)
        },
        runtimePreparerFactory = {
            runtimePreparerCreates.incrementAndGet()
            ToolRuntimePreparer(application.filesDir)
        },
        runtimeProfileManagerFactory = {
            runtimeProfileManagerCreates.incrementAndGet()
            RuntimeProfileManager(application.filesDir)
        },
    )

    private class HostDependenciesFactory(
        private val application: Application,
        private val maintenance: HostRuntimeMaintenance,
        private val dependenciesFactory: io.toolbox.host.HostDependenciesFactory,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HostDependenciesViewModel(application, maintenance, dependenciesFactory) as T
    }
}
