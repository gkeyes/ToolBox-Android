package io.toolbox.tool.packagekit

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface JsonValue {
    data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: BigDecimal) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal class JsonFormatException(message: String) : Exception(message)

internal object StrictJson {
    fun parse(bytes: ByteArray): JsonValue {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw JsonFormatException("JSON is not valid UTF-8: ${error.message}")
        }
        return Parser(text).parse()
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = readValue(depth = 0)
            skipWhitespace()
            if (index != text.length) fail("Trailing JSON data")
            return value
        }

        private fun readValue(depth: Int): JsonValue {
            if (depth > 64) fail("JSON nesting exceeds 64 levels")
            if (index >= text.length) fail("Unexpected end of JSON")
            return when (text[index]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> JsonValue.StringValue(readString())
                't' -> readLiteral("true", JsonValue.BooleanValue(true))
                'f' -> readLiteral("false", JsonValue.BooleanValue(false))
                'n' -> readLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> readNumber()
                else -> fail("Unexpected character '${text[index]}'")
            }
        }

        private fun readObject(depth: Int): JsonValue.ObjectValue {
            index++
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.ObjectValue(values)
            while (true) {
                if (index >= text.length || text[index] != '"') fail("Object key must be a string")
                val key = readString()
                if (values.containsKey(key)) fail("Duplicate object key: $key")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                values[key] = readValue(depth)
                skipWhitespace()
                if (consume('}')) return JsonValue.ObjectValue(values)
                expect(',')
                skipWhitespace()
            }
        }

        private fun readArray(depth: Int): JsonValue.ArrayValue {
            index++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.ArrayValue(values)
            while (true) {
                values += readValue(depth)
                skipWhitespace()
                if (consume(']')) return JsonValue.ArrayValue(values)
                expect(',')
                skipWhitespace()
            }
        }

        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> result.append(readEscape())
                    character.code < 0x20 -> fail("Control character in string")
                    else -> result.append(character)
                }
            }
            fail("Unterminated string")
        }

        private fun readEscape(): Char {
            if (index >= text.length) fail("Unterminated escape")
            return when (val escaped = text[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) fail("Incomplete Unicode escape")
                    val code = text.substring(index, index + 4).toIntOrNull(16)
                        ?: fail("Invalid Unicode escape")
                    index += 4
                    code.toChar()
                }
                else -> fail("Invalid escape: \\$escaped")
            }
        }

        private fun readNumber(): JsonValue.NumberValue {
            val start = index
            consume('-')
            if (consume('0')) {
                if (index < text.length && text[index].isDigit()) fail("Leading zero in number")
            } else {
                readDigits(required = true)
            }
            if (consume('.')) readDigits(required = true)
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                readDigits(required = true)
            }
            return try {
                JsonValue.NumberValue(text.substring(start, index).toBigDecimal())
            } catch (error: NumberFormatException) {
                fail("Invalid number")
            }
        }

        private fun readDigits(required: Boolean) {
            val start = index
            while (index < text.length && text[index].isDigit()) index++
            if (required && start == index) fail("Expected digit")
        }

        private fun <T : JsonValue> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) fail("Invalid literal")
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in charArrayOf(' ', '\n', '\r', '\t')) index++
        }

        private fun consume(expected: Char): Boolean {
            if (index < text.length && text[index] == expected) {
                index++
                return true
            }
            return false
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) fail("Expected '$expected'")
        }

        private fun fail(message: String): Nothing = throw JsonFormatException("$message at character $index")
    }
}

internal fun JsonValue.asObject(label: String): Map<String, JsonValue> =
    (this as? JsonValue.ObjectValue)?.values ?: throw JsonFormatException("$label must be an object")

internal fun JsonValue.asArray(label: String): List<JsonValue> =
    (this as? JsonValue.ArrayValue)?.values ?: throw JsonFormatException("$label must be an array")

internal fun JsonValue.asString(label: String): String =
    (this as? JsonValue.StringValue)?.value ?: throw JsonFormatException("$label must be a string")

internal fun JsonValue.asBoolean(label: String): Boolean =
    (this as? JsonValue.BooleanValue)?.value ?: throw JsonFormatException("$label must be a boolean")

internal fun JsonValue.asInt(label: String): Int {
    val number = (this as? JsonValue.NumberValue)?.value
        ?: throw JsonFormatException("$label must be an integer")
    return try {
        number.intValueExact()
    } catch (error: ArithmeticException) {
        throw JsonFormatException("$label must be an integer")
    }
}

internal fun Map<String, JsonValue>.requireOnly(label: String, allowed: Set<String>) {
    val unexpected = keys - allowed
    if (unexpected.isNotEmpty()) throw JsonFormatException("$label contains unsupported fields: ${unexpected.sorted()}")
}

internal fun Map<String, JsonValue>.required(name: String): JsonValue =
    this[name] ?: throw JsonFormatException("Missing required field: $name")
