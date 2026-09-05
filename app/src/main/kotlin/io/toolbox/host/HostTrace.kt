package io.toolbox.host

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

internal object HostTrace {
    private val nextAsyncCookie = AtomicInteger()

    // Intentionally non-inline/non-suspending: synchronous sections must stay on one thread.
    fun <T> bestEffortSection(name: String, block: () -> T): T {
        val opened = tryTrace { Trace.beginSection(name) }
        return try {
            block()
        } finally {
            if (opened) tryTrace(Trace::endSection)
        }
    }

    suspend fun <T> bestEffortAsyncSection(name: String, block: suspend () -> T): T {
        val cookie = nextAsyncCookie.incrementAndGet()
        val opened = tryBeginAsyncSection(name, cookie)
        return try {
            block()
        } finally {
            if (opened) bestEffortEndAsyncSection(name, cookie)
        }
    }

    fun tryBeginAsyncSection(name: String, cookie: Int): Boolean = tryTrace {
        Trace.beginAsyncSection(name, cookie)
    }

    fun bestEffortEndAsyncSection(name: String, cookie: Int) {
        tryTrace { Trace.endAsyncSection(name, cookie) }
    }

    private inline fun tryTrace(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: RuntimeException) {
        false
    }
}
