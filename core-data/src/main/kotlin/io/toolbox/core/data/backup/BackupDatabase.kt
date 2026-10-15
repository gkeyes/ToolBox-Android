package io.toolbox.core.data.backup

import android.database.Cursor
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import io.toolbox.core.data.*
import io.toolbox.core.data.db.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.coroutines.*

/** Logical, private rollback snapshots: never copies a live SQLite/WAL file or executes user-supplied SQL. */
class BackupDatabase internal constructor(private val database: ToolBoxDatabase) {
    // Nested catalog writes advance the epoch before this outer commit; advance again after it.
    suspend fun <T> transaction(action: suspend () -> T): T = try {
        database.withTransaction { action() }
    } finally {
        CatalogGrantEpoch.advance()
    }

    suspend fun checkpoint(destination: File) = withContext(Dispatchers.IO) {
        val partial = File(destination.parentFile, "${destination.name}.partial")
        try {
            database.withTransaction {
                FileOutputStream(partial).use { output ->
                    val writer = JsonWriter(output.writer(Charsets.UTF_8))
                    writer.beginArray()
                    val sql = database.openHelper.writableDatabase
                    TABLES.forEach { table ->
                        val columns = sql.query("SELECT * FROM `$table` LIMIT 0").use { it.columnNames.toList() }
                        // A row holding a cell larger than one ~2 MB CursorWindow can't be read whole (a
                        // big background result, a big stored value), so text cells come out in slices.
                        // Slices are taken from the cell's bytes: SQLite's length()/substr() on TEXT stop
                        // at the first U+0000, which would cut a text cell holding a raw NUL short.
                        // The second value is the cell's UTF-8 byte length, or -1 when it isn't text.
                        val cells = columns.joinToString { column ->
                            "CASE typeof(`$column`) WHEN 'text' THEN substr(CAST(`$column` AS BLOB), 1, $CHECKPOINT_SLICE_BYTES) ELSE `$column` END, " +
                                "CASE typeof(`$column`) WHEN 'text' THEN length(CAST(`$column` AS BLOB)) ELSE -1 END"
                        }
                        sql.query("SELECT rowid, $cells FROM `$table` ORDER BY rowid").use { cursor ->
                            writer.beginObject().name("table").value(table).name("columns").beginArray()
                            columns.forEach { writer.value(it) }
                            writer.endArray().name("rows").beginArray()
                            while (cursor.moveToNext()) {
                                currentCoroutineContext().ensureActive()
                                val rowId = cursor.getLong(0)
                                writer.beginArray()
                                columns.forEachIndexed { index, column ->
                                    val cell = 1 + index * 2
                                    val textBytes = cursor.getInt(cell + 1)
                                    if (textBytes >= 0) {
                                        val first = if (cursor.isNull(cell)) null else cursor.getBlob(cell)
                                        writer.value(checkpointText(sql, table, column, rowId, textBytes, first))
                                    } else when (cursor.getType(cell)) {
                                        Cursor.FIELD_TYPE_NULL -> writer.nullValue()
                                        Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(cell))
                                        else -> error("UNSUPPORTED_CHECKPOINT_CELL")
                                    }
                                }
                                writer.endArray()
                            }
                            writer.endArray().endObject()
                        }
                    }
                    writer.endArray().flush(); output.fd.sync()
                }
            }
            Files.move(partial.toPath(), destination.toPath(), ATOMIC_MOVE)
        } finally { partial.delete() }
    }

    /** Reassembles one text cell from its [length] UTF-8 bytes, of which [first] holds the leading slice. */
    private fun checkpointText(
        sql: SupportSQLiteDatabase,
        table: String,
        column: String,
        rowId: Long,
        length: Int,
        first: ByteArray?,
    ): String {
        val bytes = ByteArray(length)
        var filled = first?.size?.coerceAtMost(length) ?: 0
        if (filled > 0) System.arraycopy(first!!, 0, bytes, 0, filled)
        while (filled < length) {
            // substr counts blob bytes from 1. A slice can end inside a multi-byte character, so decode only once whole.
            val slice = sql.query(
                "SELECT substr(CAST(`$column` AS BLOB), ?, $CHECKPOINT_CONTINUATION_BYTES) FROM `$table` WHERE rowid = ?",
                arrayOf<Any?>(filled + 1, rowId),
            ).use { cursor -> check(cursor.moveToFirst()); if (cursor.isNull(0)) null else cursor.getBlob(0) }
            check(slice != null && slice.isNotEmpty() && filled + slice.size <= length) { "CHECKPOINT_CELL_CHANGED" }
            System.arraycopy(slice, 0, bytes, filled, slice.size)
            filled += slice.size
        }
        return String(bytes, Charsets.UTF_8)
    }

    suspend fun restoreCheckpoint(source: File) = withContext(Dispatchers.IO) {
        try { restoreCheckpointRows(source) } finally { CatalogGrantEpoch.advance() }
    }

    private suspend fun restoreCheckpointRows(source: File) {
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            TABLES.asReversed().forEach { sql.execSQL("DELETE FROM `$it`") }
            JsonReader(source.reader(Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                TABLES.forEach { table ->
                    reader.beginObject()
                    check(reader.nextName() == "table" && reader.nextString() == table)
                    check(reader.nextName() == "columns")
                    reader.beginArray()
                    val columns = buildList { while (reader.hasNext()) add(reader.nextString()) }
                    reader.endArray()
                    val expected = sql.query("SELECT * FROM `$table` LIMIT 0").use { it.columnNames.toList() }
                    check(columns == expected)
                    val statement = "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})"
                    check(reader.nextName() == "rows")
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginArray()
                        val values = Array<Any?>(columns.size) {
                            when (reader.peek()) {
                                JsonToken.NULL -> { reader.nextNull(); null }
                                JsonToken.NUMBER -> reader.nextLong()
                                JsonToken.STRING -> reader.nextString()
                                else -> error("INVALID_CHECKPOINT_CELL")
                            }
                        }
                        reader.endArray()
                        sql.execSQL(statement, values)
                    }
                    reader.endArray(); reader.endObject()
                }
                reader.endArray()
                check(reader.peek() == JsonToken.END_DOCUMENT)
            }
        }
    }

    /** Only display metadata; identity, grants and executable bundle paths remain installer-owned. */
    suspend fun restorePresentation(toolId: String, installedAt: Long, versionInstalledAt: Long, lastOpenedAt: Long?, pinnedOrder: Int?, categoryId: String?) {
        require(installedAt >= 0 && versionInstalledAt >= 0 && (lastOpenedAt == null || lastOpenedAt >= 0))
        require(pinnedOrder == null || pinnedOrder >= 0)
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("UPDATE tools SET installedAt=?, lastOpenedAt=?, pinnedOrder=?, categoryId=? WHERE id=?", arrayOf<Any?>(installedAt, lastOpenedAt, pinnedOrder, categoryId, toolId))
            sql.execSQL("UPDATE tool_versions SET installedAt=? WHERE toolId=?", arrayOf<Any?>(versionInstalledAt, toolId))
        }
    }

    /** Imported work is archival only. No pending work, alarm, permission or active session is scheduled. */
    suspend fun restoreTaskHistory(task: BackgroundTask, result: TaskRunResult?) {
        require(task.taskId.matches(Regex("^[A-Za-z0-9._:-]+$")))
        require(task.key.isNotBlank())
        require(task.createdAt >= 0 && task.updatedAt >= task.createdAt && task.runAttempt >= 0)
        require(!task.periodic || (task.intervalMinutes != null && task.intervalMinutes >= 15))
        require(result == null || (result.taskId == task.taskId && result.completedAt >= 0 && result.attemptCount >= 0))
        database.withTransaction {
            check(task.versionCode > 0 && database.versions().get(task.toolId) != null)
            check(database.backgroundTasks().get(task.taskId) == null)
            database.backgroundTasks().insert(BackgroundTaskEntity(
                task.taskId, task.toolId, task.versionCode, task.key, task.operation.name, io.toolbox.core.data.db.TaskExecutionMetadata.spec(task.specJson),
                task.periodic, task.intervalMinutes, if (task.state == TaskState.COMPLETED) "COMPLETED" else "CANCELLED",
                task.createdAt, task.updatedAt, null, task.runAttempt,
            ))
            result?.let { database.taskResults().put(TaskResultEntity(it.taskId, it.outcome.name, it.completedAt, it.payloadJson, it.errorCode, it.attemptCount)) }
        }
    }

    private companion object {
        // UTF-8 bytes per text slice in the row query, so a row of several sliced cells still fits a window.
        const val CHECKPOINT_SLICE_BYTES = 256 * 1024
        // A continuation slice is the only cell of its query, so it can be larger and the passes fewer.
        const val CHECKPOINT_CONTINUATION_BYTES = 1024 * 1024
        val TABLES = listOf("tools", "tool_versions", "permission_grants", "tool_kv", "install_transactions", "background_tasks", "task_results")
    }
}
