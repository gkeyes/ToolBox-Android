package io.toolbox.host.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Browser-process-only settings; no tool runtime storage or webpage bridge is exposed. */
class BrowserFilterStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("browser_filters_v1", Context.MODE_PRIVATE)

    fun read(): BrowserFilterSnapshot {
        val rules = runCatching {
            val array = JSONArray(prefs.getString("rules", "[]"))
            (0 until minOf(array.length(), BrowserFilterValidation.MAX_RULES)).mapNotNull { i ->
                runCatching {
                    val row = array.getJSONObject(i)
                    BrowserFilterValidation.normalize(BrowserFilterRule(
                        id = row.getString("id"), site = row.getString("site"), value = row.getString("value"),
                        kind = BrowserFilterKind.valueOf(row.getString("kind")),
                        source = BrowserFilterSource.valueOf(row.getString("source")), enabled = row.optBoolean("enabled", true),
                    ))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        return BrowserFilterSnapshot(
            enabled = prefs.getBoolean("enabled", true), builtIn = prefs.getBoolean("builtIn", true),
            exceptions = prefs.getStringSet("exceptions", emptySet()).orEmpty().toSet(), rules = rules,
        )
    }

    fun write(snapshot: BrowserFilterSnapshot) {
        val rows = JSONArray()
        snapshot.rules.forEach { rule ->
            rows.put(JSONObject().put("id", rule.id).put("site", rule.site).put("value", rule.value)
                .put("kind", rule.kind.name).put("source", rule.source.name).put("enabled", rule.enabled))
        }
        prefs.edit().putBoolean("enabled", snapshot.enabled).putBoolean("builtIn", snapshot.builtIn)
            .putStringSet("exceptions", snapshot.exceptions).putString("rules", rows.toString()).apply()
    }
}
