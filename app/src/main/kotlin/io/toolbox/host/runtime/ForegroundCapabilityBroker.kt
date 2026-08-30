package io.toolbox.host.runtime

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.database.Cursor
import android.graphics.drawable.Icon
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import io.toolbox.host.MainActivity
import io.toolbox.host.R
import io.toolbox.tool.runtime.RuntimeCameraHandler
import io.toolbox.tool.runtime.RuntimeClipboardReadHandler
import io.toolbox.tool.runtime.RuntimeFileToken
import io.toolbox.tool.runtime.RuntimeFilesHandler
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeLocationHandler
import io.toolbox.tool.runtime.RuntimeLocationResult
import io.toolbox.tool.runtime.RuntimeM3Handlers
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeSessionCleanupHandler
import io.toolbox.tool.runtime.RuntimeShareTextHandler
import io.toolbox.tool.runtime.RuntimeShortcutHandler
import io.toolbox.tool.api.ToolBoxCapabilityId
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

internal data class RuntimeFileHandle(
    val uri: Uri,
    val capability: ToolBoxCapabilityId,
    val temporaryFile: File?,
)

internal class RuntimeFileSessionResources(
    private val deleteFiles: (Set<File>) -> Unit,
) {
    private val handles = ConcurrentHashMap<String, RuntimeFileHandle>()
    private val temporaryFiles = ConcurrentHashMap.newKeySet<File>()

    fun trackTemporary(file: File) {
        temporaryFiles += file
    }

    fun removeTemporary(file: File) {
        temporaryFiles -= file
    }

    fun register(token: String, handle: RuntimeFileHandle) {
        check(handles.putIfAbsent(token, handle) == null)
    }

    fun capabilityFor(token: String): ToolBoxCapabilityId? = handles[token]?.capability

    fun take(token: String): RuntimeFileHandle? = handles.remove(token)

    fun close() {
        val files = buildSet {
            addAll(temporaryFiles)
            handles.values.mapNotNullTo(this) { it.temporaryFile }
        }
        temporaryFiles.clear()
        handles.clear()
        deleteFiles(files)
    }

    internal fun handleCount(): Int = handles.size
    internal fun temporaryFileCount(): Int = temporaryFiles.size
}

internal fun locationResult(value: android.location.Location?): Result<android.location.Location> =
    value?.let(Result.Companion::success)
        ?: Result.failure(RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "Location is unavailable"))

internal class ForegroundCapabilityBroker private constructor(
    private val activity: ComponentActivity,
) {
    private val active = AtomicBoolean(true)
    private val pickerMutex = Mutex()
    private var openResult: CompletableDeferred<Uri?>? = null
    private var saveResult: CompletableDeferred<Uri?>? = null
    private var cameraResult: CompletableDeferred<Boolean>? = null
    private var pendingCameraFile: File? = null
    private val sessions = ConcurrentHashMap.newKeySet<ToolFilesHandler>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val openLauncher = activity.registerForActivityResult(OpenDocumentContract()) { uri ->
        openResult?.complete(uri)
        openResult = null
    }
    private val saveLauncher = activity.registerForActivityResult(CreateDocumentContract()) { uri ->
        saveResult?.complete(uri)
        saveResult = null
    }
    private val cameraLauncher = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        cameraResult?.complete(saved)
        cameraResult = null
    }

    fun handlers(toolId: String, toolName: String): RuntimeM3Handlers {
        val files = ToolFilesHandler().also(sessions::add)
        return RuntimeM3Handlers(
            clipboardRead = RuntimeClipboardReadHandler { readClipboardAfterConfirmation() },
            shareText = RuntimeShareTextHandler(::shareText),
            files = files,
            shortcuts = RuntimeShortcutHandler { name -> pinShortcut(toolId, name ?: toolName) },
            camera = files,
            location = RuntimeLocationHandler(::getCurrentLocation),
            sessionCleanup = files,
        )
    }

    fun close() {
        if (!active.compareAndSet(true, false)) return
        val failure = RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "The host activity closed")
        openResult?.completeExceptionally(failure)
        saveResult?.completeExceptionally(failure)
        cameraResult?.completeExceptionally(failure)
        val filesToDelete = listOfNotNull(pendingCameraFile)
        sessions.toList().forEach(ToolFilesHandler::close)
        sessions.clear()
        openResult = null
        saveResult = null
        cameraResult = null
        pendingCameraFile = null
        synchronized(companionLock) {
            if (activeBroker.get() === this) activeBroker = WeakReference(null)
        }
        cleanupScope.launch {
            filesToDelete.forEach(File::delete)
        }
    }

    private suspend fun readClipboardAfterConfirmation(): String = withContext(Dispatchers.Main.immediate) {
        ensureActive()
        val confirmed = suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle("允许读取剪贴板？")
                .setMessage("当前工具将读取一次剪贴板文本。")
                .setNegativeButton("取消") { _, _ -> if (continuation.isActive) continuation.resume(false) }
                .setPositiveButton("允许") { _, _ -> if (continuation.isActive) continuation.resume(true) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                .create()
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
        if (!confirmed) throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "Clipboard read was cancelled")
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
            ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Clipboard is unavailable")
        if (!clipboard.hasPrimaryClip() || clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) {
            return@withContext ""
        }
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
    }

    private suspend fun shareText(text: String) = withContext(Dispatchers.Main.immediate) {
        ensureActive()
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, "分享"))
    }

    private suspend fun pinShortcut(toolId: String, label: String): Boolean {
        ensureActive()
        val route = withContext(Dispatchers.IO) {
            val token = randomToken()
            val preferences = shortcutPreferences(activity)
            val update = preferences.edit()
            preferences.all.filterValues { it == toolId }.keys.forEach(update::remove)
            update.putString(token, toolId).apply()
            ShortcutRoute(token, shortcutId(toolId))
        }
        val accepted = withContext(Dispatchers.Main.immediate) {
            ensureActive()
            val manager = activity.getSystemService(ShortcutManager::class.java) ?: return@withContext false
            if (!manager.isRequestPinShortcutSupported) return@withContext false
            val launchIntent = Intent(activity, MainActivity::class.java)
                .setAction(SHORTCUT_ACTION)
                .putExtra(EXTRA_SHORTCUT_TOKEN, route.token)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val shortcut = ShortcutInfo.Builder(activity, route.shortcutId)
                .setShortLabel(label.take(MAX_SHORTCUT_LABEL_CHARS))
                .setIcon(Icon.createWithResource(activity, R.drawable.ic_toolbox))
                .setIntent(launchIntent)
                .build()
            manager.requestPinShortcut(shortcut, null)
        }
        if (!accepted) {
            withContext(Dispatchers.IO) {
                shortcutPreferences(activity).edit().remove(route.token).apply()
            }
        }
        return accepted
    }

    private suspend fun capturePhoto(owner: ToolFilesHandler): RuntimeFileToken? = pickerMutex.withLock {
        ensureActive()
        val file = withContext(Dispatchers.IO) {
            val captureDirectory = File(activity.cacheDir, "toolbox-captures").apply { mkdirs() }
            File.createTempFile("capture-", ".jpg", captureDirectory)
        }
        owner.trackTemporary(file)
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val deferred = CompletableDeferred<Boolean>()
        cameraResult = deferred
        withContext(Dispatchers.Main.immediate) { cameraLauncher.launch(uri) }
        val saved = try {
            deferred.await()
        } finally {
            cameraResult = null
            pendingCameraFile = null
        }
        if (!saved) {
            owner.removeTemporary(file)
            file.delete()
            null
        } else {
            owner.fileToken(
                uri = uri,
                fallbackName = file.name,
                fallbackMimeType = "image/jpeg",
                fallbackSize = file.length(),
                capability = ToolBoxCapabilityId.CAMERA,
                temporaryFile = file,
            )
        }
    }

    private suspend fun getCurrentLocation(precise: Boolean, timeoutMillis: Long): RuntimeLocationResult {
        ensureActive()
        val requiredPermission = if (precise) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION
        if (activity.checkSelfPermission(requiredPermission) != PackageManager.PERMISSION_GRANTED) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.SYSTEM_PERMISSION_DENIED, "Location permission is unavailable")
        }
        val manager = activity.getSystemService(LocationManager::class.java)
            ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Location is unavailable")
        val candidates = if (precise) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        val provider = candidates.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "No location provider is enabled")
        val cancellation = CancellationSignal()
        val location = try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine<android.location.Location> { continuation ->
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    manager.getCurrentLocation(provider, cancellation, activity.mainExecutor) { value ->
                        if (!continuation.isActive) return@getCurrentLocation
                        continuation.resumeWith(locationResult(value))
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            cancellation.cancel()
            throw RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "Location request timed out")
        }
        return RuntimeLocationResult(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.toDouble(),
            capturedAt = location.time,
        )
    }

    private inner class ToolFilesHandler : RuntimeFilesHandler, RuntimeCameraHandler, RuntimeSessionCleanupHandler {
        private val resources = RuntimeFileSessionResources { files ->
            cleanupScope.launch { files.forEach(File::delete) }
        }

        override suspend fun capture(): RuntimeFileToken? = capturePhoto(this)

        override suspend fun open(mimeTypes: List<String>): RuntimeFileToken? = pickerMutex.withLock {
            ensureActive()
            val deferred = CompletableDeferred<Uri?>()
            openResult = deferred
            withContext(Dispatchers.Main.immediate) { openLauncher.launch(mimeTypes) }
            val uri = try {
                deferred.await()
            } finally {
                openResult = null
            } ?: return@withLock null
            withContext(Dispatchers.IO) {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    val probe = ByteArray(1)
                    input.read(probe)
                } ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "The selected file cannot be opened")
            }
            fileToken(uri, capability = ToolBoxCapabilityId.FILES_OPEN)
        }

        override suspend fun save(
            suggestedName: String,
            mimeType: String,
            content: ByteArray,
        ): RuntimeFileToken? = pickerMutex.withLock {
            ensureActive()
            val deferred = CompletableDeferred<Uri?>()
            saveResult = deferred
            withContext(Dispatchers.Main.immediate) { saveLauncher.launch(CreateDocumentRequest(suggestedName, mimeType)) }
            val uri = try {
                deferred.await()
            } finally {
                saveResult = null
            } ?: return@withLock null
            withContext(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(uri, "w")?.use { it.write(content) }
                    ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "The selected file cannot be written")
            }
            fileToken(
                uri = uri,
                fallbackName = suggestedName,
                fallbackMimeType = mimeType,
                fallbackSize = content.size.toLong(),
                capability = ToolBoxCapabilityId.FILES_SAVE,
            )
        }

        override suspend fun capabilityFor(token: String): ToolBoxCapabilityId =
            resources.capabilityFor(token)
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "File token is missing or already used")

        override suspend fun consume(token: String, maxBytes: Int): ByteArray {
            require(maxBytes > 0)
            val handle = resources.take(token)
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "File token is missing or already used")
            return try {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(handle.uri)?.use { readBounded(it, maxBytes) }
                        ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "The selected file cannot be opened")
                }
            } finally {
                handle.temporaryFile?.let {
                    resources.removeTemporary(it)
                    withContext(Dispatchers.IO) { it.delete() }
                }
            }
        }

        override fun close() {
            sessions.remove(this)
            resources.close()
        }

        fun trackTemporary(file: File) {
            resources.trackTemporary(file)
        }

        fun removeTemporary(file: File) {
            resources.removeTemporary(file)
        }

        suspend fun fileToken(
            uri: Uri,
            fallbackName: String = "file",
            fallbackMimeType: String = "application/octet-stream",
            fallbackSize: Long = 0,
            capability: ToolBoxCapabilityId,
            temporaryFile: File? = null,
        ): RuntimeFileToken = withContext(Dispatchers.IO) {
            val metadata = activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use(::readMetadata)
            val token = randomToken()
            resources.register(token, RuntimeFileHandle(uri, capability, temporaryFile))
            RuntimeFileToken(
                token = token,
                name = metadata?.first ?: fallbackName,
                mimeType = activity.contentResolver.getType(uri) ?: fallbackMimeType,
                size = metadata?.second ?: fallbackSize,
            )
        }

        private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "File exceeds this runtime session limit")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun readMetadata(cursor: Cursor): Pair<String, Long>? {
        if (!cursor.moveToFirst()) return null
        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameColumn >= 0) cursor.getString(nameColumn) else null
        val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else null
        return (name ?: "file") to (size ?: 0L)
    }

    private fun ensureActive() {
        if (
            !active.get() ||
            activity.isFinishing ||
            activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "No foreground host activity is available")
        }
    }

    private class OpenDocumentContract : ActivityResultContract<List<String>, Uri?>() {
        override fun createIntent(context: Context, input: List<String>): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (input.size == 1) input.single() else "*/*")
                .also { intent -> if (input.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, input.toTypedArray()) }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
    }

    private data class CreateDocumentRequest(val name: String, val mimeType: String)
    private data class ShortcutRoute(val token: String, val shortcutId: String)
    private class CreateDocumentContract : ActivityResultContract<CreateDocumentRequest, Uri?>() {
        override fun createIntent(context: Context, input: CreateDocumentRequest): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.mimeType)
                .putExtra(Intent.EXTRA_TITLE, input.name)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
    }

    companion object {
        const val SHORTCUT_ACTION = "io.toolbox.host.action.OPEN_TOOL_SHORTCUT"
        const val EXTRA_SHORTCUT_TOKEN = "io.toolbox.host.extra.SHORTCUT_TOKEN"
        private const val SHORTCUT_PREFERENCES = "toolbox_shortcut_routes"
        private const val MAX_SHORTCUT_LABEL_CHARS = 40
        private val companionLock = Any()
        private var activeBroker = WeakReference<ForegroundCapabilityBroker>(null)
        private val random = SecureRandom()

        fun attach(activity: ComponentActivity): ForegroundCapabilityBroker = synchronized(companionLock) {
            activeBroker.get()?.close()
            ForegroundCapabilityBroker(activity).also { activeBroker = WeakReference(it) }
        }

        fun activeHandlers(toolId: String, toolName: String): RuntimeM3Handlers = synchronized(companionLock) {
            activeBroker.get()?.handlers(toolId, toolName) ?: RuntimeM3Handlers()
        }

        fun resolveShortcutToolId(context: Context, intent: Intent?): String? {
            if (intent?.action != SHORTCUT_ACTION) return null
            val token = intent.getStringExtra(EXTRA_SHORTCUT_TOKEN) ?: return null
            if (token.length !in 32..128) return null
            return shortcutPreferences(context).getString(token, null)
        }

        fun clearToolShortcut(context: Context, toolId: String) {
            val preferences = shortcutPreferences(context)
            val update = preferences.edit()
            preferences.all.filterValues { it == toolId }.keys.forEach(update::remove)
            update.apply()
            context.getSystemService(ShortcutManager::class.java)?.disableShortcuts(listOf(shortcutId(toolId)))
        }

        private fun shortcutPreferences(context: Context) =
            context.getSharedPreferences(SHORTCUT_PREFERENCES, Context.MODE_PRIVATE)

        private fun randomToken(): String = ByteArray(32)
            .also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        private fun shortcutId(toolId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(toolId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }
}
