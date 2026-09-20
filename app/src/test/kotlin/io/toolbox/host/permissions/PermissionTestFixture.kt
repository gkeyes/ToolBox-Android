package io.toolbox.host.permissions

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataMutationLock
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import io.toolbox.host.HostDeleteResult
import io.toolbox.host.HostExampleInstallResult
import io.toolbox.host.HostImportCancellationResult
import io.toolbox.host.HostImportResult
import io.toolbox.host.HostInstalledManifest
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostManifestPermission
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.HostPermissionSideEffects
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.lifecycle.PackageImportControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal const val PERMISSION_TOOL_ID = "io.toolbox.permissionfixture"
internal const val SECURE_CAPABILITY = "storage.secure"

internal class PermissionTestFixture(scope: CoroutineScope) :
    CatalogRepository, PermissionGrantRepository, HostPackageOperations, HostPermissionSideEffects {
    val initialVersion = ToolVersion(
        PERMISSION_TOOL_ID, 1, "1.0.0", BundleLocator("tools/fixture/first"), 32, "first-hash", 1L,
    )
    val installed = MutableStateFlow<InstalledTool?>(InstalledTool(
        ToolMetadata(PERMISSION_TOOL_ID, "权限测试工具", SecurityProfile.STRICT, 1L), initialVersion, null,
    ))
    val storedGrants = MutableStateFlow(listOf(
        PermissionGrant(PERMISSION_TOOL_ID, SECURE_CAPABILITY, true, 1L),
        PermissionGrant(PERMISSION_TOOL_ID, "haptics", true, 1L),
        PermissionGrant(PERMISSION_TOOL_ID, "location", false, 1L),
    ))
    var capabilities = listOf(SECURE_CAPABILITY, "haptics", "location")
    val grantWrites = mutableListOf<PermissionGrant>()
    val writeFailures = mutableSetOf<String>()
    var failNextCatalogRead = false
    var failNextGrantRead = false
    val cleanupCalls = mutableListOf<String>()
    val cleanupGrants = mutableListOf<PermissionGrant?>()
    var cleanup: suspend (String) -> Unit = {}
    val packageLock = DataMutationLock()
    val runner = PermissionMutationRunner(this, this, this, this, scope, now = { 1L }, mutationLock = packageLock)

    fun grant(capability: String = SECURE_CAPABILITY) = storedGrants.value.first { it.capability == capability }

    fun replaceGrant(grant: PermissionGrant) {
        storedGrants.value = storedGrants.value.filterNot { it.capability == grant.capability } + grant
    }

    fun replaceVersion(version: ToolVersion) {
        installed.value = checkNotNull(installed.value).copy(currentVersion = version)
    }

    override fun observeTool(toolId: String): Flow<InstalledTool?> = flow {
        if (failNextCatalogRead) {
            failNextCatalogRead = false
            error("injected catalog read failure")
        }
        emitAll(installed)
    }
    override fun observeTools(): Flow<List<InstalledTool>> = installed.map { listOfNotNull(it) }
    override fun observeCatalogProjection(): Flow<List<CatalogEntry>> = flowOf(emptyList())
    override fun observeGrants(toolId: String): Flow<List<PermissionGrant>> = flow {
        if (failNextGrantRead) {
            failNextGrantRead = false
            error("injected grant read failure")
        }
        emitAll(storedGrants)
    }

    override suspend fun putForVersion(grant: PermissionGrant, expectedVersionCode: Int): DataResult<Unit> {
        if (installed.value?.currentVersion?.versionCode != expectedVersionCode) return DataResult.Failure.InvalidInput("versionCode")
        return put(grant)
    }

    override suspend fun put(grant: PermissionGrant): DataResult<Unit> {
        if (grant.capability in writeFailures) return DataResult.Failure.StorageFailure("putGrant")
        grantWrites += grant
        replaceGrant(grant)
        return DataResult.Success(Unit)
    }

    override suspend fun revoke(toolId: String, capability: String): DataResult<Unit> = error("unused")

    override suspend fun installedManifest(toolId: String): HostInstalledManifestResult {
        val tool = installed.value ?: return HostInstalledManifestResult.NotInstalled
        return HostInstalledManifestResult.Found(HostInstalledManifest(
            tool.metadata.id, tool.metadata.name, tool.currentVersion.versionCode, tool.currentVersion.version,
            capabilities.map { HostManifestPermission(it, "使用此能力", false) },
        ))
    }

    override suspend fun onCapabilityDisabled(toolId: String, capability: String) {
        cleanupCalls += capability
        cleanupGrants += storedGrants.value.firstOrNull { it.capability == capability }
        cleanup(capability)
    }

    override suspend fun importPackage(input: PackageInput, control: PackageImportControl): HostImportResult = error("unused")
    override suspend fun confirmImport(confirmationId: String, control: PackageImportControl): HostImportResult = error("unused")
    override suspend fun cancelImport(confirmationId: String): HostImportCancellationResult = error("unused")
    override suspend fun deleteTool(toolId: String): HostDeleteResult = error("unused")
    override suspend fun installBundledExamples(): HostExampleInstallResult = error("unused")
}
