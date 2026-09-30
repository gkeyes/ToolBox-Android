package io.toolbox.host.browser

import android.webkit.PermissionRequest

/**
 * Grants only media capabilities required for playback.
 *
 * Protected-media identifiers are limited to HTTPS origins so EME/DRM playback works without
 * turning the browser permission callback into a blanket grant for camera, microphone, MIDI or
 * future WebView resources.
 */
internal object BrowserMediaPolicy {
    fun grantedResources(originScheme: String?, requested: Array<out String>): Array<String> {
        if (originScheme?.equals("https", ignoreCase = true) != true) return emptyArray()
        return requested
            .asSequence()
            .filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            .distinct()
            .toList()
            .toTypedArray()
    }

    fun requestsCapture(requested: Array<out String>): Boolean =
        requested.any {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }
}
