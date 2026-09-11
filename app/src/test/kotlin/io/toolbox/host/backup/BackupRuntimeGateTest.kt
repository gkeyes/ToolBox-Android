package io.toolbox.host.backup

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackupRuntimeGateTest {
    @Test fun pauseDrainsWorkAndCleanupBeforeAllowingResume() = runBlocking {
        BackupRuntimeGate.resume()
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val worker = launch { BackupRuntimeGate.worker {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { yield(); cleaned = true } }
        } }
        entered.await()
        try {
            BackupRuntimeGate.pauseAndDrain()
            assertTrue(cleaned); assertTrue(worker.isCompleted)
            assertNull(BackupRuntimeGate.worker { "must not run" })
        } finally { BackupRuntimeGate.resume() }
        assertEquals("resumed", BackupRuntimeGate.worker { "resumed" })
    }
}
