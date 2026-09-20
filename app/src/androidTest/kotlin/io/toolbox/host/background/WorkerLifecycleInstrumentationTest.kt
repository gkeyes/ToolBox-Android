package io.toolbox.host.background

import android.annotation.SuppressLint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import io.toolbox.core.data.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the actual CoroutineWorker lifecycle with Room, controllable authorization and HTTP. */
@RunWith(AndroidJUnit4::class)
class WorkerLifecycleInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun stopDuringAuthorizationReleasesClaimAndTheSameProcessCanRetry() = runBlocking(Dispatchers.IO) {
        withHarness(BackgroundOperation.NOTIFY) { stores, task ->
            val blocked = AtomicBoolean(true)
            val entered = CompletableDeferred<Job>()
            val notifications = RecordingNotifications()
            val authorization = BackgroundAuthorization { _, _ ->
                entered.complete(currentCoroutineContext().job)
                if (blocked.get()) awaitCancellation()
                allowedPolicy()
            }
            ToolBoxBackgroundRuntime.install(dependencies(stores, authorization, notifications))
            val worker = worker(task.taskId)
            val future = worker.startWork()
            val owner = withTimeout(10_000) { entered.await() }
            val running = current(stores, task.taskId)
            assertEquals(TaskState.RUNNING, running.state)
            assertNotNull(running.executionToken)
            stop(worker, future)
            withTimeout(10_000) { owner.join() }
            assertFalse(BackgroundExecutionLimiter.isActive(task.taskId, running.executionToken))
            assertQueuedWithoutToken(stores, task.taskId)
            blocked.set(false)
            assertEquals(ListenableWorker.Result.success(), worker(task.taskId).startWork().get(10, TimeUnit.SECONDS))
            assertEquals(1, notifications.posts.get())
            assertEquals(TaskState.COMPLETED, current(stores, task.taskId).state)
        }
    }

    @Test
    fun coordinatorCancellationRemainsFinalWhenInterruptedWorkerCleansUp() = runBlocking(Dispatchers.IO) {
        withHarness(BackgroundOperation.NOTIFY) { stores, task ->
            val entered = CompletableDeferred<Job>()
            val notifications = RecordingNotifications()
            val authorization = BackgroundAuthorization { _, _ ->
                entered.complete(currentCoroutineContext().job)
                awaitCancellation()
            }
            ToolBoxBackgroundRuntime.install(dependencies(stores, authorization, notifications))
            val future = worker(task.taskId).startWork()
            val owner = withTimeout(10_000) { entered.await() }
            val token = current(stores, task.taskId).executionToken
            try {
                val coordinator = BackgroundTaskCoordinator(WorkManager.getInstance(context), stores.repositories,
                    authorization, notifications)
                val cancelled = coordinator.cancel(TOOL, task.taskId)
                assertTrue(cancelled == BackgroundCancellationResult.Cancelled ||
                    cancelled == BackgroundCancellationResult.AlreadyFinished(wasCancelled = true))
                withTimeout(10_000) { owner.join() }
                assertEquals(TaskState.CANCELLED, current(stores, task.taskId).state)
                assertNull(current(stores, task.taskId).executionToken)
                assertFalse(BackgroundExecutionLimiter.isActive(task.taskId, token))
                assertEquals(RunOutcome.CANCELLED, stores.repositories.backgroundTasks.observeResult(task.taskId).first()?.outcome)
                // A later scheduler delivery must not resurrect the administrative cancellation.
                assertEquals(ListenableWorker.Result.success(), worker(task.taskId).startWork().get(10, TimeUnit.SECONDS))
                assertEquals(0, notifications.posts.get())
            } finally { future.cancel(true) }
        }
    }

    @Test
    fun systemStopDuringHttpRequeuesPeriodicWorkAndReleasesNetworkResources() = runBlocking(Dispatchers.IO) {
        withHarness(BackgroundOperation.HTTP_GET, periodic = true) { stores, task ->
            val owner = CompletableDeferred<Job>()
            val httpEntered = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val resources = NetworkResources(availableHeap = { 128L * 1024 * 1024 }, infrastructureAvailable = { true })
            val transport = ToolNetworkTransport { request, _ ->
                if (calls.incrementAndGet() == 1) {
                    httpEntered.complete(Unit)
                    awaitCancellation()
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("ready".toResponseBody("text/plain".toMediaType())).build()
            }
            val authorization = BackgroundAuthorization { _, _ ->
                owner.complete(currentCoroutineContext().job)
                allowedPolicy()
            }
            val proxy = ToolNetworkProxy(transport = transport, resources = resources)
            ToolBoxBackgroundRuntime.install(dependencies(stores, authorization, RecordingNotifications(), proxy))
            val worker = worker(task.taskId)
            val future = worker.startWork()
            withTimeout(10_000) { httpEntered.await() }
            val running = current(stores, task.taskId)
            stop(worker, future)
            withTimeout(10_000) { owner.await().join() }
            assertQueuedWithoutToken(stores, task.taskId)
            assertFalse(BackgroundExecutionLimiter.isActive(task.taskId, running.executionToken))
            assertEquals(0L, resources.reservedBytes)
            assertEquals(ListenableWorker.Result.success(), worker(task.taskId).startWork().get(10, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertQueuedWithoutToken(stores, task.taskId)
            assertEquals(RunOutcome.SUCCEEDED, stores.repositories.backgroundTasks.observeResult(task.taskId).first()?.outcome)
            assertEquals(0L, resources.reservedBytes)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun stop(worker: ToolBoxBackgroundWorker, future: java.util.concurrent.Future<*>) {
        // WorkerWrapper signals the reason and cancels the startWork future when constraints stop it.
        worker.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        future.cancel(true)
    }

    private fun worker(taskId: String) = TestListenableWorkerBuilder<ToolBoxBackgroundWorker>(context,
        inputData = Data.Builder().putString(ToolBoxBackgroundWorker.KEY_TASK_ID, taskId).build(), runAttemptCount = 0).build()

    private suspend fun current(stores: CoreDataStores, taskId: String) =
        (stores.repositories.backgroundTasks.getTask(taskId) as DataResult.Success).value!!

    private suspend fun assertQueuedWithoutToken(stores: CoreDataStores, taskId: String) {
        val current = current(stores, taskId)
        assertEquals(TaskState.QUEUED, current.state)
        assertNull(current.executionToken)
    }

    private fun dependencies(stores: CoreDataStores, authorization: BackgroundAuthorization,
        notifications: RecordingNotifications, proxy: ToolNetworkProxy = ToolNetworkProxy()) =
        BackgroundWorkerDependencies(stores.repositories, authorization, proxy, notifications)

    private fun allowedPolicy() = BackgroundExecutionPolicy(TOOL, 1,
        backgroundEnabled = true, backgroundDeclared = true, backgroundGranted = true,
        networkDeclared = true, networkGranted = true, notificationsDeclared = true,
        notificationsGranted = true, notificationSystemPermissionGranted = true,
        networkTimeoutMillis = 0, maxNetworkResponseBytes = null)

    private suspend fun withHarness(operation: BackgroundOperation, periodic: Boolean = false,
        action: suspend (CoreDataStores, BackgroundTask) -> Unit) {
        val name = "worker-lifecycle-${UUID.randomUUID()}"
        val oldDependencies = ToolBoxBackgroundRuntime.current()
        val stores = CoreDataFactory.create(context, "$name.db", name)
        try {
            val tx = UUID.randomUUID().toString()
            success(stores.repositories.installs.begin(InstallTransaction(tx, TOOL, 1, InstallTransactionState.PREPARING, 1, 1)))
            success(stores.repositories.installs.markCommitting(tx, 1))
            success(stores.repositories.lifecycle.commitInstall(CatalogInstallAttempt(tx,
                ToolMetadata(TOOL, "Worker lifecycle", SecurityProfile.STRICT, 1),
                ToolVersion(TOOL, 1, "1.0.0", BundleLocator("miniapps/$TOOL/versions/1/bundle"), 0, "a".repeat(64), 1), emptyList())))
            val id = UUID.randomUUID().toString()
            val spec = if (operation == BackgroundOperation.NOTIFY) """{"title":"Test","body":""}"""
                else """{"url":"https://fixture.invalid/body"}"""
            val task = BackgroundTask(id, TOOL, 1, id, operation, spec, periodic, if (periodic) 15 else null,
                TaskState.QUEUED, 1, 1, 1, 0)
            success(stores.repositories.backgroundTasks.create(task))
            try { action(stores, task) } finally {
                BackgroundExecutionLimiter.cancelExecution(task.taskId)
                withTimeout(10_000) {
                    while (BackgroundExecutionLimiter.isActive(task.taskId, current(stores, task.taskId).executionToken)) delay(10)
                }
            }
        } finally {
            if (oldDependencies == null) ToolBoxBackgroundRuntime.clear() else ToolBoxBackgroundRuntime.install(oldDependencies)
            stores.close()
            context.deleteDatabase("$name.db")
            File(context.filesDir, "datastore/$name.preferences_pb").delete()
        }
    }

    private class RecordingNotifications : BackgroundNotificationGateway {
        val posts = AtomicInteger()
        override suspend fun post(toolId: String, notificationId: String, title: String, body: String): NotificationResult {
            posts.incrementAndGet()
            return NotificationResult.Posted
        }
        override suspend fun cancel(toolId: String, notificationId: String) = Unit
        override suspend fun cancelTool(toolId: String) = Unit
    }
    private fun success(result: DataResult<*>) { assertTrue(result.toString(), result is DataResult.Success) }
    companion object { private const val TOOL = "io.example.workerlifecycle" }
}
