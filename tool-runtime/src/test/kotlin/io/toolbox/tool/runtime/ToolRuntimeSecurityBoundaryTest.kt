package io.toolbox.tool.runtime

import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolRuntimeSecurityBoundaryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exactOriginCanonicalBundleEntryAndOfflinePolicyFailClosed() {
        val toolId = "com.example.alpha"
        val otherToolId = "com.example.beta"
        val origin = RuntimeIdentity.origin(toolId)
        assertEquals("https://rea3ldsl3lcggkr5hhzzya6w7k.toolbox.invalid/", origin)
        assertEquals("tbx_8901b58e4bdac4632a3d39f3", RuntimeIdentity.profileName(toolId))
        assertEquals(origin, RuntimeIdentity.origin(toolId))
        assertNotEquals(origin, RuntimeIdentity.origin(otherToolId))
        assertTrue(origin.matches(Regex("^https://[a-z2-7]{26}\\.toolbox\\.invalid/$")))
        assertTrue(RuntimeIdentity.profileName(toolId).matches(Regex("^tbx_[0-9a-f]{24}$")))
        assertTrue(RuntimeIdentity.isExactLocalUrl(origin + "index.html", origin))
        assertFalse(RuntimeIdentity.isExactLocalUrl("http://${RuntimeIdentity.originHost(toolId)}/index.html", origin))
        assertFalse(RuntimeIdentity.isExactLocalUrl("https://${RuntimeIdentity.originHost(toolId)}:443/index.html", origin))
        assertFalse(RuntimeIdentity.isExactLocalUrl("https://${RuntimeIdentity.originHost(toolId)}.evil/index.html", origin))
        listOf(
            "file:///data/local/tmp/index.html",
            "content://com.example.provider/index.html",
            "intent://index.html#Intent;scheme=https;end",
            "javascript:alert(1)",
            "https://localhost/index.html",
            "http://127.0.0.1/index.html",
        ).forEach { dangerousUrl ->
            assertFalse(RuntimeIdentity.isExactLocalUrl(dangerousUrl, origin))
        }

        val filesRoot = temporaryFolder.newFolder("files")
        val locator = RuntimeIdentity.expectedBundleLocator(toolId, 7)
        val bundle = filesRoot.toPath().resolve(locator)
        Files.createDirectories(bundle)
        val validManifest = manifest(toolId, "Alpha", 7, "index.html", "strict")
        Files.writeString(bundle.resolve("manifest.json"), validManifest)
        Files.writeString(bundle.resolve("index.html"), "<!doctype html><script src=\"app.js\"></script>")
        Files.writeString(bundle.resolve("app.js"), "document.body.textContent = 'ok'")

        val tool = installedTool(toolId, "Alpha", 7)
        val preparer = ToolRuntimePreparer(filesRoot, hostVersion = "0.3.3")
        val prepared = preparer.prepare(toolId, tool)
        assertTrue(prepared is RuntimePreparationResult.Prepared)

        val wrongLocator = tool.currentVersion.copy(bundleLocator = BundleLocator("miniapps/$toolId/versions/6/bundle"))
        assertEquals(
            RuntimePreparationCode.LOCATOR_MISMATCH,
            (preparer.prepare(toolId, tool.copy(currentVersion = wrongLocator)) as RuntimePreparationResult.Failed).code,
        )

        Files.writeString(bundle.resolve("manifest.json"), manifest(toolId, "Alpha", 7, "../index.html", "strict"))
        assertEquals(
            RuntimePreparationCode.MANIFEST_INVALID,
            (preparer.prepare(toolId, tool) as RuntimePreparationResult.Failed).code,
        )

        Files.writeString(bundle.resolve("manifest.json"), validManifest)
        Files.delete(bundle.resolve("index.html"))
        Files.createSymbolicLink(bundle.resolve("index.html"), bundle.resolve("app.js"))
        assertEquals(
            RuntimePreparationCode.ENTRY_UNAVAILABLE,
            (preparer.prepare(toolId, tool) as RuntimePreparationResult.Failed).code,
        )

        val strict = RuntimePolicy.contentSecurityPolicy(SecurityProfile.STRICT)
        val compat = RuntimePolicy.contentSecurityPolicy(SecurityProfile.COMPAT)
        assertTrue("connect-src 'none'" in strict)
        assertTrue("frame-src 'none'" in strict)
        assertTrue("worker-src 'self'" in strict)
        assertFalse("worker-src 'self' blob:" in strict)
        assertFalse("worker-src *" in strict)
        assertTrue("script-src 'self';" in strict)
        assertFalse("script-src 'self' 'unsafe-inline'" in strict)
        assertTrue("script-src 'self' 'unsafe-inline'" in compat)
        assertFalse("unsafe-eval" in compat)
    }

    private fun installedTool(toolId: String, name: String, versionCode: Int) = InstalledTool(
        metadata = ToolMetadata(
            id = toolId,
            name = name,
            securityProfile = SecurityProfile.STRICT,
            installedAt = 1L,
        ),
        currentVersion = toolVersion(toolId, versionCode, RuntimeIdentity.expectedBundleLocator(toolId, versionCode)),
        lastOpenedAt = null,
    )

    private fun toolVersion(toolId: String, versionCode: Int, locator: String) = ToolVersion(
        toolId = toolId,
        versionCode = versionCode,
        version = "1.0.0",
        bundleLocator = BundleLocator(locator),
        bundleBytes = 3L,
        integrityHash = "0".repeat(64),
        installedAt = 1L,
    )

    private fun manifest(
        toolId: String,
        name: String,
        versionCode: Int,
        entry: String,
        securityProfile: String,
    ) = """
        {
          "schemaVersion": 1,
          "id": "$toolId",
          "name": "$name",
          "version": "1.0.0",
          "versionCode": $versionCode,
          "entry": "$entry",
          "apiVersion": "1.0",
          "minHostVersion": "0.1.0",
          "permissions": [],
          "securityProfile": "$securityProfile"
        }
    """.trimIndent()
}
