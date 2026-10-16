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

    /** A page that takes every Back: returns the press index (1-based) the host first takes. */
    private fun firstHostBack(intervalMs: Long, touchAt: (Long) -> Long = { 0L }): Int {
        val streak = PageBackStreak()
        for (press in 1..40) {
            val now = 10_000L + press * intervalMs
            if (streak.hostTakes(now, touchAt(now))) return press
            streak.pageTook(now)
        }
        return -1
    }

    @Test
    fun pageCannotKeepBackPastTwoInARowAtAnyPace() {
        assertEquals(3, firstHostBack(1_000L))
        assertEquals(3, firstHostBack(1_100L))
        assertEquals(3, firstHostBack(2_000L))
        assertEquals(3, firstHostBack(30_000L))
    }

    @Test
    fun hintShownByTheHostTakenBackConfirmsTheNextBackWithinTwoSeconds() {
        val hostBackAt = 10_000L + 3 * 1_100L
        assertTrue(shouldExitRuntimeOnBack(hostBackAt, hostBackAt + 1_100L))
        assertTrue(shouldExitRuntimeOnBack(hostBackAt, hostBackAt + 2_000L))
    }

    @Test
    fun aTouchOnThePageRestartsTheCount() {
        val streak = PageBackStreak()
        streak.pageTook(10_000L)
        streak.pageTook(11_000L)
        // Touched while the page was still answering the second Back.
        assertTrue(!streak.hostTakes(13_000L, pageTouchedAt = 11_300L))
        streak.pageTook(13_000L)
        assertTrue(!streak.hostTakes(14_000L, pageTouchedAt = 11_300L))
        streak.pageTook(14_000L)
        assertTrue(streak.hostTakes(15_000L, pageTouchedAt = 11_300L))
    }

    @Test
    fun aLiftRightAroundABackPressIsTakenForTheGesture() {
        val streak = PageBackStreak()
        streak.pageTook(10_000L)
        streak.pageTook(11_000L)
        // Lifted 100 ms before this press, or 100 ms after the last one: neither restarts the count.
        assertTrue(streak.hostTakes(12_000L, pageTouchedAt = 11_900L))
        assertTrue(streak.hostTakes(12_000L, pageTouchedAt = 11_100L))
    }

    @Test
    fun aTapFromTheGestureEdgeCountsOnlyWellApartFromABack() {
        val streak = PageBackStreak()
        streak.pageTook(10_000L)
        streak.pageTook(11_000L)
        // Lifted 400 ms before this press after starting at the edge: likely the swipe itself.
        assertTrue(streak.hostTakes(12_000L, pageTouchedAt = 11_600L, fromGestureEdge = true))
        // An edge tap that opened a panel a second before the next Back restarts the count.
        assertTrue(!streak.hostTakes(13_000L, pageTouchedAt = 11_900L, fromGestureEdge = true))
    }

    @Test
    fun aPlainLiftWellBeforeABackRestartsTheCount() {
        val streak = PageBackStreak()
        streak.pageTook(10_000L)
        streak.pageTook(11_000L)
        assertTrue(!streak.hostTakes(12_000L, pageTouchedAt = 11_600L))
    }

    @Test
    fun liftsExactlyAtTheNearWindowStillBelongToTheGesture() {
        // The last handled Back is 2 s before this press, so only the gap to this press decides.
        fun hostTakes(liftBeforePress: Long, edge: Boolean): Boolean {
            val streak = PageBackStreak()
            streak.pageTook(9_000L)
            streak.pageTook(10_000L)
            return streak.hostTakes(12_000L, pageTouchedAt = 12_000L - liftBeforePress, fromGestureEdge = edge)
        }
        assertTrue(hostTakes(PAGE_TOUCH_NEAR_BACK_MS, edge = false))
        assertTrue(!hostTakes(PAGE_TOUCH_NEAR_BACK_MS + 1, edge = false))
        assertTrue(hostTakes(PAGE_EDGE_TOUCH_NEAR_BACK_MS, edge = true))
        assertTrue(!hostTakes(PAGE_EDGE_TOUCH_NEAR_BACK_MS + 1, edge = true))

        fun hostTakesAfter(liftAfterLast: Long, edge: Boolean): Boolean {
            val streak = PageBackStreak()
            streak.pageTook(10_000L)
            streak.pageTook(11_000L)
            return streak.hostTakes(13_000L, pageTouchedAt = 11_000L + liftAfterLast, fromGestureEdge = edge)
        }
        assertTrue(hostTakesAfter(PAGE_TOUCH_NEAR_BACK_MS, edge = false))
        assertTrue(!hostTakesAfter(PAGE_TOUCH_NEAR_BACK_MS + 1, edge = false))
        assertTrue(hostTakesAfter(PAGE_EDGE_TOUCH_NEAR_BACK_MS, edge = true))
        assertTrue(!hostTakesAfter(PAGE_EDGE_TOUCH_NEAR_BACK_MS + 1, edge = true))
    }

    @Test
    fun aLiftIsDatedByItsEventTimeNotWhenItWasHandled() {
        // Handled 120 ms after the finger lifted: the lift is dated 120 ms earlier on the Back clock.
        assertEquals(49_880L, liftedAtElapsed(nowElapsed = 50_000L, nowUptime = 20_000L, eventTime = 19_880L))
        // A clock reading that puts the event in the future never dates it after now.
        assertEquals(50_000L, liftedAtElapsed(nowElapsed = 50_000L, nowUptime = 20_000L, eventTime = 20_050L))
    }

    @Test
    fun aHostTakenBackRestartsTheCount() {
        val streak = PageBackStreak()
        streak.pageTook(10_000L)
        streak.pageTook(11_000L)
        assertTrue(streak.hostTakes(12_000L, 0L))
        streak.reset()
        assertTrue(!streak.hostTakes(13_000L, 0L))
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
