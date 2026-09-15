package io.toolbox.tool.packagekit.backup

import io.toolbox.tool.packagekit.JsonValue
import io.toolbox.tool.packagekit.StrictJson
import io.toolbox.core.data.ResourceCapacity
import java.io.File
import java.math.BigDecimal

/** Strict UTF-8 and duplicate-key rejection also apply to user-selected backup metadata. */
object BackupJson {
    fun parse(bytes: ByteArray): Any? = convert(StrictJson.parse(bytes))
    fun read(file: File): Map<String, Any?> {
        // UTF-8 source plus UTF-16 decoding can coexist with the original bytes.
        ResourceCapacity.requireHeapBytes(Math.multiplyExact(file.length(), 3))
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
    private fun convert(value: JsonValue): Any? {
        val pending = ArrayDeque<Pair<JsonValue, Any>>()
        fun allocate(source: JsonValue): Any? = when (source) {
            is JsonValue.ObjectValue -> linkedMapOf<String, Any?>().also { pending.addLast(source to it) }
            is JsonValue.ArrayValue -> mutableListOf<Any?>().also { pending.addLast(source to it) }
            is JsonValue.StringValue -> source.value
            is JsonValue.NumberValue -> source.value
            is JsonValue.BooleanValue -> source.value
            JsonValue.NullValue -> null
        }
        val result = allocate(value)
        while (pending.isNotEmpty()) {
            val (source, target) = pending.removeLast()
            when (source) {
                is JsonValue.ObjectValue -> {
                    @Suppress("UNCHECKED_CAST") val map = target as MutableMap<String, Any?>
                    source.values.forEach { (key, child) -> map[key] = allocate(child) }
                }
                is JsonValue.ArrayValue -> {
                    @Suppress("UNCHECKED_CAST") val list = target as MutableList<Any?>
                    source.values.forEach { list.add(allocate(it)) }
                }
                else -> error("JSON_CONTAINER")
            }
        }
        return result
    }
}
