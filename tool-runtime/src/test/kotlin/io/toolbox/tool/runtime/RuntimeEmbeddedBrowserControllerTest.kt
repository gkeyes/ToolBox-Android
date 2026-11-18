package io.toolbox.tool.runtime

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.MotionEvent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeEmbeddedBrowserControllerTest {
    private lateinit var activity: Activity
    private lateinit var container: FrameLayout
    private lateinit var owner: OwnerWebView
    private lateinit var controller: RuntimeEmbeddedBrowserController
    private val created = mutableListOf<PlayerWebView>()
    private val url = "https://www.youtube-nocookie.com/embed/y8-kOYgmNdA"
    private val bounds = RuntimeEmbeddedBrowserBounds(20.0, 80.0, 320.0, 180.0, 400.0, 0.0, 0.0, 0.0, 0.0)

    @Before
    fun attachOwner() {
        val lifecycle = Robolectric.buildActivity(Activity::class.java).create()
        activity = lifecycle.get()
        container = FrameLayout(activity)
        owner = OwnerWebView(activity)
        container.addView(owner, FrameLayout.LayoutParams(400, 800))
        activity.setContentView(container)
        lifecycle.start().resume().visible()
        container.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        container.layout(0, 0, 400, 800)
        owner.layout(0, 0, 400, 800)
        assertTrue(owner.isAttachedToWindow)
        assertEquals(400, owner.width)
        assertEquals(800, owner.height)
        controller = RuntimeEmbeddedBrowserController(owner, allowFullscreen = true) { context ->
            PlayerWebView(context).also(created::add)
        }
    }

    @After
    fun releaseOwner() {
        controller.release()
        container.removeAllViews()
        owner.destroy()
        activity.finish()
    }

    @Test
    fun officialPlayerHasAppRefererAndStaysInsideTheArticleSlot() = runBlocking {
        val id = controller.open(url, bounds) {}
        assertTrue(isEmbeddedBrowserId(id))
        val player = created.single()
        assertEquals(normalizedYouTubeEmbedUrl(url), player.loadedUrl)
        assertEquals(mapOf("Referer" to "https://${activity.packageName}"), player.loadedHeaders)
        val clip = player.parent as FrameLayout
        assertSame(container, clip.parent)
        val layout = clip.layoutParams as FrameLayout.LayoutParams
        assertEquals(20, layout.leftMargin)
        assertEquals(80, layout.topMargin)
        assertEquals(320, layout.width)
        assertEquals(180, layout.height)
        assertFalse(player.settings.allowFileAccess)
        assertFalse(player.settings.allowContentAccess)
        assertTrue(player.settings.javaScriptEnabled)
        assertFalse(player.settings.javaScriptCanOpenWindowsAutomatically)

        controller.update(id, bounds.copy(y = -90.0, clipTop = 100.0)) {}
        val clipped = clip.layoutParams as FrameLayout.LayoutParams
        assertEquals(10, clipped.topMargin)
        assertEquals(80, clipped.height)
        assertEquals(-100, (player.layoutParams as FrameLayout.LayoutParams).topMargin)
        assertEquals(180, player.layoutParams.height)

        controller.update(id, bounds.copy(y = 900.0)) {}
        assertEquals(View.INVISIBLE, clip.visibility)
        assertEquals(1, player.pauseCount)
        controller.close(id)
        assertEquals(1, player.destroyCount)
        assertEquals(1, container.childCount)
    }

    @Test
    fun cannotNavigatePlayerToAnotherPageAccessLocalToolsOrAcquireDevicePermissions() = runBlocking {
        controller.open(url, bounds) {}
        val player = created.single()
        val client = player.webViewClient
        assertNull(client.shouldInterceptRequest(player, request(player.loadedUrl!!, mainFrame = true)))
        assertTrue(client.shouldOverrideUrlLoading(player, request("https://www.youtube.com/watch?v=y8-kOYgmNdA", true)))
        assertEquals(403, client.shouldInterceptRequest(player, request("https://evil.test/", true))?.statusCode)
        assertEquals(403, client.shouldInterceptRequest(player, request("https://reader.toolbox.invalid/private.json"))?.statusCode)
        assertEquals(403, client.shouldInterceptRequest(player, request("file:///data/private"))?.statusCode)
        assertEquals(403, client.shouldInterceptRequest(player, request("http://media.example.test/video"))?.statusCode)
        assertNull(client.shouldInterceptRequest(player, request("https://rr1.googlevideo.com/videoplayback")))

        var denied = false
        var granted = false
        requireNotNull(player.webChromeClient).onPermissionRequest(object : PermissionRequest() {
            override fun getOrigin() = Uri.parse("https://www.youtube-nocookie.com")
            override fun getResources() = arrayOf(RESOURCE_AUDIO_CAPTURE)
            override fun grant(resources: Array<out String>) { granted = true }
            override fun deny() { denied = true }
        })
        assertTrue(denied)
        assertFalse(granted)
    }

    @Test
    fun replacementNavigationBackgroundAndDetachDestroyTheirPlayerExactlyOnce() = runBlocking {
        val first = controller.open(url, bounds) {}
        val second = controller.open(url, bounds) {}
        assertNotEquals(first, second)
        assertEquals(1, created[0].destroyCount)
        controller.close(first)
        assertEquals(0, created[1].destroyCount)
        controller.resetForNavigation()
        assertEquals(1, created[1].destroyCount)
        controller.open(url, bounds) {}
        controller.setForeground(false)
        assertEquals(1, created[2].destroyCount)
        controller.open(url, bounds) {}
        container.removeView(owner)
        assertEquals(1, created[3].destroyCount)
        controller.release()
        controller.release()
        assertTrue(created.all { it.destroyCount == 1 })
    }

    @Test
    fun fullscreenCoversTheToolContainerThenReturnsToTheSamePlayerSlot() = runBlocking {
        controller.open(url, bounds) {}
        val player = created.single()
        val originalClip = player.parent
        val fullscreenView = View(activity)
        var hidden = 0
        val chrome = requireNotNull(player.webChromeClient)
        chrome.onShowCustomView(fullscreenView, WebChromeClient.CustomViewCallback { hidden++ })
        assertSame(container, fullscreenView.parent)
        assertSame(originalClip, player.parent)
        chrome.onHideCustomView()
        assertNull(fullscreenView.parent)
        assertEquals(1, hidden)
        assertSame(originalClip, player.parent)
        assertEquals(0, player.destroyCount)
    }

    @Test
    fun verticalDraggingTransfersToTheArticleWhileTapsAndSeekingRemainInThePlayer() = runBlocking {
        controller.open(url, bounds) {}
        val player = created.single()
        val surface = player.parent as FrameLayout
        surface.layout(20, 80, 340, 260)
        player.layout(0, 0, 320, 180)
        fun touch(action: Int, x: Float, y: Float) {
            MotionEvent.obtain(0L, 10L, action, x, y, 0).also { surface.dispatchTouchEvent(it); it.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 100f, 40f)
        touch(MotionEvent.ACTION_UP, 100f, 40f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), player.touches)
        assertTrue(owner.touches.isEmpty())
        player.touches.clear()

        touch(MotionEvent.ACTION_DOWN, 100f, 40f)
        touch(MotionEvent.ACTION_MOVE, 160f, 43f)
        touch(MotionEvent.ACTION_UP, 160f, 43f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), player.touches)
        assertTrue(owner.touches.isEmpty())
        player.touches.clear()

        touch(MotionEvent.ACTION_DOWN, 100f, 40f)
        touch(MotionEvent.ACTION_MOVE, 104f, 100f)
        touch(MotionEvent.ACTION_UP, 104f, 120f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), player.touches)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), owner.touches.map { it.first })
        assertEquals(120f, owner.touches.first().second.first, 0.01f)
        assertEquals(120f, owner.touches.first().second.second, 0.01f)
    }

    /** ShadowWebView's no-op Chromium provider does not implement its measure/frame delegates. */
    private open class FramedWebView(context: Context) : WebView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean {
            val changed = this.left != left || this.top != top || this.right != right || this.bottom != bottom
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
            return changed
        }
    }

    private class OwnerWebView(context: Context) : FramedWebView(context) {
        val touches = mutableListOf<Pair<Int, Pair<Float, Float>>>()
        override fun hasWindowFocus(): Boolean = true
        override fun onTouchEvent(event: MotionEvent): Boolean {
            touches += event.actionMasked to (event.x to event.y)
            return true
        }
    }

    private class PlayerWebView(context: Context) : FramedWebView(context) {
        var loadedUrl: String? = null
        var loadedHeaders: Map<String, String>? = null
        var pauseCount = 0
        var destroyCount = 0
        val touches = mutableListOf<Int>()
        override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
            loadedUrl = url
            loadedHeaders = additionalHttpHeaders.toMap()
        }
        override fun onPause() { pauseCount++ }
        override fun destroy() { destroyCount++; super.destroy() }
        override fun onTouchEvent(event: MotionEvent): Boolean { touches += event.actionMasked; return true }
    }

    private fun request(url: String, mainFrame: Boolean = false) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
