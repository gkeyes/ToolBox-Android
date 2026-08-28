package io.toolbox.host.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.toolbox.core.data.GlobalSecurityPolicy
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
    onSecurityPolicySelected: (GlobalSecurityPolicy) -> Unit = {},
    onAuditRetentionSelected: (Int) -> Unit,
    onDefaultStorageQuotaSelected: (Long) -> Unit = {},
    onDeveloperModeChanged: (Boolean) -> Unit = {},
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.updateError?.let { error ->
            item {
                SurfaceCard {
                    AppText(error, size = 13, color = ToolBoxThemeTokens.colors.danger)
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
                StaticSettingsStatus(
                    title = "严格策略固定",
                    summary = "运行 API 接入后开放调整",
                )
                StaticSettingsStatus(
                    title = "工具配额与开发者工具",
                    summary = "运行 API 接入后开放",
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

@Composable
private fun StaticSettingsStatus(title: String, summary: String) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppText(title, size = 15)
        AppText(summary, size = 13, color = ToolBoxThemeTokens.colors.textSecondary)
    }
}

private val themeChoices = ThemeMode.entries.map { theme ->
    ToolBoxSettingChoice(value = theme.name, label = theme.label)
}

private val auditRetentionChoices = listOf(7, 30, 90, 365).map { days ->
    ToolBoxSettingChoice(value = days.toString(), label = "$days 天")
}
