package io.toolbox.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.importflow.ImportReviewViewModel
import io.toolbox.host.navigation.ToolBoxNavigation
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.ui.HostBootstrapScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val dependenciesViewModel = ViewModelProvider(this)[HostDependenciesViewModel::class.java]
        setContent {
            val bootstrapState by dependenciesViewModel.state.collectAsStateWithLifecycle()
            when (val state = bootstrapState) {
                HostBootstrapState.Loading -> ToolBoxTheme {
                    HostBootstrapScreen(
                        loading = true,
                        message = "正在安全地读取数据库和私有包目录。",
                        onRetry = dependenciesViewModel::retry,
                    )
                }
                is HostBootstrapState.Error -> ToolBoxTheme {
                    HostBootstrapScreen(
                        loading = false,
                        message = "${state.message}（${state.code.name}）",
                        onRetry = dependenciesViewModel::retry,
                    )
                }
                is HostBootstrapState.Ready -> {
                    LaunchedEffect(state.dependencies) {
                        withFrameNanos { }
                        dependenciesViewModel.onHostFirstFrame()
                    }
                    val featureFactory = remember(state.dependencies) {
                        HostFeatureViewModelFactory(state.dependencies)
                    }
                    val catalogViewModel = remember(state.dependencies) {
                        ViewModelProvider(this, featureFactory)
                            .get("host.catalog", CatalogViewModel::class.java)
                    }
                    val importReviewViewModel = remember(state.dependencies) {
                        ViewModelProvider(this, featureFactory)
                            .get("host.import", ImportReviewViewModel::class.java)
                    }
                    val settingsViewModel = remember(state.dependencies) {
                        ViewModelProvider(this, featureFactory)
                            .get("host.settings", SettingsViewModel::class.java)
                    }
                    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                    ToolBoxTheme(mode = settingsState.settings.theme.toToolBoxThemeMode()) {
                        ToolBoxNavigation(
                            dependencies = state.dependencies,
                            viewModelStoreOwner = this,
                            catalogViewModel = catalogViewModel,
                            importReviewViewModel = importReviewViewModel,
                            settingsViewModel = settingsViewModel,
                            contentResolver = contentResolver,
                        )
                    }
                }
            }
        }
    }
}

private fun ThemeMode.toToolBoxThemeMode(): ToolBoxThemeMode = when (this) {
    ThemeMode.SYSTEM -> ToolBoxThemeMode.System
    ThemeMode.LIGHT -> ToolBoxThemeMode.Light
    ThemeMode.DARK -> ToolBoxThemeMode.Dark
    ThemeMode.MONET_SYSTEM -> ToolBoxThemeMode.MonetSystem
    ThemeMode.MONET_LIGHT -> ToolBoxThemeMode.MonetLight
    ThemeMode.MONET_DARK -> ToolBoxThemeMode.MonetDark
}
