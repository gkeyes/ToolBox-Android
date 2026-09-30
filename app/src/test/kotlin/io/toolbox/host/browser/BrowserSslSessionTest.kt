package io.toolbox.host.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSslSessionTest {
    private class Connection {
        var proceeded = 0
        var cancelled = 0
        fun proceed() { proceeded++ }
        fun cancel() { cancelled++ }
    }

    private class Fixture {
        var current: Any? = Any()
        val view: Any get() = requireNotNull(current)
        val cleared = mutableListOf<Any>()
        val session = BrowserSslSession({ current }, { cleared.add(it) })

        fun request(url: String = "https://example.test/", page: Any = view): Connection =
            Connection().also {
                session.enqueue(page, url, "证书已经过期。", it::proceed, it::cancel)
            }
    }

    @Test fun crossOriginResourcesAreQueuedAndEachWaitsForTheUser() {
        val f = Fixture()
        f.session.navigationRequested(f.view, "https://example.test/")
        assertFalse(f.session.navigationStarted(f.view, "https://example.test/"))
        val main = f.request()
        val cdn = f.request("https://cdn.other.test/video")
        val firstId = requireNotNull(f.session.first).id
        assertEquals(2, f.session.size)
        assertEquals(0, main.proceeded + main.cancelled + cdn.proceeded + cdn.cancelled)

        f.session.resolve(firstId, true)
        assertEquals(1, main.proceeded)
        assertEquals("https://cdn.other.test/video", f.session.first?.url)
        assertEquals(0, cdn.proceeded + cdn.cancelled)
        f.session.resolve(requireNotNull(f.session.first).id, false)
        assertEquals(1, cdn.cancelled)
        assertNull(f.session.first)
        f.session.resolve(firstId, false) // A stale dialog callback must not settle another request.
        assertEquals(0, main.cancelled)
    }

    @Test fun lateFirstPageStartedAfterProceedPreservesOtherRequests() {
        val f = Fixture()
        f.session.navigationRequested(f.view, "https://example.test/")
        val main = f.request()
        val resource = f.request("https://cdn.other.test/image")
        f.session.resolve(requireNotNull(f.session.first).id, true)
        val clearsBeforeStart = f.cleared.size
        assertFalse(f.session.navigationStarted(f.view, "https://example.test/"))
        assertEquals(1, main.proceeded)
        assertEquals(0, resource.cancelled)
        assertEquals(1, f.session.size)
        assertEquals(clearsBeforeStart, f.cleared.size)
    }

    @Test fun sslBeforeFirstStartAlsoWorksWithoutAnAnnouncedNavigation() {
        val f = Fixture()
        val request = f.request()
        assertFalse(f.session.navigationStarted(f.view, "https://example.test/"))
        assertEquals(0, request.cancelled)
        f.session.resolve(requireNotNull(f.session.first).id, true)
        assertEquals(1, request.proceeded)
    }

    @Test fun sameUrlReloadCancelsPreviousQueueAndClearsCachedDecisions() {
        val f = Fixture()
        f.session.navigationRequested(f.view, "https://example.test/")
        f.session.navigationStarted(f.view, "https://example.test/")
        val previous = f.request()
        val oldId = requireNotNull(f.session.first).id
        val clears = f.cleared.size
        f.session.navigationRequested(f.view, "https://example.test/")
        assertEquals(clears + 1, f.cleared.size)
        assertEquals(1, previous.cancelled)
        assertNull(f.session.first)
        val next = f.request()
        f.session.navigationStarted(f.view, "https://example.test/")
        f.session.resolve(oldId, true)
        assertEquals(0, previous.proceeded)
        assertEquals(0, next.proceeded + next.cancelled)
        f.session.resolve(requireNotNull(f.session.first).id, true)
        assertEquals(1, next.proceeded)
    }

    @Test fun unannouncedNavigationAndUnexpectedRedirectCancelObsoleteRequests() {
        val f = Fixture()
        f.session.navigationRequested(f.view, "https://example.test/")
        val request = f.request()
        assertTrue(f.session.navigationStarted(f.view, "https://another.test/"))
        assertEquals(1, request.cancelled)
        val redirected = f.request("https://another.test/resource")
        assertTrue(f.session.navigationStarted(f.view, "https://third.test/"))
        assertEquals(1, redirected.cancelled)
        assertNull(f.session.first)
    }

    @Test fun destructionSettlesEveryPendingConnectionOnlyOnceAndClearsTrust() {
        val f = Fixture()
        val view = f.view
        val a = f.request()
        val b = f.request("https://cdn.other.test/")
        val clears = f.cleared.size
        f.session.destroyPage(view)
        f.session.destroyPage(view)
        assertEquals(1, a.cancelled)
        assertEquals(1, b.cancelled)
        assertEquals(clears + 1, f.cleared.size)
        assertNull(f.session.first)
    }

    @Test fun detachedPageCannotGainTrustOrReplaceTheActiveQueue() {
        val f = Fixture()
        val view = f.view
        val request = f.request()
        val id = requireNotNull(f.session.first).id
        f.current = Any()
        f.session.resolve(id, true)
        assertEquals(0, request.proceeded)
        assertEquals(1, request.cancelled)
        val stale = f.request(page = view)
        assertEquals(1, stale.cancelled)
        val fresh = f.request()
        f.session.navigationStarted(view, "https://old.test/")
        assertEquals(0, fresh.cancelled)
        assertEquals(1, f.session.size)
    }

    @Test fun navigationKeyIgnoresFragmentsAndLabelsDoNotExposeUrlCredentials() {
        assertEquals("https://example.test/path", BrowserSslPromptPolicy.navigationKey("https://example.test/path#anchor"))
        val label = BrowserSslPromptPolicy.connectionLabel("https://name:secret@example.test:8443/path?token=private")
        assertEquals("https://example.test:8443", label)
        assertFalse(label.contains("secret"))
        assertFalse(label.contains("private"))
        assertTrue(BrowserSslPromptPolicy.describe(true, true, true, false, false, false).contains("域名不匹配"))
    }

    @Test fun navigationBoundarySignalPreservesCurrentSafeBrowsingDecisionUntilTheUserActs() {
        val f = Fixture()
        f.session.navigationRequested(f.view, "https://example.test/")
        var safeBrowsingCancelled = 0
        // Represents a SafeBrowsingResponse queued after prepareNavigation but before page-start.
        if (f.session.navigationStarted(f.view, "https://example.test/")) safeBrowsingCancelled++
        assertEquals(0, safeBrowsingCancelled)
        // An unannounced script navigation must still tell the host to cancel old page decisions.
        if (f.session.navigationStarted(f.view, "https://other.test/")) safeBrowsingCancelled++
        assertEquals(1, safeBrowsingCancelled)
    }
}
