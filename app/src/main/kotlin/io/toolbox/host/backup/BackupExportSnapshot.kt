package io.toolbox.host.backup

import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvSnapshot
import io.toolbox.core.data.ToolKvValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Every database row an export needs, read in one short transaction. */
internal data class ExportSnapshot(
    val settings: HostSettings,
    val tools: List<ExportToolSnapshot>,
)

internal data class ExportToolSnapshot(
    val tool: InstalledTool,
    val grants: List<PermissionGrant>,
    /** In the repository's key order. */
    val rows: Map<String, ToolKvValue>,
    val tasks: List<Pair<BackgroundTask, TaskRunResult?>>,
)

/**
 * Read-only view over rows captured inside the export transaction, so secure storage can be
 * decrypted after the transaction commits while still seeing exactly the snapshotted rows.
 */
internal class SnapshotToolKvRepository(
    private val toolId: String,
    private val rows: Map<String, ToolKvValue>,
) : ToolKvRepository {
    override suspend fun <T> readSnapshot(toolId: String, action: suspend (ToolKvSnapshot) -> T): T =
        action(object : ToolKvSnapshot {
            override suspend fun getMany(keys: Set<String>): Map<String, ToolKvValue> =
                if (toolId != this@SnapshotToolKvRepository.toolId) emptyMap()
                else buildMap { for (key in keys) rows[key]?.let { put(key, it) } }
        })

    override fun observe(toolId: String, key: String): Flow<ToolKvValue?> =
        flowOf(if (toolId == this.toolId) rows[key] else null)

    override suspend fun keys(toolId: String): List<String> =
        if (toolId == this.toolId) rows.keys.toList() else emptyList()

    override suspend fun bytesUsed(toolId: String): Long =
        if (toolId == this.toolId) rows.values.sumOf { it.bytes.toLong() } else 0L

    override suspend fun put(toolId: String, key: String, valueJson: String, updatedAt: Long): DataResult<Unit> = readOnly()

    override suspend fun replace(
        toolId: String,
        removeKeys: Set<String>,
        values: Map<String, String>,
        updatedAt: Long,
    ): DataResult<Unit> = readOnly()

    override suspend fun remove(toolId: String, key: String): DataResult<Unit> = readOnly()

    private fun readOnly(): Nothing = throw UnsupportedOperationException("Backup export snapshot is read-only")
}
