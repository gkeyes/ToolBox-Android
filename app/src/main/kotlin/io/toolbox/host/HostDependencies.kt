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
import io.toolbox.tool.packagekit.ToolPackageInspector
import io.toolbox.tool.packagekit.ToolPackageInspectors
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycle
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycles
import io.toolbox.tool.runtime.RuntimeProfileManager
import io.toolbox.tool.runtime.RuntimeDataCleanupResult
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

internal data class HostDependencies(
    val repositories: CoreDataRepositories,
    val inspector: ToolPackageInspector,
    val lifecycle: ToolPackageLifecycle,
    val runtimePreparer: ToolRuntimePreparer,
    val runtimeProfileManager: RuntimeProfileManager,
)

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
                dependencies.runtimeProfileManager.reapMarkedOrphanProfiles(installedToolIds)
            } finally {
                if (traceOpen) HostTrace.bestEffortEndAsyncSection("runtimeProfile.cleanup", traceCookie)
            }
        }
}

internal class HostDependenciesViewModel(
    application: Application,
    private val runtimeMaintenance: HostRuntimeMaintenance,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, ProductionHostRuntimeMaintenance)

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
                    val inspector = ToolPackageInspectors.create(
                        File(app.filesDir, "inspection-sessions").toPath(),
                    )
                    val lifecycle = ToolPackageLifecycles.create(
                        privateFilesDirectory = app.filesDir,
                        inspector = inspector,
                        catalog = createdStores.repositories.lifecycle,
                    )
                    val runtimeProfileManager = RuntimeProfileManager(app.filesDir)
                    HostDependencies(
                        repositories = createdStores.repositories,
                        inspector = inspector,
                        lifecycle = lifecycle,
                        runtimePreparer = ToolRuntimePreparer(app.filesDir),
                        runtimeProfileManager = runtimeProfileManager,
                    )
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
            runtimeProfileManager = dependencies.runtimeProfileManager,
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
            runtimeDataCleaner = dependencies.runtimeProfileManager,
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
