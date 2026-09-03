package io.toolbox.host

import io.toolbox.core.data.CatalogRepository
import io.toolbox.tool.runtime.RuntimePreparationResult
import io.toolbox.tool.runtime.ToolRuntimePreparer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class HostInstalledManifestReader(
    privateFilesDirectory: File,
    private val catalog: CatalogRepository,
) {
    private val preparer = ToolRuntimePreparer(privateFilesDirectory, BuildConfig.VERSION_NAME)

    suspend fun read(toolId: String): HostInstalledManifestResult = withContext(Dispatchers.IO) {
        val tool = catalog.observeTool(toolId).first()
            ?: return@withContext HostInstalledManifestResult.NotInstalled
        when (val result = preparer.prepare(toolId, tool)) {
            is RuntimePreparationResult.Prepared -> {
                val manifest = result.runtime.installedManifest
                HostInstalledManifestResult.Found(
                    HostInstalledManifest(
                        toolId = manifest.id,
                        toolName = manifest.name,
                        versionCode = manifest.versionCode,
                        versionName = tool.currentVersion.version,
                        permissions = manifest.permissionDeclarations.map {
                            HostManifestPermission(it.name, it.reason, it.required)
                        },
                    ),
                )
            }
            is RuntimePreparationResult.Failed -> HostInstalledManifestResult.Failed(result.code, result.message)
        }
    }
}
