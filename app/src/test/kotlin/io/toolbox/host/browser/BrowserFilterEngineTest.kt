package io.toolbox.host.browser

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class BrowserFilterEngineTest {
    private val stored = BrowserFilterSnapshot(enabled = false)

    @Test fun backgroundReadAppliesWhenNothingChangedMeanwhile() {
        val engine = BrowserFilterEngine()
        val token = engine.revision()
        val revision = engine.offer(token, stored)
        assertNotNull(revision)
        assertTrue(engine.isCurrent(revision!!))
        assertEquals(stored, engine.awaitLoaded())
    }

    @Test fun localChangeWinsOverAReadThatStartedEarlier() {
        val engine = BrowserFilterEngine()
        val token = engine.revision()
        val changed = BrowserFilterSnapshot(builtIn = false)
        engine.replace(changed)
        assertNull(engine.offer(token, stored))
        assertEquals(changed, engine.awaitLoaded())
    }

    @Test fun appliedReadIsNoLongerCurrentAfterALocalChange() {
        val engine = BrowserFilterEngine()
        val revision = engine.offer(engine.revision(), stored)!!
        engine.replace(BrowserFilterSnapshot())
        assertFalse(engine.isCurrent(revision))
    }

    @Test fun interceptionWaitsForTheFirstStoredSnapshot() {
        val engine = BrowserFilterEngine()
        val started = CountDownLatch(1)
        var seen: BrowserFilterSnapshot? = null
        val worker = thread { started.countDown(); seen = engine.awaitLoaded() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        worker.join(100)
        assertTrue(worker.isAlive)
        engine.offer(engine.revision(), stored)
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(stored, seen)
    }

    @Test fun failedOrCanceledReadStillReleasesWaitersWithDefaults() {
        val failed = BrowserFilterEngine()
        assertNull(failed.offer(failed.revision(), null))
        assertEquals(BrowserFilterSnapshot(), failed.awaitLoaded())
        val canceled = BrowserFilterEngine()
        canceled.release()
        assertEquals(BrowserFilterSnapshot(), canceled.awaitLoaded())
    }
}
