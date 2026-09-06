package io.toolbox.tool.packagekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestValidatorTest {
    @Test
    fun networkTimeoutAcceptsThe60MinuteCeilingAndRequiresACompatibleHost() {
        assertEquals(3_600_000, parse(3_600_000).network?.timeoutMs)

        val failure = assertThrows(JsonFormatException::class.java) { parse(3_600_001) }
        assertTrue(failure.message.orEmpty().contains("network.timeoutMs"))

        val malformedHostFailure = assertThrows(JsonFormatException::class.java) {
            parse(600_001, minHostVersion = "2147483648.0.0")
        }
        assertTrue(malformedHostFailure.message.orEmpty().contains("minHostVersion"))

        for (oldHost in listOf("0.3.10", "0.3.11", "0.5.0", "0.6.0")) {
            assertEquals(600_000, parse(600_000, minHostVersion = oldHost).network?.timeoutMs)
            val oldHostFailure = assertThrows(JsonFormatException::class.java) {
                parse(600_001, minHostVersion = oldHost)
            }
            assertTrue(oldHostFailure.message.orEmpty().contains("minHostVersion"))
        }
    }

    private fun parse(timeoutMs: Int, minHostVersion: String = "0.6.1") = ManifestValidator.parse(
        """
        {"schemaVersion":1,"id":"io.toolbox.timeoutfixture","name":"Timeout fixture","version":"1.0.0","versionCode":1,"entry":"index.html","apiVersion":"1.0","minHostVersion":"$minHostVersion","permissions":[{"name":"network","reason":"Fetch data"}],"network":{"allowDomains":["api.example.com"],"timeoutMs":$timeoutMs},"securityProfile":"strict"}
        """.trimIndent().toByteArray(),
        PackageLimits(),
    )
}
