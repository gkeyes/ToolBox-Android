package io.toolbox.host.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePresentationTest {
    @Test
    fun loadingUsesTheKnownNameAndFallsBackForMissingIdentity() {
        assertEquals("笔记工具", runtimeLoadingTitle("  笔记工具  "))
        assertEquals("正在打开工具", runtimeLoadingTitle(null))
        assertEquals("正在打开工具", runtimeLoadingTitle(" \n "))
    }

    @Test
    fun exitHintUsesKnownToolNameAndGenericFallback() {
        assertEquals("再次返回即可离开小工具", runtimeExitHint(null))
        assertEquals("再次返回即可离开“笔记工具”", runtimeExitHint("  笔记工具  "))
    }

    @Test
    fun runtimeExitRequiresASecondBackInsideTheTwoSecondWindow() {
        assertTrue(!shouldExitRuntimeOnBack(0L, 10_000L))
        assertTrue(shouldExitRuntimeOnBack(10_000L, 10_001L))
        assertTrue(shouldExitRuntimeOnBack(10_000L, 12_000L))
        assertTrue(!shouldExitRuntimeOnBack(10_000L, 12_001L))
        assertTrue(!shouldExitRuntimeOnBack(10_000L, 9_999L))
    }

    @Test
    fun shortErrorsRemainFullyVisible() {
        val message = "NETWORK_UNAVAILABLE\n请检查网络后重试。"
        val result = runtimeErrorPresentation(message)
        assertEquals(message, result.summary)
        assertNull(result.details)
    }

    @Test
    fun longErrorsRetainTheFirstReasonAndAllOriginalDetails() {
        val message = "权限校验失败\n" + "diagnostic ".repeat(40)
        val result = runtimeErrorPresentation(message)
        assertEquals("权限校验失败", result.summary)
        assertEquals(message, result.details)
    }

    @Test
    fun multilineErrorsDoNotDropBlankLinesOrDiagnosticText() {
        val message = "\n错误原因\n第一项\n第二项"
        val result = runtimeErrorPresentation(message)
        assertEquals("错误原因", result.summary)
        assertEquals(message, result.details)
    }

    @Test
    fun veryLongSingleLineSummariesAreBoundedButRecoverable() {
        val message = "X".repeat(300)
        val result = runtimeErrorPresentation(message)
        assertEquals(181, result.summary.length)
        assertTrue(result.summary.endsWith("…"))
        assertEquals(message, result.details)
    }
}
