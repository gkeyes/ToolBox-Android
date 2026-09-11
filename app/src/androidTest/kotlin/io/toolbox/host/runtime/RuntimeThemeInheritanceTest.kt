package io.toolbox.host.runtime

import android.content.res.Configuration
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the production installer, session manager and WebView. No screenshot or golden comparison. */
class RuntimeThemeInheritanceTest {
    @Test fun hostOverrideChangesMediaQueryWithoutReloadingTheDocument() = runBlocking(Dispatchers.IO) {
        val f = UpgradeFixture()
        try {
            assertTrue(f.stores.repositories.settings.update { it.copy(theme = ThemeMode.DARK) } is DataResult.Success)
            f.install(1)
            val page = f.page()
            assertEquals(true, evaluate(page, "matchMedia('(prefers-color-scheme: dark)').matches"))
            evaluate(page, "window.themeSentinel = 'same-document'; window.themeEvents = 0; matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function(){window.themeEvents++});true")
            assertTrue(f.stores.repositories.settings.update { it.copy(theme = ThemeMode.LIGHT) } is DataResult.Success)
            withTimeout(10_000) {
                while (evaluate(page, "matchMedia('(prefers-color-scheme: dark)').matches") != false) delay(50)
            }
            assertEquals("same-document", evaluate(page, "window.themeSentinel"))
            assertTrue((evaluate(page, "window.themeEvents") as Number).toInt() > 0)
            assertSame(page, f.page())
            assertTrue(f.stores.repositories.settings.update { it.copy(theme = ThemeMode.MONET_DARK) } is DataResult.Success)
            withTimeout(10_000) {
                while (evaluate(page, "matchMedia('(prefers-color-scheme: dark)').matches") != true) delay(50)
            }
            assertEquals("same-document", evaluate(page, "window.themeSentinel"))
        } finally { f.close() }
    }

    @Test fun systemModeTracksConfigurationButExplicitLightStaysLight() = runBlocking(Dispatchers.IO) {
        val f = UpgradeFixture()
        try {
            f.install(1)
            val page = f.page()
            val darkConfig = Configuration(f.application.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            f.dependencies.runtimeSessions.onSystemConfigurationChanged(darkConfig)
            withTimeout(10_000) {
                while (evaluate(page, "matchMedia('(prefers-color-scheme: dark)').matches") != true) delay(50)
            }
            assertTrue(f.stores.repositories.settings.update { it.copy(theme = ThemeMode.MONET_LIGHT) } is DataResult.Success)
            withTimeout(10_000) {
                while (evaluate(page, "matchMedia('(prefers-color-scheme: dark)').matches") != false) delay(50)
            }
            f.dependencies.runtimeSessions.onSystemConfigurationChanged(darkConfig)
            assertEquals(false, evaluate(page, "matchMedia('(prefers-color-scheme: dark)').matches"))
        } finally { f.close() }
    }
}
