package io.toolbox.host.runtime

import io.toolbox.core.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeThemeModeTest {
    @Test fun everyHostAppearanceModeResolvesConsistently() {
        listOf(false, true).forEach { system ->
            assertEquals(system, ThemeMode.SYSTEM.runtimeDarkTheme(system))
            assertEquals(system, ThemeMode.MONET_SYSTEM.runtimeDarkTheme(system))
            assertEquals(false, ThemeMode.LIGHT.runtimeDarkTheme(system))
            assertEquals(false, ThemeMode.MONET_LIGHT.runtimeDarkTheme(system))
            assertEquals(true, ThemeMode.DARK.runtimeDarkTheme(system))
            assertEquals(true, ThemeMode.MONET_DARK.runtimeDarkTheme(system))
        }
    }
}
