package io.toolbox.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import io.toolbox.core.ui.component.ToolBoxCard
import io.toolbox.core.ui.component.ToolBoxIcon
import io.toolbox.core.ui.component.ToolBoxIconKey
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

@Composable
internal fun ToolGlyph(
    icon: ToolBoxIconKey,
    accent: Color,
    size: Dp = ToolBoxThemeTokens.sizes.toolGlyph,
    imageResource: Int? = null,
    imageBitmap: ImageBitmap? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(ToolBoxThemeTokens.radii.badge))
            .background(if (imageResource == null && imageBitmap == null) accent else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else if (imageResource != null) {
            Image(
                painter = painterResource(imageResource),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            ToolBoxIcon(
                icon = icon,
                contentDescription = null,
                tint = Color.White,
            )
        }
    }
}

@Composable
internal fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = ToolBoxThemeTokens.spacing.two,
    content: @Composable ColumnScope.() -> Unit,
) {
    ToolBoxCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(contentPadding),
        content = content,
    )
}

@Composable
internal fun SectionHeader(title: String, action: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppText(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            textStyle = ToolBoxThemeTokens.textStyles.sectionTitle,
        )
        if (action.isNotEmpty()) {
            AppText(action, color = ToolBoxThemeTokens.colors.primary, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ToolBoxThemeTokens.colors.textPrimary,
    weight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
    textStyle: TextStyle = ToolBoxThemeTokens.textStyles.body,
) {
    ToolBoxText(
        text = text,
        modifier = modifier,
        style = textStyle.copy(
            color = color,
            fontWeight = weight ?: textStyle.fontWeight ?: FontWeight.Normal,
            textAlign = align,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
