package io.toolbox.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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

enum class ToolBoxThemeStyle {
    Miuix,
    LiquidGlass,
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
    val onSoftWarning: Color,
    val softDanger: Color,
    val onSoftDanger: Color,
)

@Immutable
data class ToolBoxTextStyles(
    val screenTitle: TextStyle,
    val detailTitle: TextStyle,
    val sectionTitle: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val metadata: TextStyle,
    val label: TextStyle,
)

@Immutable
/** 4dp grid. [micro] is the only sub-grid value and is reserved for hairline offsets. */
data class ToolBoxSpacing(
    val micro: Dp = 2.dp,
    val tight: Dp = 4.dp,
    val half: Dp = 4.dp,
    val compact: Dp = 8.dp,
    val one: Dp = 8.dp,
    val row: Dp = 12.dp,
    val oneHalf: Dp = 12.dp,
    /** Inner padding of a card. */
    val card: Dp = 16.dp,
    val two: Dp = 16.dp,
    /** Page side gutter. */
    val page: Dp = 16.dp,
    /** Gap between stacked cards / groups. */
    val stack: Dp = 12.dp,
    val twoHalf: Dp = 20.dp,
    val three: Dp = 24.dp,
    /** Gap above a section header. */
    val section: Dp = 24.dp,
)

@Immutable
/**
 * Corner radii. Large cards 20–24dp, list items / buttons 14–16dp, small elements 10dp.
 * Shapes are built with [io.toolbox.core.ui.component.toolBoxShape], which uses Miuix's
 * continuous-curvature (G2) corners so large radii do not look "pinched".
 */
data class ToolBoxRadii(
    val control: Dp = 14.dp,
    val badge: Dp = 10.dp,
    val denseSurface: Dp = 16.dp,
    val button: Dp = 16.dp,
    val card: Dp = 20.dp,
    val sheet: Dp = 24.dp,
    val dialog: Dp = 24.dp,
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
    val catalogRow: Dp = 64.dp,
    val compactToolGlyph: Dp = 42.dp,
    val toolGlyph: Dp = 48.dp,
    val factLabelWidth: Dp = 72.dp,
    val mediumNavigationItemWidth: Dp = 80.dp,
    val mediumNavigationWidth: Dp = 96.dp,
    val contentMaxWidth: Dp = 1040.dp,
    val detailContentMaxWidth: Dp = 720.dp,
)

@Immutable
data class ToolBoxMaterialTokens(
    val realBlurEnabled: Boolean,
    val topEdgeFadeEnabled: Boolean,
    val navigationLensEnabled: Boolean,
    val pressFeedbackEnabled: Boolean,
    val glassTint: Color,
    val glassFallback: Color,
    val glassBorder: Color,
    val navigationSelectionTint: Color,
    val navigationSelectionHighlight: Color,
    val navigationSelectionBorder: Color,
    val blurRadius: Dp,
    val noiseFactor: Float,
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
    onSoftWarning = Color(0xFF8A4B00),
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
    onSoftWarning = Color(0xFFFFD08A),
    softDanger = Color(0xFF4B2524),
    onSoftDanger = Color(0xFFFFB4AB),
)

/** Digits keep a fixed advance so counters, sizes and versions do not jitter as they change. */
private const val TabularNumbers = "tnum"

private val DefaultTextStyles = ToolBoxTextStyles(
    screenTitle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    detailTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    sectionTitle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    title = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = TabularNumbers),
    metadata = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TabularNumbers),
    label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TabularNumbers),
)

private val LiquidGlassTextStyles = ToolBoxTextStyles(
    screenTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    detailTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    sectionTitle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    title = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TabularNumbers),
    metadata = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TabularNumbers),
    label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TabularNumbers),
)

private val MiuixRadii = ToolBoxRadii()
private val LiquidGlassRadii = ToolBoxRadii(
    control = 17.dp,
    badge = 12.dp,
    denseSurface = 20.dp,
    button = 22.dp,
    card = 24.dp,
    sheet = 28.dp,
    dialog = 28.dp,
)

private val LocalToolBoxColors = staticCompositionLocalOf { LightToolBoxColors }
private val LocalToolBoxTextStyles = staticCompositionLocalOf { DefaultTextStyles }
private val LocalToolBoxThemeStyle = staticCompositionLocalOf { ToolBoxThemeStyle.Miuix }
private val LocalToolBoxRadii = staticCompositionLocalOf { MiuixRadii }
private val LocalToolBoxMaterials = staticCompositionLocalOf {
    ToolBoxMaterialTokens(
        realBlurEnabled = false,
        topEdgeFadeEnabled = false,
        navigationLensEnabled = false,
        pressFeedbackEnabled = false,
        glassTint = Color.White,
        glassFallback = Color.White,
        glassBorder = Color.Transparent,
        navigationSelectionTint = Color.Transparent,
        navigationSelectionHighlight = Color.Transparent,
        navigationSelectionBorder = Color.Transparent,
        blurRadius = 24.dp,
        noiseFactor = 0f,
    )
}

@Composable
fun ToolBoxTheme(
    mode: ToolBoxThemeMode = ToolBoxThemeMode.Light,
    style: ToolBoxThemeStyle = ToolBoxThemeStyle.LiquidGlass,
    reduceTransparency: Boolean = false,
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

    val accentColors = controller.currentColors()
    val renderedController = run {
        val liquidLight = liquidGlassColors(
            dark = false,
            accent = accentColors.primary,
            accentForeground = accentColors.onPrimary,
        )
        val liquidDark = liquidGlassColors(
            dark = true,
            accent = accentColors.primary,
            accentForeground = accentColors.onPrimary,
        )
        remember(usesDarkColors, accentColors.primary, accentColors.onPrimary) {
            ThemeController(
                colorSchemeMode = if (usesDarkColors) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                lightColors = lightColorScheme(
                    primary = liquidLight.primary,
                    onPrimary = liquidLight.onPrimary,
                    primaryContainer = liquidLight.softPrimary,
                    onPrimaryContainer = liquidLight.primary,
                    background = liquidLight.background,
                    onBackground = liquidLight.textPrimary,
                    surface = liquidLight.surface,
                    onSurface = liquidLight.textPrimary,
                    surfaceVariant = liquidLight.surfaceMuted,
                    onSurfaceSecondary = liquidLight.textSecondary,
                    onSurfaceVariantSummary = liquidLight.textSecondary,
                    onSurfaceVariantActions = liquidLight.textSecondary,
                    surfaceContainer = liquidLight.surface,
                    surfaceContainerHigh = liquidLight.surfaceMuted,
                    dividerLine = liquidLight.divider,
                    errorContainer = liquidLight.softDanger,
                    onErrorContainer = liquidLight.onSoftDanger,
                    error = liquidLight.danger,
                ),
                darkColors = darkColorScheme(
                    primary = liquidDark.primary,
                    onPrimary = liquidDark.onPrimary,
                    primaryContainer = liquidDark.softPrimary,
                    onPrimaryContainer = liquidDark.primary,
                    background = liquidDark.background,
                    onBackground = liquidDark.textPrimary,
                    surface = liquidDark.surface,
                    onSurface = liquidDark.textPrimary,
                    surfaceVariant = liquidDark.surfaceMuted,
                    onSurfaceSecondary = liquidDark.textSecondary,
                    onSurfaceVariantSummary = liquidDark.textSecondary,
                    onSurfaceVariantActions = liquidDark.textSecondary,
                    surfaceContainer = liquidDark.surface,
                    onSurfaceContainer = liquidDark.textPrimary,
                    surfaceContainerHigh = liquidDark.surfaceMuted,
                    dividerLine = liquidDark.divider,
                    errorContainer = liquidDark.softDanger,
                    onErrorContainer = liquidDark.onSoftDanger,
                    error = liquidDark.danger,
                ),
                keyColor = accentColors.primary,
            )
        }
    }

    MiuixTheme(controller = if (style == ToolBoxThemeStyle.Miuix) controller else renderedController) {
        val miuixColors = MiuixTheme.colorScheme
        val miuixSemanticColors = baseColors.copy(
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
        val colors = if (style == ToolBoxThemeStyle.Miuix) miuixSemanticColors else run {
            liquidGlassColors(
                dark = usesDarkColors,
                accent = miuixSemanticColors.primary,
                accentForeground = miuixSemanticColors.onPrimary,
            )
        }
        val materials = liquidGlassMaterials(
            colors = colors,
            dark = usesDarkColors,
            enabled = style == ToolBoxThemeStyle.LiquidGlass,
            reduceTransparency = reduceTransparency,
        )
        CompositionLocalProvider(
            LocalToolBoxColors provides colors,
            LocalToolBoxTextStyles provides if (style == ToolBoxThemeStyle.Miuix) DefaultTextStyles else LiquidGlassTextStyles,
            LocalToolBoxThemeStyle provides style,
            LocalToolBoxRadii provides if (style == ToolBoxThemeStyle.Miuix) MiuixRadii else LiquidGlassRadii,
            LocalToolBoxMaterials provides materials,
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
    val sizes = ToolBoxSizes()

    val disabledContent: Color
        @Composable
        @ReadOnlyComposable
        get() = MiuixTheme.colorScheme.disabledOnSurface

    val style: ToolBoxThemeStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalToolBoxThemeStyle.current

    val radii: ToolBoxRadii
        @Composable
        @ReadOnlyComposable
        get() = LocalToolBoxRadii.current

    val materials: ToolBoxMaterialTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalToolBoxMaterials.current

    val colors: ToolBoxColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalToolBoxColors.current

    val textStyles: ToolBoxTextStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalToolBoxTextStyles.current
}

internal fun liquidGlassColors(
    dark: Boolean,
    accent: Color,
    accentForeground: Color,
): ToolBoxColorScheme {
    val base = if (dark) DarkToolBoxColors else LightToolBoxColors
    val background = if (dark) Color.Black else Color(0xFFF2F2F7)
    val surface = if (dark) Color(0xFF1C1C1E) else Color.White
    val surfaceMuted = if (dark) Color(0xFF2C2C2E) else Color(0xFFE9E9EE)
    val textPrimary = if (dark) Color(0xFFF5F5F7) else Color(0xFF1C1C1E)
    val textSecondary = if (dark) Color(0xFFAEAEB2) else Color(0xFF5F5F64)
    return base.copy(
        primary = accent,
        onPrimary = readableForeground(accentForeground, listOf(accent)),
        background = background,
        surface = surface,
        surfaceMuted = surfaceMuted,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        divider = if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.12f),
        softPrimary = accent.copy(alpha = if (dark) 0.22f else 0.12f).compositeOver(surface),
    )
}

internal fun liquidGlassMaterials(
    colors: ToolBoxColorScheme,
    dark: Boolean,
    enabled: Boolean,
    reduceTransparency: Boolean,
) = ToolBoxMaterialTokens(
    realBlurEnabled = enabled && !reduceTransparency,
    topEdgeFadeEnabled = enabled && !reduceTransparency,
    navigationLensEnabled = enabled,
    pressFeedbackEnabled = enabled,
    glassTint = colors.surface.copy(alpha = if (reduceTransparency) 1f else if (dark) 0.66f else 0.48f),
    glassFallback = colors.surface,
    glassBorder = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.72f),
    navigationSelectionTint = colors.softPrimary,
    navigationSelectionHighlight = colors.softPrimary,
    navigationSelectionBorder = colors.primary.copy(alpha = if (dark) 0.38f else 0.24f),
    blurRadius = 32.dp,
    noiseFactor = 0.015f,
)

private fun ToolBoxThemeMode.toMiuixMode(): ColorSchemeMode = when (this) {
    ToolBoxThemeMode.System -> ColorSchemeMode.System
    ToolBoxThemeMode.Light -> ColorSchemeMode.Light
    ToolBoxThemeMode.Dark -> ColorSchemeMode.Dark
    ToolBoxThemeMode.MonetSystem -> ColorSchemeMode.MonetSystem
    ToolBoxThemeMode.MonetLight -> ColorSchemeMode.MonetLight
    ToolBoxThemeMode.MonetDark -> ColorSchemeMode.MonetDark
}
