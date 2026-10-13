package io.toolbox.core.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates every Room migration against the exported schemas in core-data/schemas. Room compares
 * the migrated tables, columns, indices and foreign keys with the exported JSON of the target
 * version, so a migration that forgets a schema change fails here instead of on a user's device.
 */
@RunWith(AndroidJUnit4::class)
class ToolBoxMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ToolBoxDatabase::class.java,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun everyMigrationMatchesTheExportedSchemaOfItsTargetVersion() {
        for (migration in ToolBoxMigrations.ALL) {
            context.deleteDatabase(NAME)
            helper.createDatabase(NAME, migration.startVersion).close()
            helper.runMigrationsAndValidate(NAME, migration.endVersion, true, migration).close()
        }
    }

    @Test
    fun oldestSchemaMigratesToTheCurrentDatabaseAndRoomOpensIt() {
        val oldest = ToolBoxMigrations.ALL.minOf { it.startVersion }
        helper.createDatabase(NAME, oldest).close()
        val database = Room.databaseBuilder(context, ToolBoxDatabase::class.java, NAME)
            .addMigrations(*ToolBoxMigrations.ALL.toTypedArray())
            .build()
        try {
            // Opening runs the migrations and Room's identity-hash check for the current version.
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    @Test
    fun oneToTwoKeepsEveryRowAndReplacesBackgroundTaskIndexes() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO tools (id, name, securityProfile, installedAt, lastOpenedAt, pinnedOrder, categoryId) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("tool", "Tool", "STRICT", 10L, 20L, 3, "work"),
            )
            db.execSQL(
                "INSERT INTO tool_versions (toolId, versionCode, version, bundleLocator, bundleBytes, integrityHash, installedAt) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("tool", 7, "1.2.3", "tools/tool/7", 4096L, "sha256-abc", 11L),
            )
            db.execSQL(
                "INSERT INTO permission_grants (toolId, capability, granted, updatedAt) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>("tool", "network", 1, 12L),
            )
            db.execSQL(
                "INSERT INTO tool_kv (toolId, `key`, valueJson, updatedAt, bytes) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("tool", "k", "\"值\"", 13L, 5),
            )
            db.execSQL(
                "INSERT INTO install_transactions (id, toolId, versionCode, state, startedAt, updatedAt, failureCode) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("tx", "tool", 7, "COMPLETED", 14L, 15L, null),
            )
            db.execSQL(
                "INSERT INTO background_tasks (taskId, toolId, versionCode, `key`, operation, specJson, periodic, intervalMinutes, state, createdAt, updatedAt, nextRunAt, runAttempt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("task", "tool", 7, "daily", "NOTIFY", "{}", 1, 15L, "QUEUED", 16L, 17L, 18L, 2),
            )
            db.execSQL(
                "INSERT INTO task_results (taskId, outcome, completedAt, payloadJson, errorCode, attemptCount) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("task", "SUCCEEDED", 19L, "{\"ok\":true}", null, 2),
            )
        }

        helper.runMigrationsAndValidate(NAME, 2, true, ToolBoxMigrations.ONE_TO_TWO).use { db ->
            assertEquals(listOf("tool", "Tool", "STRICT", "10", "20", "3", "work"), row(db, "SELECT * FROM tools"))
            assertEquals(listOf("tool", "7", "1.2.3", "tools/tool/7", "4096", "sha256-abc", "11"), row(db, "SELECT * FROM tool_versions"))
            assertEquals(listOf("tool", "network", "1", "12"), row(db, "SELECT * FROM permission_grants"))
            assertEquals(listOf("tool", "k", "\"值\"", "13", "5"), row(db, "SELECT * FROM tool_kv"))
            assertEquals(listOf("tx", "tool", "7", "COMPLETED", "14", "15", null), row(db, "SELECT * FROM install_transactions"))
            assertEquals(
                listOf("task", "tool", "7", "daily", "NOTIFY", "{}", "1", "15", "QUEUED", "16", "17", "18", "2"),
                row(db, "SELECT * FROM background_tasks"),
            )
            assertEquals(listOf("task", "SUCCEEDED", "19", "{\"ok\":true}", null, "2"), row(db, "SELECT * FROM task_results"))

            val indexes = indexNames(db, "background_tasks")
            assertTrue("index_background_tasks_toolId_key" in indexes)
            assertTrue("index_background_tasks_toolId_state" in indexes)
            assertTrue("index_background_tasks_toolId_createdAt_taskId" in indexes)
            assertFalse("index_background_tasks_toolId" in indexes)
            assertFalse("index_background_tasks_state" in indexes)
        }
    }

    private fun row(db: SupportSQLiteDatabase, sql: String): List<String?> = db.query(sql).use { cursor ->
        assertTrue("expected one row for: $sql", cursor.moveToFirst())
        val values = (0 until cursor.columnCount).map { index ->
            if (cursor.isNull(index)) null else cursor.getString(index)
        }
        assertFalse("expected exactly one row for: $sql", cursor.moveToNext())
        values
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }

    private companion object {
        const val NAME = "toolbox-migration-test.db"
    }
}
