package io.toolbox.host.backup

import io.toolbox.core.data.DataMutationLock
import io.toolbox.host.*
import io.toolbox.tool.packagekit.PackageInput

/** Outer package/permission lock: do not reverse runtime-storage -> repository lock ordering. */
internal class SerializedPackageOperations(private val delegate: HostPackageOperations, private val lock: DataMutationLock) : HostPackageOperations by delegate, HostPackageMaintenance {
    // Do not queue user mutations behind an upgrade which is draining runtime work.
    // Backup owns the same lock; nested restore installs remain reentrant.
    override suspend fun importPackage(input: PackageInput): HostImportResult =
        lock.tryRun({ HostImportResult.Failed("BUSY", busyMessage) }) { delegate.importPackage(input) }
    override suspend fun confirmImport(confirmationId: String): HostImportResult =
        lock.tryRun({ HostImportResult.Failed("BUSY", busyMessage) }) { delegate.confirmImport(confirmationId) }
    override suspend fun cancelImport(confirmationId: String): HostImportCancellationResult =
        lock.tryRun({ HostImportCancellationResult.Failed("BUSY", busyMessage) }) { delegate.cancelImport(confirmationId) }
    override suspend fun deleteTool(toolId: String): HostDeleteResult =
        lock.tryRun({ HostDeleteResult.Failed("BUSY", busyMessage) }) { delegate.deleteTool(toolId) }
    override suspend fun installBundledExamples(): HostExampleInstallResult =
        lock.tryRun({ HostExampleInstallResult.Failed("BUSY", busyMessage) }) { delegate.installBundledExamples() }
    override suspend fun recoverPendingMutations() = lock.run { (delegate as? HostPackageMaintenance)?.recoverPendingMutations(); Unit }

    private val busyMessage = "正在备份、恢复或处理另一个工具包，请在当前操作完成后重试。"
}
