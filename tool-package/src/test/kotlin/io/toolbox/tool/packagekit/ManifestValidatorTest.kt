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

    @Test
    fun legacyDomainFieldsRemainReadableWithoutAnExtraHostGate() {
        assertEquals(false, parse(30_000).network?.allowUserDomains)
        val source = manifest(30_000, "0.6.3", ",\"allowUserDomains\":true")
        assertEquals(true, ManifestValidator.parse(source, PackageLimits()).network?.allowUserDomains)
        val installed = InstalledManifestVerifier.verify(
            source, "io.toolbox.timeoutfixture", 1, io.toolbox.core.data.SecurityProfile.STRICT,
        ) as InstalledManifestVerification.Verified
        assertEquals(true, installed.manifest.network?.allowUserDomains)
        for (version in listOf("0.3.12", "0.6.1", "0.6.2")) {
            assertEquals(true, ManifestValidator.parse(manifest(30_000, version, ",\"allowUserDomains\":true"), PackageLimits()).network?.allowUserDomains)
        }
        assertThrows(JsonFormatException::class.java) {
            ManifestValidator.parse(manifest(30_000, "0.6.3", ",\"allowUserDomains\":\"true\""), PackageLimits())
        }
    }

    @Test
    fun networkPermissionDoesNotRequireDestinationListsOrNetworkOptions() {
        val source = manifest(30_000, "0.6.4").toString(Charsets.UTF_8)
        val withoutOptions = source.replace("\"network\":{\"allowDomains\":[\"api.example.com\"],\"timeoutMs\":30000},", "")
        assertEquals(null, ManifestValidator.parse(withoutOptions.toByteArray(), PackageLimits()).network)
        val withoutDomains = source.replace("\"allowDomains\":[\"api.example.com\"],", "")
        assertTrue(ManifestValidator.parse(withoutDomains.toByteArray(), PackageLimits()).network!!.allowDomains.isEmpty())
        val emptyDomains = source.replace("[\"api.example.com\"]", "[]")
        assertTrue(ManifestValidator.parse(emptyDomains.toByteArray(), PackageLimits()).network!!.allowDomains.isEmpty())
    }

    private fun parse(timeoutMs: Int, minHostVersion: String = "0.6.1") = ManifestValidator.parse(
        manifest(timeoutMs, minHostVersion), PackageLimits(),
    )

    private fun manifest(timeoutMs: Int, minHostVersion: String, extraNetwork: String = "") =
        """
        {"schemaVersion":1,"id":"io.toolbox.timeoutfixture","name":"Timeout fixture","version":"1.0.0","versionCode":1,"entry":"index.html","apiVersion":"1.0","minHostVersion":"$minHostVersion","permissions":[{"name":"network","reason":"Fetch data"}],"network":{"allowDomains":["api.example.com"],"timeoutMs":$timeoutMs$extraNetwork},"securityProfile":"strict"}
        """.trimIndent().toByteArray()
}
