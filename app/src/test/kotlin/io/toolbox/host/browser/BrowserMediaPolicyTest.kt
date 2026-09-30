package io.toolbox.host.browser

import android.webkit.PermissionRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMediaPolicyTest {
    @Test fun httpsProtectedMediaIsGrantedWithoutGrantingCaptureOrUnknownResources() {
        val requested = arrayOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
            "android.webkit.resource.FUTURE_RESOURCE",
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
        )

        assertArrayEquals(
            arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID),
            BrowserMediaPolicy.grantedResources("https", requested),
        )
    }

    @Test fun protectedMediaIsNotGrantedToNonHttpsOrigins() {
        val requested = arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)

        assertArrayEquals(emptyArray<String>(), BrowserMediaPolicy.grantedResources("http", requested))
        assertArrayEquals(emptyArray<String>(), BrowserMediaPolicy.grantedResources(null, requested))
    }

    @Test fun cameraAndMicrophoneRequestsRemainDetectableAndBlockedByPolicy() {
        assertTrue(BrowserMediaPolicy.requestsCapture(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)))
        assertTrue(BrowserMediaPolicy.requestsCapture(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)))
        assertFalse(BrowserMediaPolicy.requestsCapture(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)))
    }
}
