package io.toolbox.host.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.toolbox.tool.packagekit.DiscardResult
import io.toolbox.tool.packagekit.ImportInspection
import io.toolbox.tool.packagekit.InspectionResult
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageRejection
import io.toolbox.tool.packagekit.ResumeInspectionResult
import io.toolbox.tool.packagekit.ToolPackageInspector
import io.toolbox.tool.packagekit.lifecycle.InstallLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailure
import io.toolbox.tool.packagekit.lifecycle.RecoveryLifecycleResult
import io.toolbox.tool.packagekit.lifecycle.ToolPackageLifecycle
import io.toolbox.tool.packagekit.lifecycle.ToolPackageStartupRecoveries
import io.toolbox.tool.packagekit.lifecycle.ToolPackageStartupRecovery
import io.toolbox.tool.packagekit.lifecycle.ToolPackageStartupRecoveryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ImportReviewViewModel(
    private val inspector: ToolPackageInspector,
    private val lifecycle: ToolPackageLifecycle,
    private val startupRecovery: ToolPackageStartupRecovery = ToolPackageStartupRecoveries.create(lifecycle, inspector),
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportReviewUiState())
    val state: StateFlow<ImportReviewUiState> = mutableState.asStateFlow()

    fun requestPicker() {
        if (state.value.phase != ImportReviewPhase.IDLE) return
        mutableState.value = state.value.copy(
            phase = ImportReviewPhase.PICKING,
            pickerRequestToken = state.value.nextRequestToken,
            nextRequestToken = state.value.nextRequestToken + 1,
            message = null,
            error = null,
        )
    }

    fun markPickerLaunched(token: Long) {
        if (state.value.pickerRequestToken == token) {
            mutableState.value = state.value.copy(pickerRequestToken = null)
        }
    }

    fun inspect(input: PackageInput) {
        if (state.value.phase != ImportReviewPhase.PICKING) return
        mutableState.value = ImportReviewUiState(
            phase = ImportReviewPhase.INSPECTING,
            nextRequestToken = state.value.nextRequestToken,
            selectedName = input.displayName,
        )
        launchOperation(ImportAsyncOperation.INSPECT) {
            when (val result = inspector.inspect(input)) {
                is InspectionResult.Inspected -> showInspection(result.inspection)
                is InspectionResult.Rejected -> showInspectionFailure(result.rejection)
            }
        }
    }

    fun pickerCancelled() {
        if (state.value.phase == ImportReviewPhase.PICKING) {
            mutableState.value = ImportReviewUiState(nextRequestToken = state.value.nextRequestToken)
        }
    }

    fun pickerRejected(message: String) {
        if (state.value.phase == ImportReviewPhase.PICKING) {
            mutableState.value = ImportReviewUiState(
                nextRequestToken = state.value.nextRequestToken,
                error = ImportReviewError.Message(message),
            )
        }
    }

    fun recoverColdStart() {
        if (state.value.phase != ImportReviewPhase.IDLE) return
        mutableState.value = state.value.copy(phase = ImportReviewPhase.RECOVERING, error = null)
        launchOperation(ImportAsyncOperation.STARTUP_RECOVERY) {
            when (val result = startupRecovery.recover()) {
                is ToolPackageStartupRecoveryResult.Recovered -> {
                    val recovery = result.inspections
                    val recoveryFailure = recovery.recoveryFailure
                    when {
                        recoveryFailure != null -> showInspectionFailure(recoveryFailure)
                        recovery.inspections.size == 1 -> showInspection(recovery.inspections.single())
                        else -> mutableState.value = ImportReviewUiState(
                            nextRequestToken = state.value.nextRequestToken,
                            recoverySessions = recovery.inspections.map { inspection ->
                                RecoveredInspection(
                                    sessionId = inspection.sessionId,
                                    sourceName = inspection.sourceName,
                                    toolName = inspection.manifest.name,
                                )
                            },
                            message = recoveryMessage(
                                count = recovery.inspections.size,
                                busy = recovery.busySessionCount,
                                issues = recovery.issues.size,
                                truncated = recovery.truncated,
                            ),
                        )
                    }
                }
                is ToolPackageStartupRecoveryResult.Pending -> showLifecycleRecoveryFailure(result.reason)
            }
        }
    }

    fun resume(sessionId: String) {
        if (state.value.phase != ImportReviewPhase.IDLE || sessionId.isBlank()) return
        mutableState.value = state.value.copy(phase = ImportReviewPhase.RECOVERING, error = null)
        launchOperation(ImportAsyncOperation.RESUME) {
            when (val lifecycleResult = lifecycle.recover()) {
                RecoveryLifecycleResult.Recovered -> when (val result = inspector.resume(sessionId)) {
                    is ResumeInspectionResult.Resumed -> showInspection(result.inspection)
                    ResumeInspectionResult.NotFound -> showMessage("审核会话已不存在，请重新选择工具包。")
                    ResumeInspectionResult.Busy -> showMessage("审核会话正在被其他操作使用，请稍后重试。")
                    is ResumeInspectionResult.Rejected -> showInspectionFailure(result.rejection)
                }
                is RecoveryLifecycleResult.Pending -> showLifecycleRecoveryFailure(lifecycleResult.reason)
            }
        }
    }

    fun setPermissionGrant(permission: String, choice: ImportGrantChoice) {
        val review = state.value.review ?: return
        if (state.value.phase != ImportReviewPhase.REVIEW) return
        if (review.permissions.none { it.name == permission }) {
            mutableState.value = state.value.copy(
                error = ImportReviewError.InvalidGrant("权限 $permission 未在工具清单中声明。"),
            )
            return
        }
        mutableState.value = state.value.copy(
            grants = state.value.grants + (permission to choice),
            reviewConfirmed = false,
            error = null,
        )
    }

    fun confirmReview() {
        if (state.value.phase != ImportReviewPhase.REVIEW) return
        mutableState.value = if (state.value.hasValidGrantPlan) {
            state.value.copy(reviewConfirmed = true, error = null)
        } else {
            state.value.copy(
                reviewConfirmed = false,
                error = ImportReviewError.InvalidGrant("必需权限必须逐项允许本次会话，才能安装。"),
            )
        }
    }

    fun install() {
        val snapshot = state.value
        val review = snapshot.review ?: return
        if (!snapshot.canInstall) return
        val grants = snapshot.initialGrantPlan(now())
        if (grants == null) {
            mutableState.value = snapshot.copy(error = ImportReviewError.InvalidGrant("权限计划不完整，请重新审核。"))
            return
        }
        mutableState.value = snapshot.copy(phase = ImportReviewPhase.INSTALLING, error = null)
        launchOperation(ImportAsyncOperation.INSTALL) {
            when (val result = lifecycle.install(review.sessionId, grants)) {
                is InstallLifecycleResult.Committed -> showInstalled(
                    ImportInstallFeedback.Committed(result.toolId, result.versionCode),
                )
                is InstallLifecycleResult.AlreadyCommitted -> showInstalled(
                    ImportInstallFeedback.AlreadyCommitted(result.toolId, result.versionCode),
                )
                is InstallLifecycleResult.CommittedRecoveryPending -> showInstalled(
                    ImportInstallFeedback.CommittedRecoveryPending(result.toolId, result.versionCode, result.reason),
                )
                InstallLifecycleResult.InspectionNotFound -> showInstallFailure("审核会话已失效，请重新导入。")
                InstallLifecycleResult.InspectionBusy -> showInstallFailure("审核会话正在被其他操作使用，请稍后重试。")
                is InstallLifecycleResult.Failed -> showInstallFailure(result.reason.message, result.reason)
            }
        }
    }

    fun cancelAndExit() {
        if (state.value.phase == ImportReviewPhase.INSTALLED) {
            requestExit()
            return
        }
        if (state.value.phase !in setOf(ImportReviewPhase.IDLE, ImportReviewPhase.REVIEW)) return
        val sessionId = state.value.review?.sessionId
        if (sessionId == null) {
            requestExit()
            return
        }
        if (state.value.phase != ImportReviewPhase.REVIEW) return
        mutableState.value = state.value.copy(phase = ImportReviewPhase.CANCELLING, error = null)
        launchOperation(ImportAsyncOperation.CANCEL) {
            when (val result = inspector.discard(sessionId)) {
                DiscardResult.Discarded, DiscardResult.NotFound -> {
                    val nextToken = state.value.nextRequestToken
                    mutableState.value = ImportReviewUiState(
                        exitRequestToken = nextToken,
                        nextRequestToken = nextToken + 1,
                    )
                }
                is DiscardResult.Failed -> mutableState.value = state.value.copy(
                    phase = ImportReviewPhase.REVIEW,
                    error = ImportReviewError.Inspection(result.rejection),
                    cancelRetryAvailable = true,
                )
            }
        }
    }

    fun markExitHandled(token: Long) {
        if (state.value.exitRequestToken == token) {
            mutableState.value = ImportReviewUiState(nextRequestToken = state.value.nextRequestToken)
        }
    }

    fun dismissError() {
        mutableState.value = state.value.copy(error = null)
    }

    private fun launchOperation(operation: ImportAsyncOperation, block: suspend () -> Unit) {
        viewModelScope.launchImportOperation(operation, { state.value }, { mutableState.value = it }, block)
    }

    private fun showInspection(inspection: ImportInspection) {
        mutableState.value = state.value.reviewing(inspection)
    }

    private fun showInspectionFailure(rejection: PackageRejection) {
        mutableState.value = state.value.inspectionFailed(rejection)
    }

    private fun showLifecycleRecoveryFailure(failure: LifecycleFailure) {
        mutableState.value = state.value.lifecycleRecoveryFailed(failure)
    }

    private fun showMessage(message: String) {
        mutableState.value = ImportReviewUiState(
            nextRequestToken = state.value.nextRequestToken,
            message = message,
        )
    }

    private fun showInstallFailure(message: String, failure: LifecycleFailure? = null) {
        mutableState.value = state.value.copy(
            phase = ImportReviewPhase.REVIEW,
            reviewConfirmed = false,
            error = failure?.let(ImportReviewError::Lifecycle) ?: ImportReviewError.Message(message),
        )
    }

    private fun showInstalled(feedback: ImportInstallFeedback) {
        mutableState.value = state.value.copy(
            phase = ImportReviewPhase.INSTALLED,
            installFeedback = feedback,
            reviewConfirmed = false,
        )
    }

    private fun requestExit() {
        val token = state.value.nextRequestToken
        mutableState.value = state.value.copy(
            exitRequestToken = token,
            nextRequestToken = token + 1,
        )
    }
}
