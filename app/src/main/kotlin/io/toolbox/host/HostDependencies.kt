package io.toolbox.host

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogLayoutRepository
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.CoreDataInitializationException
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.CoreDataStores
import io.toolbox.core.data.DataMutationLock
import io.toolbox.core.data.backup.BackupDatabase
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.catalog.RunningToolsViewModel
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.icons.InstalledToolIconReader
import io.toolbox.host.icons.ToolIconLoader
import io.toolbox.host.permissions.PermissionCenterViewModel
import io.toolbox.host.permissions.PermissionMutationRunner
import io.toolbox.host.runtime.HostRuntimeBridgeProvider
import io.toolbox.host.runtime.RuntimeViewModel
import io.toolbox.host.runtime.RuntimeSessionManager
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.tool.runtime.RuntimeBridgeProvider
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

/**
 * Composition root of the host process: every host service is built here, once, by constructor
 * injection, in declaration order. Nothing outside this class constructs these services, and
 * nothing is bound after construction.
 *
 * Locks — one instance per level, never merged:
 * - [packageMutations] (outer) is owned here. Package install/delete, permission writes, backup
 *   and restore hold it while they drain runtime and storage work.
 * - [CoreDataRepositories.mutations] (inner) is owned by `CoreDataFactory` and wraps every single
 *   repository write. Drained runtime work still needs it, which is why it must stay a separate
 *   instance (see PermissionMutationRunnerTest.cleanupCanDrainRuntimeWorkThatStillNeedsTheSeparateRepositoryLock).
 *   Always take the outer lock before the inner one.
 *
 * The only dependency cycle is runtime sessions -> bridge -> (background handlers, session
 * continuity) -> runtime sessions. It is broken in exactly one place: [runtimeSessions] receives
 * the bridge through a provider lambda, and [runtimeBridgeProvider] is built lazily on first use.
 */
internal class HostDependencies(
    private val application: Application,
    val repositories: CoreDataRepositories,
    private val backupDatabase: BackupDatabase,
) {
    /** Outer package/permission/backup admission lock; the single instance for this process. */
    val packageMutations = DataMutationLock()
    val catalogLayout = CatalogLayoutRepository(repositories)
    val toolIcons = ToolIconLoader(repositories.catalog) { application.filesDir.toPath() }
    val manifestCategories: (CatalogEntry) -> List<String>? = InstalledToolIconReader({ application.filesDir.toPath() })
        .let { reader -> { entry -> reader.readCategories(entry.toolId, entry.versionCode, entry.securityProfile) } }

    /** One-shot message from startup maintenance; the UI shows it once and clears it. */
    val startupNotice = MutableStateFlow<String?>(null)

    // Runtime profiles stay unconstructed until the first runtime or package operation needs them.
    private val runtimeProfileManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RuntimeProfileManager(application.filesDir)
    }

    val runtimeDataCleaner: RuntimeDataCleaner = object : RuntimeDataCleaner {
        override suspend fun <T> withPreservedData(toolId: String, action: suspend () -> T): T =
            runtimeProfileManager.withPreservedData(toolId, action)

        override suspend fun <T> clearThenRun(
            toolId: String,
            action: suspend () -> T,
        ): RuntimeDataCleanupExecution<T> =
            runtimeProfileManager.clearThenRun(toolId, action)
    }

    val runtimePermitProvider = RuntimePermitProvider { toolId, awaitExistingRuntimeRelease ->
        runtimeProfileManager.acquireRuntimePermit(toolId, awaitExistingRuntimeRelease)
    }

    val runtimePreparer: ToolRuntimePreparer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ToolRuntimePreparer(application.filesDir, BuildConfig.VERSION_NAME)
    }

    // The cycle break point (see class docs). Declared before the services it reads so its
    // delegate exists before either of them; its body only runs when a runtime is first opened.
    val runtimeBridgeProvider: RuntimeBridgeProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HostRuntimeBridgeProvider(
            context = application,
            repositories = repositories,
            serviceHandlerFactory = background,
            continuityHandlerFactory = runtimeSessions::handlers,
        )
    }

    val runtimeSessions = RuntimeSessionManager(
        context = application,
        repositories = repositories,
        preparer = runtimePreparer,
        permitProvider = runtimePermitProvider,
        bridgeProvider = { runtimeBridgeProvider },
    )

    private val background = ProductionHostBackgroundOperations(application, repositories, runtimeSessions)
    val backgroundOperations: HostBackgroundOperations = background
    val permissionSideEffects: HostPermissionSideEffects = background

    val packageOperations: HostPackageOperations by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        io.toolbox.host.backup.SerializedPackageOperations(
            ProductionHostPackageOperations(
                application = application,
                repositories = repositories,
                runtimeDataCleaner = runtimeDataCleaner,
                background = background,
                invalidateToolIcon = toolIcons::invalidate,
            ),
            packageMutations,
            catalogLayout::reconcile,
        )
    }

    val permissionMutations: PermissionMutationRunner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PermissionMutationRunner(packageOperations, repositories.grants, permissionSideEffects,
            catalog = repositories.catalog, mutationLock = packageMutations)
    }

    val backupService by lazy {
        io.toolbox.host.backup.HostBackupService(application, repositories, backupDatabase,
            packageOperations, packageMutations, backgroundOperations, runtimeSessions::backupRuntimeIds)
    }
    fun backupViewModel() = io.toolbox.host.backup.BackupViewModel(
        backupService, io.toolbox.host.backup.AndroidBackupDocumentIO(application.contentResolver),
    )

    val toolExporter: io.toolbox.host.backup.ToolExportOperations by lazy {
        io.toolbox.host.backup.HostToolPackageExporter(
            application.filesDir, repositories.catalog, packageMutations,
            io.toolbox.host.backup.AndroidBackupDocumentIO(application.contentResolver),
            java.io.File(application.cacheDir, "tool-export"),
            io.toolbox.tool.packagekit.AndroidPackageResourceProbe(application),
        )
    }

    val updates by lazy { io.toolbox.host.update.UpdateRepository(application) }

    suspend fun reapMarkedOrphanProfiles(installedToolIds: Set<String>): RuntimeDataCleanupResult =
        runtimeProfileManager.reapMarkedOrphanProfiles(installedToolIds)

    suspend fun recoverPendingPackageMutations() {
        packageMutations.run {
            (packageOperations as? HostPackageMaintenance)?.recoverPendingMutations()?.let { startupNotice.value = it }
            check(catalogLayout.reconcile() is io.toolbox.core.data.DataResult.Success) { "CATALOG_LAYOUT_WRITE" }
        }
    }

    suspend fun reconcileBackgroundTasks() {
        (backgroundOperations as? HostBackgroundMaintenance)?.reconcile()
    }
}

internal fun interface HostDependenciesFactory {
    suspend fun create(application: Application, stores: CoreDataStores): HostDependencies
}

internal object ProductionHostDependenciesFactory : HostDependenciesFactory {
    override suspend fun create(application: Application, stores: CoreDataStores): HostDependencies {
        val memory = application.getSystemService(android.app.ActivityManager::class.java)
        io.toolbox.host.background.NetworkResources.configureSystemAvailability {
            runCatching {
                val info = android.app.ActivityManager.MemoryInfo()
                checkNotNull(memory).getMemoryInfo(info)
                !info.lowMemory && info.availMem > info.threshold
            }.getOrDefault(false)
        }

        io.toolbox.host.backup.HostBackupService.recover(application, stores)
        return HostDependencies(application, stores.repositories, stores.backup)
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
                        return@withContext app.hostDependencies()
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
            } catch (failure: io.toolbox.tool.packagekit.backup.BackupException) {
                openedStores?.close()
                mutableState.value = HostBootstrapState.Error(HostBootstrapErrorCode.PRIVATE_STORAGE_UNAVAILABLE,
                    io.toolbox.host.backup.backupMessage(failure))
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
            } catch (failure: Exception) {
                android.util.Log.w("ToolBoxStartup", "Startup maintenance failed", failure)
                if (dependencies.startupNotice.value == null) {
                    dependencies.startupNotice.value = "启动维护未完成，部分中断的安装或清理将在下次启动时重试。"
                }
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
        io.toolbox.host.backup.BackupViewModel::class.java.isAssignableFrom(modelClass) -> dependencies.backupViewModel() as T
        io.toolbox.host.update.UpdateViewModel::class.java.isAssignableFrom(modelClass) -> io.toolbox.host.update.UpdateViewModel(
            repository = dependencies.updates,
            catalog = dependencies.repositories.catalog,
            packages = dependencies.packageOperations,
        ) as T
        RunningToolsViewModel::class.java.isAssignableFrom(modelClass) -> RunningToolsViewModel(
            sessions = dependencies.runtimeSessions.sessions,
            stopSession = dependencies.runtimeSessions::stopSession,
        ) as T
        CatalogViewModel::class.java.isAssignableFrom(modelClass) -> CatalogViewModel(
            catalog = dependencies.repositories.catalog,
            organization = dependencies.repositories.organization,
            settings = dependencies.repositories.settings,
            layoutRepository = dependencies.catalogLayout,
            packageOperations = dependencies.packageOperations,
            manifestCategories = dependencies.manifestCategories,
            exporter = dependencies.toolExporter,
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
            catalog = dependencies.repositories.catalog,
            grants = dependencies.repositories.grants,
            mutations = dependencies.permissionMutations,
        ) as T
    }
}
