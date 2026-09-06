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

        val oldHostFailure = assertThrows(JsonFormatException::class.java) {
            parse(600_001, minHostVersion = "0.3.10")
        }
        assertTrue(oldHostFailure.message.orEmpty().contains("minHostVersion"))
    }

    private fun parse(timeoutMs: Int, minHostVersion: String = "0.3.11") = ManifestValidator.parse(
        """
        {"schemaVersion":1,"id":"io.toolbox.timeoutfixture","name":"Timeout fixture","version":"1.0.0","versionCode":1,"entry":"index.html","apiVersion":"1.0","minHostVersion":"$minHostVersion","permissions":[{"name":"network","reason":"Fetch data"}],"network":{"allowDomains":["api.example.com"],"timeoutMs":$timeoutMs},"securityProfile":"strict"}
        """.trimIndent().toByteArray(),
        PackageLimits(),
    )
}
