package io.toolbox.host.runtime

import io.toolbox.core.data.CatalogGrantEpoch
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.runtime.RuntimeGrantStateSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first

/**
 * Serves every RPC's version and grant checks (several per call, and per network stream chunk)
 * from one snapshot per tool. A snapshot is reused only while [CatalogGrantEpoch] is unchanged;
 * every install, update, uninstall, grant write and backup restore advances it.
 */
internal class RepositoryRuntimeGrantStateSource(
    private val catalog: CatalogRepository,
    private val grants: PermissionGrantRepository,
    private val epoch: () -> Long = CatalogGrantEpoch::current,
) : RuntimeGrantStateSource {
    private class Snapshot(val epoch: Long, val versionCode: Int?, val granted: Set<String>)

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    override suspend fun currentVersionCode(toolId: String): Int? =
        if (io.toolbox.host.backup.BackupRuntimeGate.paused) null else installedVersionCode(toolId)

    /** The committed version, ignoring a paused backup restore. */
    suspend fun installedVersionCode(toolId: String): Int? = snapshot(toolId).versionCode

    override suspend fun isGranted(toolId: String, capability: ToolBoxCapabilityId): Boolean =
        ToolBoxApiV1.capability(capability).wireName in snapshot(toolId).granted

    private suspend fun snapshot(toolId: String): Snapshot {
        val current = epoch()
        snapshots[toolId]?.takeIf { it.epoch == current }?.let { return it }
        // Older snapshots (including uninstalled tools) can never be served again.
        snapshots.values.removeIf { it.epoch != current }
        val versionCode = catalog.observeTool(toolId).first()?.currentVersion?.versionCode
        val granted = grants.observeGrants(toolId).first().filter { it.granted }.mapTo(hashSetOf()) { it.capability }
        return Snapshot(current, versionCode, granted).also { snapshots[toolId] = it }
    }
}
