package io.toolbox.host.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserLegacyPageCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var page: WebView

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            page = WebView(instrumentation.targetContext)
            BrowserCompatibilityPolicy.apply(page.settings)
        }
    }

    @After
    fun tearDown() {
        if (::page.isInitialized) {
            instrumentation.runOnMainSync {
                page.stopLoading()
                page.destroy()
            }
        }
    }

    @Test
    fun browserKeepsOverviewModeForLegacyPagesWithoutViewportMeta() {
        instrumentation.runOnMainSync {
            assertTrue(
                "Legacy desktop-style pages should be fitted to the mobile viewport",
                page.settings.loadWithOverviewMode,
            )
        }
    }
}
