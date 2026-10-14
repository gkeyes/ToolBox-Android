package io.toolbox.host.background

import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.BackgroundOperation
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.BackgroundTaskHistoryPage
import io.toolbox.core.data.RunOutcome
import io.toolbox.core.data.TASK_RESULT_PREVIEW_CHARS
import io.toolbox.core.data.TaskRunResult
import io.toolbox.core.data.TaskRunResultPreview
import io.toolbox.core.data.TaskState
import io.toolbox.host.HostBackgroundOperations
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundTasksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BackgroundTasksViewModel>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun readyOnlyAfterActiveAndHistoryBothLoadAndSessionsAreFilteredToTheTool() = runTest(dispatcher) {
        val operations = FakeBackgroundOperations()
        val sessions = MutableStateFlow(listOf(session("s1", TOOL), session("s2", "other")))
        val model = model(operations, sessions)
        assertFalse(model.state.value.loaded)
        assertEquals(listOf("s1"), model.state.value.page.runtimeSessions.map { it.sessionId })

        val collector = launch { model.state.collect {} }
        runCurrent()
        assertFalse(model.state.value.loaded)

        operations.active.value = listOf(task("a", 50, TaskState.RUNNING))
        runCurrent()
        assertFalse(model.state.value.loaded)
        assertEquals(listOf("a"), model.state.value.page.tasks.map { it.taskId })

        operations.recent.value = BackgroundTaskHistoryPage(listOf(task("a", 50, TaskState.RUNNING), task("h1", 40)), hasMore = false)
        runCurrent()
        assertTrue(model.state.value.loaded)
        assertEquals(listOf("a", "h1"), model.state.value.page.tasks.map { it.taskId })
        assertFalse(model.state.value.canLoadMoreHistory)
        collector.cancel()
    }

    @Test fun loadMoreFollowsTheLiveBoundaryQueryAndReportsFailures() = runTest(dispatcher) {
        val operations = FakeBackgroundOperations()
        operations.active.value = emptyList()
        operations.recent.value = BackgroundTaskHistoryPage(listOf(task("h1", 40), task("h2", 30)), hasMore = true)
        val model = model(operations)
        val collector = launch { model.state.collect {} }
        runCurrent()
        assertTrue(model.state.value.canLoadMoreHistory)

        val page = CompletableDeferred<BackgroundTaskHistoryPage>()
        operations.nextPage = { page.await() }
        model.loadMoreHistory()
        model.loadMoreHistory()
        runCurrent()
        assertTrue(model.state.value.loadingHistory)
        assertEquals(listOf(30L to "h2"), operations.historyRequests)

        page.complete(BackgroundTaskHistoryPage(listOf(task("h3", 20)), hasMore = true))
        runCurrent()
        assertFalse(model.state.value.loadingHistory)
        assertEquals(listOf(20L to "h3"), operations.throughRequests)
        // The live query through the boundary replaces the stitched pages once it emits.
        operations.through.value = listOf(task("h1", 40), task("h3", 20))
        runCurrent()
        assertEquals(listOf("h1", "h3"), model.state.value.page.tasks.map { it.taskId })
        assertTrue(model.state.value.canLoadMoreHistory)

        operations.nextPage = { error("injected history failure") }
        model.loadMoreHistory()
        runCurrent()
        assertEquals(listOf(30L to "h2", 20L to "h3"), operations.historyRequests)
        assertEquals("读取历史任务失败，请重试。", model.state.value.historyError)
        assertFalse(model.state.value.loadingHistory)
        collector.cancel()
    }

    @Test fun lastStateIsKeptWhileNotCollected() = runTest(dispatcher) {
        val operations = FakeBackgroundOperations()
        operations.active.value = listOf(task("a", 50, TaskState.RUNNING))
        operations.recent.value = BackgroundTaskHistoryPage(emptyList(), hasMore = false)
        val model = model(operations)
        val first = launch { model.state.collect {} }
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(0, operations.activeSubscribers)
        assertTrue(model.state.value.loaded)
        assertEquals(listOf("a"), model.state.value.page.tasks.map { it.taskId })
        assertNull(model.state.value.historyError)
    }

    @Test fun resultRowReadsOnlyThePreviewAndCollapsesItsWhitespace() = runTest(dispatcher) {
        val operations = FakeBackgroundOperations()
        operations.preview = TaskRunResultPreview("a", RunOutcome.FAILED, 5, "{\n  \"body\":\t\"x\"\n}\n", "E_NET", 1)
        val summary = model(operations).resultSummary("a").first()
        assertEquals(TaskResultSummaryUi(RunOutcome.FAILED, "E_NET", "{ \"body\": \"x\" }"), summary)
        assertEquals(listOf("a"), operations.previewRequests)
        assertEquals(0, operations.fullResultReads)

        operations.preview = TaskRunResultPreview("b", RunOutcome.SUCCEEDED, 5, " \n\t ", null, 1)
        assertNull(model(operations).resultSummary("b").first()?.preview)
        operations.preview = null
        assertNull(model(operations).resultSummary("c").first())
    }

    @Test fun previewTextIsBoundedWhateverItIsGiven() {
        val long = "word \n".repeat(TASK_RESULT_PREVIEW_CHARS)
        val text = resultPreviewText(long)!!
        assertEquals(RESULT_PREVIEW_CHARS, text.length)
        assertFalse(text.contains('\n'))
        assertTrue(text.startsWith("word word"))
        assertNull(resultPreviewText(""))
    }

    private fun model(
        operations: FakeBackgroundOperations,
        sessions: MutableStateFlow<List<RuntimeBackgroundSessionUi>> = MutableStateFlow(emptyList()),
    ) = BackgroundTasksViewModel(TOOL, operations, sessions) { true }.also(models::add)

    private fun session(id: String, toolId: String) = RuntimeBackgroundSessionUi(sessionId = id, toolId = toolId, toolName = toolId, startedAt = 1L, notificationId = 1)

    private class FakeBackgroundOperations : HostBackgroundOperations {
        val active = MutableStateFlow<List<BackgroundTask>?>(null)
        val recent = MutableStateFlow<BackgroundTaskHistoryPage?>(null)
        val through = MutableStateFlow<List<BackgroundTask>?>(null)
        val historyRequests = mutableListOf<Pair<Long, String>>()
        val throughRequests = mutableListOf<Pair<Long, String>>()
        var nextPage: suspend () -> BackgroundTaskHistoryPage = { BackgroundTaskHistoryPage(emptyList(), false) }
        var activeSubscribers = 0

        override fun observeActiveTasks(toolId: String): Flow<List<BackgroundTask>> = kotlinx.coroutines.flow.flow {
            activeSubscribers++
            try { active.filterNotNull().collect { emit(it) } } finally { activeSubscribers-- }
        }
        override fun observeRecentHistory(toolId: String): Flow<BackgroundTaskHistoryPage> = recent.filterNotNull()
        override fun observeHistoryThrough(toolId: String, createdAt: Long, taskId: String): Flow<List<BackgroundTask>> {
            throughRequests += createdAt to taskId
            return through.filterNotNull()
        }
        override suspend fun historyBefore(toolId: String, createdAt: Long, taskId: String): BackgroundTaskHistoryPage {
            historyRequests += createdAt to taskId
            return nextPage()
        }
        var preview: TaskRunResultPreview? = null
        val previewRequests = mutableListOf<String>()
        var fullResultReads = 0

        override fun observeResult(taskId: String): Flow<TaskRunResult?> {
            fullResultReads++
            return flowOf(null)
        }
        override fun observeResultPreview(taskId: String): Flow<TaskRunResultPreview?> {
            previewRequests += taskId
            return flowOf(preview)
        }
        override suspend fun cancel(toolId: String, taskId: String): BackgroundCancellationResult = BackgroundCancellationResult.Cancelled
        override suspend fun cancelTool(toolId: String) = Unit
        override suspend fun cancelAll(toolIds: Collection<String>) = Unit
    }

    private companion object {
        const val TOOL = "tool"

        fun task(id: String, createdAt: Long, state: TaskState = TaskState.COMPLETED) = BackgroundTask(
            taskId = id, toolId = TOOL, versionCode = 1, key = id, operation = BackgroundOperation.NOTIFY,
            specJson = "{}", periodic = false, intervalMinutes = null, state = state,
            createdAt = createdAt, updatedAt = createdAt, nextRunAt = null, runAttempt = 1,
        )
    }
}
