package io.toolbox.host.ui

internal fun runtimeLoadingTitle(toolName: String?): String =
    toolName?.trim()?.takeIf(String::isNotEmpty) ?: "正在打开工具"

internal const val RUNTIME_EXIT_BACK_WINDOW_MS = 2_000L

internal fun runtimeExitHint(toolName: String?): String {
    val identity = toolName?.trim()?.takeIf(String::isNotEmpty)?.let { "“$it”" } ?: "小工具"
    return "再次返回即可离开$identity"
}

internal fun shouldExitRuntimeOnBack(lastBackAt: Long, now: Long): Boolean =
    lastBackAt > 0L && now >= lastBackAt && now - lastBackAt <= RUNTIME_EXIT_BACK_WINDOW_MS

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
