package io.toolbox.host.background

import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNetworkStreamsTest {
    private fun id(index: Int) = "stream-" + index.toString(16).padStart(32, '0')

    private fun errorCode(block: () -> Unit): RuntimeRpcErrorCode? =
        (runCatching(block).exceptionOrNull() as? RuntimeHandlerException)?.errorCode

    @Test
    fun retiredIdsStayBoundedWhileRecentOnesAreStillRecognised() {
        val streams = RuntimeNetworkStreams()
        try {
            val total = RuntimeNetworkStreams.REMEMBERED_STREAM_IDS + 500
            repeat(total) { index ->
                val control = streams.reserve(id(index), 0)
                streams.release(id(index), control)
            }
            assertEquals(RuntimeNetworkStreams.REMEMBERED_STREAM_IDS, streams.retiredStreamCount)
            // A recently retired ID is still rejected and a late cancel for it is ignored.
            assertEquals(RuntimeRpcErrorCode.CANCELLED, errorCode { streams.reserve(id(total - 1), 0) })
            streams.cancel(id(total - 1))
            assertEquals(RuntimeRpcErrorCode.CANCELLED, errorCode { streams.reserve(id(total - 1), 0) })
            // A long-forgotten ID behaves like an unknown one: it can be opened again.
            val reopened = streams.reserve(id(0), 0)
            streams.release(id(0), reopened)
        } finally {
            streams.close()
        }
    }

    @Test
    fun clearKeepsRetiredIdsBoundedAcrossNavigations() {
        val streams = RuntimeNetworkStreams()
        try {
            var next = 0
            repeat(10) {
                repeat(1_000) { streams.reserve(id(next++), 0) }
                streams.clear()
            }
            assertTrue(streams.retiredStreamCount <= RuntimeNetworkStreams.REMEMBERED_STREAM_IDS)
            assertEquals(RuntimeRpcErrorCode.CANCELLED, errorCode { streams.reserve(id(next - 1), 0) })
        } finally {
            streams.close()
        }
    }

    @Test
    fun cancelBeforeOpenStillCancelsTheMatchingReservation() {
        val streams = RuntimeNetworkStreams()
        try {
            streams.cancel(id(1))
            assertEquals(RuntimeRpcErrorCode.CANCELLED, errorCode { streams.reserve(id(1), 0) })
            assertEquals(1, streams.retiredStreamCount)
        } finally {
            streams.close()
        }
    }
}
