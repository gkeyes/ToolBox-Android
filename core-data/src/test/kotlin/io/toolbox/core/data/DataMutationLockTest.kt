package io.toolbox.core.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DataMutationLockTest {
    @Test fun nonBlockingAdmissionRejectsOutsideCallAndRemainsReentrantForRestore() = runBlocking {
        val packages = DataMutationLock()
        val repositories = DataMutationLock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            packages.run {
                repositories.run {
                    assertEquals("nested", packages.tryRun({ error("Restore must be reentrant") }) { "nested" })
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        try {
            assertEquals("BUSY", withTimeout(1_000) {
                packages.tryRun({ "BUSY" }) { error("Concurrent mutation must not execute") }
            })
            assertFalse(holder.isCompleted)
        } finally { release.complete(Unit); holder.join() }
        assertEquals("available", packages.tryRun({ "BUSY" }) { "available" })
    }
    @Test fun nonBlockingCancellationReleasesAdmissionAndStillHonorsRecoveryBlock() = runBlocking {
        val lock = DataMutationLock()
        val entered = CompletableDeferred<Unit>()
        val writer = launch { lock.tryRun({ error("Unexpected contention") }) { entered.complete(Unit); awaitCancellation() } }
        entered.await(); writer.cancelAndJoin()
        assertEquals("available", lock.tryRun({ "BUSY" }) { "available" })
        lock.blockUntilRestart()
        try { lock.tryRun({ fail("Not busy") }) { fail("Recovery block must not be bypassed") }; fail() }
        catch (_: DataRecoveryRequiredException) { }
    }

    @Test fun nestedOperationAndOutsideWritersAreSerialized() = runBlocking {
        val lock = DataMutationLock()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch { lock.run { lock.run { events += "snapshot" }; entered.complete(Unit); release.await(); events += "commit" } }
        entered.await()
        val second = launch { lock.run { events += "writer" } }
        yield(); assertEquals(listOf("snapshot"), events)
        release.complete(Unit); joinAll(first, second)
        assertEquals(listOf("snapshot", "commit", "writer"), events)
    }
    @Test fun packageAndRepositoryLocksRemainReentrantAcrossNestedLocks() = runBlocking {
        val packages = DataMutationLock()
        val repositories = DataMutationLock()
        // Restore holds both locks and calls the ordinary installer, which re-enters them.
        val result = withTimeout(2_000) {
            packages.run { repositories.run { packages.run { repositories.run { "installed" } } } }
        }
        assertEquals("installed", result)
    }
    @Test fun cancellationReleasesLockAndFailedRecoveryBlocksWrites() = runBlocking {
        val lock = DataMutationLock()
        val entered = CompletableDeferred<Unit>()
        val writer = launch { lock.run { entered.complete(Unit); awaitCancellation() } }
        entered.await(); writer.cancelAndJoin()
        assertEquals("available", lock.run { "available" })
        lock.blockUntilRestart()
        try { lock.run { fail("must not enter") }; fail() } catch (_: IllegalStateException) { }
    }
}
