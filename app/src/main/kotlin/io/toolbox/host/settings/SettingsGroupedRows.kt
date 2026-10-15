package io.toolbox.host.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.toolbox.host.R
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxMotion
import io.toolbox.core.ui.component.ToolBoxSwitchSettingRow
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.component.toolBoxPressFeedback
import io.toolbox.core.ui.component.toolBoxShape
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * iOS Settings leading-icon colours: each tile is a 160° two-stop gradient behind a white glyph,
 * taken from the design boards. The tile is decorative; the row title carries the meaning.
 */
internal enum class SettingsIconTint(val top: Color, val bottom: Color) {
    Pink(Color(0xFFFF7AC6), Color(0xFFA845E8)),
    Green(Color(0xFF4CD964), Color(0xFF1E9E45)),
    Mint(Color(0xFF3DD17A), Color(0xFF1E8E3E)),
    Orange(Color(0xFFFFB340), Color(0xFFF07B00)),
    Tangerine(Color(0xFFFF8A3D), Color(0xFFE8590C)),
    Amber(Color(0xFFFFC93C), Color(0xFFF29900)),
    Blue(Color(0xFF4A9BFF), Color(0xFF0A5BD6)),
    Sky(Color(0xFF6FC3FF), Color(0xFF1F86D6)),
    Cyan(Color(0xFF5AC8FA), Color(0xFF1E88E5)),
    Teal(Color(0xFF3FD8E0), Color(0xFF0E93B8)),
    Aqua(Color(0xFF2ECBCB), Color(0xFF0B8A8A)),
    Indigo(Color(0xFF7C82FF), Color(0xFF4B4FD6)),
    Purple(Color(0xFFB07CFF), Color(0xFF7B3FD0)),
    Red(Color(0xFFFF6B5E), Color(0xFFD7261B)),
    Rose(Color(0xFFFF7AA8), Color(0xFFE0306A)),
    Graphite(Color(0xFF8A94A3), Color(0xFF444C57)),
    Gray(Color(0xFF8E8E93), Color(0xFF48484A)),
}

/** Leading inset of a separator in a grouped card: side padding + 30dp tile + 12dp gap. */
internal val SettingsSeparatorInset: Dp = 58.dp

/** Rounded-square gradient tile with a white stroked glyph, as in iOS Settings. */
@Composable
internal fun SettingsIconTile(
    icon: ToolBoxIconKey,
    tint: SettingsIconTint,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 30.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(toolBoxShape(size * 0.27f))
            .drawBehind {
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(tint.top, tint.bottom),
                        start = Offset(this.size.width * 0.32f, 0f),
                        end = Offset(this.size.width * 0.68f, this.size.height),
                    ),
                )
                // Thin top highlight, like the board's inset 0 .5px white rim.
                drawRect(Color.White.copy(alpha = 0.35f), size = this.size.copy(height = 0.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        ToolBoxIcon(icon, null, Modifier.size(size * 0.6f), tint = Color.White)
    }
}

/** ToolBox's own launcher icon, masked like an app icon, for the Settings and About heroes. */
@Composable
internal fun AppLauncherMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.mipmap.ic_launcher),
        contentDescription = null,
        modifier = modifier.size(size).clip(toolBoxShape(size * 0.22f)),
        contentScale = ContentScale.Crop,
    )
}

/** Hairline separator between rows of a grouped card, inset to start after the icon tile. */
@Composable
internal fun SettingsRowSeparator(start: Dp = SettingsSeparatorInset) {
    Box(
        Modifier
            .padding(start = start)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(ToolBoxThemeTokens.colors.divider),
    )
}

/**
 * Grouped-card navigation row: coloured tile, 17pt title, 13pt secondary summary, optional
 * trailing content and a disclosure chevron when it navigates. [leading] replaces the tile with
 * other 30dp artwork, such as a tool's own icon.
 */
@Composable
internal fun SettingsNavRow(
    title: String,
    icon: ToolBoxIconKey,
    tint: SettingsIconTint,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryColor: Color = ToolBoxThemeTokens.colors.textSecondary,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = ToolBoxThemeTokens.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (summary.isNullOrBlank()) 52.dp else 60.dp)
            .toolBoxPressFeedback(interactionSource, enabled = enabled && onClick != null, scale = ToolBoxMotion.RowPressScale)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = onClickLabel,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading() else SettingsIconTile(icon, tint, enabled = enabled)
        // When large text leaves too little width beside a value or chip, the value moves under
        // the title instead of squeezing it into a one-character column.
        val configuration = LocalConfiguration.current
        val stackTrailing = trailing != null && configuration.screenWidthDp / LocalDensity.current.fontScale < 260f
        if (stackTrailing) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsRowLabels(title, summary, summaryColor, enabled, Modifier.fillMaxWidth())
                trailing?.invoke()
            }
        } else {
            SettingsRowLabels(title, summary, summaryColor, enabled, Modifier.weight(1f))
            // An unweighted trailing value is measured first; capped so it never takes the title's room.
            trailing?.let {
                Box(
                    Modifier.widthIn(max = (configuration.screenWidthDp * 0.4f).dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { it() }
            }
        }
        if (onClick != null) {
            ToolBoxIcon(
                ToolBoxIconKey.ChevronRight, null, Modifier.size(16.dp),
                tint = if (enabled) colors.textTertiary else ToolBoxThemeTokens.disabledContent,
            )
        }
    }
}

/** Read-only grouped-card row with a trailing value, e.g. version numbers. */
@Composable
internal fun SettingsValueRow(
    title: String,
    value: String,
    icon: ToolBoxIconKey,
    tint: SettingsIconTint,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    SettingsNavRow(
        title = title, icon = icon, tint = tint, modifier = modifier, summary = summary,
        trailing = {
            ToolBoxText(
                value,
                style = ToolBoxThemeTokens.textStyles.body.copy(color = ToolBoxThemeTokens.colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * Grouped-card switch row. The switch itself (and its toggle semantics, test tag and enabled
 * state) comes from the shared [ToolBoxSwitchSettingRow]; this only adds the coloured tile.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ToolBoxIconKey,
    tint: SettingsIconTint,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    // The tile sits in the switch row's own leading slot, so it is part of the toggle target and
    // the labels start at 58dp like nav rows and separators.
    ToolBoxSwitchSettingRow(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        leading = { SettingsIconTile(icon, tint, enabled = enabled) },
    )
}

@Composable
private fun SettingsRowLabels(
    title: String,
    summary: String?,
    summaryColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ToolBoxThemeTokens.colors
    val textStyles = ToolBoxThemeTokens.textStyles
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        ToolBoxText(
            title,
            style = textStyles.body.copy(color = if (enabled) colors.textPrimary else ToolBoxThemeTokens.disabledContent),
        )
        summary?.takeIf(String::isNotBlank)?.let {
            ToolBoxText(
                it,
                style = textStyles.sectionTitle.copy(color = if (enabled) summaryColor else ToolBoxThemeTokens.disabledContent),
            )
        }
    }
}

/** Secondary footnote under a grouped card. */
@Composable
internal fun SettingsFooter(text: String, modifier: Modifier = Modifier) {
    ToolBoxText(
        text,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        style = ToolBoxThemeTokens.textStyles.sectionTitle.copy(color = ToolBoxThemeTokens.colors.textSecondary),
    )
}
