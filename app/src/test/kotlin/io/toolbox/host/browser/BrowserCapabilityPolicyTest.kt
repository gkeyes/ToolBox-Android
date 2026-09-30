package io.toolbox.host.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserCapabilityPolicyTest {
    @Test fun canceledPageCannotConsumeOrCompleteNextPageRequest() {
        val queue = BrowserCapabilityQueue<String>()
        val firstPage = Any()
        val secondPage = Any()
        val first = queue.add(firstPage, "camera")
        val second = queue.add(secondPage, "location")
        assertEquals(listOf("camera"), queue.cancelPage(firstPage).map { it.value })
        assertNull(queue.take(first.id))
        assertEquals(second, queue.first)
        assertEquals("location", queue.take(second.id)?.value)
        assertNull(queue.take(second.id))
    }

    @Test fun pageCancellationRemovesEveryQueuedRequestExactlyOnce() {
        val queue = BrowserCapabilityQueue<String>()
        val page = Any()
        queue.add(page, "camera")
        queue.add(page, "microphone")
        assertEquals(2, queue.cancelPage(page).size)
        assertTrue(queue.cancelPage(page).isEmpty())
        assertNull(queue.first)
    }

    @Test fun webCancellationThenNavigationAndLateResultSettleCallbackOnce() {
        val queue = BrowserCapabilityQueue<() -> Unit>()
        val page = Any()
        var completions = 0
        val request = queue.add(page) { completions++ }
        queue.remove { it.id == request.id }.forEach { it.value() }
        queue.remove { it.id == request.id }.forEach { it.value() }
        queue.cancelPage(page).forEach { it.value() }
        queue.take(request.id)?.value?.invoke()
        assertEquals(1, completions)
    }

    @Test fun replacingFileRequestDoesNotCancelCameraOrConsumeReplacement() {
        val queue = BrowserCapabilityQueue<String>()
        val page = Any()
        val oldFile = queue.add(page, "file")
        val camera = queue.add(page, "camera")
        assertEquals(listOf(oldFile), queue.remove { it.value == "file" })
        val newFile = queue.add(page, "file")
        assertNull(queue.take(oldFile.id))
        assertEquals(camera, queue.first)
        queue.take(camera.id)
        assertEquals(newFile, queue.first)
    }

    @Test fun displayedOriginNeverIncludesCredentialsPathOrQuery() {
        assertEquals("https://example.com:8443", BrowserCapabilityPolicy.origin("https://example.com:8443/path?secret=x#hash"))
        assertNull(BrowserCapabilityPolicy.origin("https://user:password@example.com/"))
        assertNull(BrowserCapabilityPolicy.origin("file:///secret"))
        assertNull(BrowserCapabilityPolicy.origin("not a URL"))
    }

    @Test fun pickerTypesAreNormalizedAndUnknownTypesFallBackToAllFiles() {
        assertEquals(listOf("image/*", "application/pdf"), BrowserCapabilityPolicy.mimeTypes(arrayOf("image/*,application/pdf", "image/*")))
        assertEquals(listOf("*/*"), BrowserCapabilityPolicy.mimeTypes(arrayOf(".unknown-extension")))
    }

    @Test fun uploadRejectsFileOwnProviderAndCrossUserAuthority() {
        assertFalse(BrowserCapabilityPolicy.acceptsContentUri("file", "downloads", false))
        assertFalse(BrowserCapabilityPolicy.acceptsContentUri("content", "io.toolbox.host.files", true))
        assertFalse(BrowserCapabilityPolicy.acceptsContentUri("content", "10@documents", false))
        assertFalse(BrowserCapabilityPolicy.acceptsContentUri("content", null, false))
        assertTrue(BrowserCapabilityPolicy.acceptsContentUri("content", "com.android.providers.media.documents", false))
    }
}
