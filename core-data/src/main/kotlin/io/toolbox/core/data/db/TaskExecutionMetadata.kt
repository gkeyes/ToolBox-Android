package io.toolbox.core.data.db

import org.json.JSONObject

/** Metadata lives in host-owned task rows, never in a mini-app's KV namespace. */
internal object TaskExecutionMetadata {
    private const val KEY = "_toolboxExecutionToken"
    fun token(spec: String): String? = JSONObject(spec).optString(KEY).takeIf { it.isNotEmpty() }
    fun spec(spec: String, token: String? = null): String = JSONObject(spec).apply {
        remove(KEY)
        if (token != null) put(KEY, token)
    }.toString()
}
