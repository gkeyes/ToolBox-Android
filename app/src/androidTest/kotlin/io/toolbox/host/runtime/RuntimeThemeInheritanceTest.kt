package io.toolbox.host.runtime

import android.content.res.Configuration
import android.webkit.WebView
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the production installer, session manager and WebView. No screenshot or golden comparison. */
class RuntimeThemeInheritanceTest {
    @Test fun hostOverrideChangesMediaQueryWithoutReloadingTheDocument() = runBlocking(Dispatchers.IO) {
        val f = RuntimeThemeFixture()
        try {
            assertTrue(f.stores.repositories.settings.update { it.copy(theme = ThemeMode.DARK) } is DataResult.Success)
            f.install(1)
            val page = f.page()
            // Wait for the real visible document's first frame before measuring live
            // changes. evaluateJavascript alone also works in non-rendering WebViews.
            evaluateThemeView(page, "window.firstThemeFrame=false;requestAnimationFrame(function(){window.firstThemeFrame=true});true")
            withTimeout(10_000) {
                while (evaluateThemeView(page, "window.firstThemeFrame") != true) delay(50)
            }
            assertEquals(true, evaluateThemeView(page, "matchMedia('(prefers-color-scheme: dark)').matches"))
            evaluateThemeView(page, "window.themeSentinel = 'same-document'; window.themeEvents = 0; window.themeQuery = matchMedia('(prefers-color-scheme: dark)'); window.themeQuery.addEventListener('change', function(){window.themeEvents++});true")
            assertTrue(f.stores.repositories.settings.update { it.copy(theme = ThemeMode.LIGHT) } is DataResult.Success)
            awaitThemeEvent(page, dark = false, minimumEvents = 1)
            assertEquals("rgb(250, 250, 250)", evaluateThemeView(page, "getComputedStyle(document.body).backgroundColor"))
            assertEquals("same-document", evaluateThemeView(page, "window.themeSentinel"))
            assertTrue((evaluateThemeView(page, "window.themeEvents") as Number).toInt() > 0)
            assertSame(page, f.page())
            assertTrue(f.stores.repositories.settings.update { it.copy(theme = ThemeMode.MONET_DARK) } is DataResult.Success)
            awaitThemeEvent(page, dark = true, minimumEvents = 2)
            assertEquals("rgb(18, 18, 18)", evaluateThemeView(page, "getComputedStyle(document.body).backgroundColor"))
            assertEquals("same-document", evaluateThemeView(page, "window.themeSentinel"))
        } finally { f.close() }
    }

    private suspend fun awaitThemeEvent(page: WebView, dark: Boolean, minimumEvents: Int) {
        // Observe the delivered event first. Reading matches on every poll can flush
        // style before the renderer processes the queued MediaQueryList notification.
        val delivered = withTimeoutOrNull(10_000) {
            while (evaluateThemeView(page, "window.themeEvents >= $minimumEvents") != true) delay(50)
            true
        } == true
        val snapshot = evaluateThemeView(page, "JSON.stringify({events:window.themeEvents,existing:window.themeQuery.matches,fresh:matchMedia('(prefers-color-scheme: dark)').matches,css:getComputedStyle(document.body).backgroundColor,visibility:document.visibilityState,frame:window.firstThemeFrame})")
        val view = withContext(Dispatchers.Main) {
            "attached=${page.isAttachedToWindow},shown=${page.isShown},window=${page.windowVisibility},size=${page.width}x${page.height}"
        }
        assertTrue("Expected dark=$dark events>=$minimumEvents; $view; $snapshot", delivered)
        assertEquals(dark, evaluateThemeView(page, "window.themeQuery.matches"))
    }

    @Test fun systemModeTracksConfigurationButExplicitLightStaysLight() = runBlocking(Dispatchers.IO) {
        val f = RuntimeThemeFixture()
        try {
            f.install(1)
            val page = f.page()
            val darkConfig = Configuration(f.application.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            f.dependencies.runtimeSessions.onSystemConfigurationChanged(darkConfig)
            withTimeout(10_000) {
                while (evaluateThemeView(page, "matchMedia('(prefers-color-scheme: dark)').matches") != true) delay(50)
            }
            assertTrue(f.stores.repositories.settings.update { it.copy(theme = ThemeMode.MONET_LIGHT) } is DataResult.Success)
            withTimeout(10_000) {
                while (evaluateThemeView(page, "matchMedia('(prefers-color-scheme: dark)').matches") != false) delay(50)
            }
            f.dependencies.runtimeSessions.onSystemConfigurationChanged(darkConfig)
            assertEquals(false, evaluateThemeView(page, "matchMedia('(prefers-color-scheme: dark)').matches"))
        } finally { f.close() }
    }
}
