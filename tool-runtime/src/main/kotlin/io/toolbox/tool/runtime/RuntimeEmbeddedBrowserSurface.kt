package io.toolbox.tool.runtime

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs

/** Taps and timeline drags stay with the player; vertical drags scroll the article beneath it. */
internal class RuntimeEmbeddedBrowserSurface(
    context: Context,
    private val owner: WebView,
    private val fullscreen: () -> Boolean,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var down: MotionEvent? = null
    private var downX = 0f
    private var downY = 0f
    private var mayTransfer = false
    private var transferred = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearGesture()
            down = inOwnerCoordinates(event)
            downX = event.rawX
            downY = event.rawY
            mayTransfer = !fullscreen()
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) mayTransfer = false
        if (transferred) return forward(event)
        if (mayTransfer && !fullscreen() && event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
            val dx = abs(event.rawX - downX)
            val dy = abs(event.rawY - downY)
            if (dy > touchSlop && dy > dx * 1.25f) {
                MotionEvent.obtain(event).also { cancel ->
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                transferred = true
                down?.let { owner.dispatchTouchEvent(it); it.recycle() }
                down = null
                return forward(event)
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) clearGesture()
        return handled
    }

    private fun forward(event: MotionEvent): Boolean {
        inOwnerCoordinates(event).also { owner.dispatchTouchEvent(it); it.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) clearGesture()
        return true
    }

    private fun inOwnerCoordinates(event: MotionEvent): MotionEvent {
        val surfacePosition = IntArray(2).also(::getLocationOnScreen)
        val ownerPosition = IntArray(2).also(owner::getLocationOnScreen)
        return MotionEvent.obtain(event).apply {
            offsetLocation((surfacePosition[0] - ownerPosition[0]).toFloat(), (surfacePosition[1] - ownerPosition[1]).toFloat())
        }
    }

    private fun clearGesture() {
        down?.recycle()
        down = null
        transferred = false
        mayTransfer = false
    }

    override fun onDetachedFromWindow() {
        if (transferred && owner.isAttachedToWindow) {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
                owner.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        clearGesture()
        super.onDetachedFromWindow()
    }
}
