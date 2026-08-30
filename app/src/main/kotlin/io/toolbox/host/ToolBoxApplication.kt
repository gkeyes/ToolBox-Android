package io.toolbox.host

import android.app.Application
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.CoreDataStores
import io.toolbox.host.background.BackgroundWorkerDependencies
import io.toolbox.host.background.BackgroundWorkerDependencyOwner
import io.toolbox.host.background.BackgroundWorkerDependencyRegistry

class ToolBoxApplication : Application(), BackgroundWorkerDependencyOwner {
    @Volatile
    private var stores: CoreDataStores? = null
    @Volatile
    private var dependencies: HostDependencies? = null

    private val workerDependencies = BackgroundWorkerDependencyRegistry {
        createBackgroundWorkerDependencies(this, hostDependencies().repositories)
    }

    internal fun hostDependencies(): HostDependencies = dependencies ?: synchronized(this) {
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

    override fun backgroundWorkerDependencies(): BackgroundWorkerDependencies =
        workerDependencies.backgroundWorkerDependencies()
}
