package io.toolbox.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
    val surfaceMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val onDanger: Color,
    val divider: Color,
    val softPrimary: Color,
    val softSuccess: Color,
    val onSoftSuccess: Color,
    val softWarning: Color,
    val softDanger: Color,
    val onSoftDanger: Color,
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
    val control: Dp = 12.dp,
    val badge: Dp = 12.dp,
    val denseSurface: Dp = 16.dp,
    val card: Dp = 16.dp,
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
    val detailContentMaxWidth: Dp = 720.dp,
)

internal val LightToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF1264CC),
    onPrimary = Color.White,
    background = Color(0xFFF7F7F7),
    surface = Color.White,
    surfaceMuted = Color(0xFFF0F0F2),
    textPrimary = Color(0xFF17181A),
    textSecondary = Color(0xFF676A70),
    success = Color(0xFF24A865),
    warning = Color(0xFFD48718),
    danger = Color(0xFFBA252C),
    onDanger = Color.White,
    divider = Color(0xFFEDEDEF),
    softPrimary = Color(0xFFEAF3FF),
    softSuccess = Color(0xFFEAF8F0),
    onSoftSuccess = Color(0xFF197B48),
    softWarning = Color(0xFFFFF4E2),
    softDanger = Color(0xFFFFEEEE),
    onSoftDanger = Color(0xFFB3261E),
)

internal val DarkToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF4A9BFF),
    onPrimary = Color(0xFF10243A),
    background = Color(0xFF111214),
    surface = Color(0xFF1C1D20),
    surfaceMuted = Color(0xFF2C2D31),
    textPrimary = Color(0xFFF3F4F6),
    textSecondary = Color(0xFFA2A4AA),
    success = Color(0xFF75E395),
    warning = Color(0xFFFFC165),
    danger = Color(0xFFFF8B83),
    onDanger = Color(0xFF30100F),
    divider = Color(0xFF303136),
    softPrimary = Color(0xFF183A63),
    softSuccess = Color(0xFF173D27),
    onSoftSuccess = Color(0xFF75E395),
    softWarning = Color(0xFF493414),
    softDanger = Color(0xFF4B2524),
    onSoftDanger = Color(0xFFFFB4AB),
)

private val DefaultTextStyles = ToolBoxTextStyles(
    screenTitle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    sectionTitle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
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
    val baseColors = if (usesDarkColors) {
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
                primaryContainer = LightToolBoxColors.softPrimary,
                onPrimaryContainer = LightToolBoxColors.primary,
                background = LightToolBoxColors.background,
                onBackground = LightToolBoxColors.textPrimary,
                surface = LightToolBoxColors.surface,
                onSurface = LightToolBoxColors.textPrimary,
                surfaceVariant = LightToolBoxColors.surfaceMuted,
                onSurfaceSecondary = LightToolBoxColors.textSecondary,
                onSurfaceVariantSummary = LightToolBoxColors.textSecondary,
                onSurfaceVariantActions = LightToolBoxColors.textSecondary,
                surfaceContainer = LightToolBoxColors.surface,
                surfaceContainerHigh = LightToolBoxColors.surfaceMuted,
                dividerLine = LightToolBoxColors.divider,
                errorContainer = LightToolBoxColors.softDanger,
                onErrorContainer = LightToolBoxColors.onSoftDanger,
                error = LightToolBoxColors.danger,
            ),
            darkColors = darkColorScheme(
                primary = DarkToolBoxColors.primary,
                onPrimary = DarkToolBoxColors.onPrimary,
                primaryContainer = DarkToolBoxColors.softPrimary,
                onPrimaryContainer = DarkToolBoxColors.primary,
                background = DarkToolBoxColors.background,
                onBackground = DarkToolBoxColors.textPrimary,
                surface = DarkToolBoxColors.surface,
                onSurface = DarkToolBoxColors.textPrimary,
                surfaceVariant = DarkToolBoxColors.surfaceMuted,
                onSurfaceSecondary = DarkToolBoxColors.textSecondary,
                onSurfaceVariantSummary = DarkToolBoxColors.textSecondary,
                onSurfaceVariantActions = DarkToolBoxColors.textSecondary,
                surfaceContainer = DarkToolBoxColors.surface,
                onSurfaceContainer = DarkToolBoxColors.textPrimary,
                surfaceContainerHigh = DarkToolBoxColors.surfaceMuted,
                dividerLine = DarkToolBoxColors.divider,
                errorContainer = DarkToolBoxColors.softDanger,
                onErrorContainer = DarkToolBoxColors.onSoftDanger,
                error = DarkToolBoxColors.danger,
            ),
            keyColor = LightToolBoxColors.primary,
        )
    }

    MiuixTheme(controller = controller) {
        val miuixColors = MiuixTheme.colorScheme
        val colors = baseColors.copy(
            primary = miuixColors.primary,
            onPrimary = readableForeground(miuixColors.onPrimary, listOf(miuixColors.primary)),
            background = miuixColors.background,
            surface = miuixColors.surface,
            surfaceMuted = miuixColors.surfaceVariant,
            textPrimary = miuixColors.onBackground,
            textSecondary = readableForeground(
                miuixColors.onSurfaceVariantSummary,
                listOf(miuixColors.background, miuixColors.surface, miuixColors.surfaceVariant),
            ),
            danger = miuixColors.error,
            onDanger = readableForeground(baseColors.onDanger, listOf(miuixColors.error)),
            divider = miuixColors.dividerLine,
            softPrimary = miuixColors.primaryContainer,
            softDanger = miuixColors.errorContainer,
            onSoftDanger = miuixColors.onErrorContainer,
        )
        CompositionLocalProvider(
            LocalToolBoxColors provides colors,
            LocalToolBoxTextStyles provides DefaultTextStyles,
            content = content,
        )
    }
}

internal fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

/** Preserve the palette when readable, including Monet; otherwise use the safer neutral. */
internal fun readableForeground(preferred: Color, backgrounds: List<Color>): Color {
    require(backgrounds.isNotEmpty())
    fun minimumContrast(color: Color) = backgrounds.minOf { contrastRatio(color, it) }
    if (minimumContrast(preferred) >= 4.5f) return preferred
    return if (minimumContrast(Color.Black) >= minimumContrast(Color.White)) Color.Black else Color.White
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
