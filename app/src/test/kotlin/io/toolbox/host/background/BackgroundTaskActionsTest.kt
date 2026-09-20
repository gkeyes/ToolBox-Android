package io.toolbox.host.background

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundTaskActionsTest {
    @Test fun persistenceFailureKeepsItsTargetForRetryAndRejectsOverlappingCancellation() = runTest {
        val result = CompletableDeferred<BackgroundCancellationResult>()
        val calls = mutableListOf<String>()
        val actions = BackgroundTaskActions(this, { calls += it; result.await() }, { true })
        actions.cancel("failed-task")
        actions.cancel("different-task")
        runCurrent()
        assertEquals(listOf("failed-task"), calls)
        result.complete(BackgroundCancellationResult.Failed("STORAGE_ERROR"))
        advanceUntilIdle()
        assertNull(actions.state.value.cancellingTaskId)
        assertEquals(BackgroundTaskAction.Cancel("failed-task"), actions.state.value.retry)
        assertEquals("任务未能取消，请重试。", actions.state.value.message)
        actions.retry()
        advanceUntilIdle()
        assertEquals(listOf("failed-task", "failed-task"), calls)
    }

    @Test fun stopExceptionReleasesBusyAndDoesNotOfferAnUnsafeSessionIdRetry() = runTest {
        val calls = mutableListOf<String>()
        var fail = true
        val actions = BackgroundTaskActions(this, { BackgroundCancellationResult.Cancelled }, {
            calls += it
            if (fail) throw IllegalStateException("stop failed")
            true
        })
        actions.stop("session")
        actions.stop("other")
        advanceUntilIdle()
        assertNull(actions.state.value.stoppingSessionId)
        assertNull(actions.state.value.retry)
        assertEquals("后台环境停止未完成。请前往后台保障，关闭后台运行并重试停止。", actions.state.value.message)
        fail = false
        actions.retry()
        actions.stop("session")
        advanceUntilIdle()
        assertEquals(listOf("session"), calls)
        assertNull(actions.state.value.retry)
        assertEquals("后台环境停止未完成。请前往后台保障，关闭后台运行并重试停止。", actions.state.value.message)
        actions.stop("independent-session")
        advanceUntilIdle()
        assertEquals(listOf("session", "independent-session"), calls)
        assertEquals("后台环境已停止。", actions.state.value.message)
    }

    @Test fun alreadyFinishedAndUnexpectedFailuresHaveDifferentFeedback() = runTest {
        var fail = false
        val actions = BackgroundTaskActions(this, {
            if (fail) throw IllegalStateException("failed")
            BackgroundCancellationResult.AlreadyFinished()
        }, { true })
        actions.cancel("task")
        advanceUntilIdle()
        assertEquals("任务已结束或不存在。", actions.state.value.message)
        assertNull(actions.state.value.retry)
        fail = true
        actions.cancel("task")
        advanceUntilIdle()
        assertEquals("任务未能取消，请重试。", actions.state.value.message)
        assertNotNull(actions.state.value.retry)
        assertNull(actions.state.value.cancellingTaskId)
    }

    @Test fun coroutineCancellationReleasesBothBusyFields() = runTest {
        val actions = BackgroundTaskActions(this, { throw CancellationException() }, { throw CancellationException() })
        actions.cancel("task")
        actions.stop("session")
        advanceUntilIdle()
        assertNull(actions.state.value.cancellingTaskId)
        assertNull(actions.state.value.stoppingSessionId)
        assertNull(actions.state.value.message)
    }
}
