package io.toolbox.host

import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.navigation.ToolBoxNavigation
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.runtime.ForegroundCapabilityBroker
import io.toolbox.host.ui.HostBootstrapScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val shortcutIntent = MutableStateFlow<Intent?>(null)
    private var foregroundCapabilityBroker: ForegroundCapabilityBroker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        foregroundCapabilityBroker = ForegroundCapabilityBroker.attach(this)
        shortcutIntent.value = intent

        val dependenciesViewModel = ViewModelProvider(this)[HostDependenciesViewModel::class.java]
        setContent {
            val bootstrapState by dependenciesViewModel.state.collectAsStateWithLifecycle()
            when (val state = bootstrapState) {
                HostBootstrapState.Loading -> ToolBoxTheme {
                    ApplySystemBarAppearance(ToolBoxThemeMode.Light)
                    HostBootstrapScreen(
                        loading = true,
                        message = "正在打开本机工具目录。",
                        onRetry = dependenciesViewModel::retry,
                    )
                }
                is HostBootstrapState.Error -> ToolBoxTheme {
                    ApplySystemBarAppearance(ToolBoxThemeMode.Light)
                    HostBootstrapScreen(
                        loading = false,
                        message = state.message,
                        onRetry = dependenciesViewModel::retry,
                    )
                }
                is HostBootstrapState.Ready -> {
                    LaunchedEffect(state.dependencies) {
                        withFrameNanos { }
                        dependenciesViewModel.onHostFirstFrame()
                        state.dependencies.runtimeSessions.recover("process")
                    }
                    val featureFactory = remember(state.dependencies) {
                        HostFeatureViewModelFactory(state.dependencies)
                    }
                    val catalogViewModel = remember(state.dependencies) {
                        ViewModelProvider(this, featureFactory)
                            .get("host.catalog", CatalogViewModel::class.java)
                    }
                    val importViewModel = remember(state.dependencies) {
                        ViewModelProvider(this, featureFactory)
                            .get("host.import", ImportViewModel::class.java)
                    }
                    val settingsViewModel = remember(state.dependencies) {
                        ViewModelProvider(this, featureFactory)
                            .get("host.settings", SettingsViewModel::class.java)
                    }
                    val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
                    val pendingShortcutIntent by shortcutIntent.collectAsStateWithLifecycle()
                    val themeMode = settingsState.settings.theme.toToolBoxThemeMode()
                    ToolBoxTheme(mode = themeMode) {
                        ApplySystemBarAppearance(themeMode)
                        ToolBoxNavigation(
                            dependencies = state.dependencies,
                            viewModelStoreOwner = this,
                            catalogViewModel = catalogViewModel,
                            importViewModel = importViewModel,
                            settingsViewModel = settingsViewModel,
                            contentResolver = contentResolver,
                        )
                        LaunchedEffect(pendingShortcutIntent) {
                            val launchIntent = pendingShortcutIntent ?: return@LaunchedEffect
                            val requestedToolId = launchIntent.takeIf { it.action == ACTION_OPEN_TOOL }
                                ?.getStringExtra(EXTRA_TOOL_ID)
                            val shortcutToolId = requestedToolId ?: withContext(Dispatchers.IO) {
                                ForegroundCapabilityBroker.resolveShortcutToolId(this@MainActivity, launchIntent)
                            }
                            shortcutIntent.value = null
                            if (shortcutToolId != null) {
                                withFrameNanos { }
                                catalogViewModel.dispatch(CatalogAction.RequestRuntimeLaunch(shortcutToolId))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shortcutIntent.value = intent
    }

    override fun onDestroy() {
        foregroundCapabilityBroker?.close()
        foregroundCapabilityBroker = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_OPEN_TOOL = "io.toolbox.host.OPEN_TOOL"
        private const val EXTRA_TOOL_ID = "toolId"

        internal fun openToolIntent(context: Context, toolId: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_TOOL)
                .putExtra(EXTRA_TOOL_ID, toolId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    @Composable
    private fun ApplySystemBarAppearance(mode: ToolBoxThemeMode) {
        val usesDarkIcons = when (mode) {
            ToolBoxThemeMode.Dark, ToolBoxThemeMode.MonetDark -> false
            ToolBoxThemeMode.System, ToolBoxThemeMode.MonetSystem -> !isSystemInDarkTheme()
            ToolBoxThemeMode.Light, ToolBoxThemeMode.MonetLight -> true
        }
        SideEffect {
            val transparent = android.graphics.Color.TRANSPARENT
            val style = if (usesDarkIcons) {
                SystemBarStyle.light(transparent, transparent)
            } else {
                SystemBarStyle.dark(transparent)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
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
