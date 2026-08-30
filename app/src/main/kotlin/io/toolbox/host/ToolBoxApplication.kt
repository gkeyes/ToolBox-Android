package io.toolbox.host

import android.app.Application
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.host.background.BackgroundWorkerDependencies
import io.toolbox.host.background.BackgroundWorkerDependencyOwner
import io.toolbox.host.background.BackgroundWorkerDependencyRegistry

class ToolBoxApplication : Application(), BackgroundWorkerDependencyOwner {
    private val workerDependencies = BackgroundWorkerDependencyRegistry {
        val stores = CoreDataFactory.create(this)
        createBackgroundWorkerDependencies(this, stores.repositories)
    }

    override fun backgroundWorkerDependencies(): BackgroundWorkerDependencies =
        workerDependencies.backgroundWorkerDependencies()
}
