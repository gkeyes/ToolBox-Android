package io.toolbox.tool.packagekit

internal interface ResumableInspectionRecoveryConsumer {
    suspend fun recoverResumableAfterLifecycle(): ResumableInspectionRecovery
}

internal suspend fun ToolPackageInspector.recoverResumableAfterLifecycle(): ResumableInspectionRecovery? =
    (this as? ResumableInspectionRecoveryConsumer)?.recoverResumableAfterLifecycle()
