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
    private val active = WeakHashMap<WebView, AlertDialog>()

    fun current(view: WebView): AlertDialog? = active[view]
    fun dismiss(view: WebView) { active.remove(view)?.dismiss() }

    fun show(view: WebView, runtime: PreparedToolRuntime, url: String, message: String,
             kind: Kind, result: JsResult, defaultValue: String = ""): Boolean {
        val activity = activity(view.context)
        if (activity == null || activity.isFinishing || activity.isDestroyed ||
            !view.isShown || !view.isAttachedToWindow || !view.hasWindowFocus() ||
            !RuntimeIdentity.isExactLocalUrl(url, runtime.origin)
        ) {
            result.cancel()
            return true
        }
        dismiss(view)
        val padding = (20 * view.resources.displayMetrics.density).toInt()
        val content = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(view.context).apply { text = message; setTextIsSelectable(true) })
        val input = if (kind == Kind.PROMPT) EditText(view.context).apply {
            setText(defaultValue)
            contentDescription = "输入内容"
            content.addView(this)
        } else null
        var confirmed = false
        var settled = false
        val dialog = AlertDialog.Builder(view.context)
            .setTitle("${runtime.toolName} · ${runtime.toolId}")
            .setView(ScrollView(view.context).apply { addView(content) })
            .setPositiveButton("确定") { _, _ -> confirmed = true }
            .apply { if (kind != Kind.ALERT) setNegativeButton("取消", null) }
            .create()
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(target: Activity) { if (target === activity) dismiss(view) }
            override fun onActivityDestroyed(target: Activity) { if (target === activity) dismiss(view) }
            override fun onActivityCreated(target: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(target: Activity) = Unit
            override fun onActivityResumed(target: Activity) = Unit
            override fun onActivityStopped(target: Activity) = Unit
            override fun onActivitySaveInstanceState(target: Activity, state: Bundle) = Unit
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { dismiss(view) }
        }
        dialog.setOnDismissListener {
            if (active[view] === dialog) active.remove(view)
            activity.application.unregisterActivityLifecycleCallbacks(observer)
            view.removeOnAttachStateChangeListener(attachment)
            if (!settled) {
                settled = true
                if (!confirmed) result.cancel()
                else if (result is JsPromptResult) result.confirm(input?.text?.toString().orEmpty())
                else result.confirm()
            }
        }
        active[view] = dialog
        activity.application.registerActivityLifecycleCallbacks(observer)
        view.addOnAttachStateChangeListener(attachment)
        try { dialog.show() } catch (_: RuntimeException) {
            active.remove(view)
            activity.application.unregisterActivityLifecycleCallbacks(observer)
            view.removeOnAttachStateChangeListener(attachment)
            if (!settled) { settled = true; result.cancel() }
        }
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
