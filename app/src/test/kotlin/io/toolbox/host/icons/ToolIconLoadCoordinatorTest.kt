package io.toolbox.host.icons

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolIconLoadCoordinatorTest {
    @Test
    fun overlappingRequestsShareOneResultButCompletedRequestsAreNotReused() = runTest {
        val coordinator = ToolIconLoadCoordinator<Any>(dispatcher = StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val bitmap = Any()
        var loads = 0
        suspend fun load() = coordinator.share("tool", 1) {
            loads += 1
            release.await()
            bitmap
        }
        val first = async { load() }
        val second = async { load() }
        runCurrent()
        assertEquals(1, loads)
        release.complete(Unit)
        assertSame(bitmap, first.await())
        assertSame(bitmap, second.await())
        assertSame(bitmap, load())
        assertEquals("A new batch must perform its own catalog validation", 2, loads)
    }

    @Test
    fun cancellingOneConsumerKeepsTheSharedLoadForTheOtherConsumer() = runTest {
        val coordinator = ToolIconLoadCoordinator<String>(dispatcher = StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val first = async {
            coordinator.share("tool", 1) {
                loads += 1
                release.await()
                "icon"
            }
        }
        val second = async { coordinator.share("tool", 1) { error("Must share the existing load") } }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals("icon", second.await())
        assertEquals(1, loads)
    }

    @Test
    fun cancellingEveryConsumerCancelsWorkAndAllowsANewRequest() = runTest {
        val coordinator = ToolIconLoadCoordinator<String>(dispatcher = StandardTestDispatcher(testScheduler))
        val stopped = CompletableDeferred<Unit>()
        val first = async {
            coordinator.share("tool", 1) {
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val second = async { coordinator.share("tool", 1) { error("Must share the existing load") } }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(stopped.isCompleted)
        second.cancelAndJoin()
        runCurrent()
        assertTrue(stopped.isCompleted)
        assertEquals("replacement", coordinator.share("tool", 1) { "replacement" })
    }

    @Test
    fun failedSharedWorkIsRemovedAndCanBeRetried() = runTest {
        val coordinator = ToolIconLoadCoordinator<String>(dispatcher = StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        suspend fun load() = runCatching {
            coordinator.share("tool", 1) {
                attempts += 1
                release.await()
                throw IllegalStateException("retryable failure")
            }
        }
        val first = async { load() }
        val second = async { load() }
        runCurrent()
        release.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is IllegalStateException)
        assertTrue(second.await().exceptionOrNull() is IllegalStateException)
        assertEquals(1, attempts)
        assertEquals("retry", coordinator.share("tool", 1) { attempts += 1; "retry" })
        assertEquals(2, attempts)
    }

    @Test
    fun invalidationDetachesOldWorkAndItsCompletionCannotRemoveTheNewRequest() = runTest {
        val coordinator = ToolIconLoadCoordinator<String>(dispatcher = StandardTestDispatcher(testScheduler))
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val old = async { coordinator.share("tool", 1) { oldRelease.await(); "old" } }
        runCurrent()
        coordinator.withTool("tool") { coordinator.forgetShared("tool") }
        val replacement = async { coordinator.share("tool", 1) { newRelease.await(); "replacement" } }
        runCurrent()
        oldRelease.complete(Unit)
        assertEquals("old", old.await())
        val joined = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.share("tool", 1) { error("Old completion must not remove the replacement") }
        }
        newRelease.complete(Unit)
        assertEquals("replacement", replacement.await())
        assertEquals("replacement", joined.await())
    }

    @Test
    fun differentExpectedVersionsNeverShareARequest() = runTest {
        val coordinator = ToolIconLoadCoordinator<Int?>(dispatcher = StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val versions = listOf(null, 1, 2)
        var loads = 0
        val results = versions.map { version ->
            async {
                coordinator.share("tool", version) {
                    loads += 1
                    release.await()
                    version
                }
            }
        }
        runCurrent()
        assertEquals(3, loads)
        release.complete(Unit)
        assertEquals(versions, results.map { it.await() })
    }
}
