package io.toolbox.tool.packagekit

import io.toolbox.core.data.SecurityProfile as CatalogSecurityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestUiTest {
    @Test
    fun installedManifestCarriesTheUiBlock() {
        assertEquals(
            ManifestUi(
                orientation = ManifestOrientation.PORTRAIT,
                allowFullscreen = true,
                statusBarStyle = ManifestStatusBarStyle.LIGHT,
            ),
            installedUi("""{"orientation":"portrait","allowFullscreen":true,"statusBarStyle":"light","showHostToolbar":true}"""),
        )
        assertEquals(
            ManifestUi(
                orientation = ManifestOrientation.LANDSCAPE,
                allowFullscreen = false,
                statusBarStyle = ManifestStatusBarStyle.DARK,
            ),
            installedUi("""{"orientation":"landscape","statusBarStyle":"dark"}"""),
        )
    }

    @Test
    fun missingOrEmptyUiUsesHostDefaults() {
        val defaults = ManifestUi(orientation = null, allowFullscreen = false, statusBarStyle = ManifestStatusBarStyle.AUTO)
        assertEquals(defaults, installedUi(null))
        assertEquals(defaults, installedUi("{}"))
    }

    @Test
    fun deprecatedShowHostToolbarIsAcceptedButStillTypeChecked() {
        // Installed packages declare it, so it must keep verifying; it has no effect.
        assertEquals(ManifestUi(), installedUi("""{"showHostToolbar":false}"""))
        listOf(
            """{"showHostToolbar":"no"}""",
            """{"orientation":"sideways"}""",
            """{"statusBarStyle":"black"}""",
            """{"allowFullscreen":1}""",
            """{"theme":"dark"}""",
        ).forEach { block ->
            assertTrue(block, runCatching { ManifestValidator.parse(manifest(block)) }.exceptionOrNull() is JsonFormatException)
        }
    }

    private fun installedUi(block: String?): ManifestUi {
        val verified = InstalledManifestVerifier.verify(manifest(block), "com.example.ui", 1, CatalogSecurityProfile.STRICT)
        assertTrue(verified.toString(), verified is InstalledManifestVerification.Verified)
        return (verified as InstalledManifestVerification.Verified).manifest.ui
    }

    private fun manifest(ui: String?) = """{
        "schemaVersion":1,"id":"com.example.ui","name":"Ui","version":"1.0.0","versionCode":1,
        "entry":"index.html","apiVersion":"1.0","minHostVersion":"0.7.8","permissions":[],"securityProfile":"strict"
        ${ui?.let { ",\"ui\":$it" } ?: ""}
    }""".toByteArray()
}
