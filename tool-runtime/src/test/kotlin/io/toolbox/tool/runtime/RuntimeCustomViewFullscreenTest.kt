package io.toolbox.tool.runtime

import android.app.Activity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeCustomViewFullscreenTest {
    private lateinit var activity: Activity
    private lateinit var container: FrameLayout
    private lateinit var webView: WebView

    @Before
    fun attachRuntimeWebView() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        activity = controller.get()
        container = FrameLayout(activity)
        webView = WebView(activity)
        container.addView(webView)
        activity.setContentView(container)
        controller.start().resume().visible()
        assertTrue(webView.isAttachedToWindow)
    }

    @Test
    fun aToolWithoutAllowFullscreenIsRefusedAtOnce() {
        val fullscreen = RuntimeCustomViewFullscreen(webView, allowed = false)
        val callback = CountingCallback()
        val view = View(activity)

        fullscreen.show(view, callback)

        assertEquals(1, callback.hidden)
        assertFalse(fullscreen.showing)
        assertNull(view.parent)
        assertEquals(1, container.childCount)
    }

    @Test
    fun anAllowedToolCoversItsContainerUntilItExits() {
        val fullscreen = RuntimeCustomViewFullscreen(webView, allowed = true)
        val callback = CountingCallback()
        val view = View(activity)

        fullscreen.show(view, callback)
        assertTrue(fullscreen.showing)
        assertSame(container, view.parent)
        assertEquals(0, callback.hidden)

        // A second request while one is showing is refused without replacing the first.
        val second = CountingCallback()
        fullscreen.show(View(activity), second)
        assertEquals(1, second.hidden)
        assertSame(container, view.parent)

        fullscreen.exit()
        assertFalse(fullscreen.showing)
        assertNull(view.parent)
        assertEquals(1, callback.hidden)

        fullscreen.exit()
        assertEquals(1, callback.hidden)
    }

    @Test
    fun theWebViewLeavingTheWindowEndsFullscreen() {
        val fullscreen = RuntimeCustomViewFullscreen(webView, allowed = true)
        val callback = CountingCallback()
        val view = View(activity)
        fullscreen.show(view, callback)

        container.removeView(webView)

        assertFalse(fullscreen.showing)
        assertNull(view.parent)
        assertEquals(1, callback.hidden)
    }

    private class CountingCallback : WebChromeClient.CustomViewCallback {
        var hidden = 0
        override fun onCustomViewHidden() {
            hidden++
        }
    }
}
