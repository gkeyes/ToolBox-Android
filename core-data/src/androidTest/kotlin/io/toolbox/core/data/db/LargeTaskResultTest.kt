package io.toolbox.core.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.TASK_RESULT_PREVIEW_CHARS
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A background HTTP result can be larger than Android's ~2 MB CursorWindow. Reading it back, and
 * the rollback checkpoint taken before a restore, must still work on a real device database.
 */
@RunWith(AndroidJUnit4::class)
class LargeTaskResultTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ToolBoxDatabase::class.java)

    @Test
    fun resultLargerThanACursorWindowReadsBackAndSurvivesACheckpoint() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "large-result-${UUID.randomUUID()}.db"
        val checkpoint = File(context.cacheDir, "$name.checkpoint")
        // Multi-byte characters so the stored UTF-8 is about 9 MB, far past one window.
        val payload = buildString {
            append("{\"body\":\"")
            repeat(PAYLOAD_CHARS) { append(if (it % 3 == 0) '中' else 'a') }
            append("\"}")
        }
        try {
            helper.createDatabase(name, 2).use { db ->
                db.execSQL("INSERT INTO tools (id, name, securityProfile, installedAt) VALUES ('tool', 'Tool', ?, 1)",
                    arrayOf<Any?>(SecurityProfile.STRICT.name))
                db.execSQL("INSERT INTO background_tasks (taskId, toolId, versionCode, `key`, operation, specJson, periodic, state, createdAt, updatedAt, runAttempt) VALUES ('big', 'tool', 1, 'big', 'HTTP_GET', '{}', 0, 'COMPLETED', 100, 100, 1)")
                db.execSQL("INSERT INTO task_results (taskId, outcome, completedAt, payloadJson, attemptCount) VALUES ('big', ?, 100, ?, 1)",
                    arrayOf<Any?>(RunOutcome.SUCCEEDED.name, payload))
            }
            CoreDataFactory.create(context, name, "settings-$name").use { stores ->
                val result = stores.repositories.backgroundTasks.observeResult("big").first()
                assertEquals(RunOutcome.SUCCEEDED, result?.outcome)
                assertEquals(payload, result?.payloadJson)
                // The task list reads only the payload's start, in SQL.
                val preview = stores.repositories.backgroundTasks.observeResultPreview("big").first()
                assertEquals(RunOutcome.SUCCEEDED, preview?.outcome)
                assertEquals(payload.take(TASK_RESULT_PREVIEW_CHARS), preview?.payloadPrefix)

                stores.backup.checkpoint(checkpoint)
                stores.backup.restoreCheckpoint(checkpoint)
                assertEquals(payload, stores.repositories.backgroundTasks.observeResult("big").first()?.payloadJson)
            }
        } finally {
            checkpoint.delete()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun checkpointKeepsTextCellsThatHoldANul() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "nul-cell-${UUID.randomUUID()}.db"
        val checkpoint = File(context.cacheDir, "$name.checkpoint")
        // SQLite's length()/substr() on TEXT stop at a raw U+0000; the checkpoint must not.
        val toolName = "To\u0000ol"
        // Past one checkpoint slice, with multi-byte characters across slice ends and a NUL near the start.
        val value = buildString {
            append("a\u0000b")
            repeat(NUL_VALUE_CHARS) { append(if (it % 3 == 0) '中' else 'a') }
            append("\u0000tail")
        }
        try {
            helper.createDatabase(name, 2).use { db ->
                db.execSQL("INSERT INTO tools (id, name, securityProfile, installedAt) VALUES ('tool', ?, ?, 1)",
                    arrayOf<Any?>(toolName, SecurityProfile.STRICT.name))
                db.execSQL("INSERT INTO tool_kv (toolId, `key`, valueJson, updatedAt, bytes) VALUES ('tool', 'k', ?, 1, 1)",
                    arrayOf<Any?>(value))
            }
            CoreDataFactory.create(context, name, "settings-$name").use { stores ->
                stores.backup.checkpoint(checkpoint)
                stores.backup.restoreCheckpoint(checkpoint)
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                fun bytes(sql: String) = db.rawQuery(sql, null).use { assertTrue(it.moveToFirst()); it.getBlob(0) }
                assertArrayEquals(toolName.toByteArray(Charsets.UTF_8), bytes("SELECT CAST(name AS BLOB) FROM tools WHERE id = 'tool'"))
                assertArrayEquals(value.toByteArray(Charsets.UTF_8), bytes("SELECT CAST(valueJson AS BLOB) FROM tool_kv WHERE toolId = 'tool'"))
            }
        } finally {
            checkpoint.delete()
            context.deleteDatabase(name)
        }
    }

    private companion object {
        const val PAYLOAD_CHARS = 4_500_000
        // About 1 MB of UTF-8: several checkpoint slices, still one CursorWindow for the check above.
        const val NUL_VALUE_CHARS = 600_000
    }
}
