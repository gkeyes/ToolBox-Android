package io.toolbox.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.toolbox.core.ui.component.ToolBoxPressIndication
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
    /** Tertiary text: onSurface at 42%. */
    val textTertiary: Color,
    /** Sheets, pressed cards and other surfaces raised above a card. */
    val raised: Color,
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
    val softInfo: Color,
    val onSoftInfo: Color,
    val softNeutral: Color,
    val onSoftNeutral: Color,
    val scrim: Color,
)

@Immutable
data class ToolBoxTextStyles(
    /** largeTitle 30 / Bold. */
    val screenTitle: TextStyle,
    /** title 20 / SemiBold. */
    val detailTitle: TextStyle,
    /** sectionHeader 13 / Medium (secondary colour at the call site). */
    val sectionTitle: TextStyle,
    /** Row / list-item title, 16 / Medium. */
    val title: TextStyle,
    /** body 16 / Regular. */
    val body: TextStyle,
    /** subhead 14 / Regular. */
    val metadata: TextStyle,
    /** caption 12 / Regular. */
    val label: TextStyle,
    /** headline (card title) 17 / SemiBold. */
    val headline: TextStyle,
    /** Large title once collapsed into the bar: 17 / SemiBold. */
    val collapsedTitle: TextStyle,
)

@Immutable
/**
 * 4dp grid (design system v2): xxs 4, xs 8, sm 12, md 16, lg 24, xl 32.
 * The older names are kept as aliases so every call site lands on the same grid.
 */
data class ToolBoxSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val micro: Dp = 2.dp,
    val tight: Dp = 4.dp,
    val half: Dp = 4.dp,
    val compact: Dp = 8.dp,
    val one: Dp = 8.dp,
    val row: Dp = 12.dp,
    val oneHalf: Dp = 12.dp,
    /** Card inner padding (md). */
    val card: Dp = 16.dp,
    val two: Dp = 16.dp,
    /** Page side gutter (md). */
    val page: Dp = 16.dp,
    /** Gap between stacked cards / groups (sm). */
    val stack: Dp = 12.dp,
    val twoHalf: Dp = 20.dp,
    val three: Dp = 24.dp,
    /** Gap between sections (lg). */
    val section: Dp = 24.dp,
)

@Immutable
/**
 * Corner radii (iOS 27): card 26, capsule buttons, control 12, small 8, alert 34, sheet 34. Shapes use continuous-curvature corners via
 * [io.toolbox.core.ui.component.toolBoxShape].
 */
data class ToolBoxRadii(
    val control: Dp = 12.dp,
    val badge: Dp = 8.dp,
    val denseSurface: Dp = 16.dp,
    /** Buttons are capsules: the continuous shape clamps the radius to half the height. */
    val button: Dp = 999.dp,
    val card: Dp = 26.dp,
    val sheet: Dp = 34.dp,
    val dialog: Dp = 34.dp,
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
    /** Page and sheet capsule button height (iOS 26 large control). */
    val primaryButton: Dp = 50.dp,
    /** Collapsed top bar height below the status bar. */
    val topBar: Dp = 56.dp,
    /** Expanded large-title block height. */
    val largeTitle: Dp = 52.dp,
    val emptyStateIcon: Dp = 64.dp,
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
    /** Floating glass (tab bar, search circle, top round buttons) tint over the refracted page. */
    val glassTint: Color,
    val glassFallback: Color,
    val glassBorder: Color,
    /**
     * Overlay glass (sheets, alerts). [overlayFill] is the DualSim recipe for a surface that refracts
     * a live in-window backdrop; overlays are their own windows and cannot sample the app's
     * recorded layer, so they draw [overlayFallback], a denser translucent fill of the same tone.
     */
    val overlayFill: Color,
    val overlayFallback: Color,
    /** Context-menu glass; [menuFallback] for the popup window, as with [overlayFallback]. */
    val menuFill: Color,
    val menuFallback: Color,
    /** 0.5dp rim around overlay glass. */
    val overlayBorder: Color,
    /** Grouped cards placed inside an overlay sheet, translucent so the sheet material still reads. */
    val sheetCard: Color,
    val navigationSelectionTint: Color,
    val navigationSelectionHighlight: Color,
    val navigationSelectionBorder: Color,
    val blurRadius: Dp,
    val noiseFactor: Float,
)

// iOS 27 system palette. Accent and status colours are the accessible variants so that text
// in these colours keeps 4.5:1 on grouped surfaces.
internal val LightToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF0064D2),
    onPrimary = Color.White,
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    surfaceMuted = Color(0xFFEFEFF0),
    textPrimary = Color.Black,
    textSecondary = Color(0xFF6C6C70),
    textTertiary = Color(0x993C3C43),
    raised = Color.White,
    success = Color(0xFF248A3D),
    warning = Color(0xFFC93400),
    danger = Color(0xFFD70015),
    onDanger = Color.White,
    divider = Color(0x4A3C3C43),
    softPrimary = Color(0xFFE0ECFA),
    softSuccess = Color(0xFFE3F5E8),
    onSoftSuccess = Color(0xFF248A3D),
    softWarning = Color(0xFFFFF0DB),
    onSoftWarning = Color(0xFFC93400),
    softDanger = Color(0xFFFDE6E8),
    onSoftDanger = Color(0xFFD70015),
    softInfo = Color(0xFFE0ECFA),
    onSoftInfo = Color(0xFF0064D2),
    softNeutral = Color(0xFFEFEFF0),
    onSoftNeutral = Color(0xFF48484A),
    scrim = Color.Black.copy(alpha = 0.18f),
)

internal val DarkToolBoxColors = ToolBoxColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    surfaceMuted = Color(0xFF2C2C2E),
    textPrimary = Color.White,
    textSecondary = Color(0xFF98989F),
    textTertiary = Color(0x99EBEBF5),
    raised = Color(0xFF2C2C2E),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFFF6961),
    onDanger = Color(0xFF3B0A08),
    divider = Color(0x99545458),
    softPrimary = Color(0xFF0D2A4D),
    softSuccess = Color(0xFF0F3319),
    onSoftSuccess = Color(0xFF30D158),
    softWarning = Color(0xFF3A2606),
    onSoftWarning = Color(0xFFFF9F0A),
    softDanger = Color(0xFF3D1210),
    onSoftDanger = Color(0xFFFF6961),
    softInfo = Color(0xFF0D2A4D),
    onSoftInfo = Color(0xFF64B0FF),
    softNeutral = Color(0xFF2C2C2E),
    onSoftNeutral = Color(0xFFC7C7CC),
    scrim = Color.Black.copy(alpha = 0.42f),
)

/** Digits keep a fixed advance so counters, sizes and versions do not jitter as they change. */
private const val TabularNumbers = "tnum"

// iOS Dynamic Type at the default size: largeTitle 34, title2 22, headline 17, body 17,
// subheadline 15, footnote 13, caption 12.
private val DefaultTextStyles = ToolBoxTextStyles(
    screenTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.37.sp),
    detailTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.26).sp),
    sectionTitle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.08).sp, fontFeatureSettings = TabularNumbers),
    title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.43).sp, fontFeatureSettings = TabularNumbers),
    body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.43).sp, fontFeatureSettings = TabularNumbers),
    metadata = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.23).sp, fontFeatureSettings = TabularNumbers),
    label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TabularNumbers),
    headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.43).sp, fontFeatureSettings = TabularNumbers),
    collapsedTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.43).sp),
)

/** Design system v2 unifies metrics across both looks; Liquid Glass differs only in material. */
private val LiquidGlassTextStyles = DefaultTextStyles

private val MiuixRadii = ToolBoxRadii()
private val LiquidGlassRadii = ToolBoxRadii()

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
        overlayFill = Color.White,
        overlayFallback = Color.White,
        menuFill = Color.White,
        menuFallback = Color.White,
        overlayBorder = Color.Transparent,
        sheetCard = Color.White,
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
    // The iOS 27 redesign has a single look; the legacy Miuix style setting is ignored until its
    // picker is removed from Appearance.
    @Suppress("NAME_SHADOWING")
    val style = ToolBoxThemeStyle.LiquidGlass
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
            // Flat iOS press highlight, clipped by the pressed element's own shape; never a ripple.
            LocalIndication provides ToolBoxPressIndication,
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
    return base.copy(
        primary = accent,
        onPrimary = readableForeground(accentForeground, listOf(accent)),
        softPrimary = accent.copy(alpha = if (dark) 0.22f else 0.12f).compositeOver(base.surface),
    )
}

/** Dark overlay glass tone, rgb(36,36,40); dark floating glass uses rgb(40,40,44). */
private val DarkOverlayGlass = Color(0xFF242428)
private val DarkFloatingGlass = Color(0xFF28282C)

internal fun liquidGlassMaterials(
    colors: ToolBoxColorScheme,
    dark: Boolean,
    enabled: Boolean,
    reduceTransparency: Boolean,
): ToolBoxMaterialTokens {
    val overlayTone = if (dark) DarkOverlayGlass else Color.White
    // Reduce Transparency: every material becomes opaque in its own tone.
    fun overlay(alpha: Float) = overlayTone.copy(alpha = if (reduceTransparency) 1f else alpha)
    return ToolBoxMaterialTokens(
        realBlurEnabled = enabled && !reduceTransparency,
        topEdgeFadeEnabled = enabled && !reduceTransparency,
        navigationLensEnabled = enabled,
        pressFeedbackEnabled = enabled,
        // DualSim floating glass: rgba(255,255,255,0.24) / rgba(40,40,44,0.30).
        glassTint = when {
            reduceTransparency -> colors.surface
            dark -> DarkFloatingGlass.copy(alpha = 0.30f)
            else -> Color.White.copy(alpha = 0.24f)
        },
        glassFallback = colors.surface,
        glassBorder = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.72f),
        overlayFill = overlay(if (dark) 0.62f else 0.58f),
        overlayFallback = overlay(if (dark) 0.84f else 0.86f),
        menuFill = overlay(if (dark) 0.66f else 0.62f),
        menuFallback = overlay(if (dark) 0.88f else 0.90f),
        overlayBorder = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.60f),
        sheetCard = when {
            reduceTransparency -> colors.surface
            dark -> Color(0xFF767680).copy(alpha = 0.20f)
            else -> Color.White.copy(alpha = 0.60f)
        },
        navigationSelectionTint = colors.softPrimary,
        navigationSelectionHighlight = colors.softPrimary,
        navigationSelectionBorder = colors.primary.copy(alpha = if (dark) 0.38f else 0.24f),
        // Floating glass keeps the page legible: a small blur, the lens does the work.
        blurRadius = 2.dp,
        noiseFactor = 0.015f,
    )
}

private fun ToolBoxThemeMode.toMiuixMode(): ColorSchemeMode = when (this) {
    ToolBoxThemeMode.System -> ColorSchemeMode.System
    ToolBoxThemeMode.Light -> ColorSchemeMode.Light
    ToolBoxThemeMode.Dark -> ColorSchemeMode.Dark
    ToolBoxThemeMode.MonetSystem -> ColorSchemeMode.MonetSystem
    ToolBoxThemeMode.MonetLight -> ColorSchemeMode.MonetLight
    ToolBoxThemeMode.MonetDark -> ColorSchemeMode.MonetDark
}
