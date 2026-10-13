package io.toolbox.tool.packagekit

import java.io.StringReader
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonTest {
    @Test
    fun unicodeEscapeAcceptsExactlyFourAsciiHexDigits() {
        assertEquals(JsonValue.StringValue("A\u00e9\uABCD"), StrictJson.parse("\"\\u0041\\u00E9\\uabcd\"".toByteArray()))
        assertEquals(JsonValue.StringValue("\uD83D\uDE00"), StrictJson.parse("\"\\uD83D\\uDE00\"".toByteArray()))
    }

    @Test
    fun unicodeEscapeRejectsSignsAndNonHexDigits() {
        // "+00A"/"-00A" used to slip through String.toIntOrNull(16); full-width digits are not hex either.
        listOf("+00A", "-00A", "+123", "00G0", "12 4", "\uFF10\uFF10\uFF14\uFF11").forEach { digits ->
            val error = runCatching { StrictJson.parse("\"\\u$digits\"".toByteArray()) }.exceptionOrNull()
            assertTrue("\\u$digits must be rejected", error is JsonFormatException)
        }
        assertTrue(runCatching { StrictJson.parse("\"\\u004\"".toByteArray()) }.exceptionOrNull() is JsonFormatException)
    }

    @Test
    fun strictAndIntegrityReadersAgreeOnUnicodeEscapes() {
        listOf("0041", "+041", "-041", "004g").forEach { digits ->
            val strict = runCatching { StrictJson.parse("\"a\\u${digits}\"".toByteArray()) }
            val integrity = runCatching { readIntegrityPath("a\\u$digits") }
            assertEquals("\\u$digits", strict.isSuccess, integrity.isSuccess)
            if (strict.isSuccess) {
                assertEquals((strict.getOrThrow() as JsonValue.StringValue).value, integrity.getOrThrow())
            }
        }
    }

    @Test
    fun duplicateObjectKeysAreStillRejected() {
        val error = runCatching { StrictJson.parse("""{"a":1,"a":2}""".toByteArray()) }.exceptionOrNull()
        assertTrue(error is JsonFormatException)
        val escaped = runCatching { StrictJson.parse("""{"a":1,"\u0061":2}""".toByteArray()) }.exceptionOrNull()
        assertTrue(escaped is JsonFormatException)
    }

    private fun readIntegrityPath(escapedPath: String): String {
        val guard = PackageResourceGuard(
            Path.of("."),
            PackageResourceProbe { PackageResourceSnapshot(Long.MAX_VALUE, lowMemory = false) },
        )
        val json = """{"schemaVersion":1,"algorithm":"SHA-256","files":{"$escapedPath":"${"0".repeat(64)}"}}"""
        var path: String? = null
        IntegrityJsonReader(StringReader(json), guard).read { rawPath, _ -> path = rawPath }
        return checkNotNull(path)
    }
}
