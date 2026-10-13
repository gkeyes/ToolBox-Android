package io.toolbox.core.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Metadata lives in host-owned task rows, never in a mini-app's KV namespace. */
internal object TaskExecutionMetadata {
    private const val KEY = "_toolboxExecutionToken"
    fun token(spec: String): String? =
        (parse(spec)[KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    fun spec(spec: String, token: String? = null): String {
        val values = LinkedHashMap(parse(spec))
        values.remove(KEY)
        if (token != null) values[KEY] = JsonPrimitive(token)
        return JsonObject(values).toString()
    }
    private fun parse(spec: String): JsonObject = Json.parseToJsonElement(spec).jsonObject
}
