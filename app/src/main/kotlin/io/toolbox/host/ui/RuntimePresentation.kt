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

/** Backs in a row the page may take, with no touch on the page between them, before the host takes the next one. */
internal const val PAGE_BACK_STREAK = 2

/**
 * A finger lifted this close to a Back press, before or after it, is taken for the Back gesture
 * itself: some gesture systems may send Back on lift without cancelling the touch.
 */
internal const val PAGE_TOUCH_NEAR_BACK_MS = 150L

/** The same for a touch that started inside the side system gesture insets, where a Back swipe begins. */
internal const val PAGE_EDGE_TOUCH_NEAR_BACK_MS = 500L

/**
 * Counts the Backs a running page took in a row, so a page that cancels every Back can't trap the
 * user: after [PAGE_BACK_STREAK] of them the next Back goes to the host, however slowly they came.
 * A touch on the page (opening another panel) or a Back the host took starts the count again.
 */
internal class PageBackStreak {
    private var count = 0
    private var lastPageBackAt = 0L

    /**
     * Whether the Back pressed at [now] goes to the host without asking the page. [pageTouchedAt] is
     * when a finger last lifted on the page; [fromGestureEdge] says that touch started inside the
     * side system gesture insets, so it needs a wider gap from a Back to count as a real tap.
     */
    fun hostTakes(now: Long, pageTouchedAt: Long, fromGestureEdge: Boolean = false): Boolean {
        val near = if (fromGestureEdge) PAGE_EDGE_TOUCH_NEAR_BACK_MS else PAGE_TOUCH_NEAR_BACK_MS
        if (pageTouchedAt - lastPageBackAt > near && now - pageTouchedAt > near) {
            count = 0
        }
        return count >= PAGE_BACK_STREAK
    }

    /** The page took the Back pressed at [pressedAt]: a touch after that press restarts the count. */
    fun pageTook(pressedAt: Long) {
        count++
        lastPageBackAt = pressedAt
    }

    fun reset() {
        count = 0
    }
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
