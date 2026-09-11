package io.toolbox.core.data.backup

import android.database.Cursor
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import io.toolbox.core.data.*
import io.toolbox.core.data.db.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.coroutines.*

/** Logical, private rollback snapshots: never copies a live SQLite/WAL file or executes user-supplied SQL. */
class BackupDatabase internal constructor(private val database: ToolBoxDatabase) {
    suspend fun <T> transaction(action: suspend () -> T): T = database.withTransaction { action() }

    suspend fun checkpoint(destination: File) = withContext(Dispatchers.IO) {
        val partial = File(destination.parentFile, "${destination.name}.partial")
        try {
            database.withTransaction {
                FileOutputStream(partial).use { output ->
                    val writer = JsonWriter(output.writer(Charsets.UTF_8))
                    writer.beginArray()
                    TABLES.forEach { table ->
                        database.openHelper.writableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                            writer.beginObject().name("table").value(table).name("columns").beginArray()
                            cursor.columnNames.forEach { writer.value(it) }
                            writer.endArray().name("rows").beginArray()
                            while (cursor.moveToNext()) {
                                currentCoroutineContext().ensureActive()
                                writer.beginArray()
                                for (column in 0 until cursor.columnCount) when (cursor.getType(column)) {
                                    Cursor.FIELD_TYPE_NULL -> writer.nullValue()
                                    Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(column))
                                    Cursor.FIELD_TYPE_STRING -> writer.value(cursor.getString(column))
                                    else -> error("UNSUPPORTED_CHECKPOINT_CELL")
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

    suspend fun restoreCheckpoint(source: File) = withContext(Dispatchers.IO) {
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
        require(categoryId == null || categoryId.length <= 200)
        database.withTransaction {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("UPDATE tools SET installedAt=?, lastOpenedAt=?, pinnedOrder=?, categoryId=? WHERE id=?", arrayOf(installedAt, lastOpenedAt, pinnedOrder, categoryId, toolId))
            sql.execSQL("UPDATE tool_versions SET installedAt=? WHERE toolId=?", arrayOf(versionInstalledAt, toolId))
        }
    }

    /** Imported work is archival only. No pending work, alarm, permission or active session is scheduled. */
    suspend fun restoreTaskHistory(task: BackgroundTask, result: TaskRunResult?) {
        require(task.taskId.matches(Regex("^[A-Za-z0-9._:-]{1,128}$")))
        require(task.key.isNotBlank() && task.key.length <= CoreDataLimits.MAX_TASK_KEY_LENGTH)
        require(task.createdAt >= 0 && task.updatedAt >= task.createdAt && task.runAttempt >= 0)
        require(task.specJson.toByteArray().size <= CoreDataLimits.MAX_TASK_SPEC_BYTES)
        require(!task.periodic || (task.intervalMinutes != null && task.intervalMinutes >= 15))
        require(result == null || (result.taskId == task.taskId && result.completedAt >= 0 && result.attemptCount >= 0 && (result.payloadJson?.toByteArray()?.size ?: 0) <= CoreDataLimits.MAX_TASK_RESULT_BYTES))
        database.withTransaction {
            check(task.versionCode > 0 && database.versions().get(task.toolId) != null)
            check(database.backgroundTasks().get(task.taskId) == null)
            database.backgroundTasks().insert(BackgroundTaskEntity(
                task.taskId, task.toolId, task.versionCode, task.key, task.operation.name, task.specJson,
                task.periodic, task.intervalMinutes, if (task.state == TaskState.COMPLETED) "COMPLETED" else "CANCELLED",
                task.createdAt, task.updatedAt, null, task.runAttempt,
            ))
            result?.let { database.taskResults().put(TaskResultEntity(it.taskId, it.outcome.name, it.completedAt, it.payloadJson, it.errorCode, it.attemptCount)) }
        }
    }

    private companion object {
        val TABLES = listOf("tools", "tool_versions", "permission_grants", "tool_kv", "install_transactions", "background_tasks", "task_results")
    }
}
