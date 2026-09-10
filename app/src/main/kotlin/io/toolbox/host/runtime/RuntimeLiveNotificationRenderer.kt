package io.toolbox.host.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import com.xzakota.hyper.notification.focus.FocusNotification
import io.toolbox.host.R
import io.toolbox.host.background.LiveNotificationSupportState
import io.toolbox.tool.runtime.RuntimeLiveNotificationTone

internal class RuntimeLiveNotificationRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ToolBox 实时活动", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "显示小工具持续运行与实时更新"
                setShowBadge(false)
            },
        )
    }

    fun build(
        card: RuntimeNotificationCard,
        support: LiveNotificationSupportState,
        open: PendingIntent,
        stopCurrent: PendingIntent,
        toolIcon: Bitmap? = null,
    ): Notification {
        val live = card.presentation
        val title = live?.request?.title ?: card.session.toolName
        val content = live?.request?.let { request ->
            listOfNotNull(request.primaryText, request.secondaryText?.takeIf(String::isNotBlank)).joinToString(" · ")
        } ?: "后台环境运行中，可打开工具或停止当前会话"
        // An omitted live body means no expanded prose, not a copy of the status line.
        val body = if (live == null) content else live.request.body?.takeIf(String::isNotBlank)
        val updatedAt = live?.request?.updatedAt ?: live?.receivedAt ?: card.session.startedAt
        val accent = live?.request?.accentColor?.let(::parseColor)
            ?: live?.request?.tone?.let(::toneColor)
            ?: DEFAULT_ACCENT

        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_toolbox_notification)
            .setLargeIcon(toolIcon?.let(Icon::createWithBitmap) ?: Icon.createWithResource(appContext, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(open)
            .setWhen(updatedAt)
            .setShowWhen(true)
            .setColor(accent)
            .setColorized(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "打开", open).build())
            .addAction(Notification.Action.Builder(null, "停止当前", stopCurrent).build())
            .apply {
                body?.let { setStyle(Notification.BigTextStyle().bigText(it)) }
                live?.request?.secondaryText?.takeIf(String::isNotBlank)?.let { setSubText(it) }
                live?.request?.progress?.let { setProgress(100, it, false) }
            }

        if (live != null && Build.VERSION.SDK_INT >= 36) {
            live.request.shortText?.takeIf(String::isNotBlank)?.let(builder::setShortCriticalText)
            // Promotion was added in Android 16's first minor release, not API 36.0.
            if (support.androidLiveAllowed &&
                Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
            ) {
                runCatching { builder.setRequestPromotedOngoing(true) }
            }
        }
        if (live != null && support.hyperOsSupported) {
            runCatching { buildHyperOsV3(card, title, body, accent, toolIcon) }
                .getOrNull()?.let(builder::addExtras)
        }
        return builder.build()
    }

    private fun buildHyperOsV3(
        card: RuntimeNotificationCard,
        notificationTitle: String,
        notificationBody: String?,
        accent: Int,
        toolIcon: Bitmap?,
    ) = FocusNotification.buildV3 {
        val live = requireNotNull(card.presentation)
        val request = live.request
        val displayValue = request.primaryText
        val displayTitle = request.title
        val displaySecondary = request.secondaryText?.takeIf(String::isNotBlank)
        val minimalContent = displaySecondary == null && notificationBody == null
        val accentHex = String.format("#%06X", 0xFFFFFF and accent)
        val icon = createPicture(
            "tool-icon-${card.session.sessionId}",
            toolIcon?.let(Icon::createWithBitmap) ?: Icon.createWithResource(appContext, R.mipmap.ic_launcher),
        )
        business = "toolbox_live"
        notifyId = card.notificationId.toString()
        orderId = "${card.session.toolId}:${card.session.sessionId}"
        sequence = live.sequence * 2 + if (toolIcon != null) 1 else 0
        updatable = true
        isShowNotification = true
        filterWhenNoPermission = false
        reopen = "close"
        ticker = request.shortText ?: request.primaryText.take(12)
        tickerPic = icon
        tickerPicDark = icon
        aodTitle = displayValue
        aodPic = icon
        baseInfo {
            type = 1
            title = if (minimalContent) displayTitle else displayValue
            content = if (minimalContent) displayValue else displayTitle
            subTitle = displaySecondary
            subContent = notificationBody
            extraTitle = if (minimalContent) null else request.updatedAt?.let(::formatTime)
            showDivider = false
            showContentDivider = false
        }
        iconTextInfo {
            type = 1
            title = if (minimalContent) displayTitle else displayValue
            content = if (minimalContent) displayValue else displayTitle
            subTitle = displaySecondary
            subContent = notificationBody
        }
        if (minimalContent) {
            request.progress?.let { value ->
                multiProgressInfo {
                    progress = value
                    color = accentHex
                    points = 0
                }
            }
        }
        island {
            business = "toolbox_live"
            islandProperty = 1
            islandPriority = 1
            islandTimeout = 43_200
            islandOrder = false
            highlightColor = accentHex
            smallIslandArea {
                picInfo {
                    type = 0
                    pic = icon
                    contentDescription = notificationTitle
                }
            }
            bigIslandArea {
                textInfo = com.xzakota.hyper.notification.island.model.TextInfo().apply {
                    title = if (minimalContent) displayTitle else displayValue
                    frontTitle = if (minimalContent) null else displayTitle.take(16)
                    content = if (minimalContent) displayValue else displaySecondary
                    showHighlightColor = false
                    narrowFont = !minimalContent
                    isTitleDigit = !minimalContent && displayValue.any(Char::isDigit)
                    turnAnim = true
                }
            }
        }
    }

    private fun parseColor(value: String): Int = runCatching { Color.parseColor(value) }.getOrDefault(DEFAULT_ACCENT)

    private fun toneColor(tone: RuntimeLiveNotificationTone): Int = when (tone) {
        RuntimeLiveNotificationTone.NEUTRAL -> DEFAULT_ACCENT
        RuntimeLiveNotificationTone.POSITIVE -> Color.rgb(46, 125, 50)
        RuntimeLiveNotificationTone.NEGATIVE -> Color.rgb(198, 40, 40)
        RuntimeLiveNotificationTone.WARNING -> Color.rgb(245, 124, 0)
    }

    private fun formatTime(value: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        .format(java.util.Date(value))

    companion object {
        const val CHANNEL_ID = "toolbox.runtime.live.v1"
        private val DEFAULT_ACCENT = Color.rgb(10, 132, 255)
    }
}
