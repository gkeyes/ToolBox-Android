package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.tool.packagekit.ResumableInspectionRecovery
import io.toolbox.tool.packagekit.ToolPackageInspector
import io.toolbox.tool.packagekit.recoverResumableAfterLifecycle

fun interface ToolPackageStartupRecovery {
    suspend fun recover(): ToolPackageStartupRecoveryResult
}

object ToolPackageStartupRecoveries {
    fun create(
        lifecycle: ToolPackageLifecycle,
        inspector: ToolPackageInspector,
    ): ToolPackageStartupRecovery = ToolPackageStartupRecovery {
        when (val lifecycleResult = lifecycle.recover()) {
            is RecoveryLifecycleResult.Pending -> ToolPackageStartupRecoveryResult.Pending(lifecycleResult.reason)
            RecoveryLifecycleResult.Recovered -> {
                val inspections = inspector.recoverResumableAfterLifecycle()
                if (inspections == null) {
                    ToolPackageStartupRecoveryResult.Pending(
                        LifecycleFailure(
                            LifecycleFailureCode.RECOVERY_REQUIRED,
                            "Inspector does not support durable startup recovery",
                        ),
                    )
                } else {
                    ToolPackageStartupRecoveryResult.Recovered(inspections)
                }
            }
        }
    }
}

sealed interface ToolPackageStartupRecoveryResult {
    data class Recovered(val inspections: ResumableInspectionRecovery) : ToolPackageStartupRecoveryResult
    data class Pending(val reason: LifecycleFailure) : ToolPackageStartupRecoveryResult
}
