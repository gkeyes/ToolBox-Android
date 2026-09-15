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

    private val workerDependencies = BackgroundWorkerDependencyRegistry {
        createBackgroundWorkerDependencies(this, hostDependencies().repositories)
    }

    internal fun hostDependencies(): HostDependencies {
        check(getProcessName() != "$packageName:browser") { "Browser must not initialize tool dependencies" }
        return dependencies ?: synchronized(this) {
            dependencies ?: CoreDataFactory.create(this).let { openedStores ->
                try {
                    ProductionHostDependenciesFactory.create(this, openedStores).also { created ->
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

    override fun backgroundWorkerDependencies(): BackgroundWorkerDependencies =
        workerDependencies.backgroundWorkerDependencies()
}
