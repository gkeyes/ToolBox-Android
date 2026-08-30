package io.toolbox.host

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.CoreDataInitializationException
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.CoreDataStores
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.permissions.PermissionCenterViewModel
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.host.runtime.RuntimeSessionManager
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.tool.runtime.RuntimeDataCleaner
import io.toolbox.tool.runtime.RuntimeDataCleanupExecution
import io.toolbox.tool.runtime.RuntimeDataCleanupResult
import io.toolbox.tool.runtime.RuntimePermitProvider
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.ToolRuntimePreparer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HostDependencies(
    private val application: Application,
    val repositories: CoreDataRepositories,
    private val packageOperationsFactory: (RuntimeDataCleaner, HostBackgroundOperations) -> HostPackageOperations,
    val backgroundOperations: HostBackgroundOperations,
    val permissionSideEffects: HostPermissionSideEffects,
    private val runtimePreparerFactory: () -> ToolRuntimePreparer,
    private val runtimeProfileManagerFactory: () -> RuntimeProfileManager,
    private val runtimeBridgeProviderFactory: (
        HostRuntimeM2HandlerFactory,
        (io.toolbox.tool.runtime.PreparedToolRuntime) -> io.toolbox.host.runtime.HostRuntimeContinuityHandlers,
    ) -> io.toolbox.tool.runtime.RuntimeBridgeProvider,
    private val runtimeM2HandlerFactory: HostRuntimeM2HandlerFactory,
) {
    private val deferredRuntimeProfileManager = lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
        runtimeProfileManagerFactory,
    )

    val runtimePreparer: ToolRuntimePreparer by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
        runtimePreparerFactory,
    )

    val runtimeDataCleaner: RuntimeDataCleaner = object : RuntimeDataCleaner {
        override suspend fun <T> clearThenRun(
            toolId: String,
            action: suspend () -> T,
        ): RuntimeDataCleanupExecution<T> =
            deferredRuntimeProfileManager.value.clearThenRun(toolId, action)
    }

    val runtimePermitProvider = RuntimePermitProvider { toolId, awaitExistingRuntimeRelease ->
        deferredRuntimeProfileManager.value.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease)
    }

    val runtimeBridgeProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runtimeBridgeProviderFactory(runtimeM2HandlerFactory, runtimeSessions::handlers)
    }

    val runtimeSessions: RuntimeSessionManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RuntimeSessionManager(
            context = application,
            repositories = repositories,
            preparer = runtimePreparer,
            permitProvider = runtimePermitProvider,
            bridgeProvider = { runtimeBridgeProvider },
        )
    }

    val packageOperations: HostPackageOperations by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        packageOperationsFactory(runtimeDataCleaner, backgroundOperations)
    }

    suspend fun reapMarkedOrphanProfiles(installedToolIds: Set<String>): RuntimeDataCleanupResult =
        deferredRuntimeProfileManager.value.reapMarkedOrphanProfiles(installedToolIds)

    suspend fun recoverPendingPackageMutations() {
        (packageOperations as? HostPackageMaintenance)?.recoverPendingMutations()
    }

    suspend fun reconcileBackgroundTasks() {
        (backgroundOperations as? HostBackgroundMaintenance)?.reconcile()
    }
}

internal fun interface HostDependenciesFactory {
    fun create(application: Application, stores: CoreDataStores): HostDependencies
}

internal object ProductionHostDependenciesFactory : HostDependenciesFactory {
    override fun create(application: Application, stores: CoreDataStores): HostDependencies {
        val backgroundOperations = ProductionHostBackgroundOperations(application, stores.repositories)
        lateinit var dependencies: HostDependencies
        dependencies = HostDependencies(
            application = application,
            repositories = stores.repositories,
            packageOperationsFactory = { runtimeDataCleaner, background ->
                ProductionHostPackageOperations(
                    application = application,
                    repositories = stores.repositories,
                    runtimeDataCleaner = runtimeDataCleaner,
                    background = background,
                )
            },
            backgroundOperations = backgroundOperations,
            permissionSideEffects = backgroundOperations,
            runtimePreparerFactory = { ToolRuntimePreparer(application.filesDir, BuildConfig.VERSION_NAME) },
            runtimeProfileManagerFactory = { RuntimeProfileManager(application.filesDir) },
            runtimeBridgeProviderFactory = { m2Handlers, continuityHandlers ->
                io.toolbox.host.runtime.HostRuntimeBridgeProvider(
                    context = application,
                    repositories = stores.repositories,
                    m2HandlerFactory = m2Handlers,
                    continuityHandlerFactory = continuityHandlers,
                )
            },
            runtimeM2HandlerFactory = backgroundOperations,
        )
        backgroundOperations.attachRuntimeSessions(dependencies.runtimeSessions)
        return dependencies
    }
}

internal sealed interface HostBootstrapState {
    data object Loading : HostBootstrapState

    data class Ready(val dependencies: HostDependencies) : HostBootstrapState

    data class Error(
        val code: HostBootstrapErrorCode,
        val message: String,
    ) : HostBootstrapState
}

internal enum class HostBootstrapErrorCode {
    CORE_DATA_MAIN_THREAD,
    SETTINGS_PATH_UNAVAILABLE,
    PRIVATE_STORAGE_UNAVAILABLE,
}

internal fun interface HostRuntimeMaintenance {
    suspend fun run(dependencies: HostDependencies): RuntimeDataCleanupResult
}

private object ProductionHostRuntimeMaintenance : HostRuntimeMaintenance {
    override suspend fun run(dependencies: HostDependencies): RuntimeDataCleanupResult =
        withContext(Dispatchers.IO) {
            val traceCookie = System.identityHashCode(dependencies)
            val traceOpen = HostTrace.tryBeginAsyncSection("runtimeProfile.cleanup", traceCookie)
            try {
                dependencies.recoverPendingPackageMutations()
                dependencies.reconcileBackgroundTasks()
                val installedToolIds = dependencies.repositories.catalog.observeCatalogProjection()
                    .first()
                    .mapTo(hashSetOf()) { it.toolId }
                dependencies.reapMarkedOrphanProfiles(installedToolIds)
            } finally {
                if (traceOpen) HostTrace.bestEffortEndAsyncSection("runtimeProfile.cleanup", traceCookie)
            }
        }
}

internal class HostDependenciesViewModel(
    application: Application,
    private val runtimeMaintenance: HostRuntimeMaintenance,
    private val dependenciesFactory: HostDependenciesFactory = ProductionHostDependenciesFactory,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        ProductionHostRuntimeMaintenance,
        ProductionHostDependenciesFactory,
    )

    private val mutableState = MutableStateFlow<HostBootstrapState>(HostBootstrapState.Loading)
    val state: StateFlow<HostBootstrapState> = mutableState.asStateFlow()

    private var stores: CoreDataStores? = null
    private var maintenanceStarted = false

    init {
        initialize()
    }

    fun retry() {
        if (mutableState.value !is HostBootstrapState.Error) return
        initialize()
    }

    fun onHostFirstFrame() {
        val dependencies = (mutableState.value as? HostBootstrapState.Ready)?.dependencies ?: return
        if (maintenanceStarted) return
        maintenanceStarted = true
        launchBestEffortRuntimeMaintenance(dependencies)
    }

    private fun initialize() {
        maintenanceStarted = false
        mutableState.value = HostBootstrapState.Loading
        viewModelScope.launch {
            var openedStores: CoreDataStores? = null
            try {
                val dependencies = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    if (app is ToolBoxApplication && dependenciesFactory === ProductionHostDependenciesFactory) {
                        return@withContext HostTrace.bestEffortSection("coreData.create") {
                            app.hostDependencies()
                        }
                    }
                    val createdStores = HostTrace.bestEffortSection("coreData.create") { CoreDataFactory.create(app) }
                    openedStores = createdStores
                    dependenciesFactory.create(app, createdStores)
                }
                coroutineContext.ensureActive()
                stores = openedStores
                mutableState.value = HostBootstrapState.Ready(dependencies)
            } catch (cancelled: CancellationException) {
                openedStores?.close()
                throw cancelled
            } catch (failure: CoreDataInitializationException) {
                openedStores?.close()
                mutableState.value = HostBootstrapState.Error(
                    code = when (failure.reason) {
                        CoreDataInitializationException.Reason.MAIN_THREAD_INITIALIZATION ->
                            HostBootstrapErrorCode.CORE_DATA_MAIN_THREAD
                        CoreDataInitializationException.Reason.SETTINGS_PATH_UNAVAILABLE ->
                            HostBootstrapErrorCode.SETTINGS_PATH_UNAVAILABLE
                    },
                    message = "本机目录初始化失败，请重试。",
                )
            } catch (_: Exception) {
                openedStores?.close()
                mutableState.value = HostBootstrapState.Error(
                    code = HostBootstrapErrorCode.PRIVATE_STORAGE_UNAVAILABLE,
                    message = "私有存储暂时不可用，请确认设备空间后重试。",
                )
            }
        }
    }

    private fun launchBestEffortRuntimeMaintenance(dependencies: HostDependencies) {
        viewModelScope.launch {
            try {
                runtimeMaintenance.run(dependencies)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
        }
    }

    override fun onCleared() {
        stores?.close()
        stores = null
    }
}

internal class RuntimeViewModelFactory(
    private val toolId: String,
    private val dependencies: HostDependencies,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(RuntimeViewModel::class.java.isAssignableFrom(modelClass)) {
            "Unsupported runtime ViewModel: ${modelClass.name}"
        }
        return RuntimeViewModel(
            toolId = toolId,
            sessions = dependencies.runtimeSessions,
        ) as T
    }
}

internal class HostFeatureViewModelFactory(
    private val dependencies: HostDependencies,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        CatalogViewModel::class.java.isAssignableFrom(modelClass) -> CatalogViewModel(
            catalog = dependencies.repositories.catalog,
            organization = dependencies.repositories.organization,
            packageOperations = dependencies.packageOperations,
        ) as T
        ImportViewModel::class.java.isAssignableFrom(modelClass) -> ImportViewModel(
            operations = dependencies.packageOperations,
        ) as T
        SettingsViewModel::class.java.isAssignableFrom(modelClass) -> SettingsViewModel(
            repository = dependencies.repositories.settings,
            catalog = dependencies.repositories.catalog,
            background = dependencies.backgroundOperations,
        ) as T
        else -> error("Unsupported host ViewModel: ${modelClass.name}")
    }
}

internal class PermissionCenterViewModelFactory(
    private val toolId: String,
    private val dependencies: HostDependencies,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(PermissionCenterViewModel::class.java.isAssignableFrom(modelClass)) {
            "Unsupported permission ViewModel: ${modelClass.name}"
        }
        return PermissionCenterViewModel(
            toolId = toolId,
            packages = dependencies.packageOperations,
            grants = dependencies.repositories.grants,
            sideEffects = dependencies.permissionSideEffects,
        ) as T
    }
}
