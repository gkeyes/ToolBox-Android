package io.toolbox.host

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.component.ToolBoxToastHost
import io.toolbox.core.ui.component.ToolBoxToasts
import io.toolbox.core.ui.theme.ToolBoxTheme
import io.toolbox.core.ui.theme.ToolBoxThemeMode
import io.toolbox.core.ui.theme.ToolBoxThemeStyle
import io.toolbox.host.browser.BrowserAppearance
import io.toolbox.host.catalog.CatalogAction
import io.toolbox.host.catalog.CatalogViewModel
import io.toolbox.host.importflow.ImportViewModel
import io.toolbox.host.navigation.ToolBoxNavigation
import io.toolbox.host.runtime.ForegroundCapabilityBroker
import io.toolbox.host.runtime.RuntimeSessionManager
import io.toolbox.host.settings.SettingsUiState
import io.toolbox.host.settings.SettingsViewModel
import io.toolbox.host.ui.HostBootstrapScreen
import io.toolbox.host.ui.LocalToolIconLoader
import io.toolbox.host.ui.applyHyperOsGestureNavigationImmersion
import io.toolbox.host.update.launchUpdateHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val shortcutIntent = MutableStateFlow<Intent?>(null)
    private var foregroundCapabilityBroker: ForegroundCapabilityBroker? = null
    private var runtimeSessions: RuntimeSessionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyHyperOsGestureNavigationImmersion()
        foregroundCapabilityBroker = ForegroundCapabilityBroker.attach(this)
        // A recreated activity (rotation, theme switch, resize) or a launch from Recents still
        // carries the intent that first opened it; replaying it would reopen that tool. A restore
        // only picks it up again when it was still waiting to be handled (e.g. during startup).
        if (shouldOpenLaunchIntent(
                restored = savedInstanceState != null,
                pendingWhenSaved = savedInstanceState?.getBoolean(STATE_LAUNCH_INTENT_PENDING) == true,
                intentFlags = intent.flags,
            )
        ) {
            shortcutIntent.value = intent
        }

        val dependenciesViewModel = ViewModelProvider(this)[HostDependenciesViewModel::class.java]
        setContent {
            val bootstrapState by dependenciesViewModel.state.collectAsStateWithLifecycle()
            when (val state = bootstrapState) {
                HostBootstrapState.Loading -> ToolBoxTheme(mode = ToolBoxThemeMode.System) {
                    ApplySystemBarAppearance(ToolBoxThemeMode.System)
                    HostBootstrapScreen(
                        loading = true,
                        message = "正在打开本机工具目录。",
                        onRetry = dependenciesViewModel::retry,
                    )
                }
                is HostBootstrapState.Error -> ToolBoxTheme(mode = ToolBoxThemeMode.System) {
                    ApplySystemBarAppearance(ToolBoxThemeMode.System)
                    HostBootstrapScreen(
                        loading = false,
                        message = state.message,
                        onRetry = dependenciesViewModel::retry,
                    )
                }
                is HostBootstrapState.Ready -> {
                    LaunchedEffect(state.dependencies) {
                        runtimeSessions = state.dependencies.runtimeSessions
                        runtimeSessions?.setHostActivityResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        withFrameNanos { }
                        dependenciesViewModel.onHostFirstFrame()
                        state.dependencies.runtimeSessions.recover("process")
                    }
                    LaunchedEffect(state.dependencies) {
                        state.dependencies.startupNotice.collect { notice ->
                            if (notice != null) {
                                ToolBoxToasts.show(this@MainActivity, notice, long = true)
                                state.dependencies.startupNotice.value = null
                            }
                        }
                    }
                    LaunchedEffect(state.dependencies) {
                        // Let the first frames and startup maintenance go first; the check itself runs off the main thread.
                        kotlinx.coroutines.delay(3_000)
                        state.dependencies.updates.launchUpdateHint(state.dependencies.repositories.catalog)
                            ?.let { ToolBoxToasts.show(this@MainActivity, it, long = true) }
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
                    val settingsState by remember(settingsViewModel) {
                        settingsViewModel.state.rootSettings()
                    }.collectAsStateWithLifecycle(initialValue = SettingsUiState().rootSettings())
                    val pendingShortcutIntent by shortcutIntent.collectAsStateWithLifecycle()
                    if (!settingsState.loaded) {
                        ToolBoxTheme(mode = ToolBoxThemeMode.System) {
                            ApplySystemBarAppearance(ToolBoxThemeMode.System)
                            HostBootstrapScreen(
                                loading = true,
                                message = "正在读取外观设置。",
                                onRetry = dependenciesViewModel::retry,
                            )
                        }
                        return@setContent
                    }
                    val catalogReady by remember(catalogViewModel) {
                        catalogViewModel.state.catalogReadiness()
                    }.collectAsStateWithLifecycle(initialValue = false)
                    // Report usable host content, not the earlier bootstrap placeholder.
                    ReportDrawnWhen { catalogReady && !settingsState.hasError }
                    val themeMode = settingsState.theme.toToolBoxThemeMode()
                    // A legacy stored Miuix style still renders the single iOS look.
                    val themeStyle = ToolBoxThemeStyle.LiquidGlass
                    LaunchedEffect(themeMode) { applyLaunchNightMode(themeMode) }
                    SideEffect {
                        BrowserAppearance.current = BrowserAppearance(
                            themeMode = themeMode,
                            reduceTransparency = settingsState.reduceTransparency,
                            themeStyle = themeStyle,
                        )
                    }
                    ToolBoxTheme(
                        mode = themeMode,
                        style = themeStyle,
                        reduceTransparency = settingsState.reduceTransparency,
                    ) {
                        ApplySystemBarAppearance(themeMode)
                        Box(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalToolIconLoader provides state.dependencies.toolIcons) {
                                ToolBoxNavigation(
                                    dependencies = state.dependencies,
                                    viewModelStoreOwner = this@MainActivity,
                                    catalogViewModel = catalogViewModel,
                                    importViewModel = importViewModel,
                                    settingsViewModel = settingsViewModel,
                                    contentResolver = contentResolver,
                                )
                            }
                            ToolBoxToastHost(Modifier.align(Alignment.BottomCenter))
                        }
                        LaunchedEffect(pendingShortcutIntent) {
                            val launchIntent = pendingShortcutIntent ?: return@LaunchedEffect
                            val shortcutToolId = withContext(Dispatchers.IO) {
                                resolveLaunchToolId(this@MainActivity, launchIntent)
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

    override fun onResume() {
        super.onResume()
        runtimeSessions?.setHostActivityResumed(true)
        applyHyperOsGestureNavigationImmersion()
    }

    override fun onPause() {
        runtimeSessions?.setHostActivityResumed(false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LAUNCH_INTENT_PENDING, shortcutIntent.value != null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shortcutIntent.value = intent
    }

    override fun onDestroy() {
        runtimeSessions?.setHostActivityResumed(false)
        runtimeSessions = null
        // A rotation or theme switch keeps an open picker or camera waiting for the next instance.
        foregroundCapabilityBroker?.close(keepPendingRequests = isChangingConfigurations)
        foregroundCapabilityBroker = null
        super.onDestroy()
    }

    companion object {
        private const val STATE_LAUNCH_INTENT_PENDING = "io.toolbox.host.launchIntentPending"

        /** For the host's own notifications and alarms; goes through the non-exported [ToolLaunchActivity]. */
        internal fun openToolIntent(context: Context, toolId: String, notificationId: String? = null): Intent =
            ToolLaunchActivity.openToolIntent(context, toolId, notificationId)
    }

    @Composable
    internal fun ApplySystemBarAppearance(mode: ToolBoxThemeMode) {
        val usesDarkIcons = when (mode) {
            ToolBoxThemeMode.Dark, ToolBoxThemeMode.MonetDark -> false
            ToolBoxThemeMode.System, ToolBoxThemeMode.MonetSystem -> !isSystemInDarkTheme()
            ToolBoxThemeMode.Light, ToolBoxThemeMode.MonetLight -> true
        }
        // Reapply on a new window or icon polarity, not unrelated recompositions.
        // Each bootstrap/ready composition entry still initializes its own appearance.
        LaunchedEffect(window, usesDarkIcons) {
            val transparent = android.graphics.Color.TRANSPARENT
            val style = if (usesDarkIcons) {
                SystemBarStyle.light(transparent, transparent)
            } else {
                SystemBarStyle.dark(transparent)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            applyHyperOsGestureNavigationImmersion()
        }
    }
}


/**
 * Tell the system which day/night theme this app uses, so the window that shows before Compose
 * draws (the starting window, the browser's first frame) already has the user's forced Light or
 * Dark background instead of the device's. Available since Android 12, below minSdk.
 */
private fun ComponentActivity.applyLaunchNightMode(mode: ToolBoxThemeMode) {
    val nightMode = when (mode) {
        ToolBoxThemeMode.Dark, ToolBoxThemeMode.MonetDark -> android.app.UiModeManager.MODE_NIGHT_YES
        ToolBoxThemeMode.Light, ToolBoxThemeMode.MonetLight -> android.app.UiModeManager.MODE_NIGHT_NO
        ToolBoxThemeMode.System, ToolBoxThemeMode.MonetSystem -> android.app.UiModeManager.MODE_NIGHT_AUTO
    }
    getSystemService(android.app.UiModeManager::class.java)?.setApplicationNightMode(nightMode)
}

/**
 * Whether onCreate should act on its launch intent: a fresh launch that isn't a Recents relaunch,
 * or a restore whose previous instance had not handled the intent yet.
 */
internal fun shouldOpenLaunchIntent(restored: Boolean, pendingWhenSaved: Boolean, intentFlags: Int): Boolean =
    if (restored) pendingWhenSaved else (intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0

/**
 * The tool a launch intent asks for. MainActivity is exported, so it only trusts a pinned shortcut's
 * token or a one-time token from [ToolLaunchActivity]; a bare tool id from another app opens nothing.
 */
internal fun resolveLaunchToolId(context: Context, intent: Intent): String? =
    ToolLaunchActivity.consumeLaunchToken(intent)
        ?: ForegroundCapabilityBroker.resolveShortcutToolId(context, intent)

private fun ThemeMode.toToolBoxThemeMode(): ToolBoxThemeMode = when (this) {
    ThemeMode.SYSTEM -> ToolBoxThemeMode.System
    ThemeMode.LIGHT -> ToolBoxThemeMode.Light
    ThemeMode.DARK -> ToolBoxThemeMode.Dark
    ThemeMode.MONET_SYSTEM -> ToolBoxThemeMode.MonetSystem
    ThemeMode.MONET_LIGHT -> ToolBoxThemeMode.MonetLight
    ThemeMode.MONET_DARK -> ToolBoxThemeMode.MonetDark
}
