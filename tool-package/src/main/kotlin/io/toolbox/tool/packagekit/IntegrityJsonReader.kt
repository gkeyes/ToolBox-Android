package io.toolbox.tool.packagekit

import java.io.Reader

/** The integrity format has one large object; consume its entries without building a JSON tree. */
internal class IntegrityJsonReader(private val input: Reader, private val resources: PackageResourceGuard) {
    private var next = input.read()
    private var untilCheck = 0

    fun read(onFile: (String, String) -> Unit) {
        val fields = mutableSetOf<String>()
        objectEntries {
            val key = string(13) // Longest valid root field is schemaVersion.
            if (!fields.add(key)) fail("Duplicate integrity field")
            expect(':')
            when (key) {
                "schemaVersion" -> versionOne()
                "algorithm" -> if (string(7) != "SHA-256") fail("Expected SHA-256")
                "files" -> objectEntries {
                    // ZIP32 names cannot exceed 65535 bytes. Normalized paths are checked separately.
                    val path = string(0xffff)
                    expect(':')
                    onFile(path, string(64))
                }
                else -> fail("Unsupported integrity field")
            }
        }
        if (fields != setOf("schemaVersion", "algorithm", "files")) fail("Missing integrity field")
        whitespace()
        if (next != -1) fail("Trailing integrity JSON data")
    }

    private fun objectEntries(entry: () -> Unit) {
        expect('{')
        whitespace()
        if (consume('}')) return
        while (true) {
            entry()
            whitespace()
            if (consume('}')) return
            expect(',')
        }
    }

    private fun string(maxCharacters: Int): String {
        expect('"')
        val value = StringBuilder()
        while (next != -1) {
            val character = take().toChar()
            when {
                character == '"' -> return value.toString()
                character == '\\' -> value.append(escape())
                character.code < 0x20 -> fail("Control character in string")
                else -> value.append(character)
            }
            if (value.length > maxCharacters) fail("Integrity string does not fit its field")
        }
        fail("Unterminated string")
    }

    private fun escape(): Char = when (val character = take().toChar()) {
        '"', '\\', '/' -> character
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            var code = 0
            repeat(4) {
                val digit = when (val hex = take().toChar()) {
                    in '0'..'9' -> hex - '0'
                    in 'a'..'f' -> hex - 'a' + 10
                    in 'A'..'F' -> hex - 'A' + 10
                    else -> fail("Invalid Unicode escape")
                }
                code = code * 16 + digit
            }
            code.toChar()
        }
        else -> fail("Invalid escape")
    }

    // Preserve integer-valued JSON spellings such as 1.0 and 10e-1 without buffering a number.
    private fun versionOne() {
        whitespace()
        val negative = consume('-')
        var significant = false
        var coefficientIsOne = true
        var trailingZeros = 0L
        var fractionDigits = 0L
        fun coefficientDigit() {
            val digit = take() - '0'.code
            if (!significant && digit != 0) {
                significant = true
                if (digit != 1) coefficientIsOne = false
            } else if (significant) {
                if (digit != 0) coefficientIsOne = false
                trailingZeros++
            }
        }
        if (next == '0'.code) {
            coefficientDigit()
            if (isDigit()) fail("Leading zero in number")
        } else {
            if (!isDigit()) fail("Expected schema version number")
            while (isDigit()) coefficientDigit()
        }
        if (consume('.')) {
            if (!isDigit()) fail("Missing fractional digits")
            while (isDigit()) { coefficientDigit(); fractionDigits++ }
        }
        var exponent = 0L
        var exponentNegative = false
        if (consume('e') || consume('E')) {
            exponentNegative = consume('-')
            if (!exponentNegative) consume('+')
            if (!isDigit()) fail("Missing exponent")
            while (isDigit()) {
                val digit = take() - '0'.code
                if (exponent > (Long.MAX_VALUE - digit) / 10) fail("Schema version is not 1")
                exponent = exponent * 10 + digit
            }
        }
        if (exponentNegative) exponent = -exponent
        if (negative || !significant || !coefficientIsOne || exponent != fractionDigits - trailingZeros) {
            fail("Schema version must be 1")
        }
    }

    private fun isDigit() = next in '0'.code..'9'.code

    private fun take(): Int {
        if (untilCheck-- <= 0) {
            resources.check()
            untilCheck = DEFAULT_BUFFER_SIZE
        }
        val result = next
        if (result == -1) fail("Unexpected end of JSON")
        next = input.read()
        return result
    }

    private fun whitespace() {
        while (next == 0x20 || next == 0x09 || next == 0x0a || next == 0x0d) take()
    }

    private fun consume(character: Char): Boolean {
        if (next != character.code) return false
        take()
        return true
    }

    private fun expect(character: Char) {
        whitespace()
        if (!consume(character)) fail("Expected '$character'")
    }

    private fun fail(message: String): Nothing = throw JsonFormatException(message)
}
