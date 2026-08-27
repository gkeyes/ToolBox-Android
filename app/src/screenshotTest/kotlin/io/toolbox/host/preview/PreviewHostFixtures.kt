package io.toolbox.host.preview

import io.toolbox.host.ui.HostCatalogScreenModel
import io.toolbox.host.ui.ToolCardModel
import io.toolbox.host.ui.ToolTrust
import io.toolbox.host.ui.UiState

object PreviewHostFixtures {
    private val tools = listOf(
        ToolCardModel("preview-calculator", "仓位计算器", "v1.2.0 · 8.2 MB · 今天使用", "▦"),
        ToolCardModel("preview-json", "JSON 格式化", "v1.0.3 · 5.9 MB · 昨天使用", "{ }"),
        ToolCardModel("preview-qr", "二维码工具", "v1.1.0 · 4.6 MB · 3 天前", "▦", ToolTrust.NeedsReview),
        ToolCardModel("preview-text", "文本处理", "v2.0.1 · 7.1 MB · 5 天前", "文"),
        ToolCardModel("preview-date", "日期计算", "v1.0.0 · 3.8 MB · 1 周前", "日"),
        ToolCardModel("preview-network", "网络诊断", "v0.9.2 · 6.4 MB · 从未使用", "网", ToolTrust.HighRisk),
    )

    val home = HostCatalogScreenModel(UiState.Content(tools.take(5)))
    val toolManager = HostCatalogScreenModel(UiState.Content(tools))
}
