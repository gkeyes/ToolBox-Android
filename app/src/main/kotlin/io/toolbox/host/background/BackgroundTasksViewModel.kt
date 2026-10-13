package io.toolbox.host.background

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.toolbox.core.data.BackgroundTask
import io.toolbox.core.data.BackgroundTaskHistoryPage
import io.toolbox.core.data.TaskRunResult
import io.toolbox.host.HostBackgroundOperations
import io.toolbox.host.runtime.RuntimeBackgroundSessionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class BackgroundTasksUiState(
    val page: BackgroundTasksPageModel = BackgroundTasksPageModel(emptyList(), emptyList()),
    /** Both the active list and the first history page have been read at least once. */
    val loaded: Boolean = false,
    val canLoadMoreHistory: Boolean = false,
    val loadingHistory: Boolean = false,
    val historyError: String? = null,
)

/**
 * Owns the background task list of one tool: the live active/history queries, the
 * keyset pagination of older history and the cancel/stop actions.
 */
internal class BackgroundTasksViewModel(
    private val toolId: String,
    private val operations: HostBackgroundOperations,
    private val sessions: StateFlow<List<RuntimeBackgroundSessionUi>>,
    stopSession: suspend (String) -> Boolean,
) : ViewModel() {
    private data class HistoryBoundary(val createdAt: Long, val taskId: String)
    private data class LoadedHistory(val boundary: HistoryBoundary, val tasks: List<BackgroundTask>)
    private data class Snapshot(
        val active: List<BackgroundTask> = emptyList(),
        val activeLoaded: Boolean = false,
        val recentHistory: BackgroundTaskHistoryPage = BackgroundTaskHistoryPage(emptyList(), false),
        val historyLoaded: Boolean = false,
        val olderHistory: List<BackgroundTask> = emptyList(),
        val loadedBoundary: HistoryBoundary? = null,
        val moreBeyondBoundary: Boolean = false,
        val loadedHistory: LoadedHistory? = null,
        val loadingHistory: Boolean = false,
        val historyError: String? = null,
    ) {
        val hasMoreHistory: Boolean get() = if (loadedBoundary == null) recentHistory.hasMore else moreBeyondBoundary
    }

    private val snapshot = MutableStateFlow(Snapshot())

    val actions = BackgroundTaskActions(viewModelScope, { operations.cancel(toolId, it) }, stopSession)

    /**
     * Upstream queries run only while the screen collects this state (it collects with the
     * lifecycle), and the last values are kept across a stop/start like the screen did before.
     */
    val state: StateFlow<BackgroundTasksUiState> = flow {
        coroutineScope {
            launch {
                operations.observeActiveTasks(toolId).collect { tasks ->
                    snapshot.update { it.copy(active = tasks, activeLoaded = true) }
                }
            }
            launch {
                operations.observeRecentHistory(toolId).collect { page ->
                    snapshot.update { it.copy(recentHistory = page, historyLoaded = true) }
                }
            }
            launch {
                snapshot.map { it.loadedBoundary }.distinctUntilChanged().collectLatest { boundary ->
                    if (boundary == null) {
                        snapshot.update { it.copy(loadedHistory = null) }
                    } else {
                        operations.observeHistoryThrough(toolId, boundary.createdAt, boundary.taskId).collect { tasks ->
                            snapshot.update { it.copy(loadedHistory = LoadedHistory(boundary, tasks)) }
                        }
                    }
                }
            }
            emitAll(combine(snapshot, sessions) { s, current -> uiState(s, current) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), uiState(snapshot.value, sessions.value))

    /** Latest run result of one task; collected lazily by each visible task row. */
    fun result(taskId: String): Flow<TaskRunResult?> = operations.observeResult(taskId)

    fun loadMoreHistory() {
        val current = snapshot.value
        val cursor = current.loadedBoundary
            ?: current.recentHistory.tasks.lastOrNull()?.let { HistoryBoundary(it.createdAt, it.taskId) }
        if (cursor == null || !current.hasMoreHistory || current.loadingHistory) return
        snapshot.update { it.copy(loadingHistory = true, historyError = null) }
        viewModelScope.launch {
            try {
                val next = operations.historyBefore(toolId, cursor.createdAt, cursor.taskId)
                snapshot.update { s ->
                    s.copy(
                        olderHistory = (s.olderHistory + next.tasks).distinctBy(BackgroundTask::taskId),
                        loadedBoundary = next.tasks.lastOrNull()?.let { HistoryBoundary(it.createdAt, it.taskId) }
                            ?: s.loadedBoundary,
                        moreBeyondBoundary = next.hasMore,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot.update { it.copy(historyError = "读取历史任务失败，请重试。") }
            } finally {
                snapshot.update { it.copy(loadingHistory = false) }
            }
        }
    }

    private fun uiState(s: Snapshot, sessions: List<RuntimeBackgroundSessionUi>): BackgroundTasksUiState {
        val visibleHistory = if (s.loadedBoundary != null && s.loadedHistory?.boundary == s.loadedBoundary) {
            s.loadedHistory?.tasks.orEmpty()
        } else {
            (s.recentHistory.tasks + s.olderHistory).distinctBy(BackgroundTask::taskId)
        }
        return BackgroundTasksUiState(
            page = backgroundTasksPageModel(toolId, (s.active + visibleHistory).distinctBy(BackgroundTask::taskId), sessions),
            loaded = s.activeLoaded && s.historyLoaded,
            canLoadMoreHistory = s.hasMoreHistory,
            loadingHistory = s.loadingHistory,
            historyError = s.historyError,
        )
    }
}

internal class BackgroundTasksViewModelFactory(
    private val toolId: String,
    private val operations: HostBackgroundOperations,
    private val sessions: StateFlow<List<RuntimeBackgroundSessionUi>>,
    private val stopSession: suspend (String) -> Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(BackgroundTasksViewModel::class.java.isAssignableFrom(modelClass)) {
            "Unsupported background tasks ViewModel: ${modelClass.name}"
        }
        return BackgroundTasksViewModel(toolId, operations, sessions, stopSession) as T
    }
}
