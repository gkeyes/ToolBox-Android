package io.toolbox.host.runtime

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RuntimeLiveNotificationRendererTest {
    @Test
    fun preparingRestoringLiveAndSummaryTextStayWhiteAcrossNotificationParceling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = RuntimeLiveNotificationRenderer(context)
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction("io.toolbox.host.TEST_LIVE_TEXT"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val session = RuntimeBackgroundSessionUi("session-1", "io.example.tool", "测试工具", 1L)
        val other = session.copy(sessionId = "session-2", toolId = "io.example.other", toolName = "其他工具")
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
        val snapshots = listOf(
            RuntimeForegroundNotificationSnapshot(emptyList(), emptyList(), usesLocation = false),
            RuntimeForegroundNotificationSnapshot(listOf(session), emptyList(), usesLocation = false),
            RuntimeForegroundNotificationSnapshot(listOf(session), listOf(live), usesLocation = false),
            RuntimeForegroundNotificationSnapshot(listOf(session, other), listOf(live), usesLocation = false),
        )
        val supportStates = listOf(
            LiveNotificationSupportState(0, false, false, false, false),
            LiveNotificationSupportState(3, true, false, true, true),
        )
        try {
            supportStates.forEach { support ->
                snapshots.forEach { snapshot ->
                    val notification = renderer.build(snapshot, support, intent, intent, intent)
                    val parcel = Parcel.obtain()
                    try {
                        notification.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        val restored = Notification.CREATOR.createFromParcel(parcel)
                        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                            .forEach { key -> assertWhite(restored.extras.getCharSequence(key)) }
                        if (snapshot.presentations.isNotEmpty()) {
                            assertWhite(restored.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                        }
                        restored.actions.forEach { action -> assertWhite(action.title) }
                        assertFalse(restored.extras.getBoolean("android.colorized"))
                        if (support.hyperOsSupported) {
                            assertWhiteFocusPayload(JSONObject(checkNotNull(restored.extras.getString("miui.focus.param"))))
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
