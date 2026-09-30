package io.toolbox.host.background

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.TaskState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BackgroundTaskMigrationTest {
    @Test
    fun versionOneRowsSurviveIndexMigrationAndHistoryUsesStableKeyset() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "background-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                createVersionOneSchema(old)
                old.execSQL("INSERT INTO tools (id, name, securityProfile, installedAt) VALUES ('tool', 'Tool', 'STANDARD', 1)")
                repeat(52) { index ->
                    old.execSQL(
                        "INSERT INTO background_tasks (taskId, toolId, versionCode, `key`, operation, specJson, periodic, state, createdAt, updatedAt, runAttempt) VALUES (?, 'tool', 1, ?, 'NOTIFY', '{}', 0, 'COMPLETED', 100, 100, 1)",
                        arrayOf("task-%03d".format(index), "key-$index"),
                    )
                }
                old.execSQL("INSERT INTO background_tasks (taskId, toolId, versionCode, `key`, operation, specJson, periodic, state, createdAt, updatedAt, runAttempt) VALUES ('active', 'tool', 1, 'active', 'NOTIFY', '{}', 0, 'QUEUED', 101, 101, 0)")
                old.execSQL("INSERT INTO task_results (taskId, outcome, completedAt, attemptCount) VALUES ('task-000', 'SUCCESS', 100, 1)")
                old.version = 1
            }

            CoreDataFactory.create(context, name, "settings-$name").use { stores ->
                val tasks = stores.repositories.backgroundTasks
                assertEquals(listOf("active"), tasks.observeActiveTasks("tool").first().map { it.taskId })
                val first = tasks.observeRecentHistory("tool").first()
                assertEquals(50, first.tasks.size)
                assertTrue(first.hasMore)
                assertEquals("task-000", first.tasks.first().taskId)
                assertEquals("task-049", first.tasks.last().taskId)
                val next = tasks.historyBefore("tool", first.tasks.last().createdAt, first.tasks.last().taskId)
                assertEquals(listOf("task-050", "task-051"), next.tasks.map { it.taskId })
                assertFalse(next.hasMore)
                assertEquals(TaskState.QUEUED, tasks.observeActiveTasks("tool").first().single().state)
                assertEquals(53, tasks.observeTasks("tool").first().size)
                assertEquals("task-000", tasks.observeResult("task-000").first()?.taskId)
            }

            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { migrated ->
                assertEquals(2, migrated.version)
                val indexes = mutableSetOf<String>()
                migrated.rawQuery("PRAGMA index_list('background_tasks')", null).use { cursor ->
                    while (cursor.moveToNext()) indexes += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                }
                assertTrue("index_background_tasks_toolId_key" in indexes)
                assertTrue("index_background_tasks_toolId_state" in indexes)
                assertTrue("index_background_tasks_toolId_createdAt_taskId" in indexes)
                assertFalse("index_background_tasks_toolId" in indexes)
                assertFalse("index_background_tasks_state" in indexes)
            }

            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { changed ->
                repeat(51) { index ->
                    changed.execSQL(
                        "INSERT INTO background_tasks (taskId, toolId, versionCode, `key`, operation, specJson, periodic, state, createdAt, updatedAt, runAttempt) VALUES (?, 'tool', 1, ?, 'NOTIFY', '{}', 0, 'COMPLETED', 200, 200, 1)",
                        arrayOf("new-%03d".format(index), "new-key-$index"),
                    )
                }
                changed.execSQL("UPDATE background_tasks SET periodic = 1, state = 'RUNNING' WHERE taskId = 'task-051'")
            }
            CoreDataFactory.create(context, name, "settings-$name").use { stores ->
                val tasks = stores.repositories.backgroundTasks
                val recent = tasks.observeRecentHistory("tool").first()
                assertEquals(50, recent.tasks.size)
                assertEquals("new-000", recent.tasks.first().taskId)
                val loaded = tasks.observeHistoryThrough("tool", 100, "task-051").first()
                assertEquals(102, loaded.size)
                assertEquals("new-050", loaded[50].taskId)
                assertEquals("task-050", loaded.last().taskId)
                assertTrue(tasks.observeActiveTasks("tool").first().any { it.taskId == "task-051" && it.state == TaskState.RUNNING })
            }
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { changed ->
                changed.execSQL("UPDATE background_tasks SET state = 'COMPLETED', updatedAt = 201 WHERE taskId = 'task-051'")
            }
            CoreDataFactory.create(context, name, "settings-$name").use { stores ->
                val loaded = stores.repositories.backgroundTasks.observeHistoryThrough("tool", 100, "task-051").first()
                assertEquals(103, loaded.size)
                assertEquals("task-051", loaded.last().taskId)
                assertEquals(201L, loaded.last().updatedAt)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createVersionOneSchema(db: SQLiteDatabase) {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val schema = JSONObject(testContext.assets.open("room-v1/1.json").bufferedReader().use { it.readText() })
            .getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        repeat(entities.length()) { index ->
            val entity = entities.getJSONObject(index)
            val tableName = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", tableName))
            val indices = entity.getJSONArray("indices")
            repeat(indices.length()) { item ->
                db.execSQL(indices.getJSONObject(item).getString("createSql").replace("${'$'}{TABLE_NAME}", tableName))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        repeat(setup.length()) { index -> db.execSQL(setup.getString(index)) }
    }
}
