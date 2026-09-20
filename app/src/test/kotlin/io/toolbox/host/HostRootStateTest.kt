package io.toolbox.host

import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.ThemeMode
import io.toolbox.host.catalog.CatalogUiState
import io.toolbox.host.settings.SettingsUiState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HostRootStateTest {
    @Test fun searchAndLayoutChangesDoNotInvalidateTheRootButLoadingFailuresStillDo() = runTest {
        val ready = CatalogUiState(isLoaded = true)
        val failed = ready.copy(loadFailed = true)
        val values = flowOf(
            CatalogUiState(), ready, ready.copy(query = "RSS", isSearching = true),
            ready.copy(layout = CatalogLayout(favorites = listOf("io.toolbox.rss"))),
            failed, failed.copy(query = ""), ready,
        ).catalogReadiness().toList()
        assertEquals(listOf(false, true, false, true), values)
    }

    @Test fun featureSettingsStayLocalWhileAppearanceAndBootstrapChangesReachTheRoot() = runTest {
        val ready = SettingsUiState(loaded = true)
        val dark = ready.copy(settings = ready.settings.copy(theme = ThemeMode.DARK))
        val failure = dark.copy(error = "Settings unavailable")
        val loading = failure.copy(loaded = false)
        val values = flowOf(
            ready,
            ready.copy(settings = ready.settings.copy(catalogLayout = CatalogLayout(favorites = listOf("io.toolbox.rss")))),
            ready.copy(backgroundWorking = true),
            ready.copy(backgroundError = "Stop failed"),
            ready.copy(settings = ready.settings.copy(backgroundEnabled = !ready.settings.backgroundEnabled)),
            dark, failure, failure.copy(error = "Retry failed"), loading,
        ).rootSettings().toList()
        assertEquals(listOf(ready, dark, failure, loading).map(SettingsUiState::rootSettings), values)
    }
}
