package io.toolbox.host.backup

import io.toolbox.core.data.DataMutationLock
import io.toolbox.host.*
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.lifecycle.PackageImportControl

/** Outer package/permission lock: do not reverse runtime-storage -> repository lock ordering. */
internal class SerializedPackageOperations(private val delegate: HostPackageOperations, private val lock: DataMutationLock,
    private val reconcileCatalog: suspend () -> io.toolbox.core.data.DataResult<Unit> = { io.toolbox.core.data.DataResult.Success(Unit) },
) : HostPackageOperations by delegate, HostPackageMaintenance {
    // Do not queue user mutations behind an upgrade which is draining runtime work.
    // Backup owns the same lock; nested restore installs remain reentrant.
    override suspend fun importPackage(input: PackageInput, control: PackageImportControl): HostImportResult =
        lock.tryRun({ HostImportResult.Failed("BUSY", busyMessage) }) { delegate.importPackage(input, control) }
    override suspend fun confirmImport(confirmationId: String, control: PackageImportControl): HostImportResult =
        lock.tryRun({ HostImportResult.Failed("BUSY", busyMessage) }) { delegate.confirmImport(confirmationId, control) }
    override suspend fun cancelImport(confirmationId: String): HostImportCancellationResult =
        lock.tryRun({ HostImportCancellationResult.Failed("BUSY", busyMessage) }) { delegate.cancelImport(confirmationId) }
    override suspend fun deleteTool(toolId: String): HostDeleteResult =
        lock.tryRun({ HostDeleteResult.Failed("BUSY", busyMessage) }) {
            val result = delegate.deleteTool(toolId)
            if (result == HostDeleteResult.Deleted || result == HostDeleteResult.AlreadyAbsent) {
                if (reconcileCatalog() !is io.toolbox.core.data.DataResult.Success) {
                    return@tryRun HostDeleteResult.Failed("CATALOG_LAYOUT_WRITE", "工具已删除，但收藏与分组清理未保存，请重新启动 ToolBox 重试。")
                }
            }
            result
        }
    override suspend fun installBundledExamples(): HostExampleInstallResult =
        lock.tryRun({ HostExampleInstallResult.Failed("BUSY", busyMessage) }) { delegate.installBundledExamples() }
    override suspend fun recoverPendingMutations() = lock.run { (delegate as? HostPackageMaintenance)?.recoverPendingMutations(); Unit }

    private val busyMessage = "正在备份、恢复或处理另一个工具包，请在当前操作完成后重试。"
}
