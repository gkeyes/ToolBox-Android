package io.toolbox.host.backup

import io.toolbox.core.data.DataMutationLock
import io.toolbox.host.*
import io.toolbox.tool.packagekit.PackageInput

/** Outer package/permission lock: do not reverse runtime-storage -> repository lock ordering. */
internal class SerializedPackageOperations(private val delegate: HostPackageOperations, private val lock: DataMutationLock) : HostPackageOperations by delegate, HostPackageMaintenance {
    override suspend fun importPackage(input: PackageInput) = lock.run { delegate.importPackage(input) }
    override suspend fun confirmImport(confirmationId: String) = lock.run { delegate.confirmImport(confirmationId) }
    override suspend fun cancelImport(confirmationId: String) = lock.run { delegate.cancelImport(confirmationId) }
    override suspend fun deleteTool(toolId: String) = lock.run { delegate.deleteTool(toolId) }
    override suspend fun installBundledExamples() = lock.run { delegate.installBundledExamples() }
    override suspend fun recoverPendingMutations() = lock.run { (delegate as? HostPackageMaintenance)?.recoverPendingMutations(); Unit }
}
