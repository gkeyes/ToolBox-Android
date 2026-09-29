package io.toolbox.tool.packagekit

import org.junit.Assert.*
import org.junit.Test

class ManifestNetworkBudgetTest {
    @Test
    fun installedAndImportedBudgetsPreserveSafeLongsAndOmittedState() {
        listOf(null, "1", "2147483647", "2147483648", "9007199254740991").forEach { budget ->
            val bytes = manifest(budget)
            val parsed = ManifestValidator.parse(bytes)
            assertEquals(budget?.toLong(), parsed.network?.maxResponseBytes)
            val installed = InstalledManifestVerifier.verify(bytes, "com.example.budget", 1, io.toolbox.core.data.SecurityProfile.STRICT)
            assertTrue(installed is InstalledManifestVerification.Verified)
            assertEquals(budget?.toLong(), (installed as InstalledManifestVerification.Verified).manifest.network?.maxResponseBytes)
        }
    }

    @Test
    fun unsafeFractionalAndNonpositiveBudgetsAreRejectedInBothPaths() {
        listOf("0", "-1", "1.5", "9007199254740992", "9223372036854775808", "\"100\"").forEach { budget ->
            val bytes = manifest(budget)
            assertTrue(runCatching { ManifestValidator.parse(bytes) }.exceptionOrNull() is JsonFormatException)
            assertTrue(InstalledManifestVerifier.verify(bytes, "com.example.budget", 1, io.toolbox.core.data.SecurityProfile.STRICT)
                is InstalledManifestVerification.Rejected)
        }
    }

    @Test
    fun removedCompatibilityFieldsAreRejected() {
        val base = manifest(null).toString(Charsets.UTF_8)
        val removedFields = listOf(
            base.replace("\"network\":{}", "\"network\":{\"allowDomains\":[]}"),
            base.replace("\"network\":{}", "\"network\":{\"allowUserDomains\":true}"),
            base.replace("\"network\":{}", "\"network\":{\"allowRedirects\":true}"),
            base.replace("\"network\":{}", "\"limits\":{\"storageBytes\":1}"),
            base.replace("\"network\":{}", "\"limits\":{\"maxBridgePayloadBytes\":1}"),
        )
        removedFields.forEach { bytes ->
            assertTrue(runCatching { ManifestValidator.parse(bytes.toByteArray()) }.exceptionOrNull() is JsonFormatException)
        }
    }

    private fun manifest(budget: String?) = """{
        "schemaVersion":1,"id":"com.example.budget","name":"Budget","version":"1.0.0","versionCode":1,
        "entry":"index.html","apiVersion":"1.0","minHostVersion":"0.7.8","permissions":[],"securityProfile":"strict",
        "network":{${budget?.let { "\"maxResponseBytes\":$it" } ?: ""}}
    }""".toByteArray()
}
