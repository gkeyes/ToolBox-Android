package io.toolbox.host.icons

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolIconLoadCoordinatorTest {
    @Test
    fun unrelatedLoadsProgressWhileSameToolInvalidationWaits() = runTest {
        val loads = ToolIconLoadCoordinator(2)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val slow = async { loads.withTool("slow") { loads.decode { release.await(); order += "publish" } } }
        runCurrent()
        val invalidate = async { loads.withTool("slow") { order += "invalidate" } }
        val other = async { loads.withTool("other") { loads.decode { 7 } } }
        runCurrent()
        assertEquals(7, other.await())
        assertFalse(invalidate.isCompleted)
        release.complete(Unit)
        slow.await()
        invalidate.await()
        assertEquals(listOf("publish", "invalidate"), order)
    }

    @Test
    fun decodeLimitDoesNotBlockCacheHitsAndCancellationReleasesCapacity() = runTest {
        val loads = ToolIconLoadCoordinator(1)
        val release = CompletableDeferred<Unit>()
        val first = async { loads.withTool("first") { loads.decode { release.await() } } }
        runCurrent()
        val queued = async { loads.withTool("second") { loads.decode { 2 } } }
        val sameToolWaiter = async { loads.withTool("first") { error("Cancelled waiter ran") } }
        val cached = async { loads.withTool("cached") { 3 } }
        runCurrent()
        assertFalse(queued.isCompleted)
        assertEquals(3, cached.await())
        sameToolWaiter.cancelAndJoin()
        first.cancelAndJoin()
        assertEquals(2, queued.await())
        assertTrue(loads.withTool("first") { loads.decode { true } })
    }
}
