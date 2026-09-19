package io.toolbox.host.background

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundExecutionIdentityTest {
    @Test fun executionIdentitySurvivesReopenAndRejectsLatePeriodResults() = runBlocking {
        withContext(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "execution-${UUID.randomUUID()}"
            var stores = CoreDataFactory.create(context, "$name.db", name)
            try {
                install(stores, 1)
                val task = task("periodic")
                val repo = stores.repositories.backgroundTasks
                success(repo.create(task))
                success(repo.claimExecution(task.taskId, 1, null, "process-a:run-1", 2, 1))
                assertTrue(stores.repositories.keyValues.keys(TOOL).isEmpty())
                stores.close()
                stores = CoreDataFactory.create(context, "$name.db", name)
                val reopened = stores.repositories.backgroundTasks
                val running = (reopened.getTask(task.taskId) as DataResult.Success).value!!
                assertEquals("process-a:run-1", running.executionToken)
                assertFalse(running.specJson.contains("process-a"))
                // A recovered process replaces exactly the persisted generation it observed.
                success(reopened.claimExecution(task.taskId, 1, running.executionToken, "process-b:run-2", 3, 2))
                assertTrue(reopened.claimExecution(task.taskId, 1, running.executionToken, "stale-claim", 3, 2) is DataResult.Failure.InvalidState)
                assertTrue(reopened.finishRun(task.taskId, result(task.taskId, 4), TaskState.QUEUED, 900_004, "process-a:run-1") is DataResult.Failure.InvalidState)
                success(reopened.finishRun(task.taskId, result(task.taskId, 4), TaskState.QUEUED, 900_004, "process-b:run-2"))
                success(reopened.claimExecution(task.taskId, 1, null, "process-b:run-3", 900_004, 1))
                assertTrue(reopened.deferRetry(task.taskId, 900_005, 900_010, 1, "process-b:run-2") is DataResult.Failure.InvalidState)
                assertTrue(reopened.finishCancelled(task.taskId, result(task.taskId, 900_005, RunOutcome.CANCELLED), "process-b:run-2") is DataResult.Failure.InvalidState)
                assertEquals("process-b:run-3", (reopened.getTask(task.taskId) as DataResult.Success).value!!.executionToken)
                // Administrative cancellation invalidates the active execution before a late success.
                success(reopened.finishCancelled(task.taskId, result(task.taskId, 900_006, RunOutcome.CANCELLED)))
                assertTrue(reopened.finishRun(task.taskId, result(task.taskId, 900_007), TaskState.QUEUED, 1_800_007, "process-b:run-3") is DataResult.Failure.InvalidState)
                assertEquals(RunOutcome.CANCELLED, reopened.observeResult(task.taskId).first()!!.outcome)
            } finally { stores.close(); context.deleteDatabase("$name.db") }
        }
    }

    @Test fun versionReplacementAndArchivedRestoreCannotReuseExecution() = runBlocking {
        withContext(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "execution-${UUID.randomUUID()}"
            val stores = CoreDataFactory.create(context, "$name.db", name)
            try {
                install(stores, 1)
                val repo = stores.repositories.backgroundTasks
                val task = task("old-version")
                success(repo.create(task))
                success(repo.claimExecution(task.taskId, 1, null, "run", 2, 1))
                install(stores, 2)
                assertTrue(repo.finishRun(task.taskId, result(task.taskId, 3), TaskState.QUEUED, 900_003, "run") is DataResult.Failure)
                val archive = task("archive").copy(versionCode = 2, state = TaskState.RUNNING,
                    specJson = "{\"title\":\"test\",\"body\":\"\",\"_toolboxExecutionToken\":\"injected\"}", executionToken = "injected")
                stores.backup.restoreTaskHistory(archive, null)
                val restored = (repo.getTask(archive.taskId) as DataResult.Success).value!!
                assertNull(restored.executionToken)
                assertEquals(TaskState.CANCELLED, restored.state)
                assertFalse(restored.specJson.contains("injected"))
            } finally { stores.close(); context.deleteDatabase("$name.db") }
        }
    }

    private suspend fun install(stores: CoreDataStores, version: Int) {
        val tx = UUID.randomUUID().toString()
        success(stores.repositories.installs.begin(InstallTransaction(tx, TOOL, version, InstallTransactionState.PREPARING, 1, 1)))
        success(stores.repositories.installs.markCommitting(tx, 1))
        success(stores.repositories.lifecycle.commitInstall(CatalogInstallAttempt(tx,
            ToolMetadata(TOOL, "Test", SecurityProfile.STRICT, 1),
            ToolVersion(TOOL, version, "1.0.$version", BundleLocator("miniapps/$TOOL/versions/$version/bundle"), 0, "a".repeat(64), 1), emptyList())))
    }
    private fun task(id: String) = BackgroundTask(id, TOOL, 1, id, BackgroundOperation.NOTIFY,
        "{\"title\":\"test\",\"body\":\"\"}", true, 15, TaskState.QUEUED, 1, 1, 1, 0)
    private fun result(id: String, at: Long, outcome: RunOutcome = RunOutcome.SUCCEEDED) =
        TaskRunResult(id, outcome, at, null, if (outcome == RunOutcome.CANCELLED) "CANCELLED" else null, 1)
    private fun success(value: DataResult<*>) { assertTrue(value.toString(), value is DataResult.Success) }
    companion object { private const val TOOL = "io.example.execution" }
}
