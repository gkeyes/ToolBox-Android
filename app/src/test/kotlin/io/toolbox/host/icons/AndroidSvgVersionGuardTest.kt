package io.toolbox.host.icons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolBoxSvgReferenceGuard lives inside AndroidSVG's package and reads its package-private model
 * (style flags, href fields, CSS rule matching). Those internals can change in any release, so a
 * version bump must re-review the guard. This test fails until the guard's documented version is
 * updated to the one in the version catalog, which is the reminder to do that review.
 */
class AndroidSvgVersionGuardTest {
    @Test
    fun guardWasReviewedForTheAndroidSvgVersionInUse() {
        val catalog = projectFile("gradle/libs.versions.toml").readText()
        val version = Regex("""(?m)^androidsvg\s*=\s*"([^"]+)"""").find(catalog)?.groupValues?.get(1)
            ?: throw AssertionError("androidsvg version not found in gradle/libs.versions.toml")
        assertTrue(
            "androidsvg library coordinates changed; re-check ToolBoxSvgReferenceGuard",
            catalog.contains("""androidsvg = { module = "com.caverock:androidsvg-aar", version.ref = "androidsvg" }"""),
        )

        val guard = projectFile(GUARD).readText()
        val reviewed = Regex("""pinned AndroidSVG ([0-9][0-9A-Za-z.\-]*) model""").find(guard)?.groupValues?.get(1)
            ?: throw AssertionError("$GUARD must name the reviewed version as \"pinned AndroidSVG <version> model\"")
        assertEquals(
            "androidsvg changed from $reviewed to $version. Re-check $GUARD against the new " +
                "library's SVG/CSSParser internals (and its instrumentation tests), then update " +
                "the version named in its class comment.",
            reviewed,
            version,
        )
    }

    /** Unit tests run from the module directory; also accept the repository root. */
    private fun projectFile(path: String): File =
        listOf(File("../$path"), File(path))
            .firstOrNull(File::isFile)
            ?: throw AssertionError("$path not found from ${File("").absolutePath}")

    private companion object {
        const val GUARD = "app/src/main/java/com/caverock/androidsvg/ToolBoxSvgReferenceGuard.java"
    }
}
