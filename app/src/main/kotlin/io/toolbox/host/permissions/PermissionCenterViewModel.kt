package io.toolbox.host.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.tool.runtime.RuntimePreparationCode
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
) {
    val loaded: Boolean get() = loadState != PermissionLoadState.Loading
}

internal data class SystemPermissionRequest(
    val id: String,
    val capability: String,
    val permissions: List<String>,
    val versionCode: Int,
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

    private var manifestVersion: Int? = null
    private var pendingSystemRequest: SystemPermissionRequest? = null
    private var cleared = false

    init {
        viewModelScope.launch {
            catalog.observeTool(toolId).map { it?.currentVersion?.versionCode }.distinctUntilChanged().collectLatest {
                manifestVersion = null
                mutableState.value = PermissionCenterUiState()
                observeCurrentManifest()
            }
        }
    }

    private suspend fun observeCurrentManifest() {
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
        manifestVersion = manifest.versionCode
        grants.observeGrants(toolId).collect { stored ->
            val values = stored.associateBy(PermissionGrant::capability)
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
            )
        }
    }

    fun setEnabled(capability: String, enabled: Boolean) {
        if (cleared) return
        val item = state.value.items.firstOrNull { it.capability == capability } ?: return
        if (item.enabled == enabled) return
        if (enabled && item.androidPermissions.isNotEmpty()) {
            if (pendingSystemRequest != null) return
            val request = SystemPermissionRequest(
                id = UUID.randomUUID().toString(),
                capability = capability,
                permissions = item.androidPermissions,
                versionCode = manifestVersion ?: return,
            )
            pendingSystemRequest = request
            if (!mutableRequests.tryEmit(request)) pendingSystemRequest = null
        } else {
            save(capability, enabled)
        }
    }

    fun systemPermissionResult(requestId: String, results: Map<String, Boolean>) {
        if (cleared) return
        val request = pendingSystemRequest ?: return
        if (request.id != requestId) return
        pendingSystemRequest = null
        if (request.versionCode != manifestVersion ||
            state.value.items.none { it.capability == request.capability }
        ) return
        val granted = if (request.capability == "location") {
            request.permissions.any { results[it] == true }
        } else {
            request.permissions.isNotEmpty() && request.permissions.all { results[it] == true }
        }
        if (granted) save(request.capability, true) else {
            mutableState.value = mutableState.value.copy(
                message = "系统权限未授予，工具权限保持关闭。",
                showSystemSettings = true,
            )
        }
    }

    fun dismissMessage() {
        mutableState.value = mutableState.value.copy(message = null, showSystemSettings = false)
    }

    private fun save(capability: String, enabled: Boolean) {
        val expectedVersion = manifestVersion ?: return
        val result = mutations.submit(toolId, capability, enabled, expectedVersion)
        viewModelScope.launch {
            val outcome = result.await()
            if (manifestVersion != expectedVersion) return@launch
            mutableState.value = mutableState.value.copy(
                message = when (outcome) {
                    PermissionMutationResult.Saved -> null
                    PermissionMutationResult.Outdated -> "工具已更新，请在最新权限列表中重试。"
                    PermissionMutationResult.WriteFailed -> "权限未保存，请重试。"
                    PermissionMutationResult.CleanupFailed -> "权限操作未完成，请重试；安全存储清理失败时不会重新开启。"
                },
                showSystemSettings = false,
            )
        }
    }

    override fun onCleared() {
        cleared = true
        pendingSystemRequest = null
        manifestVersion = null
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
