package io.toolbox.host.ui

internal fun runtimeLoadingTitle(toolName: String?): String =
    toolName?.trim()?.takeIf(String::isNotEmpty) ?: "正在打开工具"

internal fun runtimeExitSummary(toolName: String?): String {
    val identity = toolName?.trim()?.takeIf(String::isNotEmpty)?.let { "“$it”" } ?: "当前小工具"
    return "即将离开$identity，返回 ToolBox。请确认需要保留的内容已保存。"
}

internal data class RuntimeErrorPresentation(val summary: String, val details: String?)

/** Preserve the first actual reason in the summary and the original diagnostic text in full. */
internal fun runtimeErrorPresentation(message: String): RuntimeErrorPresentation {
    if (message.length <= 240 && message.count { it == '\n' } <= 2) {
        return RuntimeErrorPresentation(message, null)
    }
    val firstLine = message.lineSequence().firstOrNull(String::isNotBlank)?.trim().orEmpty()
    val summary = when {
        firstLine.isEmpty() -> "工具未能完成加载。请展开错误详情查看完整原因。"
        firstLine.length > 180 -> firstLine.take(180) + "…"
        else -> firstLine
    }
    return RuntimeErrorPresentation(summary, message)
}
