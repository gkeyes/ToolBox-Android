package io.toolbox.host.runtime

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Parcel
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.host.MainActivity
import io.toolbox.host.background.LiveNotificationSupportState
import io.toolbox.tool.runtime.RuntimeLiveNotificationRequest
import io.toolbox.tool.runtime.RuntimeLiveNotificationTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RuntimeLiveNotificationRendererTest {
    @Test
    fun independentBackgroundAndLiveCardTextRetainsWhiteSpansAcrossNotificationParceling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = RuntimeLiveNotificationRenderer(context)
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction("io.toolbox.host.TEST_LIVE_TEXT"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val session = RuntimeBackgroundSessionUi("session-1", "io.example.tool", "测试工具", 1L, 0x550000)
        val other = session.copy(
            sessionId = "session-2", toolId = "io.example.other", toolName = "其他工具", notificationId = 0x550001,
        )
        val live = RuntimeLiveNotificationUi(
            toolId = session.toolId,
            toolName = session.toolName,
            request = RuntimeLiveNotificationRequest(
                sessionId = session.sessionId,
                title = "构建进度",
                primaryText = "60%",
                secondaryText = "正在运行",
                body = "已用 2 分钟",
                shortText = "60%",
                updatedAt = 1L,
                progress = 60,
                accentColor = "#000000",
                tone = RuntimeLiveNotificationTone.NEUTRAL,
            ),
            receivedAt = 1L,
            sequence = 1L,
        )
        val cards = listOf(
            RuntimeNotificationCard(session, null),
            RuntimeNotificationCard(session, live),
            RuntimeNotificationCard(other, live.copy(
                toolId = other.toolId,
                toolName = other.toolName,
                request = live.request.copy(sessionId = other.sessionId, title = "其他进度", primaryText = "20%"),
                sequence = 2L,
            )),
        )
        val supportStates = listOf(
            LiveNotificationSupportState(0, false, false, false, false),
            LiveNotificationSupportState(3, true, false, true, true),
        )
        try {
            supportStates.forEach { support ->
                cards.forEach { card ->
                    val toolColor = if (card.session.sessionId == other.sessionId) Color.BLUE else Color.RED
                    val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(toolColor) }
                    val notification = renderer.build(card, support, intent, intent, toolIcon = artwork)
                    val parcel = Parcel.obtain()
                    try {
                        notification.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        val restored = Notification.CREATOR.createFromParcel(parcel)
                        val largeIcon = requireNotNull(restored.getLargeIcon()).loadDrawable(context) as BitmapDrawable
                        assertEquals(toolColor, largeIcon.bitmap.getPixel(largeIcon.bitmap.width / 2, largeIcon.bitmap.height / 2))
                        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                            .forEach { key -> assertWhite(restored.extras.getCharSequence(key)) }
                        if (card.presentation != null) {
                            assertWhite(restored.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                        }
                        assertEquals(2, restored.actions.size)
                        assertEquals(listOf("打开", "停止当前"), restored.actions.map { it.title.toString() })
                        restored.actions.forEach { action -> assertWhite(action.title) }
                        assertFalse(restored.extras.getBoolean("android.colorized"))
                        assertNull(restored.group)
                        assertEquals(0, restored.flags and Notification.FLAG_GROUP_SUMMARY)
                        assertNull(restored.deleteIntent)
                        assertEquals(
                            card.presentation?.request?.title ?: card.session.toolName,
                            restored.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
                        )
                        if (support.hyperOsSupported && card.presentation != null) {
                            val payload = JSONObject(checkNotNull(restored.extras.getString("miui.focus.param")))
                            assertWhiteFocusPayload(payload)
                            assertTrue(payload.toString().contains(card.notificationId.toString()))
                            assertTrue(payload.toString().contains("${card.session.toolId}:${card.session.sessionId}"))
                            assertTrue(payload.toString().contains("tool-icon-${card.session.sessionId}"))
                            val placeholder = renderer.build(card, support, intent, intent)
                            assertNull(placeholder.getLargeIcon())
                            assertNotEquals(placeholder.extras.getString("miui.focus.param"), restored.extras.getString("miui.focus.param"))
                            assertTrue(payload.toString().contains("\"reopen\":\"close\""))
                            assertTrue(payload.toString().contains("\"islandOrder\":false"))
                        } else {
                            assertNull(restored.extras.getString("miui.focus.param"))
                        }
                        if (Build.VERSION.SDK_INT >= 36 && support.androidLiveAllowed && card.presentation != null) {
                            assertTrue(restored.hasPromotableCharacteristics())
                        }
                    } finally {
                        parcel.recycle()
                    }
                }
            }
        } finally {
            intent.cancel()
        }
    }

    @Test
    fun openAndStopPendingIntentsHaveSeparateImmutableIdentitiesForEverySession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val openA = RuntimeForegroundService.openSessionPendingIntent(context, "tool-a", "Aa")
        val openB = RuntimeForegroundService.openSessionPendingIntent(context, "tool-b", "BB")
        val replacement = RuntimeForegroundService.openSessionPendingIntent(context, "tool-a", "new-session")
        val stopA = RuntimeForegroundService.stopSessionPendingIntent(context, "tool-a", "Aa")
        val stopB = RuntimeForegroundService.stopSessionPendingIntent(context, "tool-b", "BB")
        val pending = listOf(openA, openB, replacement, stopA, stopB)
        try {
            assertEquals("Aa".hashCode(), "BB".hashCode())
            assertEquals(5, pending.toSet().size)
            assertNotEquals(openA, replacement)
            assertEquals(openA, RuntimeForegroundService.openSessionPendingIntent(context, "tool-a", "Aa"))
            assertEquals(stopA, RuntimeForegroundService.stopSessionPendingIntent(context, "tool-a", "Aa"))
            if (Build.VERSION.SDK_INT >= 31) pending.forEach { assertTrue(it.isImmutable) }
        } finally {
            pending.forEach(PendingIntent::cancel)
        }
    }

    private fun assertWhiteFocusPayload(payload: JSONObject) {
        val fields = setOf(
            "colorTitle", "colorTitleDark", "colorContent", "colorContentDark",
            "colorSubTitle", "colorSubTitleDark", "colorExtraTitle", "colorExtraTitleDark",
            "colorSubContent", "colorSubContentDark",
        )
        var checked = 0
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    if (key in fields) {
                        assertEquals("#FFFFFF", value.getString(key))
                        checked += 1
                    } else visit(value.get(key))
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.get(it)) }
            }
        }
        visit(payload)
        assertEquals("Both Focus text blocks must specify light and dark foregrounds", 20, checked)
    }

    private fun assertWhite(text: CharSequence?) {
        assertTrue("Notification text must retain its explicit white color", text is Spanned)
        val styled = text as Spanned
        val spans = styled.getSpans(0, styled.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.WHITE, spans.single().foregroundColor)
        assertEquals(0, styled.getSpanStart(spans.single()))
        assertEquals(styled.length, styled.getSpanEnd(spans.single()))
    }
}
