package io.toolbox.host.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostPermissionSideEffects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal data class PermissionItem(
    val capability: String,
    val title: String,
    val reason: String,
    val enabled: Boolean,
    val androidPermissions: List<String>,
)

internal data class PermissionCenterUiState(
    val toolName: String = "权限",
    val items: List<PermissionItem> = emptyList(),
    val loaded: Boolean = false,
    val message: String? = null,
    val showSystemSettings: Boolean = false,
)

internal data class SystemPermissionRequest(val capability: String, val permissions: List<String>)

internal class PermissionCenterViewModel(
    private val toolId: String,
    private val packages: HostPackageOperations,
    private val grants: PermissionGrantRepository,
    private val sideEffects: HostPermissionSideEffects,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PermissionCenterUiState())
    val state: StateFlow<PermissionCenterUiState> = mutableState.asStateFlow()
    private val mutableRequests = MutableSharedFlow<SystemPermissionRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<SystemPermissionRequest> = mutableRequests.asSharedFlow()

    init {
        viewModelScope.launch {
            val manifest = packages.installedManifest(toolId)
            if (manifest == null) {
                mutableState.value = PermissionCenterUiState(loaded = true, message = "工具已不存在。")
                return@launch
            }
            grants.observeGrants(toolId).collect { stored ->
                val values = stored.associateBy(PermissionGrant::capability)
                mutableState.value = mutableState.value.copy(
                    toolName = manifest.toolName,
                    loaded = true,
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
    }

    fun setEnabled(capability: String, enabled: Boolean) {
        val item = state.value.items.firstOrNull { it.capability == capability } ?: return
        if (item.enabled == enabled) return
        if (enabled && item.androidPermissions.isNotEmpty()) {
            mutableRequests.tryEmit(SystemPermissionRequest(capability, item.androidPermissions))
        } else {
            save(capability, enabled)
        }
    }

    fun systemPermissionResult(capability: String, granted: Boolean) {
        if (granted) save(capability, true) else {
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
        viewModelScope.launch {
            try {
                if (!enabled) sideEffects.onCapabilityDisabled(toolId, capability)
                when (grants.put(PermissionGrant(toolId, capability, enabled, now()))) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> mutableState.value = mutableState.value.copy(message = "权限未保存，请重试。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(message = "权限未保存，请重试。")
            }
        }
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
