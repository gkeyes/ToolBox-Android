package io.toolbox.host.runtime

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeImageShareFormat
import io.toolbox.tool.runtime.RuntimeImageShareHandler
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RuntimeImageShareSession(
    context: Context,
    private val launchChooser: (File, RuntimeImageShareFormat) -> Unit,
) : RuntimeImageShareHandler {
    private class Transfer(val id: String, var file: File, val format: RuntimeImageShareFormat, val length: Int) {
        var written = 0
        var finishing = false
        var launched = false
    }

    private val root = File(context.cacheDir, DIRECTORY)
    private val guard = Any()
    private var current: Transfer? = null
    private var closed = false
    private var document = 0L

    override suspend fun begin(id: String, format: RuntimeImageShareFormat, length: Int) {
        val expectedDocument = synchronized(guard) { document }
        var installed: Transfer? = null
        try {
            withContext(Dispatchers.IO) {
                cacheMutex.withLock {
                    synchronized(guard) { checkAvailable(expectedDocument) }
                    val usage = pruneAndMeasure(root)
                    if (usage.files >= CACHE_FILE_LIMIT || length > CACHE_BUDGET - usage.bytes) {
                        throw RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Image share storage is temporarily full")
                    }
                    val directory = File(root, STAGING)
                    directory.mkdirs()
                    val file = File.createTempFile("image-", format.extension, directory)
                    try {
                        // Sparse reservation makes the shared cache budget account for
                        // concurrent uploads before their chunks have all arrived.
                        RandomAccessFile(file, "rw").use { it.setLength(length.toLong()) }
                        val transfer = Transfer(id, file, format, length)
                        synchronized(guard) {
                            checkAvailable(expectedDocument)
                            current = transfer
                            installed = transfer
                        }
                    } finally {
                        if (installed == null) file.delete()
                    }
                }
            }
        } catch (failure: Exception) {
            installed?.let(::discard)
            throw failure
        }
    }

    override suspend fun write(id: String, offset: Int, bytes: ByteArray) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val transfer = requireCurrent(id)
            require(!transfer.finishing && offset == transfer.written && bytes.size in 1..(transfer.length - offset))
            RandomAccessFile(transfer.file, "rw").use {
                it.seek(offset.toLong())
                it.write(bytes)
            }
            transfer.written += bytes.size
        }
    }

    override suspend fun finish(id: String, beforeLaunch: suspend () -> Unit) {
        var owned: Transfer? = null
        try {
            val transfer = withContext(Dispatchers.IO) {
                cacheMutex.withLock {
                    val candidate = requireCurrent(id)
                    require(!candidate.finishing)
                    candidate.finishing = true
                    owned = candidate
                    require(candidate.written == candidate.length)
                    val header = ByteArray(minOf(20, candidate.length))
                    RandomAccessFile(candidate.file, "r").use { it.readFully(header) }
                    if (!candidate.format.matchesHeader(header, candidate.length.toLong())) {
                        throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_MIME, "The bytes do not match the requested image format")
                    }
                    val ready = File(root, READY)
                    ready.mkdirs()
                    val destination = File(ready, candidate.file.name)
                    Files.move(candidate.file.toPath(), destination.toPath())
                    candidate.file = destination
                    candidate
                }
            }
            withContext(Dispatchers.Main.immediate) {
                beforeLaunch()
                ensureActive()
                synchronized(guard) {
                    if (current !== transfer || closed) throw cancelled()
                    transfer.launched = true
                    try {
                        launchChooser(transfer.file, transfer.format)
                    } catch (error: Exception) {
                        transfer.launched = false
                        throw error
                    }
                    current = null
                }
            }
        } finally {
            owned?.let(::discard)
        }
    }

    override suspend fun cancel(id: String) {
        synchronized(guard) { current?.takeIf { it.id == id } }?.let(::discard)
    }

    override fun resetForNavigation() = reset(ending = false)
    override fun close() = reset(ending = true)

    private fun reset(ending: Boolean) {
        val transfer = synchronized(guard) {
            if (ending) closed = true
            document++
            current.also { current = null }
        }
        transfer?.let(::deleteStaging)
    }

    private fun checkAvailable(expectedDocument: Long) {
        if (closed || expectedDocument != document) throw cancelled()
        if (current != null) throw RuntimeHandlerException(RuntimeRpcErrorCode.BUSY, "An image share is already in progress")
    }

    private fun requireCurrent(id: String): Transfer = synchronized(guard) {
        current?.takeIf { it.id == id && !closed } ?: throw cancelled()
    }

    private fun discard(transfer: Transfer) {
        synchronized(guard) {
            if (current === transfer) current = null
            if (transfer.launched) return
        }
        deleteStaging(transfer)
    }

    private fun deleteStaging(transfer: Transfer) {
        cleanupScope.launch {
            cacheMutex.withLock {
                if (!synchronized(guard) { transfer.launched }) transfer.file.delete()
            }
        }
    }

    companion object {
        private const val DIRECTORY = "toolbox-share-images"
        private const val STAGING = "staging"
        private const val READY = "ready"
        private const val RETAIN_MILLIS = 24 * 60 * 60 * 1000L
        private const val CACHE_BUDGET = 128 * 1024 * 1024L
        private const val CACHE_FILE_LIMIT = 64
        private val startupCleanup = AtomicBoolean()
        private val cacheMutex = Mutex()
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun cancelled() = RuntimeHandlerException(RuntimeRpcErrorCode.CANCELLED, "The image share session has ended")

        fun cleanupOnStartup(context: Context) {
            if (!startupCleanup.compareAndSet(false, true)) return
            val root = File(context.cacheDir, DIRECTORY)
            cleanupScope.launch { cacheMutex.withLock { pruneAndMeasure(root) } }
        }

        private data class CacheUsage(val files: Int, val bytes: Long)

        private fun pruneAndMeasure(root: File): CacheUsage {
            val cutoff = System.currentTimeMillis() - RETAIN_MILLIS
            var files = 0
            var bytes = 0L
            for (name in listOf(STAGING, READY)) {
                for (file in File(root, name).listFiles().orEmpty()) {
                    if (file.lastModified() < cutoff && file.delete()) continue
                    files++
                    bytes += file.length()
                }
            }
            return CacheUsage(files, bytes)
        }
    }
}

internal fun createImageShareChooser(context: Context, file: File, format: RuntimeImageShareFormat): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(format.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .apply { clipData = ClipData.newRawUri("图片", uri) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, "分享图片")
}
