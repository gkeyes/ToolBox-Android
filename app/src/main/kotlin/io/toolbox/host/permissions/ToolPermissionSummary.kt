package io.toolbox.host.permissions

import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.host.HostInstalledManifestResult
import io.toolbox.host.HostPackageOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal sealed interface ToolPermissionSummary {
    data object Loading : ToolPermissionSummary
    data object Unavailable : ToolPermissionSummary
    /** [granted] lists the declared capabilities counted in [enabled]. */
    data class Ready(val enabled: Int, val total: Int, val granted: Set<String> = emptySet()) : ToolPermissionSummary
}

/** Stored grants stay on after the user revokes the Android permission in system settings; count those as off. */
internal fun ToolPermissionSummary.withSystemState(systemGranted: (String) -> Boolean, exactAlarms: Boolean = true): ToolPermissionSummary =
    if (this !is ToolPermissionSummary.Ready) this
    else granted.filterTo(mutableSetOf()) { capabilitySystemAllowed(it, systemGranted, exactAlarms) }.let { on -> copy(enabled = on.size, granted = on) }

internal val ToolPermissionSummary.label: String
    get() = when (this) {
        ToolPermissionSummary.Loading -> "正在读取权限…"
        ToolPermissionSummary.Unavailable -> "状态暂不可用 · 点此查看"
        is ToolPermissionSummary.Ready -> when {
            total == 0 -> "未声明权限"
            enabled == total -> "已允许全部 $total 项"
            else -> "已允许 $enabled / $total 项 · ${total - enabled} 项关闭"
        }
    }

/** Read-only, per-visible-row flow. It never requests system permissions or writes grants. */
internal class ToolPermissionSummaryReader(
    private val catalog: CatalogRepository,
    private val packages: HostPackageOperations,
    private val grants: PermissionGrantRepository,
) {
    private val manifestReads = Semaphore(2)

    fun observe(tool: InstalledTool): Flow<ToolPermissionSummary> = flow<ToolPermissionSummary> {
        emit(ToolPermissionSummary.Loading)
        val toolId = tool.metadata.id
        val expected = tool.currentVersion
        val found = manifestReads.withPermit { packages.installedManifest(toolId) } as? HostInstalledManifestResult.Found
        val manifest = found?.manifest
        if (manifest == null || manifest.toolId != toolId || manifest.versionCode != expected.versionCode || manifest.versionName != expected.version) {
            emit(ToolPermissionSummary.Unavailable)
            return@flow
        }
        val declared = manifest.permissions.map { it.capability }.toSet()
        // Compare the full installed version, including integrity and bundle identity.
        // A same-version replacement must not keep displaying the previous grants summary.
        emitAll(combine(
            grants.observeGrants(toolId),
            catalog.observeTool(toolId).map { it?.currentVersion }.distinctUntilChanged(),
        ) { values, current ->
            if (current != expected) ToolPermissionSummary.Unavailable
            else summarizeToolPermissions(toolId, declared, values)
        }.distinctUntilChanged())
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(ToolPermissionSummary.Unavailable)
    }.flowOn(Dispatchers.IO)
}

internal fun summarizeToolPermissions(toolId: String, declared: Set<String>, grants: List<PermissionGrant>): ToolPermissionSummary.Ready {
    val values = grants.filter { it.toolId == toolId }.associateBy(PermissionGrant::capability)
    val granted = declared.filterTo(mutableSetOf()) { capability -> values[capability]?.granted ?: capability.defaultEnabled() }
    return ToolPermissionSummary.Ready(enabled = granted.size, total = declared.size, granted = granted)
}
