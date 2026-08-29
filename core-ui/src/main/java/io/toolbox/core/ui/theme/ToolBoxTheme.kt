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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    val title: TextStyle,
    val body: TextStyle,
    val metadata: TextStyle,
    val label: TextStyle,
)

@Immutable
data class ToolBoxSpacing(
    val micro: Dp = 2.dp,
    val tight: Dp = 3.dp,
    val half: Dp = 4.dp,
    val compact: Dp = 6.dp,
    val one: Dp = 8.dp,
    val row: Dp = 10.dp,
    val oneHalf: Dp = 12.dp,
    val card: Dp = 14.dp,
    val two: Dp = 16.dp,
    val twoHalf: Dp = 20.dp,
    val three: Dp = 24.dp,
)

@Immutable
data class ToolBoxRadii(
    val badge: Dp = 12.dp,
    val denseSurface: Dp = 16.dp,
    val card: Dp = 18.dp,
    val full: Dp = 999.dp,
)

@Immutable
data class ToolBoxSizes(
    val divider: Dp = 1.dp,
    val badgeIcon: Dp = 14.dp,
    val rowIcon: Dp = 18.dp,
    val touchTarget: Dp = 48.dp,
    val compactChrome: Dp = 56.dp,
    val runtimeChrome: Dp = 52.dp,
    val denseRow: Dp = 56.dp,
    val catalogRow: Dp = 72.dp,
    val compactToolGlyph: Dp = 44.dp,
    val toolGlyph: Dp = 48.dp,
    val factLabelWidth: Dp = 72.dp,
    val mediumNavigationItemWidth: Dp = 80.dp,
    val mediumNavigationWidth: Dp = 96.dp,
    val contentMaxWidth: Dp = 1040.dp,
)

private val LightToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF8E8E93),
    success = Color(0xFF34C759),
    warning = Color(0xFFFF9500),
    danger = Color(0xFFFF3B30),
    divider = Color(0xFFE5E5EA),
    softPrimary = Color(0xFFE9F2FF),
    softSuccess = Color(0xFFEAF9EF),
    softWarning = Color(0xFFFFF4DE),
    softDanger = Color(0xFFFFECEA),
)

private val DarkToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF98989D),
    success = Color(0xFF75E395),
    warning = Color(0xFFFFC165),
    danger = Color(0xFFFF8B83),
    divider = Color(0xFF38383A),
    softPrimary = Color(0xFF1B365A),
    softSuccess = Color(0xFF173D27),
    softWarning = Color(0xFF493414),
    softDanger = Color(0xFF4B2524),
)

private val DefaultTextStyles = ToolBoxTextStyles(
    screenTitle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    sectionTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    title = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    metadata = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
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
            darkColors = darkColorScheme(
                primary = DarkToolBoxColors.primary,
                onPrimary = DarkToolBoxColors.onPrimary,
                background = DarkToolBoxColors.background,
                onBackground = DarkToolBoxColors.textPrimary,
                surface = DarkToolBoxColors.surface,
                onSurface = DarkToolBoxColors.textPrimary,
                surfaceVariant = DarkToolBoxColors.surface,
                onSurfaceSecondary = DarkToolBoxColors.textSecondary,
                onSurfaceVariantSummary = DarkToolBoxColors.textSecondary,
                onSurfaceVariantActions = DarkToolBoxColors.textSecondary,
                surfaceContainer = DarkToolBoxColors.surface,
                onSurfaceContainer = DarkToolBoxColors.textPrimary,
                dividerLine = DarkToolBoxColors.divider,
                error = DarkToolBoxColors.danger,
            ),
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
    val spacing = ToolBoxSpacing()
    val radii = ToolBoxRadii()
    val sizes = ToolBoxSizes()

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
