package io.toolbox.host.browser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.toolbox.core.ui.component.ToolBoxModalDialog
import io.toolbox.core.ui.component.ToolBoxPrimaryButton
import io.toolbox.core.ui.component.ToolBoxSecondaryButton
import io.toolbox.core.ui.component.ToolBoxText
import io.toolbox.core.ui.theme.ToolBoxThemeTokens

/**
 * Construct as an Activity field, before STARTED; result launchers must never be registered lazily.
 * Call cancelPage on every top-level navigation and before destroying a WebView. Origin consent is
 * per request, including when Android permissions were granted previously; no grants are persisted.
 */
internal class BrowserCapabilityController(
    private val activity: ComponentActivity,
    private val isCurrentPage: (WebView) -> Boolean,
) {
    private sealed interface Request {
        val page: WebView
        val origin: String
        val label: String
        fun deny()
    }
    private data class Media(
        override val page: WebView,
        override val origin: String,
        val request: PermissionRequest,
        val resources: List<String>,
    ) : Request {
        override val label = resources.joinToString("、") {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "摄像头"
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "麦克风"
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "受保护媒体播放"
                else -> "MIDI 设备访问"
            }
        }
        override fun deny() = request.deny()
    }
    private data class Location(
        override val page: WebView,
        override val origin: String,
        val callbackOrigin: String,
        val callback: GeolocationPermissions.Callback,
    ) : Request {
        override val label = "位置信息"
        override fun deny() = callback.invoke(callbackOrigin, false, false)
    }
    private data class Upload(
        override val page: WebView,
        override val origin: String,
        val callback: ValueCallback<Array<Uri>>,
        val types: Array<String>,
        val multiple: Boolean,
    ) : Request {
        override val label = "选择文件并上传"
        override fun deny() = callback.onReceiveValue(null)
    }

    private val queue = BrowserCapabilityQueue<Request>()
    private var prompt by mutableStateOf<BrowserCapabilityQueue.Entry<Request>?>(null)
    // Keep these IDs until Android delivers its result, even if navigation canceled the request.
    private var permissionLaunchId: Long? = null
    private var fileLaunchId: Long? = null
    val hasPrompt: Boolean get() = prompt != null

    private val permissionsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val id = permissionLaunchId
        permissionLaunchId = null
        if (id != null) finishPermission(id)
        advance()
    }
    private val fileLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val id = fileLaunchId
        fileLaunchId = null
        val entry = id?.let(queue::take)
        val upload = entry?.value as? Upload
        if (upload != null) {
            val uris = if (result.resultCode == Activity.RESULT_OK && isCurrentPage(upload.page)) {
                val data = result.data
                val returned = buildList {
                    data?.data?.let(::add)
                    data?.clipData?.let { clip ->
                        for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
                    }
                }.distinct()
                // Reject the whole result if any item is outside the picker content boundary.
                returned.takeIf { it.isNotEmpty() && it.all(::safeUploadUri) }
                    ?.let { if (upload.multiple) it else it.take(1) }?.toTypedArray()
            } else null
            runCatching { upload.callback.onReceiveValue(uris) }
        }
        advance()
    }

    fun handleWebPermission(page: WebView, request: PermissionRequest) {
        val origin = BrowserCapabilityPolicy.origin(request.origin.toString())
        val resources = request.resources.distinct().filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID || it == PermissionRequest.RESOURCE_MIDI_SYSEX
        }
        if (!isCurrentPage(page) || origin == null || resources.isEmpty()) {
            runCatching { request.deny() }
            return
        }
        val playbackResources = BrowserMediaPolicy.grantedResources(request.origin.scheme, request.resources)
        if (playbackResources.isNotEmpty() && request.resources.all { it in playbackResources }) {
            // Preserve the existing HTTPS playback path; capture and MIDI still need origin consent.
            runCatching { request.grant(playbackResources) }
            return
        }
        enqueue(Media(page, origin, request, resources))
    }

    fun cancelWebPermission(request: PermissionRequest) {
        cancelMatching { it is Media && it.request === request }
    }

    fun handleGeolocation(page: WebView, origin: String, callback: GeolocationPermissions.Callback) {
        val displayedOrigin = BrowserCapabilityPolicy.origin(origin)
        if (!isCurrentPage(page) || displayedOrigin == null) {
            callback.invoke(origin, false, false)
            return
        }
        enqueue(Location(page, displayedOrigin, origin, callback))
    }

    fun cancelGeolocation(page: WebView) = cancelMatching { it is Location && it.page === page }

    fun showFileChooser(
        page: WebView,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        // WebView replaces a previous file chooser request; settle it before accepting its successor.
        cancelMatching { it is Upload }
        val origin = BrowserCapabilityPolicy.origin(page.url.orEmpty())
        if (!isCurrentPage(page) || origin == null) {
            callback.onReceiveValue(null)
            return true
        }
        val accepts = params.acceptTypes.map { type ->
            if (type.startsWith('.')) MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(type.removePrefix(".").lowercase()).orEmpty() else type
        }.toTypedArray()
        enqueue(Upload(page, origin, callback, BrowserCapabilityPolicy.mimeTypes(accepts).toTypedArray(),
            params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE))
        return true
    }

    fun cancelPage(page: WebView) {
        queue.cancelPage(page).forEach { runCatching { it.value.deny() } }
        advance()
    }

    private fun cancelMatching(predicate: (Request) -> Boolean) {
        queue.remove { predicate(it.value) }.forEach { runCatching { it.value.deny() } }
        advance()
    }

    private fun enqueue(request: Request) {
        queue.add(request.page, request)
        advance()
    }

    private fun advance() {
        val entry = if (permissionLaunchId == null && fileLaunchId == null) queue.first else null
        prompt = entry?.takeUnless { it.value is Upload }
        // The system picker is the user's explicit file selection; do not interpose another dialog.
        if (entry?.value is Upload) allow(entry)
    }

    private fun deny(id: Long) {
        queue.take(id)?.let { runCatching { it.value.deny() } }
        advance()
    }

    private fun allow(entry: BrowserCapabilityQueue.Entry<Request>) {
        if (queue.first?.id != entry.id || permissionLaunchId != null || fileLaunchId != null) return
        val request = entry.value
        if (request !is Upload && prompt?.id != entry.id) return
        if (!isCurrentPage(request.page)) { deny(entry.id); return }
        if (request is Upload) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = request.types.singleOrNull() ?: "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, request.types)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.multiple)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fileLaunchId = entry.id
            advance()
            runCatching { fileLauncher.launch(intent) }.onFailure {
                fileLaunchId = null
                deny(entry.id)
            }
            return
        }
        val missing = permissionsFor(request).filterNot(::permissionGranted)
        if (missing.isEmpty()) {
            finishPermission(entry.id)
            advance()
        } else {
            permissionLaunchId = entry.id
            advance()
            runCatching { permissionsLauncher.launch(missing.toTypedArray()) }.onFailure {
                permissionLaunchId = null
                deny(entry.id)
            }
        }
    }

    private fun permissionsFor(request: Request): List<String> = when (request) {
        is Media -> buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) add(Manifest.permission.CAMERA)
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) add(Manifest.permission.RECORD_AUDIO)
        }
        is Location -> if (permissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) emptyList()
            else listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        is Upload -> emptyList()
    }

    private fun permissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun finishPermission(id: Long) {
        val request = queue.take(id)?.value ?: return
        runCatching {
            if (!isCurrentPage(request.page)) { request.deny(); return@runCatching }
            when (request) {
                is Media -> {
                    val allowed = request.resources.filter {
                        when (it) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> permissionGranted(Manifest.permission.CAMERA)
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> permissionGranted(Manifest.permission.RECORD_AUDIO)
                            else -> true
                        }
                    }.toTypedArray()
                    if (allowed.isEmpty()) request.deny() else request.request.grant(allowed)
                }
                is Location -> request.callback.invoke(request.callbackOrigin,
                    permissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                        permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION), false)
                is Upload -> request.deny()
            }
        }
    }

    private fun safeUploadUri(uri: Uri): Boolean {
        if (!BrowserCapabilityPolicy.acceptsContentUri(uri.scheme, uri.authority, false)) return false
        val provider = activity.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0) ?: return false
        if (!BrowserCapabilityPolicy.acceptsContentUri(uri.scheme, uri.authority,
                provider.applicationInfo.uid == activity.applicationInfo.uid || provider.packageName == activity.packageName)) return false
        return runCatching {
            activity.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    @Composable
    fun Prompt(visible: Boolean = true) {
        val entry = prompt ?: return
        if (!visible) return
        val colors = ToolBoxThemeTokens.colors
        ToolBoxModalDialog(onDismissRequest = { deny(entry.id) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolBoxText("网站权限", style = ToolBoxThemeTokens.textStyles.title.copy(color = colors.textPrimary))
                ToolBoxText(entry.value.origin, style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textPrimary))
                ToolBoxText("此网站请求${entry.value.label}。允许后网站可在当前页面使用这些能力，请仅允许你信任的网站。",
                    style = ToolBoxThemeTokens.textStyles.body.copy(color = colors.textSecondary))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolBoxSecondaryButton("拒绝", { deny(entry.id) }, Modifier.weight(1f))
                    ToolBoxPrimaryButton("允许并继续", { allow(entry) }, Modifier.weight(1f))
                }
            }
        }
    }
}
