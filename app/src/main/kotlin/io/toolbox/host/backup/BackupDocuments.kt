package io.toolbox.host.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import io.toolbox.tool.packagekit.backup.BackupArchive
import io.toolbox.tool.packagekit.backup.BackupException
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.*

internal class CreateBackupDocument : ActivityResultContracts.CreateDocument("application/zip") {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
internal class OpenBackupDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
internal interface BackupDocumentIO {
    suspend fun open(location: String): InputStream
    suspend fun save(file: File, location: String, progress: (String, Int) -> Unit, published: () -> Unit)
}

/** Provider-neutral SAF transport. Never assumes an arbitrary DocumentsProvider can rename atomically. */
internal class AndroidBackupDocumentIO(private val resolver: ContentResolver) : BackupDocumentIO {
    override suspend fun open(location: String): InputStream = withContext(Dispatchers.IO) {
        resolver.openInputStream(Uri.parse(location)) ?: throw BackupException("CORRUPT")
    }
    override suspend fun save(file: File, location: String, progress: (String, Int) -> Unit, published: () -> Unit) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(location)
        var complete = false
        try {
            val expected = BackupArchive.hash(file)
            val size = file.length().coerceAtLeast(1)
            resolver.openOutputStream(uri, "wt")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var transferred = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        transferred += n
                        progress("写入所选本地文件", (transferred * 90 / size).toInt())
                    }
                }
                output.flush()
            } ?: throw BackupException("DESTINATION")
            progress("复核目标文件", 95)
            val actual = resolver.openInputStream(uri)?.use { BackupArchive.hash(it) } ?: throw BackupException("DESTINATION")
            if (expected != actual) throw BackupException("DESTINATION")
            complete = true
            published() // Preserves a truthful outcome when cancellation arrives after publication.
        } catch (failure: Throwable) {
            if (!complete) {
                val deleted = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
                }
                if (!deleted) throw BackupException("DESTINATION_CLEANUP")
            }
            throw failure
        }
    }
}
