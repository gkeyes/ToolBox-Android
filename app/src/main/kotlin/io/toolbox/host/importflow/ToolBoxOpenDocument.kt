package io.toolbox.host.importflow

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import io.toolbox.tool.packagekit.PackageInput
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ToolBoxOpenDocument {
    const val FILE_EXTENSION = ".tbx"
    const val TOOLBOX_MIME_TYPE = "application/vnd.toolbox.tbx"
    const val ZIP_MIME_TYPE = "application/zip"
    const val OCTET_STREAM_MIME_TYPE = "application/octet-stream"

    val contract = ActivityResultContracts.OpenDocument()

    fun mimeTypes(): Array<String> = arrayOf(
        TOOLBOX_MIME_TYPE,
        ZIP_MIME_TYPE,
        OCTET_STREAM_MIME_TYPE,
    )
}

internal sealed interface SelectedPackageSource {
    data object Cancelled : SelectedPackageSource
    data class Ready(val input: PackageInput) : SelectedPackageSource
    data class Rejected(val message: String) : SelectedPackageSource
}

internal class ContentResolverPackageInputFactory(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun fromPickerResult(uri: Uri?): SelectedPackageSource {
        if (uri == null) return selectedPackageSource(null, null)
        return withContext(ioDispatcher) {
            val displayNameCandidate = readDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
            selectedPackageSource(displayNameCandidate) {
                contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("The selected package is no longer available")
            }
        }
    }

    private fun readDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}

internal fun selectedPackageSource(
    rawDisplayName: String?,
    sourceOpener: (() -> InputStream)?,
): SelectedPackageSource {
    if (sourceOpener == null) return SelectedPackageSource.Cancelled
    if (rawDisplayName.isNullOrBlank()) return SelectedPackageSource.Rejected("无法确认所选文件是 .tbx 工具包")
    if (rawDisplayName.containsUnsafeFormatCodePoint()) {
        return SelectedPackageSource.Rejected("所选文件名包含不安全字符")
    }
    val displayName = safePackageDisplayName(rawDisplayName)
    if (!displayName.endsWith(ToolBoxOpenDocument.FILE_EXTENSION, ignoreCase = true)) {
        return SelectedPackageSource.Rejected("请选择 .tbx 工具包")
    }
    return SelectedPackageSource.Ready(OneShotPackageInput(displayName, sourceOpener))
}

internal class OneShotPackageInput(
    rawDisplayName: String?,
    private val sourceOpener: () -> InputStream,
) : PackageInput {
    private val opened = AtomicBoolean(false)

    override val displayName: String = safePackageDisplayName(rawDisplayName)

    override fun openStream(): InputStream {
        check(opened.compareAndSet(false, true)) { "Selected package content may only be opened once" }
        return sourceOpener()
    }
}

internal fun safePackageDisplayName(rawDisplayName: String?): String {
    val candidate = rawDisplayName?.trim().orEmpty()
    val cleaned = buildString {
        var offset = 0
        while (offset < candidate.length) {
            val codePoint = Character.codePointAt(candidate, offset)
            when {
                codePoint == '/'.code || codePoint == '\\'.code || Character.isISOControl(codePoint) ||
                    isUnsafeFormatCodePoint(codePoint) -> append('_')
                else -> appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
    }.trim('.', ' ', '_')
    if (cleaned.isEmpty()) return "selected-tool.tbx"
    if (cleaned.codePointCount(0, cleaned.length) <= MAX_DISPLAY_NAME_CHARACTERS) return cleaned
    val suffix = if (cleaned.endsWith(ToolBoxOpenDocument.FILE_EXTENSION, ignoreCase = true)) {
        ToolBoxOpenDocument.FILE_EXTENSION
    } else {
        ""
    }
    val prefixCodePoints = MAX_DISPLAY_NAME_CHARACTERS - suffix.codePointCount(0, suffix.length)
    return cleaned.substring(0, cleaned.offsetByCodePoints(0, prefixCodePoints)) + suffix
}

private const val MAX_DISPLAY_NAME_CHARACTERS = 120

private fun String.containsUnsafeFormatCodePoint(): Boolean {
    var offset = 0
    while (offset < length) {
        val codePoint = Character.codePointAt(this, offset)
        if (isUnsafeFormatCodePoint(codePoint)) return true
        offset += Character.charCount(codePoint)
    }
    return false
}

private fun isUnsafeFormatCodePoint(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.FORMAT.toInt()
