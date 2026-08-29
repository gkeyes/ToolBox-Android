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
import io.toolbox.host.importflow.ImportReviewViewModel
import io.toolbox.host.permissions.PermissionCenterViewModel
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.tool.packagekit.DiscardResult
import io.toolbox.tool.packagekit.InspectionResult
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.ResumeInspectionResult
import io.toolbox.tool.packagekit.ToolPackageInspector
import io.toolbox.tool.packagekit.ToolPackageInspectors
import io.toolbox.tool.packagekit.lifecycle.InstallLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.RecoveryLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.RollbackLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycle
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycles
import io.toolbox.tool.packagekit.lifecycle.UninstallLifecycleResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.tool.runtime.RuntimeDataCleaner
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.RuntimeDataCleanupResult
import io.toolbox.tool.runtime.RuntimePermitProvider
import io.toolbox.tool.runtime.ToolRuntimePreparer
import java.io.File
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
    val repositories: CoreDataRepositories,
    private val inspectorFactory: () -> ToolPackageInspector,
    private val lifecycleFactory: (ToolPackageInspector) -> ToolPackageLifecycle,
    private val runtimePreparerFactory: () -> ToolRuntimePreparer,
    private val runtimeProfileManagerFactory: () -> RuntimeProfileManager,
) {
    private val deferredInspector = lazy(LazyThreadSafetyMode.SYNCHRONIZED, inspectorFactory)
    private val deferredLifecycle = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        lifecycleFactory(deferredInspector.value)
    }
    private val deferredRuntimeProfileManager = lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
        runtimeProfileManagerFactory,
    )

    val inspector: ToolPackageInspector = object : ToolPackageInspector {
        override suspend fun inspect(input: PackageInput): InspectionResult = deferredInspector.value.inspect(input)

        override suspend fun resume(sessionId: String): ResumeInspectionResult =
            deferredInspector.value.resume(sessionId)

        override suspend fun discard(sessionId: String): DiscardResult = deferredInspector.value.discard(sessionId)
    }

    val lifecycle: ToolPackageLifecycle = object : ToolPackageLifecycle {
        override suspend fun install(
            inspectionSessionId: String,
            initialGrants: List<PermissionGrant>,
        ): InstallLifecycleResult = deferredLifecycle.value.install(inspectionSessionId, initialGrants)

        override suspend fun rollback(toolId: String): RollbackLifecycleResult =
            deferredLifecycle.value.rollback(toolId)

        override suspend fun uninstall(toolId: String): UninstallLifecycleResult =
            deferredLifecycle.value.uninstall(toolId)

        override suspend fun recover(): RecoveryLifecycleResult = deferredLifecycle.value.recover()
    }

    val runtimePreparer: ToolRuntimePreparer by lazy(LazyThreadSafetyMode.SYNCHRONIZED, runtimePreparerFactory)
    val runtimeDataCleaner: RuntimeDataCleaner = object : RuntimeDataCleaner {
        override suspend fun <T> clearThenRun(
            toolId: String,
            action: suspend () -> T,
        ) = deferredRuntimeProfileManager.value.clearThenRun(toolId, action)
    }
    val runtimePermitProvider = RuntimePermitProvider { toolId, awaitExistingRuntimeRelease ->
        deferredRuntimeProfileManager.value.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease)
    }

    suspend fun reapMarkedOrphanProfiles(installedToolIds: Set<String>): RuntimeDataCleanupResult =
        deferredRuntimeProfileManager.value.reapMarkedOrphanProfiles(installedToolIds)
}

internal fun interface HostDependenciesFactory {
    fun create(application: Application, stores: CoreDataStores): HostDependencies
}

private object ProductionHostDependenciesFactory : HostDependenciesFactory {
    override fun create(application: Application, stores: CoreDataStores): HostDependencies = HostDependencies(
        repositories = stores.repositories,
        inspectorFactory = {
            ToolPackageInspectors.create(
                File(application.filesDir, "inspection-sessions").toPath(),
            )
        },
        lifecycleFactory = { inspector ->
            ToolPackageLifecycles.create(
                privateFilesDirectory = application.filesDir,
                inspector = inspector,
                catalog = stores.repositories.lifecycle,
            )
        },
        runtimePreparerFactory = { ToolRuntimePreparer(application.filesDir) },
        runtimeProfileManagerFactory = { RuntimeProfileManager(application.filesDir) },
    )
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
            val installedToolIds = dependencies.repositories.catalog.observeCatalogProjection()
                .first()
                .mapTo(hashSetOf()) { it.toolId }
            val traceOpen = HostTrace.tryBeginAsyncSection("runtimeProfile.cleanup", traceCookie)
            try {
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
                    val createdStores = HostTrace.bestEffortSection("coreData.create") {
                        CoreDataFactory.create(app)
                    }
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
        require(modelClass.isAssignableFrom(RuntimeViewModel::class.java)) {
            "Unsupported runtime ViewModel: ${modelClass.name}"
        }
        return RuntimeViewModel(
            toolId = toolId,
            catalog = dependencies.repositories.catalog,
            lifecycle = dependencies.repositories.lifecycle,
            preparer = dependencies.runtimePreparer,
            runtimeProfileManager = dependencies.runtimePermitProvider,
        ) as T
    }
}

internal class HostFeatureViewModelFactory(
    private val dependencies: HostDependencies,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(CatalogViewModel::class.java) -> CatalogViewModel(
            catalog = dependencies.repositories.catalog,
            organization = dependencies.repositories.organization,
            packageLifecycle = dependencies.lifecycle,
            runtimeDataCleaner = dependencies.runtimeDataCleaner,
        ) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(
            repository = dependencies.repositories.settings,
            audit = dependencies.repositories.audit,
            nowMillis = System::currentTimeMillis,
        ) as T
        modelClass.isAssignableFrom(ImportReviewViewModel::class.java) ->
            createImportReviewViewModelAtRecoveryApiBoundary() as T
        else -> error("Unsupported host ViewModel: ${modelClass.name}")
    }

    private fun createImportReviewViewModelAtRecoveryApiBoundary(): ImportReviewViewModel = ImportReviewViewModel(
        inspector = dependencies.inspector,
        lifecycle = dependencies.lifecycle,
    )
}

internal class PermissionCenterViewModelFactory(
    private val toolId: String,
    private val repositories: CoreDataRepositories,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PermissionCenterViewModel::class.java)) {
            "Unsupported permission ViewModel: ${modelClass.name}"
        }
        return PermissionCenterViewModel(toolId, repositories.grants) as T
    }
}
