package io.toolbox.host.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeWebViewPlaybackTest {
    @Test
    fun repeatedActivityAndRuntimeTransitionsOnlyCallWebViewWhenStateChanges() {
        val calls = mutableListOf<String>()
        val playback = RuntimeWebViewPlayback(
            pause = { calls += "pause" },
            resume = { calls += "resume" },
        )

        playback.setResumed(false)
        playback.setResumed(false)
        playback.setResumed(true)
        playback.setResumed(true)
        playback.setResumed(false)

        assertEquals(listOf("pause", "resume", "pause"), calls)
    }
}
