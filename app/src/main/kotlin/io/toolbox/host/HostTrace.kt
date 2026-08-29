package io.toolbox.host

import android.os.Trace

internal object HostTrace {
    suspend fun <T> bestEffortSection(name: String, block: suspend () -> T): T {
        val opened = tryTrace { Trace.beginSection(name) }
        return try {
            block()
        } finally {
            if (opened) tryTrace(Trace::endSection)
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
