package io.toolbox.tool.runtime

import io.toolbox.core.data.SecurityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRuntimeSecurityBoundaryTest {
    @Test
    fun wasmCompilationAndLocalFetchPreserveScriptAndWorkerBoundaries() {
        SecurityProfile.entries.forEach { profile ->
            val directives = RuntimePolicy.contentSecurityPolicy(profile).split(';')
                .map(String::trim).filter(String::isNotEmpty)
                .associate { directive ->
                    val tokens = directive.split(Regex("\\s+"))
                    tokens.first() to tokens.drop(1).toSet()
                }
            val script = directives.getValue("script-src")
            assertTrue("'self'" in script)
            assertTrue("'wasm-unsafe-eval'" in script)
            assertFalse("'unsafe-eval'" in script)
            assertEquals(profile == SecurityProfile.COMPAT, "'unsafe-inline'" in script)
            assertEquals(setOf("'self'"), directives.getValue("connect-src"))
            assertEquals(setOf("'self'"), directives.getValue("worker-src"))
            assertEquals(setOf("'none'"), directives.getValue("frame-src"))
            assertEquals(setOf("'none'"), directives.getValue("object-src"))
            assertEquals("nosniff", RuntimePolicy.responseHeaders(profile)["X-Content-Type-Options"])
        }
    }

    @Test
    fun statelessModeBlocksWorkersThatTheDocumentStartHardeningCannotReach() {
        SecurityProfile.entries.forEach { profile ->
            val csp = RuntimePolicy.contentSecurityPolicy(profile, allowWorkers = false)
            val workers = csp.split(';').map(String::trim).single { it.startsWith("worker-src") }
            assertEquals("worker-src 'none'", workers)
            assertEquals(csp, RuntimePolicy.responseHeaders(profile, allowWorkers = false)["Content-Security-Policy"])
            assertEquals(
                RuntimePolicy.contentSecurityPolicy(profile).replace("worker-src 'self'", "worker-src 'none'"),
                csp,
            )
        }
    }

    @Test
    fun localResourceReadsRequireTheExactToolOrigin() {
        val origin = RuntimeIdentity.origin("com.example.alpha")
        val other = RuntimeIdentity.origin("com.example.beta")
        assertNotEquals(origin, other)
        assertTrue(RuntimeIdentity.isExactLocalUrl(origin + "module.wasm", origin))
        assertTrue(RuntimeIdentity.isExactLocalUrl(origin + "module.wasm", java.net.URI(origin)))
        listOf(
            other + "module.wasm",
            origin.replace("https://", "http://") + "module.wasm",
            origin.removeSuffix("/") + ":443/module.wasm",
            origin.removeSuffix("/") + ".evil/module.wasm",
            "https://example.com/module.wasm",
            "file:///tmp/module.wasm",
            "content://com.example.provider/module.wasm",
        ).forEach {
            assertFalse("Must reject $it", RuntimeIdentity.isExactLocalUrl(it, origin))
            assertFalse("Preparsed origin must reject $it", RuntimeIdentity.isExactLocalUrl(it, java.net.URI(origin)))
            assertFalse("Navigation must reject $it", RuntimeIdentity.isLocalNavigationUrl(it, origin))
            assertFalse("Navigation must reject $it#faq", RuntimeIdentity.isLocalNavigationUrl("$it#faq", origin))
        }
    }

    @Test
    fun localNavigationKeepsFragmentsWhileResourceReadsStayFragmentFree() {
        val origin = RuntimeIdentity.origin("com.example.alpha")
        listOf(origin + "help.html", origin + "help.html#faq", origin + "index.html?tab=1#top", origin + "#").forEach {
            assertTrue("Navigation must allow $it", RuntimeIdentity.isLocalNavigationUrl(it, origin))
            assertTrue("Preparsed navigation must allow $it", RuntimeIdentity.isLocalNavigationUrl(it, java.net.URI(origin)))
        }
        assertTrue(RuntimeIdentity.isExactLocalUrl(origin + "help.html", origin))
        assertFalse(RuntimeIdentity.isExactLocalUrl(origin + "help.html#faq", origin))
        listOf(
            "https://user@" + origin.removePrefix("https://") + "help.html#faq",
            "javascript:alert(1)",
            "about:blank#frag",
            "not a url",
        ).forEach { assertFalse("Navigation must reject $it", RuntimeIdentity.isLocalNavigationUrl(it, origin)) }
    }
}
