package io.toolbox.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

enum class ToolBoxThemeMode {
    System,
    Light,
    Dark,
    MonetSystem,
    MonetLight,
    MonetDark,
}

@Immutable
data class ToolBoxColorScheme(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val divider: Color,
    val softPrimary: Color,
    val softSuccess: Color,
    val softWarning: Color,
    val softDanger: Color,
)

@Immutable
data class ToolBoxTextStyles(
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    val body: TextStyle,
    val metadata: TextStyle,
    val label: TextStyle,
)

private val LightToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    background = Color(0xFFF3F6FB),
    surface = Color.White,
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF737B8C),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9500),
    danger = Color(0xFFFF3B30),
    divider = Color(0xFFE8EDF5),
    softPrimary = Color(0xFFEEF5FF),
    softSuccess = Color(0xFFEAF9EF),
    softWarning = Color(0xFFFFF4DE),
    softDanger = Color(0xFFFFECEA),
)

private val DarkToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF9DC2FF),
    onPrimary = Color(0xFF00315F),
    background = Color(0xFF10141B),
    surface = Color(0xFF1A1F29),
    textPrimary = Color(0xFFE8EDF5),
    textSecondary = Color(0xFFB3BBCB),
    success = Color(0xFF75E395),
    warning = Color(0xFFFFC165),
    danger = Color(0xFFFF8B83),
    divider = Color(0xFF303844),
    softPrimary = Color(0xFF1B365A),
    softSuccess = Color(0xFF173D27),
    softWarning = Color(0xFF493414),
    softDanger = Color(0xFF4B2524),
)

private val DefaultTextStyles = ToolBoxTextStyles(
    screenTitle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    sectionTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 16.sp),
    metadata = TextStyle(fontSize = 13.sp),
    label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val LocalToolBoxColors = staticCompositionLocalOf { LightToolBoxColors }
private val LocalToolBoxTextStyles = staticCompositionLocalOf { DefaultTextStyles }

@Composable
fun ToolBoxTheme(
    mode: ToolBoxThemeMode = ToolBoxThemeMode.Light,
    content: @Composable () -> Unit,
) {
    val usesDarkColors = when (mode) {
        ToolBoxThemeMode.Dark, ToolBoxThemeMode.MonetDark -> true
        ToolBoxThemeMode.System, ToolBoxThemeMode.MonetSystem -> isSystemInDarkTheme()
        ToolBoxThemeMode.Light, ToolBoxThemeMode.MonetLight -> false
    }
    val colors = if (usesDarkColors) {
        DarkToolBoxColors
    } else {
        LightToolBoxColors
    }
    val controller = remember(mode) {
        ThemeController(
            colorSchemeMode = mode.toMiuixMode(),
            lightColors = lightColorScheme(
                primary = LightToolBoxColors.primary,
                onPrimary = LightToolBoxColors.onPrimary,
                background = LightToolBoxColors.background,
                onBackground = LightToolBoxColors.textPrimary,
                surface = LightToolBoxColors.surface,
                onSurface = LightToolBoxColors.textPrimary,
                surfaceVariant = LightToolBoxColors.surface,
                onSurfaceSecondary = LightToolBoxColors.textSecondary,
                onSurfaceVariantSummary = LightToolBoxColors.textSecondary,
                onSurfaceVariantActions = LightToolBoxColors.textSecondary,
                dividerLine = LightToolBoxColors.divider,
                error = LightToolBoxColors.danger,
            ),
            darkColors = darkColorScheme(),
            keyColor = LightToolBoxColors.primary,
        )
    }

    MiuixTheme(controller = controller) {
        CompositionLocalProvider(
            LocalToolBoxColors provides colors,
            LocalToolBoxTextStyles provides DefaultTextStyles,
            content = content,
        )
    }
}

object ToolBoxThemeTokens {
    val colors: ToolBoxColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalToolBoxColors.current

    val textStyles: ToolBoxTextStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalToolBoxTextStyles.current
}

private fun ToolBoxThemeMode.toMiuixMode(): ColorSchemeMode = when (this) {
    ToolBoxThemeMode.System -> ColorSchemeMode.System
    ToolBoxThemeMode.Light -> ColorSchemeMode.Light
    ToolBoxThemeMode.Dark -> ColorSchemeMode.Dark
    ToolBoxThemeMode.MonetSystem -> ColorSchemeMode.MonetSystem
    ToolBoxThemeMode.MonetLight -> ColorSchemeMode.MonetLight
    ToolBoxThemeMode.MonetDark -> ColorSchemeMode.MonetDark
}
