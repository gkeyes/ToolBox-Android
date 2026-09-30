package io.toolbox.host.browser

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserMediaLayoutInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var page: WebView

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            page = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(384, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 384, 800)
            }
        }
    }

    @After
    fun tearDown() {
        if (::page.isInitialized) {
            instrumentation.runOnMainSync {
                page.stopLoading()
                page.loadUrl("about:blank")
                page.destroy()
            }
        }
    }

    @Test
    fun collapsedDPlayerRecoversInsideRealAndroidWebView() {
        loadFixture(playerClass = "dplayer", playerId = "player")

        val before = metrics("player", "wrap", "video")
        assertEquals(0, before.getInt("playerHeight"))
        assertEquals(0, before.getInt("videoHeight"))

        val repairSource = instrumentation.targetContext.assets
            .open("browser/media-layout-compat.js")
            .bufferedReader()
            .use { it.readText() }

        val repaired = evaluate("($repairSource)()")
        assertEquals("1", repaired)

        val after = metrics("player", "wrap", "video")
        assertTrue(after.toString(), after.getInt("playerHeight") >= 200)
        assertTrue(after.toString(), after.getInt("wrapHeight") >= 200)
        assertTrue(after.toString(), after.getInt("videoHeight") >= 200)
        assertEquals("fallback-16:9:1.7778", after.getString("marker"))
        assertEquals("fallback-16:9", after.getString("videoRepair"))
    }

    @Test
    fun genericCollapsedMediaContainerIsNotModifiedInsideRealAndroidWebView() {
        loadFixture(playerClass = "", playerId = "content")

        val repairSource = instrumentation.targetContext.assets
            .open("browser/media-layout-compat.js")
            .bufferedReader()
            .use { it.readText() }

        val repaired = evaluate("($repairSource)()")
        assertEquals("0", repaired)

        val after = metrics("content", "wrap", "video")
        assertEquals(0, after.getInt("playerHeight"))
        assertEquals(0, after.getInt("videoHeight"))
        assertEquals("none", after.getString("marker"))
    }

    private fun loadFixture(playerClass: String, playerId: String) {
        val wrapClass = if (playerClass.isNotEmpty()) "dplayer-video-wrap" else ""
        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                html, body { margin: 0; width: 384px; min-height: 800px; }
                #$playerId { width: 384px; height: 0; display: block; }
                #wrap { width: 384px; height: 0; display: flex; }
                #video { width: 384px; height: 0; display: block; }
              </style>
            </head>
            <body>
              <div id="$playerId" class="$playerClass">
                <div id="wrap" class="$wrapClass">
                  <video id="video" class="dplayer-video dplayer-video-current"></video>
                </div>
              </div>
              <script>
                const video = document.getElementById('video');
                video.src = URL.createObjectURL(new Blob(['not-real-media'], { type: 'video/mp4' }));
              </script>
            </body>
            </html>
        """.trimIndent()

        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            page.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    loaded.countDown()
                }
            }
            page.loadDataWithBaseURL("https://fixture.invalid/", html, "text/html", "UTF-8", null)
        }
        assertTrue("fixture did not finish loading", loaded.await(20, TimeUnit.SECONDS))
    }

    private fun metrics(playerId: String, wrapId: String, videoId: String): JSONObject {
        val raw = evaluate(
            """
            (() => {
              const player = document.getElementById('$playerId');
              const wrap = document.getElementById('$wrapId');
              const video = document.getElementById('$videoId');
              return {
                playerHeight: Math.round(player.getBoundingClientRect().height),
                wrapHeight: Math.round(wrap.getBoundingClientRect().height),
                videoHeight: Math.round(video.getBoundingClientRect().height),
                marker: player.dataset.toolboxMediaLayoutRepair || 'none',
                videoRepair: video.dataset.toolboxLayoutRepairRatio || 'none'
              };
            })()
            """.trimIndent(),
        )
        return JSONObject(raw)
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync {
            page.evaluateJavascript(script) {
                value = it ?: ""
                done.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out", done.await(20, TimeUnit.SECONDS))
        return value
    }
}
