package io.toolbox.host.browser

import android.view.MotionEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Hides the browser toolbars on a deliberate downward scroll and shows them again on an upward one.
 * Free of Android views so the thresholds can be unit tested; the Activity feeds it raw touch data.
 * [hiddenState] is Compose state in the app; JVM tests pass a plain implementation. */
internal class BrowserChromeGesture(hiddenState: MutableState<Boolean> = mutableStateOf(false)) {
    var hidden by hiddenState
        private set
    private var lastY: Float? = null
    private var downPx = 0f
    private var upPx = 0f
    private var committed = false

    fun reset() {
        lastY = null
        downPx = 0f
        upPx = 0f
        committed = false
    }

    fun show() {
        hidden = false
        downPx = 0f
        upPx = 0f
    }

    /**
     * @param locked a dialog, sheet, IME or error keeps the toolbars visible.
     * @param scrollY the page's current vertical scroll offset in px.
     * @param topThresholdPx a scroll offset at or below this value means the page counts as scrolled to the top.
     * @param action [MotionEvent.getActionMasked].
     */
    fun onTouch(
        locked: Boolean,
        scrollY: Int,
        topThresholdPx: Int,
        pointerCount: Int,
        action: Int,
        rawY: Float,
        density: Float,
    ) {
        if (locked) {
            show()
            reset()
            return
        }

        if (scrollY <= topThresholdPx) {
            show()
        }

        if (pointerCount > 1) {
            reset()
            return
        }

        val hideThreshold = 24f * density
        val showThreshold = 11f * density

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastY = rawY
                downPx = 0f
                upPx = 0f
                committed = false
            }

            MotionEvent.ACTION_MOVE -> {
                val previousY = lastY ?: rawY
                val delta = previousY - rawY
                lastY = rawY

                if (committed) return

                when {
                    delta > 0f -> {
                        downPx += delta
                        upPx = 0f
                        if (!hidden &&
                            scrollY > topThresholdPx &&
                            downPx >= hideThreshold
                        ) {
                            hidden = true
                            committed = true
                        }
                    }

                    delta < 0f -> {
                        upPx += -delta
                        downPx = 0f
                        if (hidden && upPx >= showThreshold) {
                            hidden = false
                            committed = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> reset()
        }
    }
}
