package io.toolbox.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.security.SecureRandom
import java.util.Base64

/**
 * Opens a tool for the host's own notifications, alarms and session cards. It is not exported, so
 * only ToolBox can reach it; it hands MainActivity a one-time token for the tool and closes without
 * drawing anything. The exported MainActivity never opens a tool from a bare tool id.
 */
class ToolLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(forwardIntent(this, intent))
        finish()
    }

    companion object {
        private const val ACTION_OPEN_TOOL = "io.toolbox.host.OPEN_TOOL"
        private const val EXTRA_TOOL_ID = "toolId"
        private const val ACTION_LAUNCH_TOOL = "io.toolbox.host.action.LAUNCH_TOOL"
        private const val EXTRA_LAUNCH_TOKEN = "io.toolbox.host.extra.LAUNCH_TOKEN"
        private val launchTokens = mutableMapOf<String, String>()
        private val random = SecureRandom()

        internal fun openToolIntent(context: Context, toolId: String): Intent =
            Intent(context, ToolLaunchActivity::class.java)
                .setAction(ACTION_OPEN_TOOL)
                .putExtra(EXTRA_TOOL_ID, toolId)

        /**
         * MainActivity with a token for the requested tool. A relaunch from Recents (the task may
         * have started here) just shows ToolBox, like MainActivity does with its own launch intent.
         */
        internal fun forwardIntent(context: Context, intent: Intent?): Intent {
            // An open MainActivity gets the tool through onNewIntent; the new tool replaces the open one.
            val target = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val toolId = intent
                ?.takeIf { it.action == ACTION_OPEN_TOOL && (it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0 }
                ?.getStringExtra(EXTRA_TOOL_ID)
                ?.takeIf(String::isNotBlank)
                ?: return target
            val token = ByteArray(32)
                .also(random::nextBytes)
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            synchronized(launchTokens) { launchTokens[token] = toolId }
            return target.setAction(ACTION_LAUNCH_TOOL).putExtra(EXTRA_LAUNCH_TOKEN, token)
        }

        /** The tool id for a token this process issued, once; null for anything else. */
        internal fun consumeLaunchToken(intent: Intent): String? {
            if (intent.action != ACTION_LAUNCH_TOOL) return null
            val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN) ?: return null
            return synchronized(launchTokens) { launchTokens.remove(token) }
        }
    }
}
