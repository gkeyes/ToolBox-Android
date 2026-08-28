package io.toolbox.host.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.GrantScope
import io.toolbox.core.data.GrantSource
import io.toolbox.core.data.GrantState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PermissionCenterViewModel(
    private val toolId: String,
    private val repository: PermissionGrantRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PermissionCenterUiState(toolId = toolId))
    val state: StateFlow<PermissionCenterUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeGrants(toolId).collectLatest { grants ->
                mutableState.value = mutableState.value.copy(
                    grants = grants.map(PermissionGrant::toItem),
                    isLoaded = true,
                )
            }
        }
    }

    fun revoke(permission: String) {
        if (state.value.grants.none { it.permission == permission }) {
            mutableState.value = mutableState.value.copy(
                feedback = PermissionCenterFeedback.NotDeclared(permission),
            )
            return
        }
        viewModelScope.launch {
            when (val result = repository.revoke(toolId, permission)) {
                is DataResult.Success -> {
                    mutableState.value = mutableState.value.copy(
                        feedback = PermissionCenterFeedback.Revoked(permission),
                    )
                }
                is DataResult.Failure -> {
                    mutableState.value = mutableState.value.copy(
                        feedback = PermissionCenterFeedback.RevokeFailed(result.userMessage()),
                    )
                }
            }
        }
    }

    fun explainRuntimeGranting() {
        mutableState.value = mutableState.value.copy(
            feedback = PermissionCenterFeedback.RuntimeConfirmationRequired,
        )
    }

    fun dismissFeedback() {
        mutableState.value = mutableState.value.copy(feedback = null)
    }
}

data class PermissionCenterUiState(
    val toolId: String,
    val grants: List<PermissionGrantItem> = emptyList(),
    val isLoaded: Boolean = false,
    val feedback: PermissionCenterFeedback? = null,
)

data class PermissionGrantItem(
    val permission: String,
    val title: String,
    val state: GrantState,
    val scope: GrantScope,
    val expiresAt: Long?,
    val source: GrantSource,
) {
    val details: String
        get() = "状态：${state.label} · 范围：${scope.label} · 到期：${expiresAt ?: "无"} · 来源：${source.label}"
}

sealed interface PermissionCenterFeedback {
    data class Revoked(val permission: String) : PermissionCenterFeedback
    data class NotDeclared(val permission: String) : PermissionCenterFeedback
    data class RevokeFailed(val reason: String) : PermissionCenterFeedback
    data object RuntimeConfirmationRequired : PermissionCenterFeedback

    val message: String
        get() = when (this) {
            is Revoked -> "已撤销 ${permission.displayName()} 的安装授权记录。"
            is NotDeclared -> "${permission.displayName()} 不在该工具的已安装声明中，未作更改。"
            is RevokeFailed -> "撤销未完成：$reason"
            RuntimeConfirmationRequired -> "新增授权只能在工具运行时按已声明能力逐项确认；该流程尚未接入。"
        }
}

private fun PermissionGrant.toItem() = PermissionGrantItem(
    permission = permission,
    title = permission.displayName(),
    state = state,
    scope = scope,
    expiresAt = expiresAt,
    source = source,
)

private fun String.displayName(): String = when (this) {
    "storage" -> "专属存储"
    "storage.secure" -> "安全存储"
    "clipboard.write" -> "写入剪贴板"
    "clipboard.read" -> "读取剪贴板"
    "share" -> "系统分享"
    "files.open" -> "打开文件"
    "files.save" -> "保存文件"
    "network" -> "受控网络访问"
    "device.basic" -> "设备基础信息"
    "haptics" -> "触感反馈"
    "notifications" -> "通知"
    "shortcuts" -> "桌面快捷方式"
    "camera" -> "相机"
    "location" -> "位置"
    else -> this
}

private val GrantState.label: String
    get() = when (this) {
        GrantState.GRANTED -> "已允许"
        GrantState.DENIED -> "已拒绝"
        GrantState.BLOCKED -> "已阻止"
    }

private val GrantScope.label: String
    get() = when (this) {
        GrantScope.ONCE -> "仅本次"
        GrantScope.SESSION -> "本次会话"
        GrantScope.PERSISTENT -> "持久"
    }

private val GrantSource.label: String
    get() = when (this) {
        GrantSource.INSTALL -> "安装审核"
        GrantSource.RUNTIME -> "运行时确认"
        GrantSource.SETTINGS -> "权限中心"
        GrantSource.POLICY -> "安全策略"
    }

private fun DataResult.Failure.userMessage(): String = when (this) {
    is DataResult.Failure.NotFound -> "工具已不存在"
    is DataResult.Failure.StorageFailure -> "存储暂时不可用"
    else -> "请求被安全策略拒绝"
}
