package io.toolbox.host.runtime

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Parcel
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.host.MainActivity
import io.toolbox.host.R
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
    fun independentBackgroundAndLiveCardTextKeepsSystemManagedColorsAcrossNotificationParceling() {
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
                        assertNotificationSmallIcon(restored)
                        val largeIcon = requireNotNull(restored.getLargeIcon()).loadDrawable(context) as BitmapDrawable
                        assertEquals(toolColor, largeIcon.bitmap.getPixel(largeIcon.bitmap.width / 2, largeIcon.bitmap.height / 2))
                        val placeholder = parcelCopy(renderer.build(card, support, intent, intent))
                        assertNotificationSmallIcon(placeholder)
                        assertLauncherResourceIcon(requireNotNull(placeholder.getLargeIcon()))
                        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                            .forEach { key -> assertNoForcedForeground(restored.extras.getCharSequence(key)) }
                        if (card.presentation != null) {
                            assertNoForcedForeground(restored.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                        }
                        assertEquals(2, restored.actions.size)
                        assertEquals(listOf("打开", "停止当前"), restored.actions.map { it.title.toString() })
                        restored.actions.forEach { action -> assertNoForcedForeground(action.title) }
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
                            assertNoForcedFocusForeground(payload)
                            assertTrue(payload.toString().contains(card.notificationId.toString()))
                            assertTrue(payload.toString().contains("${card.session.toolId}:${card.session.sessionId}"))
                            assertTrue(payload.toString().contains("tool-icon-${card.session.sessionId}"))
                            assertFocusPicture(restored, card.session.sessionId, toolColor)
                            assertFocusLauncherPicture(placeholder, card.session.sessionId)
                            val placeholderPayload = JSONObject(checkNotNull(placeholder.extras.getString("miui.focus.param")))
                            assertNotEquals(
                                payload.getJSONObject("param_v2").get("sequence"),
                                placeholderPayload.getJSONObject("param_v2").get("sequence"),
                            )
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

    @Test
    fun notificationSmallIconIsTransparentMonochromeWhite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = requireNotNull(context.getDrawable(R.drawable.ic_toolbox_notification))
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.TRANSPARENT)
            icon.setBounds(0, 0, bitmap.width, bitmap.height)
            icon.draw(this)
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Notification icon must retain a transparent background", pixels.any { Color.alpha(it) == 0 })
        val foreground = pixels.filter { Color.alpha(it) > 0 }
        assertTrue("Notification icon must draw a visible outline", foreground.isNotEmpty())
        assertTrue(
            "Notification icon foreground must be monochrome white",
            foreground.all { Color.red(it) == 255 && Color.green(it) == 255 && Color.blue(it) == 255 },
        )
    }

    private fun assertNoForcedFocusForeground(payload: JSONObject) {
        val fields = setOf(
            "colorTitle", "colorTitleDark", "colorContent", "colorContentDark",
            "colorSubTitle", "colorSubTitleDark", "colorExtraTitle", "colorExtraTitleDark",
            "colorSubContent", "colorSubContentDark",
        )
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    if (key in fields) {
                        assertNotEquals("#FFFFFF", value.optString(key).uppercase())
                    } else visit(value.get(key))
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.get(it)) }
            }
        }
        visit(payload)
    }

    private fun assertFocusPicture(notification: Notification, sessionId: String, expectedColor: Int) {
        val icon = requireNotNull(
            requireNotNull(notification.extras.getBundle("miui.focus.pics"))
                .getParcelable("tool-icon-$sessionId", Icon::class.java),
        )
        assertEquals(Icon.TYPE_BITMAP, icon.type)
        val drawable = requireNotNull(icon.loadDrawable(InstrumentationRegistry.getInstrumentation().targetContext)) as BitmapDrawable
        assertEquals(expectedColor, drawable.bitmap.getPixel(drawable.bitmap.width / 2, drawable.bitmap.height / 2))
    }

    private fun assertFocusLauncherPicture(notification: Notification, sessionId: String) {
        val icon = requireNotNull(
            requireNotNull(notification.extras.getBundle("miui.focus.pics"))
                .getParcelable("tool-icon-$sessionId", Icon::class.java),
        )
        assertLauncherResourceIcon(icon)
    }

    private fun assertLauncherResourceIcon(icon: Icon) {
        assertEquals(Icon.TYPE_RESOURCE, icon.type)
        assertEquals(R.mipmap.ic_launcher, icon.resId)
    }

    private fun assertNotificationSmallIcon(notification: Notification) {
        val icon = requireNotNull(notification.smallIcon)
        assertEquals(Icon.TYPE_RESOURCE, icon.type)
        assertEquals(R.drawable.ic_toolbox_notification, icon.resId)
    }

    private fun parcelCopy(notification: Notification): Notification {
        val parcel = Parcel.obtain()
        return try {
            notification.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Notification.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun assertNoForcedForeground(text: CharSequence?) {
        if (text is Spanned) {
            assertTrue(
                "Notification foreground must remain under SystemUI control",
                text.getSpans(0, text.length, ForegroundColorSpan::class.java).isEmpty(),
            )
        }
    }
}
