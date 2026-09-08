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

    @Test
    fun userDomainsRequireExplicitDeclarationAndCompatibleHostAndSurviveInstalledVerification() {
        assertEquals(false, parse(30_000).network?.allowUserDomains)
        val source = manifest(30_000, "0.3.12", ",\"allowUserDomains\":true")
        assertEquals(true, ManifestValidator.parse(source, PackageLimits()).network?.allowUserDomains)
        val installed = InstalledManifestVerifier.verify(
            source, "io.toolbox.timeoutfixture", 1, io.toolbox.core.data.SecurityProfile.STRICT,
        ) as InstalledManifestVerification.Verified
        assertEquals(true, installed.manifest.network?.allowUserDomains)
        for (version in listOf("0.3.11", "0.2.99")) {
            val failure = assertThrows(JsonFormatException::class.java) {
                ManifestValidator.parse(manifest(30_000, version, ",\"allowUserDomains\":true"), PackageLimits())
            }
            assertTrue(failure.message.orEmpty().contains("minHostVersion 0.3.12"))
        }
        assertThrows(JsonFormatException::class.java) {
            ManifestValidator.parse(manifest(30_000, "0.3.12", ",\"allowUserDomains\":\"true\""), PackageLimits())
        }
    }

    private fun parse(timeoutMs: Int, minHostVersion: String = "0.3.11") = ManifestValidator.parse(
        manifest(timeoutMs, minHostVersion), PackageLimits(),
    )

    private fun manifest(timeoutMs: Int, minHostVersion: String, extraNetwork: String = "") =
        """
        {"schemaVersion":1,"id":"io.toolbox.timeoutfixture","name":"Timeout fixture","version":"1.0.0","versionCode":1,"entry":"index.html","apiVersion":"1.0","minHostVersion":"$minHostVersion","permissions":[{"name":"network","reason":"Fetch data"}],"network":{"allowDomains":["api.example.com"],"timeoutMs":$timeoutMs$extraNetwork},"securityProfile":"strict"}
        """.trimIndent().toByteArray()
}
