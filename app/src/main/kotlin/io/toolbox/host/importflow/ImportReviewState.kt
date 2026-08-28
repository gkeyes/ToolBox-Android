package io.toolbox.host.importflow

import io.toolbox.core.data.GrantScope
import io.toolbox.core.data.GrantSource
import io.toolbox.core.data.GrantState
import io.toolbox.core.data.PermissionGrant
import io.toolbox.tool.packagekit.ImportInspection
import io.toolbox.tool.packagekit.InspectionBlocker
import io.toolbox.tool.packagekit.ManifestPermission
import io.toolbox.tool.packagekit.PackageRejection
import io.toolbox.tool.packagekit.RiskFinding
import io.toolbox.tool.packagekit.SecurityProfile
import io.toolbox.tool.packagekit.SignatureEvidence
import io.toolbox.tool.packagekit.lifecycle.LifecycleFailure

enum class ImportReviewPhase { IDLE, PICKING, RECOVERING, INSPECTING, REVIEW, CANCELLING, INSTALLING, INSTALLED }

enum class ImportGrantChoice { DENY, ALLOW_SESSION }

data class ImportReviewUiState(
    val phase: ImportReviewPhase = ImportReviewPhase.IDLE,
    val pickerRequestToken: Long? = null,
    val exitRequestToken: Long? = null,
    internal val nextRequestToken: Long = 1,
    val selectedName: String? = null,
    val review: ImportReviewFacts? = null,
    val grants: Map<String, ImportGrantChoice> = emptyMap(),
    val reviewConfirmed: Boolean = false,
    val cancelRetryAvailable: Boolean = false,
    val recoverySessions: List<RecoveredInspection> = emptyList(),
    val installFeedback: ImportInstallFeedback? = null,
    val message: String? = null,
    val error: ImportReviewError? = null,
) {
    val hasValidGrantPlan: Boolean
        get() {
            val facts = review ?: return false
            val declared = facts.permissions.map(ImportPermissionFact::name).toSet()
            if (declared.size != facts.permissions.size || grants.keys != declared) return false
            return facts.permissions.none { it.required && grants[it.name] != ImportGrantChoice.ALLOW_SESSION }
        }

    val canInstall: Boolean
        get() = phase == ImportReviewPhase.REVIEW && review?.installable == true && reviewConfirmed && hasValidGrantPlan
}

data class RecoveredInspection(val sessionId: String, val sourceName: String, val toolName: String)

data class ImportReviewFacts(
    val sessionId: String,
    val sourceName: String,
    val toolId: String,
    val toolName: String,
    val version: String,
    val versionCode: Int,
    val entry: String,
    val apiVersion: String,
    val securityProfile: SecurityProfile,
    val signature: SignatureEvidence,
    val publisherName: String?,
    val publisherKeyId: String?,
    val compressedBytes: Long,
    val extractedBytes: Long,
    val fileCount: Int,
    val files: List<String>,
    val permissions: List<ImportPermissionFact>,
    val networkDomains: List<String>,
    val riskFindings: List<RiskFinding>,
    val blockers: List<InspectionBlocker>,
    val installable: Boolean,
)

data class ImportPermissionFact(val name: String, val reason: String, val required: Boolean)

internal fun ImportReviewUiState.initialGrantPlan(grantedAt: Long): List<PermissionGrant>? {
    val facts = review ?: return null
    val declared = facts.permissions.associateBy(ImportPermissionFact::name)
    val plan = grants.mapNotNull { (name, choice) ->
        val permission = declared[name] ?: return@mapNotNull null
        PermissionGrant(
            toolId = facts.toolId,
            permission = permission.name,
            state = if (choice == ImportGrantChoice.ALLOW_SESSION) GrantState.GRANTED else GrantState.DENIED,
            scope = GrantScope.SESSION,
            grantedAt = grantedAt,
            expiresAt = null,
            source = GrantSource.INSTALL,
        )
    }
    return plan.takeIf { it.size == declared.size }
}

internal fun ImportReviewUiState.reviewing(inspection: ImportInspection): ImportReviewUiState {
    val facts = inspection.toReviewFacts()
    return ImportReviewUiState(
        phase = ImportReviewPhase.REVIEW,
        nextRequestToken = nextRequestToken,
        selectedName = inspection.sourceName,
        review = facts,
        grants = facts.permissions.associate { it.name to ImportGrantChoice.DENY },
    )
}

internal fun ImportReviewUiState.inspectionFailed(rejection: PackageRejection) = ImportReviewUiState(
    nextRequestToken = nextRequestToken,
    selectedName = selectedName,
    error = ImportReviewError.Inspection(rejection),
)

internal fun ImportReviewUiState.lifecycleRecoveryFailed(failure: LifecycleFailure) = ImportReviewUiState(
    nextRequestToken = nextRequestToken,
    error = ImportReviewError.Lifecycle(failure),
    message = "安装目录尚未恢复，未读取任何审核会话。",
)

sealed interface ImportInstallFeedback {
    val toolId: String
    val versionCode: Int

    data class Committed(override val toolId: String, override val versionCode: Int) : ImportInstallFeedback
    data class AlreadyCommitted(override val toolId: String, override val versionCode: Int) : ImportInstallFeedback
    data class CommittedRecoveryPending(
        override val toolId: String,
        override val versionCode: Int,
        val reason: LifecycleFailure,
    ) : ImportInstallFeedback
}

sealed interface ImportReviewError {
    data class Inspection(val rejection: PackageRejection) : ImportReviewError
    data class Lifecycle(val failure: LifecycleFailure) : ImportReviewError
    data class InvalidGrant(val detail: String) : ImportReviewError
    data class Message(val detail: String) : ImportReviewError

    val userMessage: String
        get() = when (this) {
            is Inspection -> "工具包未通过检查：${rejection.detail}"
            is Lifecycle -> "安装目录操作未完成：${failure.message}"
            is InvalidGrant -> detail
            is Message -> detail
        }
}

internal fun ImportInspection.toReviewFacts() = ImportReviewFacts(
    sessionId = sessionId,
    sourceName = sourceName,
    toolId = manifest.id,
    toolName = manifest.name,
    version = manifest.version,
    versionCode = manifest.versionCode,
    entry = manifest.entry,
    apiVersion = manifest.apiVersion,
    securityProfile = manifest.securityProfile,
    signature = signature,
    publisherName = manifest.publisher?.name,
    publisherKeyId = manifest.publisher?.keyId,
    compressedBytes = archive.compressedBytes,
    extractedBytes = archive.extractedBytes,
    fileCount = archive.fileCount,
    files = archive.files,
    permissions = manifest.permissions.map(ManifestPermission::toFact),
    networkDomains = manifest.network?.allowDomains.orEmpty(),
    riskFindings = riskFindings,
    blockers = blockers,
    installable = installable,
)

private fun ManifestPermission.toFact() = ImportPermissionFact(name, reason, required)

internal fun recoveryMessage(count: Int, busy: Int, issues: Int, truncated: Boolean): String = buildString {
    if (count == 0) append("没有待继续的审核。") else append("发现 $count 个可继续的审核。")
    if (busy > 0) append(" $busy 个会话正在使用。")
    if (issues > 0) append(" 已处理 $issues 个损坏残留。")
    if (truncated) append(" 仍有候选会话等待下次恢复。")
}
