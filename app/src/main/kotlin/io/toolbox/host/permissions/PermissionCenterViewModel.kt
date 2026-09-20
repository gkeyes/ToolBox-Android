package io.toolbox.host.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.ToolVersion
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.tool.runtime.RuntimePreparationCode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class PermissionItem(
    val capability: String,
    val title: String,
    val reason: String,
    val enabled: Boolean,
    val androidPermissions: List<String>,
)

internal sealed interface PermissionLoadState {
    data object Loading : PermissionLoadState
    data object Ready : PermissionLoadState
    data object NotInstalled : PermissionLoadState
    data class Failed(val code: RuntimePreparationCode, val message: String) : PermissionLoadState
}

internal data class PermissionCenterUiState(
    val toolName: String = "权限",
    val items: List<PermissionItem> = emptyList(),
    val loadState: PermissionLoadState = PermissionLoadState.Loading,
    val message: String? = null,
    val showSystemSettings: Boolean = false,
    val busyCapabilities: Set<String> = emptySet(),
    val cleanupRetries: Map<String, PermissionCleanupRetry> = emptyMap(),
) {
    val loaded: Boolean get() = loadState != PermissionLoadState.Loading
}

internal data class SystemPermissionRequest(
    val id: String,
    val capability: String,
    val permissions: List<String>,
    val expectedVersion: ToolVersion,
    val expectedGrant: PermissionGrant?,
)

internal class PermissionCenterViewModel(
    private val toolId: String,
    private val packages: HostPackageOperations,
    private val catalog: CatalogRepository,
    private val grants: PermissionGrantRepository,
    private val mutations: PermissionMutationRunner,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PermissionCenterUiState())
    val state: StateFlow<PermissionCenterUiState> = mutableState.asStateFlow()
    private val mutableRequests = MutableSharedFlow<SystemPermissionRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<SystemPermissionRequest> = mutableRequests.asSharedFlow()

    private var manifestVersion: ToolVersion? = null
    private var observedGrants = emptyMap<String, PermissionGrant>()
    private var pendingSystemRequest: SystemPermissionRequest? = null
    private val activeOperations = mutableMapOf<String, Long>()
    private var nextOperationId = 0L
    private var feedbackOperationId = 0L
    private var cleared = false

    init {
        viewModelScope.launch {
            catalog.observeTool(toolId).map { it?.currentVersion }.distinctUntilChanged().collectLatest { version ->
                manifestVersion = null
                observedGrants = emptyMap()
                activeOperations.clear()
                pendingSystemRequest = null
                feedbackOperationId = ++nextOperationId
                mutableState.value = PermissionCenterUiState()
                observeCurrentManifest(version)
            }
        }
    }

    private suspend fun observeCurrentManifest(version: ToolVersion?) {
        if (version == null) {
            mutableState.value = PermissionCenterUiState(loadState = PermissionLoadState.NotInstalled)
            return
        }
        val manifest = when (val result = packages.installedManifest(toolId)) {
            is HostInstalledManifestResult.Found -> result.manifest
            HostInstalledManifestResult.NotInstalled -> {
                mutableState.value = PermissionCenterUiState(loadState = PermissionLoadState.NotInstalled)
                return
            }
            is HostInstalledManifestResult.Failed -> {
                mutableState.value = PermissionCenterUiState(
                    loadState = PermissionLoadState.Failed(result.code, result.message),
                )
                return
            }
        }
        if (manifest.toolId != toolId || manifest.versionCode != version.versionCode || manifest.versionName != version.version) {
            mutableState.value = PermissionCenterUiState(
                loadState = PermissionLoadState.Failed(RuntimePreparationCode.MANIFEST_INVALID, "工具状态已变化，请返回后重新打开权限。"),
            )
            return
        }
        manifestVersion = version
        coroutineScope {
            grants.observeGrants(toolId).collect { stored ->
                val values = stored.associateBy(PermissionGrant::capability)
                observedGrants = values
                pendingSystemRequest?.let { request ->
                    if (values[request.capability] != request.expectedGrant) pendingSystemRequest = null
                }
                mutableState.value = mutableState.value.copy(
                    toolName = manifest.toolName,
                    loadState = PermissionLoadState.Ready,
                    items = manifest.permissions.map { permission ->
                        PermissionItem(
                            capability = permission.capability,
                            title = permission.capability.capabilityTitle(),
                            reason = permission.reason,
                            enabled = values[permission.capability]?.granted ?: permission.capability.defaultEnabled(),
                            androidPermissions = permission.capability.androidPermissions(),
                        )
                    },
                    cleanupRetries = mutableState.value.cleanupRetries.filterValues {
                        it.version == version && values[it.revokedGrant.capability] == it.revokedGrant
                    },
                    busyCapabilities = busyCapabilities(),
                )
            }
        }
    }

    fun setEnabled(capability: String, enabled: Boolean) {
        if (cleared) return
        if (capability in state.value.busyCapabilities) return
        val item = state.value.items.firstOrNull { it.capability == capability } ?: return
        if (item.enabled == enabled) return
        if (enabled && item.androidPermissions.isNotEmpty()) {
            if (pendingSystemRequest != null) return
            val request = SystemPermissionRequest(
                id = UUID.randomUUID().toString(),
                capability = capability,
                permissions = item.androidPermissions,
                expectedVersion = manifestVersion ?: return,
                expectedGrant = observedGrants[capability],
            )
            pendingSystemRequest = request
            if (!mutableRequests.tryEmit(request)) pendingSystemRequest = null
            feedbackOperationId = ++nextOperationId
            mutableState.value = mutableState.value.copy(
                busyCapabilities = busyCapabilities(), message = null, showSystemSettings = false,
            )
        } else {
            save(capability, enabled)
        }
    }

    fun systemPermissionResult(requestId: String, results: Map<String, Boolean>) {
        if (cleared) return
        val request = pendingSystemRequest ?: return
        if (request.id != requestId) return
        pendingSystemRequest = null
        mutableState.value = mutableState.value.copy(busyCapabilities = busyCapabilities())
        if (request.expectedVersion != manifestVersion || observedGrants[request.capability] != request.expectedGrant ||
            state.value.items.none { it.capability == request.capability }
        ) return
        val granted = if (request.capability == "location") {
            request.permissions.any { results[it] == true }
        } else {
            request.permissions.isNotEmpty() && request.permissions.all { results[it] == true }
        }
        if (granted) save(request.capability, true) else {
            feedbackOperationId = ++nextOperationId
            mutableState.value = mutableState.value.copy(
                message = "系统权限未授予，工具权限保持关闭。",
                showSystemSettings = true,
            )
        }
    }

    fun systemPermissionLaunchFailed(requestId: String) {
        if (cleared) return
        if (requestId.isNotEmpty()) {
            if (pendingSystemRequest?.id != requestId) return
            pendingSystemRequest = null
        }
        feedbackOperationId = ++nextOperationId
        mutableState.value = mutableState.value.copy(
            message = "无法打开系统闹钟授权页面，请在系统设置中检查 ToolBox 的闹钟和提醒权限后重试。",
            showSystemSettings = false,
            busyCapabilities = busyCapabilities(),
        )
    }

    fun dismissMessage() {
        feedbackOperationId = ++nextOperationId
        mutableState.value = mutableState.value.copy(message = null, showSystemSettings = false)
    }

    private fun save(capability: String, enabled: Boolean) {
        val expectedVersion = manifestVersion ?: return
        val expectedGrant = observedGrants[capability]
        observeMutation(
            capability, expectedVersion, expectedGrant,
            mutations.submit(toolId, capability, enabled, expectedVersion, expectedGrant),
        )
    }

    fun retryCleanup(capability: String) {
        if (cleared || capability in state.value.busyCapabilities) return
        val retry = state.value.cleanupRetries[capability] ?: return
        if (retry.version != manifestVersion || observedGrants[capability] != retry.revokedGrant) return
        observeMutation(capability, retry.version, retry.revokedGrant, mutations.retryCleanup(retry))
    }

    private fun busyCapabilities(): Set<String> = activeOperations.keys.toSet() +
        listOfNotNull(pendingSystemRequest?.capability)

    private fun observeMutation(
        capability: String,
        expectedVersion: ToolVersion,
        expectedGrant: PermissionGrant?,
        result: Deferred<PermissionMutationResult>,
    ) {
        val operationId = ++nextOperationId
        feedbackOperationId = operationId
        activeOperations[capability] = operationId
        mutableState.value = mutableState.value.copy(
            busyCapabilities = busyCapabilities(), message = null, showSystemSettings = false,
        )
        viewModelScope.launch {
            var outcome: PermissionMutationResult? = null
            try {
                val completed = try {
                    result.await()
                } catch (_: CancellationException) {
                    // Restore may cancel the host worker while this page is still alive.
                    currentCoroutineContext().ensureActive()
                    PermissionMutationResult.WriteFailed
                }
                outcome = completed
                val currentVersion = catalog.observeTool(toolId).first()?.currentVersion
                val currentGrant = grants.observeGrants(toolId).first().firstOrNull { it.capability == capability }
                if (cleared || activeOperations[capability] != operationId) return@launch
                var next = mutableState.value
                if (manifestVersion != expectedVersion || currentVersion != expectedVersion) {
                    mutableState.value = next.copy(cleanupRetries = next.cleanupRetries - capability)
                    return@launch
                }
                val receiptGrant = when (completed) {
                    is PermissionMutationResult.Saved -> completed.grant
                    is PermissionMutationResult.CleanupFailed -> completed.retry.revokedGrant
                    else -> expectedGrant
                }
                if (currentGrant != receiptGrant) {
                    mutableState.value = next.copy(cleanupRetries = next.cleanupRetries - capability)
                    return@launch
                }
                next = next.copy(cleanupRetries = when (completed) {
                    is PermissionMutationResult.CleanupFailed -> next.cleanupRetries + (capability to completed.retry)
                    PermissionMutationResult.WriteFailed -> next.cleanupRetries
                    else -> next.cleanupRetries - capability
                })
                if (feedbackOperationId == operationId) next = next.copy(
                    message = when (completed) {
                        is PermissionMutationResult.Saved, is PermissionMutationResult.CleanupFailed -> null
                        PermissionMutationResult.Outdated -> "工具或权限状态已变化，请重新操作。"
                        PermissionMutationResult.WriteFailed -> "权限操作未完成，请重试。"
                    }, showSystemSettings = false,
                )
                mutableState.value = next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!cleared && activeOperations[capability] == operationId && manifestVersion == expectedVersion) {
                    var next = mutableState.value
                    // A failed fresh read is not a success receipt. Retain a cleanup
                    // target only while the live subscription still matches it; retry
                    // always revalidates catalog, manifest and grant inside the runner.
                    val retry = ((outcome as? PermissionMutationResult.CleanupFailed)?.retry
                        ?: next.cleanupRetries[capability])?.takeIf {
                        it.version == manifestVersion && observedGrants[capability] == it.revokedGrant
                    }
                    next = next.copy(cleanupRetries = (next.cleanupRetries - capability) +
                        if (retry == null) emptyMap() else mapOf(capability to retry))
                    if (feedbackOperationId == operationId) next = next.copy(
                        message = if (retry != null) "暂时无法确认最新权限状态，可重试清除。"
                            else "暂时无法确认最新权限状态，请返回后重新打开权限。",
                        showSystemSettings = false,
                    )
                    mutableState.value = next
                }
            } finally {
                if (activeOperations[capability] == operationId) {
                    activeOperations.remove(capability)
                    mutableState.value = mutableState.value.copy(busyCapabilities = busyCapabilities())
                }
            }
        }
    }

    override fun onCleared() {
        cleared = true
        pendingSystemRequest = null
        manifestVersion = null
        activeOperations.clear()
    }
}

private fun String.defaultEnabled() = this in setOf(
    "storage",
    "storage.secure",
    "device.basic",
    "clipboard.write",
    "haptics",
)

private fun String.androidPermissions(): List<String> = when (this) {
    "alarms" -> listOf(EXACT_ALARM_PERMISSION)
    "notifications" -> if (android.os.Build.VERSION.SDK_INT >= 33) listOf("android.permission.POST_NOTIFICATIONS") else emptyList()
    "location" -> listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION")
    "location.background" -> listOf("android.permission.ACCESS_BACKGROUND_LOCATION")
    else -> emptyList()
}

private fun String.capabilityTitle(): String = when (this) {
    "storage" -> "工具存储"
    "storage.secure" -> "安全存储"
    "clipboard.write" -> "写入剪贴板"
    "clipboard.read" -> "读取剪贴板"
    "share" -> "系统分享"
    "browser" -> "内置浏览器"
    "files.open" -> "打开文件"
    "files.save" -> "保存文件"
    "network" -> "网络"
    "device.basic" -> "设备基础信息"
    "haptics" -> "触感反馈"
    "notifications" -> "通知"
    "shortcuts" -> "桌面快捷方式"
    "camera" -> "系统相机"
    "location" -> "位置"
    "background.tasks" -> "后台任务"
    "background.runtime" -> "持续运行环境"
    "location.background" -> "后台位置"
    "alarms" -> "精确闹钟"
    else -> this
}

internal const val EXACT_ALARM_PERMISSION = "android.permission.SCHEDULE_EXACT_ALARM"
