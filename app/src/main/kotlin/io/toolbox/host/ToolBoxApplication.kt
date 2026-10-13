package io.toolbox.host

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.CoreDataStores
import io.toolbox.host.background.BackgroundWorkerDependencies
import io.toolbox.host.background.BackgroundWorkerDependencyOwner
import io.toolbox.host.background.BackgroundWorkerDependencyRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ToolBoxApplication : Application(), BackgroundWorkerDependencyOwner {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Before providers or any other android.webkit API can initialize this process.
        if (getProcessName() == "$packageName:browser") {
            WebView.setDataDirectorySuffix("browser")
        }
    }

    @Volatile
    private var stores: CoreDataStores? = null
    @Volatile
    private var dependencies: HostDependencies? = null
    // Suspending initialisation: callers wait without blocking a thread while an interrupted
    // backup restore is rolled back, instead of runBlocking under the Application lock.
    private val dependencyInitialization = Mutex()

    private val workerDependencies = BackgroundWorkerDependencyRegistry {
        createBackgroundWorkerDependencies(this, hostDependencies().repositories)
    }

    internal suspend fun hostDependencies(): HostDependencies {
        check(getProcessName() != "$packageName:browser") { "Browser must not initialize tool dependencies" }
        dependencies?.let { return it }
        return dependencyInitialization.withLock {
            dependencies ?: withContext(Dispatchers.IO) {
                val openedStores = HostTrace.bestEffortSection("coreData.create") { CoreDataFactory.create(this@ToolBoxApplication) }
                try {
                    ProductionHostDependenciesFactory.create(this@ToolBoxApplication, openedStores).also { created ->
                        stores = openedStores
                        dependencies = created
                    }
                } catch (failure: Exception) {
                    openedStores.close()
                    throw failure
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Do not initialize persistence on the main thread just because Android changed configuration.
        dependencies?.runtimeSessions?.onSystemConfigurationChanged(newConfig)
    }

    override suspend fun backgroundWorkerDependencies(): BackgroundWorkerDependencies =
        workerDependencies.backgroundWorkerDependencies()
}
