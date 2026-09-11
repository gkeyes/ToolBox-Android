package io.toolbox.tool.packagekit.backup

import io.toolbox.tool.packagekit.JsonValue
import io.toolbox.tool.packagekit.StrictJson
import java.io.File
import java.math.BigDecimal

/** Strict UTF-8 and duplicate-key rejection also apply to user-selected backup metadata. */
object BackupJson {
    fun parse(bytes: ByteArray): Any? = convert(StrictJson.parse(bytes))
    fun read(file: File, limit: Long = 4L * 1024 * 1024): Map<String, Any?> {
        require(file.length() <= limit) { "JSON_LIMIT" }
        return obj(parse(file.readBytes()))
    }
    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: error("JSON_OBJECT")
    fun string(value: Any?): String = value as? String ?: error("JSON_STRING")
    fun long(value: Any?): Long = when (value) {
        is BigDecimal -> value.longValueExact()
        is Long -> value
        is Int -> value.toLong()
        else -> error("JSON_INTEGER")
    }
    fun int(value: Any?): Int = Math.toIntExact(long(value))
    fun bool(value: Any?): Boolean = value as? Boolean ?: error("JSON_BOOLEAN")
    fun array(value: Any?): List<Any?> = value as? List<*> ?: error("JSON_ARRAY")
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32 || c.isSurrogate()) append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
        is Boolean, is Int, is Long, is BigDecimal -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, item) -> "${encode(key as String)}:${encode(item)}" }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> error("JSON_TYPE")
    }
    private fun convert(value: JsonValue): Any? = when (value) {
        is JsonValue.ObjectValue -> value.values.mapValues { convert(it.value) }
        is JsonValue.ArrayValue -> value.values.map(::convert)
        is JsonValue.StringValue -> value.value
        is JsonValue.NumberValue -> value.value
        is JsonValue.BooleanValue -> value.value
        JsonValue.NullValue -> null
    }
}
