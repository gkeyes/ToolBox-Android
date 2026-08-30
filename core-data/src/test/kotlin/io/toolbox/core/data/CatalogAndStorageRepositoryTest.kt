package io.toolbox.core.data

import io.toolbox.core.data.memory.InMemoryCoreData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogAndStorageRepositoryTest {
    @Test
    fun failedCommitLeavesCatalogAndTransactionUnchanged() = runTest {
        val repositories = InMemoryCoreData.createForTest(
            commitHook = CatalogCommitHook { error("injected") },
        ).repositories
        val attempt = attempt(transactionId = "tx-failed", versionCode = 1)
        assertEquals(DataResult.Success(Unit), repositories.installs.begin(transaction(attempt)))

        assertEquals(
            DataResult.Failure.StorageFailure("commitInstall"),
            repositories.lifecycle.commitInstall(attempt),
        )
        assertNull(repositories.catalog.observeTool(TOOL_ID).first())
        assertEquals(
            InstallTransactionState.PREPARING,
            (repositories.installs.get("tx-failed") as DataResult.Success).value!!.state,
        )
    }

    @Test
    fun higherVersionReplacementClearsVersionScopedStateAndUninstallClearsToolState() = runTest {
        val repositories = InMemoryCoreData.create()
        val first = attempt(transactionId = "tx-1", versionCode = 1)

        assertEquals(DataResult.Success(Unit), repositories.installs.begin(transaction(first)))
        assertEquals(DataResult.Success(CommitInstallOutcome.Committed), repositories.lifecycle.commitInstall(first))
        assertEquals(
            DataResult.Success(CommittedInstall(TOOL_ID, 1)),
            repositories.lifecycle.findCommittedInstall("tx-1"),
        )
        assertEquals(DataResult.Success(Unit), repositories.keyValues.put(TOOL_ID, "draft", "saved", 10))
        assertEquals(
            DataResult.Success(Unit),
            repositories.grants.put(PermissionGrant(TOOL_ID, "network", true, 11)),
        )
        val task = BackgroundTask(
            taskId = "task-1",
            toolId = TOOL_ID,
            versionCode = 1,
            key = "refresh",
            operation = BackgroundOperation.HTTP_GET,
            specJson = "{}",
            periodic = false,
            intervalMinutes = null,
            state = TaskState.QUEUED,
            createdAt = 12,
            updatedAt = 12,
            nextRunAt = null,
            runAttempt = 0,
        )
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.create(task))
        assertEquals(DataResult.Success(Unit), repositories.backgroundTasks.markRunning(task.taskId, 13, 1))
        val taskResult = TaskRunResult(task.taskId, RunOutcome.SUCCEEDED, 14, "{}", null, 1)
        assertEquals(
            DataResult.Success(Unit),
            repositories.backgroundTasks.finishRun(task.taskId, taskResult, TaskState.COMPLETED, null),
        )

        val update = attempt(transactionId = "tx-2", versionCode = 2, storageGranted = false)
        assertEquals(DataResult.Success(Unit), repositories.installs.begin(transaction(update)))
        assertEquals(DataResult.Success(CommitInstallOutcome.Committed), repositories.lifecycle.commitInstall(update))
        assertEquals(2, repositories.catalog.observeTool(TOOL_ID).first()!!.currentVersion.versionCode)
        assertEquals("saved", repositories.keyValues.observe(TOOL_ID, "draft").first()!!.valueJson)
        assertEquals(
            listOf(PermissionGrant(TOOL_ID, "storage", false, 2)),
            repositories.grants.observeGrants(TOOL_ID).first(),
        )
        assertEquals(emptyList<BackgroundTask>(), repositories.backgroundTasks.observeTasks(TOOL_ID).first())
        assertNull(repositories.backgroundTasks.observeResult(task.taskId).first())

        val stale = attempt(transactionId = "tx-stale", versionCode = 1)
        assertEquals(DataResult.Success(Unit), repositories.installs.begin(transaction(stale)))
        assertEquals(
            DataResult.Failure.NonMonotonicVersion(TOOL_ID, 1, 2),
            repositories.lifecycle.commitInstall(stale),
        )
        assertEquals(2, repositories.catalog.observeTool(TOOL_ID).first()!!.currentVersion.versionCode)

        assertEquals(
            DataResult.Success(DeleteToolCatalogOutcome.Deleted),
            repositories.lifecycle.deleteToolCatalog(TOOL_ID),
        )
        assertNull(repositories.catalog.observeTool(TOOL_ID).first())
        assertEquals(DataResult.Success(null), repositories.installs.get("tx-2"))
        assertNull(repositories.keyValues.observe(TOOL_ID, "draft").first())
        assertEquals(emptyList<PermissionGrant>(), repositories.grants.observeGrants(TOOL_ID).first())
        assertEquals(emptyList<BackgroundTask>(), repositories.backgroundTasks.observeTasks(TOOL_ID).first())
        assertNull(repositories.backgroundTasks.observeResult(task.taskId).first())
    }

    private fun attempt(
        transactionId: String,
        versionCode: Int,
        storageGranted: Boolean = true,
    ) = CatalogInstallAttempt(
        transactionId = transactionId,
        metadata = ToolMetadata(TOOL_ID, "Test tool", SecurityProfile.STRICT, 1),
        version = ToolVersion(
            toolId = TOOL_ID,
            versionCode = versionCode,
            version = "$versionCode.0.0",
            bundleLocator = BundleLocator("tools/test/current"),
            bundleBytes = 128,
            integrityHash = "sha256:$versionCode",
            installedAt = versionCode.toLong(),
        ),
        initialGrants = listOf(PermissionGrant(TOOL_ID, "storage", storageGranted, versionCode.toLong())),
    )

    private fun transaction(attempt: CatalogInstallAttempt) = InstallTransaction(
        id = attempt.transactionId,
        toolId = attempt.metadata.id,
        versionCode = attempt.version.versionCode,
        state = InstallTransactionState.PREPARING,
        startedAt = attempt.version.installedAt,
        updatedAt = attempt.version.installedAt,
    )

    private companion object {
        const val TOOL_ID = "io.toolbox.test"
    }
}
