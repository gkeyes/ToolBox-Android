package io.toolbox.host

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The APK ships the targets marked "bundled" in scripts/ci/tbx-targets.json; the host installs the
 * assets named in ProductionHostPackageOperations.BUNDLED_EXAMPLES. Both lists must name the same tools.
 */
class BundledExamplesRegistryTest {
    @Test
    fun installedExamplesMatchTheBundledRegistryTargets() {
        val registry = Json.parseToJsonElement(projectFile(REGISTRY).readText()).jsonObject
        val bundled = registry
            .filterValues { it.jsonObject["bundled"]?.jsonPrimitive?.booleanOrNull == true }
            .keys
            .map { "$it.tbx" }
            .toSet()
        assertTrue("$REGISTRY marks no target as bundled", bundled.isNotEmpty())
        val installed = ProductionHostPackageOperations.BUNDLED_EXAMPLES
        assertEquals("BUNDLED_EXAMPLES lists a tool twice", installed.size, installed.toSet().size)
        assertEquals(
            "ProductionHostPackageOperations.BUNDLED_EXAMPLES must match the bundled targets in $REGISTRY",
            bundled,
            installed.toSet(),
        )
    }

    /** Unit tests run from the module directory; also accept the repository root. */
    private fun projectFile(path: String): File =
        listOf(File("../$path"), File(path))
            .firstOrNull(File::isFile)
            ?: throw AssertionError("$path not found from ${File("").absolutePath}")

    private companion object {
        const val REGISTRY = "scripts/ci/tbx-targets.json"
    }
}
