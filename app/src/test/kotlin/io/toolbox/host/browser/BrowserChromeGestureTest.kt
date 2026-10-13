package io.toolbox.host.browser

import android.view.MotionEvent
import androidx.compose.runtime.MutableState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserChromeGestureTest {
    /** Plain holder: keeps the test off the Android snapshot-state implementation. */
    private class PlainState(override var value: Boolean = false) : MutableState<Boolean> {
        override fun component1() = value
        override fun component2(): (Boolean) -> Unit = { value = it }
    }

    private fun newGesture() = BrowserChromeGesture(PlainState())

    private val density = 2f
    private val top = 16

    private fun BrowserChromeGesture.touch(
        action: Int,
        rawY: Float,
        scrollY: Int = 500,
        locked: Boolean = false,
        pointers: Int = 1,
    ) = onTouch(locked, scrollY, top, pointers, action, rawY, density)

    @Test fun deliberateDownwardScrollHidesOncePerGesture() {
        val gesture = newGesture()
        gesture.touch(MotionEvent.ACTION_DOWN, 500f)
        gesture.touch(MotionEvent.ACTION_MOVE, 480f) // 20px < 48px
        assertFalse(gesture.hidden)
        gesture.touch(MotionEvent.ACTION_MOVE, 452f) // 48px total
        assertTrue(gesture.hidden)
        // Committed: an upward move in the same gesture does not flip it back.
        gesture.touch(MotionEvent.ACTION_MOVE, 600f)
        assertTrue(gesture.hidden)
    }

    @Test fun upwardScrollShowsAfterSmallerThreshold() {
        val gesture = newGesture()
        gesture.touch(MotionEvent.ACTION_DOWN, 500f)
        gesture.touch(MotionEvent.ACTION_MOVE, 400f)
        assertTrue(gesture.hidden)
        gesture.touch(MotionEvent.ACTION_UP, 400f)
        gesture.touch(MotionEvent.ACTION_DOWN, 400f)
        gesture.touch(MotionEvent.ACTION_MOVE, 420f) // 20px < 22px
        assertTrue(gesture.hidden)
        gesture.touch(MotionEvent.ACTION_MOVE, 422f) // 22px total
        assertFalse(gesture.hidden)
    }

    @Test fun neverHidesAtTopOfPage() {
        val gesture = newGesture()
        gesture.touch(MotionEvent.ACTION_DOWN, 500f, scrollY = top)
        gesture.touch(MotionEvent.ACTION_MOVE, 300f, scrollY = top)
        assertFalse(gesture.hidden)
    }

    @Test fun lockedAndMultiTouchShowAndReset() {
        val gesture = newGesture()
        gesture.touch(MotionEvent.ACTION_DOWN, 500f)
        gesture.touch(MotionEvent.ACTION_MOVE, 400f)
        assertTrue(gesture.hidden)
        gesture.touch(MotionEvent.ACTION_MOVE, 400f, locked = true)
        assertFalse(gesture.hidden)

        gesture.touch(MotionEvent.ACTION_DOWN, 500f)
        gesture.touch(MotionEvent.ACTION_MOVE, 470f)
        gesture.touch(MotionEvent.ACTION_MOVE, 440f, pointers = 2)
        // Pinch reset the accumulated distance and the last position, so 30px more is not enough.
        gesture.touch(MotionEvent.ACTION_MOVE, 410f)
        gesture.touch(MotionEvent.ACTION_MOVE, 380f)
        assertFalse(gesture.hidden)
    }

    @Test fun showClearsHiddenState() {
        val gesture = newGesture()
        gesture.touch(MotionEvent.ACTION_DOWN, 500f)
        gesture.touch(MotionEvent.ACTION_MOVE, 400f)
        assertTrue(gesture.hidden)
        gesture.show()
        assertFalse(gesture.hidden)
    }
}
