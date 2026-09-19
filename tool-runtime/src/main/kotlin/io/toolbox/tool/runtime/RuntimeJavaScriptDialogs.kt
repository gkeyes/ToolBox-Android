package io.toolbox.tool.runtime

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap

/** Only the visible local page can present a dialog; results always settle once. */
internal object RuntimeJavaScriptDialogs {
    enum class Kind { ALERT, CONFIRM, PROMPT }
    private data class Session(val dialog: AlertDialog, val cancel: () -> Unit)
    private val active = WeakHashMap<WebView, Session>()

    fun current(view: WebView): AlertDialog? = active[view]?.dialog
    fun dismiss(view: WebView) { active.remove(view)?.let { it.cancel(); it.dialog.dismiss() } }

    fun show(view: WebView, runtime: PreparedToolRuntime, url: String, message: String,
             kind: Kind, result: JsResult, defaultValue: String = ""): Boolean {
        // Runtime WebViews deliberately retain only an application-based themed context so a
        // background session cannot leak its previous Activity. The attached window owns dialogs.
        val activity = activity(view.rootView.context) ?: generateSequence(view.parent) { it.parent }
            .filterIsInstance<View>().mapNotNull { activity(it.context) }.firstOrNull()
        if (activity == null || activity.isFinishing || activity.isDestroyed ||
            !view.isShown || !view.isAttachedToWindow || !view.hasWindowFocus() ||
            !RuntimeIdentity.isExactLocalUrl(url.substringBefore('#'), runtime.origin)
        ) {
            result.cancel()
            return true
        }
        dismiss(view)
        val dark = view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dialogContext = android.view.ContextThemeWrapper(activity,
            if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
        val padding = (20 * view.resources.displayMetrics.density).toInt()
        val content = LinearLayout(dialogContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(dialogContext).apply { text = message; setTextIsSelectable(true) })
        val input = if (kind == Kind.PROMPT) EditText(dialogContext).apply {
            setText(defaultValue)
            contentDescription = "输入内容"
            content.addView(this)
        } else null
        var confirmed = false
        var settled = false
        val dialog = AlertDialog.Builder(dialogContext)
            .setTitle("${runtime.toolName} · ${runtime.toolId}")
            .setView(ScrollView(dialogContext).apply { addView(content) })
            .setPositiveButton("确定") { _, _ -> confirmed = true }
            .apply { if (kind != Kind.ALERT) setNegativeButton("取消", null) }
            .create()
        var dismissalPending = false
        var settlementPosted = false
        lateinit var observer: Application.ActivityLifecycleCallbacks
        lateinit var attachment: View.OnAttachStateChangeListener
        lateinit var focus: android.view.ViewTreeObserver.OnWindowFocusChangeListener
        val treeObserver = view.viewTreeObserver
        fun finish(accept: Boolean) {
            if (settled) return
            settled = true
            if (active[view]?.dialog === dialog) active.remove(view)
            activity.application.unregisterActivityLifecycleCallbacks(observer)
            view.removeOnAttachStateChangeListener(attachment)
            if (treeObserver.isAlive) treeObserver.removeOnWindowFocusChangeListener(focus)
            if (!accept) result.cancel()
            else if (result is JsPromptResult) result.confirm(input?.text?.toString().orEmpty())
            else result.confirm()
        }
        fun finishAfterFocusDispatch() {
            if (settled || !dismissalPending || !view.hasWindowFocus() || settlementPosted) return
            settlementPosted = true
            // Leave the current window-focus/dismiss callback before resuming renderer JavaScript.
            // A later focus loss must not turn a pending completion into a background dialog.
            view.post {
                settlementPosted = false
                if (!settled) {
                    if (!view.isAttachedToWindow || !view.isShown || activity.isFinishing || activity.isDestroyed) finish(false)
                    else if (view.hasWindowFocus()) finish(confirmed)
                }
            }
        }
        observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(target: Activity) { if (target === activity) dismiss(view) }
            override fun onActivityDestroyed(target: Activity) { if (target === activity) dismiss(view) }
            override fun onActivityCreated(target: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(target: Activity) = Unit
            override fun onActivityResumed(target: Activity) = Unit
            override fun onActivityStopped(target: Activity) = Unit
            override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit
        }
        attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { dismiss(view) }
        }
        focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) finishAfterFocusDispatch()
        }
        dialog.setOnDismissListener {
            dismissalPending = true
            // Resume the JS continuation only once the host window owns focus again. This permits
            // alert(); confirm(); prompt() in one script without a timeout or a touch-age gate.
            finishAfterFocusDispatch()
        }
        active[view] = Session(dialog) { finish(false) }
        activity.application.registerActivityLifecycleCallbacks(observer)
        view.addOnAttachStateChangeListener(attachment)
        treeObserver.addOnWindowFocusChangeListener(focus)
        try { dialog.show() } catch (_: RuntimeException) { finish(false) }
        return true
    }

    private fun activity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val next = current.baseContext
            if (next === current) return null
            current = next
        }
        return current as? Activity
    }
}
