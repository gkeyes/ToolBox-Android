package io.toolbox.host.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Only native-initiated evaluateJavascript callbacks cross the boundary. Pages get no native API. */
class BrowserFilterController(context: Context) {
    private val store = BrowserFilterStore(context)
    private val script by lazy { context.applicationContext.assets.open("browser/element-picker.js").bufferedReader().use { it.readText() } }
    var snapshot by mutableStateOf(store.read())
        private set
    @Volatile private var engine = snapshot
    private data class RequestPage(val host: String, val blocked: AtomicInteger = AtomicInteger())
    @Volatile private var requestPage = RequestPage("")
    private var page: WebView? = null
    private var generation = 0
    private var pickerEpoch = 0
    private var pollPending = false
    var saving by mutableStateOf(false)
        private set
    private var key = newKey()
    var site by mutableStateOf("")
        private set
    var picker by mutableStateOf(PickerState())
        private set
    var sheet by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
        private set
    private var undo: (() -> Unit)? = null
    val canUndo get() = undo != null
    val blockedCount get() = requestPage.blocked.get()

    data class PickerState(
        val active: Boolean = false, val selector: String = "", val count: Int = 0,
        val warning: String = "", val preview: Boolean = false,
        val canShrink: Boolean = false, val canExpand: Boolean = false,
    )

    fun attach(view: WebView, url: String) {
        page = view
        navigated(url)
    }

    fun navigated(url: String) {
        page?.evaluateJavascript("window[${JSONObject.quote(key)}]?.stop()", null)
        generation++
        pickerEpoch++
        pollPending = false
        key = newKey()
        site = BrowserFilterValidation.urlHost(url).orEmpty()
        requestPage = RequestPage(site)
        picker = PickerState()
        sheet = false
        notice = null
        undo = null
    }

    fun historyChanged(url: String) {
        val host = BrowserFilterValidation.urlHost(url).orEmpty()
        if (host != site) navigated(url)
    }

    fun detach(view: WebView) {
        if (page !== view) return
        stopPicker()
        page = null
        generation++
        requestPage = RequestPage("")
    }

    fun refresh() { snapshot = store.read(); engine = snapshot; applyToPage() }

    fun change(transform: (BrowserFilterSnapshot) -> BrowserFilterSnapshot) {
        snapshot = transform(store.read())
        engine = snapshot
        store.write(snapshot)
        stopPicker()
        applyToPage()
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val current = requestPage
        if (!engine.blocks(current.host, request.url.toString(), request.isForMainFrame)) return null
        current.blocked.incrementAndGet()
        return WebResourceResponse("text/plain", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }

    fun applyToPage(after: ((Boolean) -> Unit)? = null) {
        val view = page ?: run { after?.invoke(false); return }
        val expected = generation
        val host = site
        if (host.isEmpty() || BrowserFilterValidation.urlHost(view.url.orEmpty()) != host) { after?.invoke(false); return }
        val values = JSONArray(snapshot.selectors(host)).toString()
        view.evaluateJavascript("($script)(${JSONObject.quote(key)},$values,${JSONObject.quote(host)})") { value ->
            after?.invoke(page === view && generation == expected && value == "true")
        }
    }

    fun startPicker() {
        if (!snapshot.active(site)) return
        sheet = false
        notice = null
        val epoch = ++pickerEpoch
        applyToPage { applied -> if (applied && epoch == pickerEpoch) command("start") else notice = "当前页面暂不能点选，请加载完成后重试。" }
    }

    fun stopPicker() {
        pickerEpoch++
        pollPending = false
        page?.evaluateJavascript("window[${JSONObject.quote(key)}]?.stop()", null)
        picker = PickerState()
    }

    fun poll() { if (picker.active && !pollPending) { pollPending = true; command("state") } }
    fun adjust(delta: Int) = command("adjust", if (delta > 0) "1" else "-1")
    fun preview() = command("preview")
    fun editPicker(value: String) = command("edit", JSONObject.quote(value.take(BrowserFilterValidation.MAX_SELECTOR_LENGTH)))

    private fun evaluate(method: String, args: String = "", result: (JSONObject?) -> Unit) {
        val view = page ?: run { result(null); return }
        val expected = generation
        view.evaluateJavascript("window[${JSONObject.quote(key)}]?.$method($args)") { raw ->
            result(if (page === view && expected == generation) runCatching { JSONObject(raw ?: "null") }.getOrNull() else null)
        }
    }

    private fun command(method: String, args: String = "") {
        val epoch = pickerEpoch
        evaluate(method, args) { data ->
            if (epoch != pickerEpoch) return@evaluate
            if (method == "state") pollPending = false
            if (data == null) { picker = PickerState(); return@evaluate }
            picker = PickerState(
                active = data.optBoolean("active"), selector = data.optString("selector").take(1024),
                count = data.optInt("count").coerceIn(0, 100000), warning = data.optString("warning").take(160),
                preview = data.optBoolean("preview"), canShrink = data.optBoolean("canShrink"), canExpand = data.optBoolean("canExpand"),
            )
        }
    }

    fun savePicker() {
        if (!picker.active || !picker.preview || picker.count <= 0) return
        save(BrowserFilterRule(site = site, value = picker.selector, source = BrowserFilterSource.Picker)) { failure ->
            if (failure != null) notice = failure
        }
    }

    fun save(input: BrowserFilterRule, completion: (String?) -> Unit) {
        if (saving) { completion("正在保存，请稍候。"); return }
        saving = true
        val savePage = page
        val expectedGeneration = generation
        var finished = false
        fun result(message: String?) {
            if (finished) return
            finished = true
            saving = false
            completion(message)
        }
        // A stalled renderer must not leave the editor permanently disabled or
        // allow a late callback to write a rule after the timeout.
        savePage?.postDelayed({ result("页面未响应，请稍后重试。") }, 5000)
        val rule = BrowserFilterValidation.normalize(input)
        if (rule == null) { result("网站域名或规则格式不正确。"); return }
        val latest = store.read()
        if (latest.rules.size >= BrowserFilterValidation.MAX_RULES && latest.rules.none { it.id == rule.id }) {
            result("最多保存 ${BrowserFilterValidation.MAX_RULES} 条规则，请先整理现有规则。"); return
        }
        if (latest.rules.any { it.id != rule.id && it.site == rule.site && it.kind == rule.kind && it.value == rule.value }) {
            result("此规则已存在，请在规则列表中启用或编辑。"); return
        }
        fun commit() {
            if (finished) return
            if (generation != expectedGeneration) { result("页面已变化，请重新选择网站。"); return }
            val previous = store.read().rules.firstOrNull { it.id == rule.id }
            change { state ->
                state.copy(rules = state.rules.filterNot { it.id == rule.id } + rule)
            }
            undo = {
                change { state -> state.copy(rules = state.rules.filterNot { it.id == rule.id } + listOfNotNull(previous)) }
            }
            notice = "已对此网站生效"
            result(null)
        }
        if (rule.kind == BrowserFilterKind.Cosmetic && rule.site == site) {
            applyToPage { applied ->
                if (finished) return@applyToPage
                if (!applied) { result("页面暂不可用，请加载完成后重试。"); return@applyToPage }
                evaluate("inspect", JSONObject.quote(rule.value)) { checked ->
                    if (finished) return@evaluate
                    if (checked?.optBoolean("valid") != true) result(checked?.optString("message") ?: "页面已变化，请重新打开后保存。")
                    else if (rule.source == BrowserFilterSource.Picker && checked.optInt("count") <= 0) result("页面已变化，请重新选择。")
                    else commit()
                }
            }
        } else commit()
    }

    fun delete(rule: BrowserFilterRule) {
        change { state -> state.copy(rules = state.rules.filterNot { it.id == rule.id }) }
        undo = { change { state -> if (state.rules.any { it.id == rule.id }) state else state.copy(rules = state.rules + rule) } }
        notice = "规则已删除"
    }

    fun resetPicked() {
        val host = site
        val removed = snapshot.rules.filter { it.site == host && it.source == BrowserFilterSource.Picker }
        change { state -> state.copy(rules = state.rules.filterNot { it.site == host && it.source == BrowserFilterSource.Picker }) }
        undo = { change { state -> state.copy(rules = (state.rules + removed).distinctBy { it.id }) } }
        notice = "此网站的点选规则已清除"
    }

    fun undoLast() { val action = undo; undo = null; notice = null; action?.invoke() }
    fun dismissNotice() { notice = null; undo = null }
    private fun newKey() = "__toolbox_filter_" + UUID.randomUUID().toString().replace("-", "")
}
