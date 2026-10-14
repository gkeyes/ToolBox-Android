package io.toolbox.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.SecurityProfile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

                stores.backup.checkpoint(checkpoint)
                stores.backup.restoreCheckpoint(checkpoint)
                assertEquals(payload, stores.repositories.backgroundTasks.observeResult("big").first()?.payloadJson)
            }
        } finally {
            checkpoint.delete()
            context.deleteDatabase(name)
        }
    }

    private companion object {
        const val PAYLOAD_CHARS = 4_500_000
    }
}
