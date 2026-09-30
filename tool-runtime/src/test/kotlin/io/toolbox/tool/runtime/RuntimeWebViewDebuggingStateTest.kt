package io.toolbox.tool.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWebViewDebuggingStateTest {
    @Test fun runtimeCreationKeepsTheUserChoiceAndANewProcessStartsDisabled() {
        val applied = mutableListOf<Boolean>()
        val state = RuntimeWebViewDebuggingState(applied::add)
        assertFalse(state.enabled.value)
        state.applyCurrentSetting()
        state.setEnabled(true)
        state.applyCurrentSetting()
        assertTrue(state.enabled.value)
        state.setEnabled(false)
        state.applyCurrentSetting()
        assertEquals(listOf(false, true, true, false, false), applied)

        state.setEnabled(true)
        val restarted = RuntimeWebViewDebuggingState(applied::add)
        restarted.applyCurrentSetting()
        assertFalse(restarted.enabled.value)
        assertFalse(applied.last())
    }

    @Test fun aFailedNativeChangeDoesNotReportSuccessOrChangeTheNextRuntimeSetting() {
        val applied = mutableListOf<Boolean>()
        var fail = true
        val state = RuntimeWebViewDebuggingState { enabled ->
            if (fail) throw IllegalStateException("Native debugging unavailable")
            applied += enabled
        }
        assertThrows(IllegalStateException::class.java) { state.setEnabled(true) }
        assertFalse(state.enabled.value)
        fail = false
        state.applyCurrentSetting()
        state.setEnabled(true)
        fail = true
        assertThrows(IllegalStateException::class.java) { state.setEnabled(false) }
        assertTrue(state.enabled.value)
        fail = false
        state.applyCurrentSetting()
        assertEquals(listOf(false, true, true), applied)
    }
}
