package io.toolbox.host.backup

import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.PermissionGrant
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskState
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.core.data.ToolKvSnapshot
import io.toolbox.core.data.ToolKvValue
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What an export read inside its transaction; bulk rows live in spill files, not in memory. */
internal data class ExportSnapshot(
    val settings: HostSettings,
    val tools: List<ExportToolSnapshot>,
)

internal data class ExportToolSnapshot(
    val tool: InstalledTool,
    val grants: List<PermissionGrant>,
    /** Key-value rows in the repository's key order, written by [ExportSpillWriter.kv]. */
    val keyValues: File,
    /** Task rows with their results, written by [ExportSpillWriter.task]. */
    val tasks: File,
)

/**
 * Plain, streaming serialization of rows read inside the export transaction, so the transaction
 * holds at most one page of rows in memory and does no JSON, ZIP or Keystore work. Strings are
 * stored as raw UTF-16 code units so every key and value (including lone surrogates) round-trips
 * exactly. A spill without its end marker is rejected as truncated.
 */
internal class ExportSpillWriter(file: File) : Closeable {
    private val output = DataOutputStream(BufferedOutputStream(FileOutputStream(file)))

    fun kv(row: ToolKvValue) {
        output.writeByte(SPILL_KV)
        text(row.key); text(row.valueJson); output.writeLong(row.updatedAt)
    }

    /** The execution token is host-only and is never spilled. */
    fun task(task: BackgroundTask, result: TaskRunResult?) {
        output.writeByte(SPILL_TASK)
        text(task.taskId); text(task.toolId); output.writeInt(task.versionCode); text(task.key)
        text(task.operation.name); text(task.specJson); output.writeBoolean(task.periodic)
        optionalLong(task.intervalMinutes); text(task.state.name); output.writeLong(task.createdAt)
        output.writeLong(task.updatedAt); optionalLong(task.nextRunAt); output.writeInt(task.runAttempt)
        output.writeBoolean(result != null)
        if (result != null) {
            text(result.taskId); text(result.outcome.name); output.writeLong(result.completedAt)
            optionalText(result.payloadJson); optionalText(result.errorCode); output.writeInt(result.attemptCount)
        }
    }

    /** Marks the spill complete; call once after the last row. */
    fun finish() { output.writeByte(SPILL_END); output.flush() }

    override fun close() = output.close()

    private fun text(value: String) {
        output.writeInt(value.length)
        val bytes = ByteArray(value.length * 2)
        for (index in value.indices) {
            val code = value[index].code
            bytes[2 * index] = (code shr 8).toByte()
            bytes[2 * index + 1] = code.toByte()
        }
        output.write(bytes)
    }
    private fun optionalText(value: String?) { output.writeBoolean(value != null); if (value != null) text(value) }
    private fun optionalLong(value: Long?) { output.writeBoolean(value != null); if (value != null) output.writeLong(value) }
}

/** Reads one row at a time back from an [ExportSpillWriter] file. */
internal class ExportSpillReader(file: File) : Closeable {
    private val input = DataInputStream(BufferedInputStream(FileInputStream(file)))

    /** Next key-value row, or null at the end marker. */
    fun nextKv(): ToolKvValue? = when (input.readByte().toInt()) {
        SPILL_END -> null
        SPILL_KV -> ToolKvValue(text(), text(), input.readLong())
        else -> throw IOException("Invalid backup spill record")
    }

    /** Next task and result, or null at the end marker. */
    fun nextTask(): Pair<BackgroundTask, TaskRunResult?>? = when (input.readByte().toInt()) {
        SPILL_END -> null
        SPILL_TASK -> {
            val task = BackgroundTask(
                taskId = text(), toolId = text(), versionCode = input.readInt(), key = text(),
                operation = BackgroundOperation.valueOf(text()), specJson = text(), periodic = input.readBoolean(),
                intervalMinutes = optionalLong(), state = TaskState.valueOf(text()), createdAt = input.readLong(),
                updatedAt = input.readLong(), nextRunAt = optionalLong(), runAttempt = input.readInt(),
            )
            val result = if (input.readBoolean()) TaskRunResult(
                taskId = text(), outcome = RunOutcome.valueOf(text()), completedAt = input.readLong(),
                payloadJson = optionalText(), errorCode = optionalText(), attemptCount = input.readInt(),
            ) else null
            task to result
        }
        else -> throw IOException("Invalid backup spill record")
    }

    override fun close() = input.close()

    private fun text(): String {
        val length = input.readInt()
        if (length < 0 || length > Int.MAX_VALUE / 2) throw IOException("Invalid backup spill record")
        val bytes = ByteArray(length * 2)
        input.readFully(bytes)
        val chars = CharArray(length) { index ->
            (((bytes[2 * index].toInt() and 0xff) shl 8) or (bytes[2 * index + 1].toInt() and 0xff)).toChar()
        }
        return String(chars)
    }
    private fun optionalText(): String? = if (input.readBoolean()) text() else null
    private fun optionalLong(): Long? = if (input.readBoolean()) input.readLong() else null
}

private const val SPILL_END = 0
private const val SPILL_KV = 1
private const val SPILL_TASK = 2

/**
 * Read-only view over secure rows captured inside the export transaction, so secure storage can
 * be decrypted after the transaction commits while still seeing exactly the snapshotted rows.
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
