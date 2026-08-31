package io.toolbox.host.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
        snapshot: RuntimeForegroundNotificationSnapshot,
        support: LiveNotificationSupportState,
        open: PendingIntent,
        stopCurrent: PendingIntent?,
        stopAll: PendingIntent,
    ): Notification {
        val primary = snapshot.primaryPresentation
        val sessionCount = snapshot.sessions.size
        val title = primary?.request?.title
            ?: snapshot.primarySession?.toolName?.let { "正在恢复 $it" }
            ?: "正在准备后台工具"
        val content = primary?.request?.let { request ->
            listOfNotNull(request.primaryText, request.secondaryText).joinToString(" · ")
        } ?: "页面恢复后会重新发布实时状态"
        val body = if (sessionCount > 1) {
            snapshot.sessions.joinToString("\n") { session ->
                val presentation = snapshot.presentations.firstOrNull { it.request.sessionId == session.sessionId }
                presentation?.request?.let { request ->
                    "${request.title}：${request.primaryText}${request.secondaryText?.let { " · $it" }.orEmpty()}"
                } ?: "${session.toolName}：正在恢复"
            }
        } else {
            primary?.request?.body ?: content
        }
        val updatedAt = primary?.request?.updatedAt ?: primary?.receivedAt ?: System.currentTimeMillis()
        val accent = primary?.request?.accentColor?.let(::parseColor)
            ?: primary?.request?.tone?.let(::toneColor)
            ?: DEFAULT_ACCENT

        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_toolbox)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setWhen(updatedAt)
            .setShowWhen(true)
            .setColor(accent)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(sessionCount > 1)
            .setSubText(if (sessionCount > 1) "$sessionCount 个后台环境" else primary?.request?.secondaryText)
            .addAction(Notification.Action.Builder(null, "打开", open).build())
            .apply {
                stopCurrent?.let { addAction(Notification.Action.Builder(null, "停止当前", it).build()) }
                if (sessionCount > 0) addAction(Notification.Action.Builder(null, "全部停止", stopAll).build())
                primary?.request?.progress?.let { setProgress(100, it, false) }
            }

        if (Build.VERSION.SDK_INT >= 36) {
            primary?.request?.shortText?.takeIf(String::isNotBlank)?.let(builder::setShortCriticalText)
            if (support.androidLiveAllowed) runCatching { builder.setRequestPromotedOngoing(true) }
        }
        if (support.hyperOsSupported && primary != null) {
            runCatching { buildHyperOsV3(primary, accent) }.getOrNull()?.let(builder::addExtras)
        }
        return builder.build()
    }

    private fun buildHyperOsV3(
        live: RuntimeLiveNotificationUi,
        accent: Int,
    ) = FocusNotification.buildV3 {
        val request = live.request
        val accentHex = String.format("#%06X", 0xFFFFFF and accent)
        val icon = createPicture(
            "toolbox-live-icon",
            Icon.createWithResource(appContext, R.drawable.ic_toolbox),
        )
        business = "toolbox_live"
        notifyId = "toolbox-runtime-live"
        orderId = "toolbox-runtime-live"
        sequence = live.sequence
        updatable = true
        isShowNotification = true
        filterWhenNoPermission = false
        reopen = "reopen"
        ticker = request.shortText ?: request.primaryText.take(12)
        tickerPic = icon
        tickerPicDark = icon
        aodTitle = request.primaryText
        aodPic = icon
        baseInfo {
            type = 1
            title = request.primaryText
            colorTitle = accentHex
            colorTitleDark = accentHex
            content = request.title
            subTitle = request.secondaryText
            extraTitle = request.updatedAt?.let(::formatTime)
            showDivider = false
            showContentDivider = false
        }
        iconTextInfo {
            type = 1
            title = request.primaryText
            colorTitle = accentHex
            colorTitleDark = accentHex
            content = request.title
            subTitle = request.secondaryText
        }
        island {
            business = "toolbox_live"
            islandProperty = 1
            islandPriority = 1
            islandTimeout = 43_200
            islandOrder = true
            highlightColor = accentHex
            smallIslandArea {
                picInfo {
                    type = 0
                    pic = icon
                    contentDescription = request.title
                }
            }
            bigIslandArea {
                textInfo = com.xzakota.hyper.notification.island.model.TextInfo().apply {
                    title = request.primaryText
                    frontTitle = request.title.take(16)
                    content = request.secondaryText
                    showHighlightColor = true
                    narrowFont = true
                    isTitleDigit = request.primaryText.any(Char::isDigit)
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
        const val NOTIFICATION_ID = 0x544258
        private const val GROUP_KEY = "io.toolbox.host.runtime.live"
        private val DEFAULT_ACCENT = Color.rgb(10, 132, 255)
    }
}
