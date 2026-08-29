package io.toolbox.host.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.ui.component.ToolBoxChoiceSettingRow
import io.toolbox.core.ui.component.ToolBoxSettingChoice
import io.toolbox.core.ui.theme.ToolBoxThemeTokens
import io.toolbox.host.ui.AppText
import io.toolbox.host.ui.SectionHeader
import io.toolbox.host.ui.SurfaceCard

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onThemeSelected = viewModel::selectTheme,
        onAuditRetentionSelected = viewModel::selectAuditRetention,
        contentPadding = contentPadding,
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThemeSelected: (ThemeMode) -> Unit,
    onAuditRetentionSelected: (Int) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(ToolBoxThemeTokens.spacing.one),
    ) {
        state.updateError?.let { error ->
            item {
                SurfaceCard {
                    AppText(
                        error,
                        textStyle = ToolBoxThemeTokens.textStyles.metadata,
                        color = ToolBoxThemeTokens.colors.danger,
                    )
                }
            }
        }
        item { SectionHeader("外观", "") }
        item {
            SettingsCard {
                ToolBoxChoiceSettingRow(
                    title = "主题",
                    selectedValue = state.settings.theme.name,
                    choices = themeChoices,
                    onSelected = { onThemeSelected(ThemeMode.valueOf(it)) },
                    summary = "只影响宿主界面",
                    enabled = state.isLoaded,
                )
            }
        }

        item { SectionHeader("安全与审计", "") }
        item {
            SettingsCard {
                ToolBoxChoiceSettingRow(
                    title = "审计日志保留",
                    selectedValue = state.settings.auditRetentionDays.toString(),
                    choices = auditRetentionChoices,
                    onSelected = { onAuditRetentionSelected(it.toInt()) },
                    summary = "仅保留最小审计元数据",
                    enabled = state.isLoaded,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    SurfaceCard(contentPadding = 0.dp) {
        content()
    }
}

private val themeChoices = ThemeMode.entries.map { theme ->
    ToolBoxSettingChoice(value = theme.name, label = theme.label)
}

private val auditRetentionChoices = listOf(7, 30, 90, 365).map { days ->
    ToolBoxSettingChoice(value = days.toString(), label = "$days 天")
}
